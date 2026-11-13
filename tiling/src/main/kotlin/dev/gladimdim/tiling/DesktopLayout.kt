package dev.gladimdim.tiling

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
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

    private val gapOut = (GAP_OUT_DP * density).toInt()
    private val gapIn = (GAP_IN_DP * density).toInt()
    private val border = (BORDER_DP * density).toInt().coerceAtLeast(1)

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
        // Asks Android not to take a drag that starts on the bar for its back gesture.
        bar.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ -> v.systemGestureExclusionRects = listOf(Rect(0, 0, r - l, b - t)) }
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
        if (hasWindowFocus && Tiling.active) navigationButtons(shown = false)
    }

    private fun barArea(edge: Edge) = usable().let { barArea(it.width, it.height, edge, thickness(edge)).moved(it.left, it.top) }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        post(::sync)
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
     */
    private fun placeTiles() {
        val work = usable().let { workArea(it.width, it.height, Tiling.edge, thickness(Tiling.edge), gapOut).moved(it.left, it.top) }
        val colors = host.colors
        val away = width * 2f
        val kept = HashSet<Tile>()
        for ((index, all) in Tiling.workspaces.withIndex()) {
            val current = index == Tiling.current
            val alone = Tiling.aloneOn(index)
            val boxes = dwindle(work, all.size, gapIn)
            if (current) Tiling.placed = if (alone != null) emptyList() else boxes
            for ((i, tile) in all.withIndex()) {
                kept += tile
                val box = if (tile === alone) work else boxes[i]
                val view = tiles.getOrPut(tile) { TileView(activity, tile, border).also { addView(it, 0, LayoutParams(box.width, box.height)) } }
                val params = view.layoutParams as LayoutParams
                if (params.width != box.width || params.height != box.height || params.leftMargin != box.left || params.topMargin != box.top) {
                    params.width = box.width
                    params.height = box.height
                    params.leftMargin = box.left
                    params.topMargin = box.top
                    params.gravity = Gravity.TOP or Gravity.LEFT
                    view.layoutParams = params
                }
                view.translationX = if (current && (alone == null || tile === alone)) 0f else away
                view.setBackgroundColor((if (tile === Tiling.focused) colors.accent else colors.border).toArgb())
            }
        }
        for ((tile, view) in tiles.entries.toList()) {
            if (tile !in kept) {
                removeView(view)
                tiles.remove(tile)
            }
        }
    }

    private fun placeMenu() {
        val open = Tiling.menuOpen
        val shown = menu
        if (open && shown == null) {
            menu = ComposeView(activity).apply { setContent { AppMenu(host) } }.also { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
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
    }
}

/** A window: its border, and the surface its app's display draws into. Pointer events over it go to that display. */
@SuppressLint("ViewConstructor")
internal class TileView(
    activity: Activity,
    private val tile: Tile,
    border: Int,
) : FrameLayout(activity),
    SurfaceHolder.Callback {
    private val surface =
        object : SurfaceView(activity) {
            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) Tiling.clicked(tile)
                Tiling.inject(tile, event)
                return true
            }

            /** A mouse's wheel and buttons, and a stylus's. */
            override fun onGenericMotionEvent(event: MotionEvent): Boolean {
                Tiling.inject(tile, event)
                return true
            }

            override fun onHoverEvent(event: MotionEvent): Boolean {
                Tiling.inject(tile, event)
                return true
            }
        }

    init {
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply { setMargins(border, border, border, border) })
        surface.holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) = Tiling.surface(tile, holder.surface, width, height)

    override fun surfaceDestroyed(holder: SurfaceHolder) = Tiling.surfaceGone(tile)
}
