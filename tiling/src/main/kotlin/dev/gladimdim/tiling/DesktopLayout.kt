package dev.gladimdim.tiling

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import kotlin.math.abs

/**
 * The desktop on screen, over the launcher's own content: a [TileView] for every window, the bar on its edge, and the
 * app menu. Home and its widgets stay underneath, moved in from the bar, and show wherever no window is. A plain view
 * tree, so a mouse's hover, wheel and buttons reach the tiles as Android delivers them; the bar and the menu are
 * Compose inside it.
 */
// Left is the screen's left edge here, whichever way the language reads: tiles and the bar are placed in pixels.
@SuppressLint("ViewConstructor", "RtlHardcoded")
internal class DesktopLayout(
    private val activity: Activity,
    private val host: TilingHost,
) : FrameLayout(activity) {
    private val density = resources.displayMetrics.density

    /** Upright on a side edge it is wider: the outer part of it lies in the system's edge-swipe zone, and cannot start a drag. */
    private fun thickness(edge: Edge) = ((if (edge.vertical) SIDE_BAR_DP else BAR_DP) * density).toInt()

    // Without gaps (Super+Shift+Backspace) the windows meet, with a hairline between them.
    private val gapOut get() = if (Tiling.gapless) 0 else (GAP_OUT_DP * density).toInt()
    private val gapIn get() = if (Tiling.gapless) 0 else (GAP_IN_DP * density).toInt()
    private val border get() = if (Tiling.gapless) 1 else (BORDER_DP * density).toInt().coerceAtLeast(1)

    private val tiles = HashMap<Tile, TileView>()
    private val bar = ComposeView(activity).apply { setContent { Bar(host) } }
    private val notice = ComposeView(activity).apply { setContent { Notice(host) } }
    private var menu: ComposeView? = null
    private val paint = Paint()

    /** The launcher's own content, under this view. */
    private val home: View? get() = (parent as? ViewGroup)?.getChildAt(0)?.takeIf { it !== this }

    init {
        setWillNotDraw(false)
        visibility = GONE
        // Able to hold the focus itself, for when the menu closes (placeMenu).
        isFocusable = true
        isFocusableInTouchMode = true
        addView(notice, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, thickness(Edge.TOP)))
        // Above every window, floating ones too, for drawing and for clicks; no shadow of their own.
        for (view in listOf(notice, bar)) onTop(view)
        // Asks Android not to take a drag that starts on the bar for its back gesture.
        bar.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ -> v.systemGestureExclusionRects = listOf(Rect(0, 0, r - l, b - t)) }
    }

    private fun onTop(view: View) {
        view.translationZ = TOP_Z
        view.outlineProvider = null
    }

    /**
     * The part of the screen that is the desktop's: all of it but a camera cutout. Not the navigation bar's strip: the
     * bar is hidden here, and when Android brings it up for a moment (with the on-screen keyboard, after a swipe from
     * the edge) it lies over the windows instead of resizing every one of them.
     */
    private fun usable(): Area {
        // The desktop itself only shows from Android 13 on.
        if (Build.VERSION.SDK_INT < 30) return Area(0, 0, width, height)
        val insets = rootWindowInsets?.getInsets(WindowInsets.Type.displayCutout())
        return if (insets == null) Area(0, 0, width, height) else Area(insets.left, insets.top, width - insets.right, height - insets.bottom)
    }

    /**
     * Android's back, home and recents buttons, out of sight while the desktop is on: a keyboard has them (Esc is
     * back), and a swipe in from the bottom edge still brings them up for a moment.
     */
    private fun navigationButtons(shown: Boolean) {
        if (Build.VERSION.SDK_INT < 30) return
        val bars = activity.window.insetsController ?: return
        if (shown) {
            bars.show(WindowInsets.Type.navigationBars())
        } else {
            bars.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsets.Type.navigationBars())
        }
    }

    /** Android puts the buttons back when the window was out of focus, behind the shade or another app. */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) barsAgain()
    }

    /**
     * Hides the navigation bar again wherever Android has put it back: on coming back from recents or an app (the
     * keys may be in a window then, and this one gets no focus to notice by), and whenever the insets say it shows.
     */
    fun barsAgain() {
        if (Tiling.active) navigationButtons(shown = false)
    }

    private fun barArea(edge: Edge) = usable().let { barArea(it.width, it.height, edge, thickness(edge)).moved(it.left, it.top) }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        post(::sync)
        // Up without the on-screen keyboard having asked for it: not wanted here. (The keyboard's own bar is left.)
        if (Build.VERSION.SDK_INT >= 30 && Tiling.active && insets.isVisible(WindowInsets.Type.navigationBars()) && !insets.isVisible(WindowInsets.Type.ime())) {
            post(::barsAgain)
        }
        return super.onApplyWindowInsets(insets)
    }

    /** A press no window, bar or menu took is home's, underneath: the keys are its own again, not the focused window's. */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) Tiling.homePressed()
        return false
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        post(::sync)
    }

    /** Every tile again, with a new surface: the helper has just connected, and has no display for any of them. */
    fun rebuild() {
        tiles.values.forEach(::removeView)
        tiles.clear()
        sync()
    }

    /** Brings the views in line with [Tiling]. */
    fun sync() {
        val on = Tiling.active
        if (on != (visibility == VISIBLE)) {
            visibility = if (on) VISIBLE else GONE
            navigationButtons(shown = !on)
        }
        if (!on) {
            home?.setPadding(0, 0, 0, 0)
            if (tiles.isNotEmpty()) rebuild()
            return
        }
        if (width == 0 || height == 0) return
        bar.visibility = if (Tiling.coversBar) GONE else VISIBLE
        placeBar()
        placeTiles()
        placeMenu()
        invalidate()
    }

    private fun placeBar() {
        val edge = Tiling.edge
        val deep = thickness(edge)
        val area = barArea(edge)
        val params = bar.layoutParams as LayoutParams
        if (params.width != area.width || params.height != area.height || params.leftMargin != area.left || params.topMargin != area.top) {
            params.width = area.width
            params.height = area.height
            params.leftMargin = area.left
            params.topMargin = area.top
            params.gravity = Gravity.TOP or Gravity.LEFT
            bar.layoutParams = params
        }
        // Home keeps clear of the bar, as it does of the status bar the bar stands in for.
        home?.setPadding(
            if (edge == Edge.LEFT) deep else 0,
            if (edge == Edge.TOP) deep else 0,
            if (edge == Edge.RIGHT) deep else 0,
            if (edge == Edge.BOTTOM) deep else 0,
        )
    }

    /**
     * Every window of every workspace keeps its view, and so its surface: the ones out of sight are only moved off
     * the screen. Their apps go on drawing, and a workspace is there the moment it is switched to, where taking the
     * surface away and giving it back made its apps flash black and animate in.
     *
     * A workspace's tiled windows share its room by the dwindle; floating ones lie over them where they were put, the
     * one raised last on top; the scratchpad's lie over all of that, in a room of their own inside the screen.
     */
    private fun placeTiles() {
        val screen = usable()
        val work = workArea(screen.width, screen.height, Tiling.edge, thickness(Tiling.edge), gapOut).moved(screen.left, screen.top)
        val inset = (SCRATCH_INSET * minOf(work.width, work.height)).toInt()
        val scratch = Area(work.left + inset, work.top + inset, work.right - inset, work.bottom - inset)
        Tiling.workArea = work
        Tiling.scratchArea = scratch
        Tiling.gap = gapIn
        val colors = host.colors
        val edge = border
        val kept = HashSet<Tile>()
        val shownTiles = ArrayList<Tile>()
        val shownBoxes = ArrayList<Area>()
        val here = if (Tiling.scratchShown) Tiling.SCRATCH else Tiling.current
        for ((index, all) in Tiling.workspaces.withIndex()) {
            val isScratch = index == Tiling.SCRATCH
            val room = if (isScratch) scratch else work
            val alone = Tiling.aloneOn(index)
            val tiled = all.filter { !it.floating }
            val boxes = dwindle(room, tiled.map { it.split }, gapIn)
            for (tile in all) {
                kept += tile
                val box =
                    when {
                        // One shown alone has the windows' room, or with Super+F the whole screen, the bar's place too.
                        tile === alone -> if (Tiling.wholeScreenOn(index) && !isScratch) screen else room
                        tile.floating -> within(tile.box ?: room, screen).also { tile.box = it }
                        else -> boxes[tiled.indexOf(tile)]
                    }
                val view = tiles.getOrPut(tile) { TileView(activity, tile).also { addView(it, 0, LayoutParams(box.width, box.height)) } }
                val params = view.layoutParams as LayoutParams
                if (params.width != box.width || params.height != box.height || params.leftMargin != box.left || params.topMargin != box.top) {
                    params.width = box.width
                    params.height = box.height
                    params.leftMargin = box.left
                    params.topMargin = box.top
                    params.gravity = Gravity.TOP or Gravity.LEFT
                    view.layoutParams = params
                }
                view.border(edge)
                view.workspace = index
                view.pinned = tile.pinned
                // One shown alone has the others of its workspace out of sight behind it.
                view.hidden = alone != null && tile !== alone
                // Higher is nearer: tiled windows, then floating ones as they were raised, then the scratchpad's.
                view.level((if (isScratch) SCRATCH_LEVEL else 0) + if (tile.floating) FLOAT_LEVEL + tile.raised else 0)
                view.setBackgroundColor((if (tile === Tiling.focused) colors.accent else colors.border).toArgb())
                if (!view.hidden && (index == here || tile.pinned && !isScratch)) {
                    shownTiles += tile
                    shownBoxes += box
                }
            }
        }
        Tiling.placedTiles = shownTiles
        Tiling.placed = shownBoxes
        for ((tile, view) in tiles.entries.toList()) {
            if (tile !in kept) {
                removeView(view)
                tiles.remove(tile)
            }
        }
        scrolled()
    }

    /** A floating window's place, pushed back so that a good part of it stays on the screen to be caught by. */
    private fun within(
        box: Area,
        screen: Area,
    ): Area {
        val keep = (MIN_SHOWN_DP * density).toInt()
        val dx = (screen.left + keep - box.right).coerceAtLeast(0) + (screen.right - keep - box.left).coerceAtMost(0)
        val dy = (screen.top - box.top).coerceAtLeast(0) + (screen.bottom - keep - box.top).coerceAtMost(0)
        return if (dx == 0 && dy == 0) box else box.moved(dx, dy)
    }

    /**
     * Where each window is sideways: its workspace's distance from the current one in screens, less what a page swipe
     * under way has pulled the current one aside ([Tiling.shift]), so the windows travel with home's page. A pinned
     * one stays; the scratchpad's are there while it is up. All of them off the screen while home's own app list is.
     */
    fun scrolled() {
        val away = width * 2f
        val raised = Tiling.homeRaised
        for (view in tiles.values) {
            val aside =
                when {
                    view.workspace == Tiling.SCRATCH -> if (Tiling.scratchShown) 0f else 2f
                    view.pinned -> 0f
                    else -> view.workspace - Tiling.current - Tiling.shift
                }
            // Only what is on screen or sliding in moves with the swipe; the rest stays parked in one place.
            val to = if (raised || view.hidden || abs(aside) >= 1f) away else aside * width
            if (view.translationX != to) view.translationX = to
        }
    }

    private fun placeMenu() {
        val open = Tiling.menuOpen
        val shown = menu
        if (open && shown == null) {
            menu =
                ComposeView(activity).apply { setContent { AppMenu(host) } }.also {
                    onTop(it)
                    addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                }
        } else if (!open && shown != null) {
            // The focus the menu's field had stays here, on nothing. Left to Android it would go to the next field
            // down, home's bar, which opens home's own app list on being focused.
            requestFocus()
            removeView(shown)
            menu = null
        }
        notice.visibility = if (open) GONE else VISIBLE
    }

    /** Where a bar being dragged would land. */
    override fun onDraw(canvas: Canvas) {
        val edge = Tiling.dragEdge ?: return
        val area = barArea(edge)
        paint.color = host.colors.accent.copy(alpha = 0.45f).toArgb()
        canvas.drawRect(area.left.toFloat(), area.top.toFloat(), area.right.toFloat(), area.bottom.toFloat(), paint)
    }

    companion object {
        // Omarchy's Hyprland defaults: gaps_out 10, gaps_in 5 (each side), border 2; its waybar is 26 high.
        const val BAR_DP = 30
        const val SIDE_BAR_DP = 44
        const val GAP_OUT_DP = 8
        const val GAP_IN_DP = 8
        const val BORDER_DP = 2

        /** How far in from the windows' room the scratchpad's is, as a share of its shorter side. */
        const val SCRATCH_INSET = 0.08f

        /** How much of a floating window stays on the screen however far it is dragged. */
        const val MIN_SHOWN_DP = 80

        const val FLOAT_LEVEL = 10
        const val SCRATCH_LEVEL = 100_000
        const val TOP_Z = 1_000_000f
    }
}

/**
 * A window: its border, and the surface its app's display draws into. Pointer events over it go to that display, but
 * for Super with a mouse button, which moves and resizes the window itself.
 */
@SuppressLint("ViewConstructor")
internal class TileView(
    activity: Activity,
    private val tile: Tile,
) : FrameLayout(activity),
    SurfaceHolder.Callback {
    /** Which workspace it is on, whether it shows on all of them, and whether another window shown alone covers it: [DesktopLayout.scrolled] places it by them. */
    var workspace = 0
    var pinned = false
    var hidden = false

    /** Super was held as the button went down: the gesture is the desktop's until the button is let go. */
    private var dragging = false
    private var lastX = 0f
    private var lastY = 0f

    private val surface =
        object : SurfaceView(activity) {
            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (drag(event)) return true
                if (event.actionMasked == MotionEvent.ACTION_DOWN) Tiling.clicked(tile)
                Tiling.inject(tile, event)
                return true
            }

            /** A mouse's wheel and buttons, and a stylus's. The wheel with Super held is the desktop's: it turns workspaces. */
            override fun onGenericMotionEvent(event: MotionEvent): Boolean {
                if (dragging) return true
                if (!Tiling.wheel(event)) Tiling.inject(tile, event)
                return true
            }

            override fun onHoverEvent(event: MotionEvent): Boolean {
                // Focus follows the mouse: the window under the pointer has the keys.
                if (event.actionMasked != MotionEvent.ACTION_HOVER_EXIT && event.isFromSource(InputDevice.SOURCE_MOUSE)) Tiling.hovered(tile)
                Tiling.inject(tile, event)
                return true
            }
        }

    init {
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        surface.holder.addCallback(this)
    }

    /** Super with the left button moves the window, with the right one resizes it; the app sees none of it. */
    private fun drag(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.metaState and KeyEvent.META_META_ON == 0 || !event.isFromSource(InputDevice.SOURCE_MOUSE)) return false
                dragging = true
                lastX = event.rawX
                lastY = event.rawY
                Tiling.dragStart(tile, resize = event.buttonState and MotionEvent.BUTTON_SECONDARY != 0)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                Tiling.dragBy(event.rawX - lastX, event.rawY - lastY)
                lastX = event.rawX
                lastY = event.rawY
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                Tiling.dragEnd(event.rawX, event.rawY)
            }
            else -> return dragging
        }
        return true
    }

    private var edge = -1

    /** The border's width: the room left around the surface, which the view's color shows through. */
    fun border(width: Int) {
        if (width == edge) return
        edge = width
        (surface.layoutParams as LayoutParams).setMargins(width, width, width, width)
        surface.requestLayout()
    }

    private var level = -1

    /**
     * How near it lies: for clicks and the border by the view's height over the others, for the app's picture by the
     * surface's place among the window's surfaces. All of them stay under the launcher's window, where the bar is.
     */
    fun level(to: Int) {
        if (to == level) return
        level = to
        translationZ = to.toFloat().coerceAtMost(DesktopLayout.TOP_Z / 2)
        // A lifted window has no outline to throw a shadow from: its middle is a hole onto the surface.
        outlineProvider = null
        if (Build.VERSION.SDK_INT >= 36) surface.compositionOrder = BELOW_WINDOW + to
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) = Tiling.surface(tile, holder.surface, width, height)

    override fun surfaceDestroyed(holder: SurfaceHolder) = Tiling.surfaceGone(tile)

    private companion object {
        /** Under the window by more than any window is ever raised. */
        const val BELOW_WINDOW = -10_000_000
    }
}
