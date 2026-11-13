package dev.gladimdim.tiling

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import java.util.concurrent.Executors
import kotlin.math.abs

/** The theme's colors the desktop draws with. */
class TilingColors(
    val background: Color,
    val surface: Color,
    val foreground: Color,
    val muted: Color,
    val accent: Color,
    val border: Color,
)

/** An app the menu can open. [component] is `package/class`. */
class TilingApp(
    val label: String,
    val component: String,
    val icon: ImageBitmap?,
)

/** What the desktop needs of the launcher it lives in; the module knows nothing else of it. */
interface TilingHost {
    val colors: TilingColors
    val font: FontFamily

    fun apps(): List<TilingApp>

    /** A workspace came in front: home may turn to the page of widgets that goes with it. */
    fun workspaceShown(index: Int)
}

/** One window: an app on its own display, drawn into a tile. */
class Tile internal constructor(
    val id: Int,
    val label: String,
    /** `am start` arguments that open it. */
    val args: Array<String>,
) {
    /** The helper's display, once the tile has been on screen. */
    var displayId = -1
        internal set
    internal var width = 0
    internal var height = 0
    internal var tasks: List<Int> = emptyList()

    /** Whether its app was ever seen on the display: an empty display before that is an app still starting. */
    internal var seen = false
    internal var emptyPolls = 0
    internal val openedAt = SystemClock.uptimeMillis()
}

/**
 * The tiling desktop: five workspaces of windows, each window an Android app on a virtual display that the helper
 * process ([HelperLink]) creates and the launcher draws into a tile. On while a hardware keyboard is connected.
 *
 * Everything here runs on the main thread.
 */
object Tiling {
    const val WORKSPACES = 5
    private const val TAG = "Tiling"
    private const val POLL_MS = 1500L
    private const val START_TIMEOUT_MS = 10_000L

    val workspaces = List(WORKSPACES) { ArrayList<Tile>() }
    private val lastFocused = arrayOfNulls<Tile>(WORKSPACES)
    private val fullscreen = arrayOfNulls<Tile>(WORKSPACES)

    var current by mutableIntStateOf(0)
        private set
    var focused: Tile? = null
        private set
    var edge by mutableStateOf(Edge.TOP)
        private set

    /** Where a bar being dragged would land. */
    var dragEdge by mutableStateOf<Edge?>(null)
        private set
    var menuOpen by mutableStateOf(false)
        private set

    /** Counts every change to the windows, for what draws them. */
    var version by mutableIntStateOf(0)
        private set

    /** A hardware keyboard is connected. */
    var keyboard by mutableStateOf(false)
        private set

    /** Super+Shift+D turns the desktop off and on again while the keyboard stays. */
    var enabled by mutableStateOf(true)
        private set

    /** Trusted virtual displays, which the tiles are, came with Android 13. */
    val supported = Build.VERSION.SDK_INT >= 33
    /**
     * The helper was connected on this device at least once: the desktop is someone's who set it up. Without that a
     * keyboard changes nothing, as for everyone who never started the helper.
     */
    private var optedIn by mutableStateOf(false)

    val active get() = supported && enabled && keyboard && (optedIn || HelperLink.helper != null)

    /** The launcher's activity is on screen: only then are the shortcuts the desktop's. */
    private var inFront = false

    internal var host: TilingHost? = null
        private set

    // The home activity and its view, while there is one: let go of in [detach], and replaced by the next one.
    @SuppressLint("StaticFieldLeak")
    private var activity: Activity? = null

    @SuppressLint("StaticFieldLeak")
    private var layout: DesktopLayout? = null
    private val main = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor()
    private var nextId = 1
    private val helper get() = HelperLink.helper

    /** The tile shown alone on a workspace, if one is. */
    internal fun aloneOn(workspace: Int): Tile? = fullscreen[workspace]?.takeIf { it in workspaces[workspace] }

    /** The shortcuts, over the desktop: until the first window is ever opened, and from the bar's question mark. */
    var help by mutableStateOf(false)
        private set

    internal fun toggleHelp() {
        help = !help
        if (!help) activity?.let { prefs(it).edit().putBoolean("help_seen", true).apply() }
    }

    // ---- life in the launcher's activity ----

    /** Called once from the home activity's onCreate, after its own content is set. */
    fun attach(
        activity: Activity,
        host: TilingHost,
    ) {
        if (Build.VERSION.SDK_INT < 33) return
        this.activity = activity
        this.host = host
        edge = runCatching { Edge.valueOf(prefs(activity).getString("edge", null) ?: "TOP") }.getOrDefault(Edge.TOP)
        help = !prefs(activity).getBoolean("help_seen", false)
        optedIn = prefs(activity).getBoolean("opted_in", false)
        enabled = prefs(activity).getBoolean("enabled", true)
        val view = DesktopLayout(activity, host)
        layout = view
        activity.addContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        inFront = true
        activity.application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(started: Activity) {
                    if (started === this@Tiling.activity) inFront = true
                }

                override fun onActivityStopped(stopped: Activity) {
                    if (stopped === this@Tiling.activity) inFront = false
                }

                override fun onActivityDestroyed(destroyed: Activity) {
                    if (destroyed === activity) activity.application.unregisterActivityLifecycleCallbacks(this)
                }

                override fun onActivityCreated(
                    created: Activity,
                    state: Bundle?,
                ) = Unit

                override fun onActivityResumed(resumed: Activity) = Unit

                override fun onActivityPaused(paused: Activity) = Unit

                override fun onActivitySaveInstanceState(
                    saved: Activity,
                    state: Bundle,
                ) = Unit
            },
        )
        if (!watching) {
            watching = true
            val input = activity.applicationContext.getSystemService(InputManager::class.java)
            input.registerInputDeviceListener(
                object : InputManager.InputDeviceListener {
                    override fun onInputDeviceAdded(deviceId: Int) = keyboardsChanged(input)

                    override fun onInputDeviceRemoved(deviceId: Int) = keyboardsChanged(input)

                    override fun onInputDeviceChanged(deviceId: Int) = keyboardsChanged(input)
                },
                main,
            )
            keyboardsChanged(input)
        }
        // The activity may be a new one over windows that are already open: its views start from them.
        changed()
    }

    /** The keyboard watch runs once for the process, whichever activity shows the desktop. */
    private var watching = false

    /** The activity is gone (it is recreated rarely: it handles its own configuration changes). */
    fun detach(activity: Activity) {
        if (this.activity !== activity) return
        this.activity = null
        layout = null
    }

    private fun keyboardsChanged(input: InputManager) {
        val found =
            input.inputDeviceIds.any { id ->
                val device = input.getInputDevice(id) ?: return@any false
                !device.isVirtual && device.isEnabled && device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
                    device.sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD
            }
        if (found == keyboard) return
        keyboard = found
        Log.i(TAG, "hardware keyboard: $found")
        if (active) host?.workspaceShown(current)
        changed()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("tiling", Context.MODE_PRIVATE)

    private fun changed() {
        version++
        if (active && !running) start()
        layout?.sync()
    }

    /** The bar's status and the task poll, from the first time the desktop is on: nothing of them runs for an install that never uses it. */
    private var running = false

    private fun start() {
        val context = activity?.applicationContext ?: return
        if (Build.VERSION.SDK_INT < 33) return
        running = true
        BarStatus.start(context)
        poll()
    }

    // ---- the helper ----

    internal fun helperReady() {
        Log.i(TAG, "helper connected")
        if (!optedIn) {
            optedIn = true
            activity?.let { prefs(it).edit().putBoolean("opted_in", true).apply() }
        }
        if (active) host?.workspaceShown(current)
        layout?.rebuild()
        changed()
    }

    /** Its displays went with it, and Android moved their apps to the main screen: no window is ours any more. */
    internal fun helperLost() {
        Log.w(TAG, "helper lost")
        workspaces.forEach { it.clear() }
        lastFocused.fill(null)
        fullscreen.fill(null)
        focused = null
        changed()
    }

    /** A tile's surface exists or changed size: its display is created with it, and its app started there. */
    internal fun surface(
        tile: Tile,
        surface: Surface,
        width: Int,
        height: Int,
    ) {
        val helper = helper ?: return
        if (width < 32 || height < 32) return
        val dpi = activity?.resources?.displayMetrics?.densityDpi ?: return
        runCatching {
            if (tile.displayId < 0) {
                tile.displayId = helper.createDisplay(surface, width, height, dpi, "tile-${tile.id}")
                if (tile.displayId >= 0) helper.startActivity(tile.displayId, tile.args) else Log.w(TAG, "no display for ${tile.label}")
            } else {
                if (tile.width != width || tile.height != height) helper.resizeDisplay(tile.displayId, width, height, dpi)
                helper.setSurface(tile.displayId, surface)
            }
            tile.width = width
            tile.height = height
        }.onFailure { Log.w(TAG, "surface for ${tile.label}: $it") }
    }

    /** Out of sight (another workspace, or the launcher itself is): its app stays and stops drawing. */
    internal fun surfaceGone(tile: Tile) {
        if (tile.displayId >= 0) runCatching { helper?.setSurface(tile.displayId, null) }
    }

    internal fun inject(
        tile: Tile,
        event: MotionEvent,
    ) {
        if (tile.displayId >= 0) runCatching { helper?.injectMotion(tile.displayId, event) }
    }

    /** Asks the helper which tasks are on which display, and closes the tiles whose app has gone. */
    private fun poll() {
        main.postDelayed(::poll, POLL_MS)
        val helper = helper ?: return
        if (!active || workspaces.all { it.isEmpty() }) return
        background.execute {
            val text = runCatching { helper.tasks() }.getOrNull() ?: return@execute
            val byDisplay = HashMap<Int, ArrayList<Int>>()
            for (line in text.lineSequence()) {
                val parts = line.split(' ')
                if (parts.size < 2) continue
                byDisplay.getOrPut(parts[1].toIntOrNull() ?: continue) { ArrayList() } += parts[0].toIntOrNull() ?: continue
            }
            main.post { reconcile(byDisplay) }
        }
    }

    private fun reconcile(byDisplay: Map<Int, List<Int>>) {
        val gone = ArrayList<Tile>()
        for (tile in workspaces.flatten()) {
            if (tile.displayId < 0) continue
            tile.tasks = byDisplay[tile.displayId].orEmpty()
            if (tile.tasks.isNotEmpty()) {
                tile.seen = true
                tile.emptyPolls = 0
            } else if (tile.seen) {
                if (++tile.emptyPolls >= 2) gone += tile
            } else if (SystemClock.uptimeMillis() - tile.openedAt > START_TIMEOUT_MS) {
                gone += tile
            }
        }
        gone.forEach(::close)
    }

    // ---- windows ----

    /** Opens an app in a new tile on the current workspace. */
    fun open(app: TilingApp) = openFromHome(app.label, app.component)

    fun open(
        label: String,
        args: Array<String>,
    ): Boolean {
        if (!active || helper == null) return false
        val tile = Tile(nextId++, label, args)
        workspaces[current] += tile
        fullscreen[current] = null
        setFocus(tile)
        menuOpen = false
        if (help) toggleHelp()
        changed()
        return true
    }

    /** An app home was asked to open (a pinned icon, its own app list, a widget): in a tile while the desktop is on. */
    fun openFromHome(
        label: String,
        component: String,
    ): Boolean = open(label, arrayOf("-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-n", component))

    fun close(tile: Tile) {
        val ws = workspaces.indexOfFirst { tile in it }
        if (ws < 0) return
        val at = workspaces[ws].indexOf(tile)
        workspaces[ws].remove(tile)
        if (fullscreen[ws] === tile) fullscreen[ws] = null
        if (lastFocused[ws] === tile) lastFocused[ws] = workspaces[ws].getOrNull(at) ?: workspaces[ws].lastOrNull()
        if (tile.displayId >= 0) runCatching { helper?.releaseDisplay(tile.displayId) }
        tile.displayId = -1
        if (focused === tile) {
            focused = null
            if (ws == current) setFocus(lastFocused[ws])
            giveKeys()
        }
        changed()
    }

    private fun setFocus(tile: Tile?) {
        focused = tile
        if (tile != null) workspaces.indexOfFirst { tile in it }.takeIf { it >= 0 }?.let { lastFocused[it] = tile }
    }

    /** Home, under the windows, was pressed: typing is for it now (its bar, a widget's field), not for a window. */
    internal fun homePressed() {
        if (focused == null) return
        focused = null
        changed()
    }

    /** A tile was clicked: the click itself gives its app the keys. */
    internal fun clicked(tile: Tile) {
        if (focused === tile) return
        setFocus(tile)
        changed()
    }

    /** Android's keys follow ours: to the focused tile's app, or back to the launcher when there is none. */
    private fun giveKeys() {
        val tile = focused
        if (tile != null && tile.displayId >= 0 && !menuOpen) {
            runCatching { helper?.focusDisplay(tile.displayId) }
        } else {
            keysToLauncher()
        }
    }

    private fun keysToLauncher() {
        val activity = activity ?: return
        val helper = helper
        if (helper != null) {
            runCatching { helper.focusTask(activity.taskId) }
        } else {
            runCatching { activity.getSystemService(ActivityManager::class.java).moveTaskToFront(activity.taskId, 0) }
        }
    }

    fun switchTo(workspace: Int) {
        if (workspace !in 0 until WORKSPACES || workspace == current) return
        current = workspace
        setFocus(lastFocused[workspace]?.takeIf { it in workspaces[workspace] } ?: workspaces[workspace].lastOrNull())
        host?.workspaceShown(workspace)
        changed()
        giveKeys()
    }

    /**
     * Home turned to one of its pages, by a swipe: the workspace of that number comes with it. Pages and workspaces
     * are one set of spaces; a page past the last workspace keeps the windows that were showing.
     */
    fun pageShown(page: Int) {
        if (active) switchTo(page - 1)
    }

    /** Takes the focused window along to another workspace, as Omarchy's Super+Shift+number does. */
    fun moveFocusedTo(workspace: Int) {
        val tile = focused ?: return
        if (workspace !in 0 until WORKSPACES || workspace == current) return
        workspaces[current].remove(tile)
        if (fullscreen[current] === tile) fullscreen[current] = null
        lastFocused[current] = workspaces[current].lastOrNull()
        workspaces[workspace] += tile
        lastFocused[workspace] = tile
        fullscreen[workspace] = null
        current = workspace
        host?.workspaceShown(workspace)
        changed()
        giveKeys()
    }

    /** Where the current workspace's windows are, as the layout last placed them. */
    internal var placed: List<Area> = emptyList()

    private fun towards(
        dx: Int,
        dy: Int,
        swap: Boolean,
    ) {
        val tiles = workspaces[current]
        val from = tiles.indexOf(focused)
        if (from < 0) {
            setFocus(tiles.lastOrNull() ?: return)
        } else {
            val to = neighbour(placed, from, dx, dy) ?: return
            if (swap) {
                val other = tiles[to]
                tiles[to] = tiles[from]
                tiles[from] = other
            } else {
                setFocus(tiles[to])
            }
        }
        changed()
        giveKeys()
    }

    fun toggleFullscreen() {
        val tile = focused ?: return
        fullscreen[current] = if (fullscreen[current] === tile) null else tile
        changed()
    }

    fun toggleMenu() {
        if (helper == null) return
        menuOpen = !menuOpen
        changed()
        giveKeys()
    }

    fun closeMenu() {
        if (!menuOpen) return
        menuOpen = false
        changed()
        giveKeys()
    }

    // ---- the bar ----

    internal fun barDragged(
        x: Float,
        y: Float,
    ) {
        val layout = layout ?: return
        val to = nearestEdge(x, y, layout.width, layout.height)
        if (to == dragEdge) return
        dragEdge = to
        changed()
    }

    internal fun barDropped(cancelled: Boolean) {
        val to = dragEdge ?: return
        dragEdge = null
        if (!cancelled) moveBar(to)
        changed()
    }

    /**
     * The bar to the next edge, clockwise: a right click on it. For where a drag cannot start: HyperOS keeps swipes
     * that begin at the left, right and bottom edges of the screen for its own back and home gestures.
     */
    internal fun barToNextEdge() {
        val next = listOf(Edge.TOP, Edge.RIGHT, Edge.BOTTOM, Edge.LEFT)
        moveBar(next[(next.indexOf(edge) + 1) % next.size])
        changed()
    }

    private fun moveBar(to: Edge) {
        if (to == edge) return
        edge = to
        activity?.let { prefs(it).edit().putString("edge", to.name).apply() }
    }

    /** A settings page from the bar, in a tile like any app. */
    internal fun openSettings(
        label: String,
        action: String,
    ) = open(label, arrayOf("-a", action))

    internal fun notifications() {
        if (TilingKeyService.showNotifications()) return
        runCatching { helper?.expandNotifications() }
    }

    // ---- three-finger swipes ----

    /** Three fingers are down and the gesture is the desktop's, until the last of them lifts. */
    private var swiping = false

    /** This gesture has switched the workspace, or grew a fourth finger: it does nothing more. */
    private var swiped = false
    private var swipeX = 0f
    private var swipeY = 0f

    /**
     * Every touch on the launcher's window, before its views get it: three fingers swiped sideways go to the next or
     * the previous workspace, as they turn the page on Omarchy's touchpad. Left brings the next one in, the way a page
     * follows the fingers. True when the event is used up; [cancel] ends the touch for the view (a window, home) that
     * had the first two fingers.
     */
    fun touch(
        event: MotionEvent,
        cancel: () -> Unit,
    ): Boolean {
        if (!active) {
            swiping = false
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> swiping = false
            MotionEvent.ACTION_POINTER_DOWN ->
                if (swiping) {
                    swiped = true
                } else if (event.pointerCount == 3) {
                    swiping = true
                    swiped = false
                    swipeX = mean(event) { event.getX(it) }
                    swipeY = mean(event) { event.getY(it) }
                    cancel()
                }
            MotionEvent.ACTION_MOVE ->
                if (swiping && !swiped && event.pointerCount == 3) {
                    val dx = mean(event) { event.getX(it) } - swipeX
                    val dy = mean(event) { event.getY(it) } - swipeY
                    val far = SWIPE_DP * (activity?.resources?.displayMetrics?.density ?: 1f)
                    if (abs(dx) > far && abs(dx) > abs(dy) * 1.5f) {
                        swiped = true
                        switchTo(current + if (dx < 0) 1 else -1)
                    }
                }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                if (swiping) {
                    swiping = false
                    return true
                }
        }
        return swiping
    }

    private fun mean(
        event: MotionEvent,
        of: (Int) -> Float,
    ): Float {
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += of(i)
        return sum / event.pointerCount
    }

    private const val SWIPE_DP = 48f

    // ---- keys ----

    /** The open app menu's own keys (arrows, Enter, Esc): true when it used the key. */
    internal var menuKeys: ((Int) -> Boolean)? = null

    /** Keys whose press was a shortcut: their repeats and release are ours too. */
    private val held = HashSet<Int>()

    /**
     * A key from the keyboard, before any app sees it ([TilingKeyService]) or as the launcher's window gets it
     * ([fromLauncher]). True when it was a shortcut and is used up.
     */
    fun key(
        event: KeyEvent,
        fromLauncher: Boolean,
    ): Boolean {
        if (!supported) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        if (!down) return held.remove(event.keyCode) || fromLauncher && forward(event)
        if (menuOpen && inFront && !event.isMetaPressed) {
            // A held arrow goes on moving through the list.
            val again = event.keyCode in held
            val arrow = event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
            if (again && !arrow) return true
            if (menuKeys?.invoke(event.keyCode) == true) {
                held += event.keyCode
                return true
            }
        }
        if (event.keyCode in held) return true
        if (event.isMetaPressed && event.isShiftPressed && event.keyCode == KeyEvent.KEYCODE_D && keyboard && inFront) {
            enabled = !enabled
            activity?.let { prefs(it).edit().putBoolean("enabled", enabled).apply() }
            menuOpen = false
            held += event.keyCode
            if (active) host?.workspaceShown(current)
            changed()
            return true
        }
        if (!active || !inFront) return false
        if (!event.isMetaPressed || !shortcut(event.keyCode, event.isShiftPressed, event.isAltPressed)) return fromLauncher && forward(event)
        held += event.keyCode
        return true
    }

    /**
     * Omarchy's bindings where Android leaves them free. It does not leave Super with a bare arrow (HyperOS splits the
     * screen on it, and acts before any app or service can take the key), Super+Tab (recents), Super+D or Super+L
     * (the lock screen): focus moves with Super+Alt+arrow instead.
     */
    private fun shortcut(
        keyCode: Int,
        shift: Boolean,
        alt: Boolean,
    ): Boolean {
        when (keyCode) {
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_5 -> (keyCode - KeyEvent.KEYCODE_1).let { if (shift) moveFocusedTo(it) else switchTo(it) }
            KeyEvent.KEYCODE_SPACE -> toggleMenu()
            KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_Q -> focused?.let(::close)
            KeyEvent.KEYCODE_F -> toggleFullscreen()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!shift && !alt) return false
                val dx = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else 0
                val dy = if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else 0
                towards(dx, dy, swap = shift)
            }
            else -> return false
        }
        return true
    }

    /**
     * The launcher's window has the keys though a tile is focused (a click on the bar took them): what is typed
     * goes on to the tile's app.
     */
    private fun forward(event: KeyEvent): Boolean {
        val tile = focused ?: return false
        if (!active || menuOpen || tile.displayId < 0) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> return false
        }
        val helper = helper ?: return false
        runCatching { helper.injectKey(tile.displayId, event) }
        return true
    }
}
