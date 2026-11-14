package dev.gladimdim.tiling

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import java.io.File
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

    /** Home's own list of every app, opened or closed: the bar's start button. */
    fun toggleDrawer()

    fun closeDrawer()

    /** Windows lie over all of home, or no longer do: its widgets need not refresh under them. */
    fun homeCovered(covered: Boolean)

    /** Where `tiling.conf`, the keys that open apps, is kept: a folder the user can reach to edit it. */
    val configDir: File

    /** Opens the launcher's Linux terminal; its window comes back through [Tiling.openWindow]. */
    fun terminal()

    /** Dismisses every notification that can be, or only the latest one. False without access to notifications. */
    fun dismissNotifications(all: Boolean): Boolean
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
    internal var openedAt = SystemClock.uptimeMillis()

    /** The package of the app in it, once seen: what tells whether it still runs when the desktop comes back. */
    internal var pkg: String? = null

    /** A running task to take onto its display instead of starting [args]: the app that was in front as the desktop came. */
    internal var adopt = -1

    /** How it divides the space with the windows after it: Super+minus and equal move it, Super+J turns it. */
    internal val split = Split()

    /** An activity of the launcher's own to start on its display instead of [args]: a Linux window. */
    internal var intent: Intent? = null

    /** Lifted out of the tiling (Super+T): it lies over the tiled windows at [box], moved and resized by hand. */
    internal var floating = false

    /** Floating and on every workspace (Super+O). */
    internal var pinned = false
    internal var box: Area? = null

    /** Its place in the pile of floating windows: the one raised last is on top. */
    internal var raised = 0
}

/**
 * The tiling desktop: five workspaces of windows, each window an Android app on a virtual display that the helper
 * process ([HelperLink]) creates and the launcher draws into a tile. On while a hardware keyboard is connected.
 *
 * Everything here runs on the main thread.
 */
object Tiling {
    const val WORKSPACES = 10

    /** The scratchpad's place in [workspaces], after the numbered ones: a workspace shown over the current one. */
    const val SCRATCH = WORKSPACES

    /** How many workspaces the bar always shows; a later one is shown while it has windows or is the current one. */
    const val ALWAYS_SHOWN = 5
    private const val TAG = "Tiling"
    private const val POLL_MS = 1500L
    private const val START_TIMEOUT_MS = 10_000L

    val workspaces = List(WORKSPACES + 1) { ArrayList<Tile>() }
    private val lastFocused = arrayOfNulls<Tile>(WORKSPACES + 1)
    private val fullscreen = arrayOfNulls<Tile>(WORKSPACES + 1)

    /** Whether a workspace's lone window takes the whole screen, the bar's room too (Super+F), or only the windows' (Super+Alt+F). */
    private val wholeScreen = BooleanArray(WORKSPACES + 1)

    /** The scratchpad is up (Super+S), over the current workspace. */
    var scratchShown by mutableStateOf(false)
        private set

    /** Where the keys act: the scratchpad while it is up, else the current workspace. */
    private val here get() = if (scratchShown) SCRATCH else current

    /** No gaps between the windows and around them (Super+Shift+Backspace). */
    var gapless by mutableStateOf(false)
        private set

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

    /** Hotkey to switch the active input language (null = disabled). */
    var languageHotkey by mutableStateOf<KeyHotkey?>(KeyHotkey.DEFAULT_LANGUAGE)
        private set

    /** Whether the active input language flag is shown in the bar. */
    var showLanguageInBar by mutableStateOf(true)
        private set

    /** Whether connecting a hardware keyboard automatically enters the tiling desktop. */
    var autoEnableOnKeyboard by mutableStateOf(true)
        private set

    /** Whether the window helper process is currently connected (for settings UI). */
    val helperConnected: Boolean get() = helper != null

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

    internal fun wholeScreenOn(workspace: Int) = wholeScreen[workspace]

    /** The current workspace shows one window over everything, the bar included. */
    internal val coversBar get() = aloneOn(current) != null && wholeScreen[current]

    /** The room the current workspace's windows are laid out in, as the layout last had it: resizing measures by it. */
    internal var workArea: Area? = null
    internal var scratchArea: Area? = null
    internal var gap = 0

    /** The shortcuts, over the desktop: until the first window is ever opened, and from the bar's question mark. */
    var help by mutableStateOf(false)
        private set

    /** The app finder's "show keyboard shortcuts". */
    internal fun showHelp() {
        menuOpen = false
        if (!help) help = true
        changed()
        giveKeys()
    }

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
        val p = prefs(activity)
        edge = runCatching { Edge.valueOf(p.getString("edge", null) ?: "TOP") }.getOrDefault(Edge.TOP)
        help = !p.getBoolean("help_seen", false)
        optedIn = p.getBoolean("opted_in", false)
        enabled = p.getBoolean("enabled", true)
        gapless = p.getBoolean("gapless", false)
        showLanguageInBar = p.getBoolean("show_language_in_bar", true)
        autoEnableOnKeyboard = p.getBoolean("auto_enable_on_keyboard", true)
        languageHotkey =
            if (p.contains("language_hotkey")) KeyHotkey.parse(p.getString("language_hotkey", null)) else KeyHotkey.DEFAULT_LANGUAGE
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

                override fun onActivityResumed(resumed: Activity) {
                    // Back from recents or another app, Android has its navigation bar up again.
                    if (resumed === this@Tiling.activity) layout?.barsAgain()
                }

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
        // Attaching the keyboard asks for the desktop, also after it was left with Super+Shift+D.
        if (found && !enabled && autoEnableOnKeyboard) turn(true)
        if (active) host?.workspaceShown(current)
        changed()
    }

    fun setLanguageHotkey(
        context: Context,
        hotkey: KeyHotkey?,
    ) {
        languageHotkey = hotkey
        prefs(context).edit().putString("language_hotkey", hotkey?.format() ?: "off").apply()
    }

    fun setShowLanguageInBar(
        context: Context,
        show: Boolean,
    ) {
        showLanguageInBar = show
        prefs(context).edit().putBoolean("show_language_in_bar", show).apply()
        if (show && Build.VERSION.SDK_INT >= 33) BarStatus.refreshInputLanguage(context)
    }

    fun setAutoEnableOnKeyboard(
        context: Context,
        auto: Boolean,
    ) {
        autoEnableOnKeyboard = auto
        prefs(context).edit().putBoolean("auto_enable_on_keyboard", auto).apply()
    }

    /** Switches the active input language / keyboard layout via the shell helper or Android's IME picker. */
    fun switchInputLanguage() {
        val ctx = activity
        val h = helper
        if (h != null) {
            runCatching { h.tapKey(KeyEvent.KEYCODE_LANGUAGE_SWITCH) }
        } else if (ctx != null) {
            runCatching { ctx.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker() }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            BarStatus.refreshInputLanguage(ctx)
            main.postDelayed({ BarStatus.refreshInputLanguage(ctx) }, 150)
            main.postDelayed({ BarStatus.refreshInputLanguage(ctx) }, 450)
        }
    }

    fun openAccessibilitySettings(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun openAppInfoSettings(context: Context) {
        val intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun turn(on: Boolean) {
        enabled = on
        activity?.let { prefs(it).edit().putBoolean("enabled", on).apply() }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("tiling", Context.MODE_PRIVATE)

    private fun changed() {
        version++
        val on = active
        if (on != wasOn) {
            wasOn = on
            if (on) comeBack() else handBack()
        }
        if (on && !running) start()
        layout?.sync()
        covering()
    }

    private var covered = false

    /** Tells home when the current workspace's windows hide it: not while a page swipe shows the page beside, or home's app list is up. */
    private fun covering() {
        val now = active && workspaces[current].any { !it.floating } && shift == 0f && !homeRaised
        if (now == covered) return
        covered = now
        host?.homeCovered(now)
    }

    private var wasOn = false

    /**
     * The desktop goes (the keyboard came off): its windows become ordinary apps again. Android moves them to the
     * main screen as their displays go, where recents and their icons find them; left on displays nobody draws, an
     * app opened from home would come up out of sight. The focused window's app goes last and so stays in front, full
     * screen, as on a tablet; with none focused, home does.
     */
    private fun handBack() {
        menuOpen = false
        val open = workspaces.flatten().filter { it.displayId >= 0 }
        if (open.isEmpty()) return
        val front = focused?.takeIf { it in open }
        val order = open.filter { it !== front } + listOfNotNull(front)
        val home = if (front == null) activity?.taskId ?: -1 else -1
        runCatching { helper?.dropDisplays(order.map { it.displayId }.toIntArray(), home) }
        open.forEach { it.displayId = -1 }
    }

    /**
     * The desktop is back: every window whose app still runs gets a display again, and its app moves onto it as it
     * was; one whose app was closed meanwhile is not brought back. An app in front of home at that moment comes along:
     * to its old window if it had one, else as a new one on this workspace. Then home comes in front as the desktop.
     */
    private fun comeBack() {
        val helper = helper ?: return
        val tasks =
            runCatching { helper.tasks() }.getOrNull()?.lineSequence()?.map { it.split(' ') }?.filter { it.size >= 3 }?.toList() ?: return
        val running = tasks.map { it[2].substringBefore('/') }.toSet()
        val alive = tasks.mapNotNull { it[0].toIntOrNull() }.toSet()
        val now = SystemClock.uptimeMillis()
        for ((index, tiles) in workspaces.withIndex()) {
            // The task it had comes back as it is. A Linux window has no other way back: its activity can't be started again.
            for (tile in tiles) tile.adopt = tile.tasks.firstOrNull { it in alive } ?: -1
            tiles.removeAll { it.adopt < 0 && (it.intent != null || it.pkg != null && it.pkg !in running) }
            if (fullscreen[index] !in tiles) fullscreen[index] = null
            if (lastFocused[index] !in tiles) lastFocused[index] = tiles.lastOrNull()
            for (tile in tiles) {
                tile.seen = false
                tile.emptyPolls = 0
                tile.openedAt = now
            }
        }
        if (focused != null && workspaces.none { focused in it }) focused = lastFocused[current]
        val activity = activity ?: return
        if (inFront) return
        // What covers home: the first task of the main screen that is neither home nor the system's launcher and recents.
        val top = tasks.firstOrNull { it[1] == "0" && it[2].substringBefore('/').let { p -> p != activity.packageName && p !in SYSTEM_HOMES } }
        if (top != null) {
            val pkg = top[2].substringBefore('/')
            val known = workspaces.flatten().firstOrNull { it.pkg == pkg }
            if (known != null) {
                current = workspaces.indexOfFirst { known in it }
                shift = 0f
                setFocus(known)
                host?.workspaceShown(current)
            } else {
                val tile = Tile(nextId++, pkg, emptyArray())
                tile.pkg = pkg
                tile.adopt = top[0].toIntOrNull() ?: -1
                if (tile.adopt >= 0) {
                    workspaces[current] += tile
                    fullscreen[current] = null
                    setFocus(tile)
                }
            }
        }
        keysToLauncher()
    }

    /** Packages whose tasks are a launcher or recents, never an app to take along. */
    private val SYSTEM_HOMES = setOf("com.miui.home", "com.android.launcher3", "com.google.android.apps.nexuslauncher", "com.sec.android.app.launcher")

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
                tile.displayId = helper.createDisplay(surface, width, height, dpi, "tile-${tile.id}", screenRate())
                if (tile.displayId < 0) {
                    Log.w(TAG, "no display for ${tile.label}")
                } else if (tile.adopt >= 0) {
                    helper.moveTaskToDisplay(tile.adopt, tile.displayId)
                    tile.adopt = -1
                } else if (tile.intent != null) {
                    // One of the launcher's own activities: it may start on the display itself.
                    activity?.startActivity(
                        Intent(tile.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                        ActivityOptions.makeBasic().setLaunchDisplayId(tile.displayId).toBundle(),
                    )
                } else {
                    helper.startActivity(tile.displayId, tile.args)
                }
            } else {
                if (tile.width != width || tile.height != height) helper.resizeDisplay(tile.displayId, width, height, dpi)
                helper.setSurface(tile.displayId, surface)
            }
            tile.width = width
            tile.height = height
        }.onFailure { Log.w(TAG, "surface for ${tile.label}: $it") }
    }

    /**
     * The refresh rate the screen runs at when something moves: its fastest mode, no faster than the rate picked in
     * Android's display settings. Windows are made to run at it too.
     */
    private fun screenRate(): Float {
        val activity = activity ?: return 0f
        if (Build.VERSION.SDK_INT < 30) return 0f
        val fastest = activity.display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: return 0f
        val picked = runCatching { Settings.System.getFloat(activity.contentResolver, "peak_refresh_rate") }.getOrDefault(0f)
        return if (picked >= 30f) minOf(fastest, picked) else fastest
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

    private var keysAskedAt = 0L
    private var keysAsked = 0

    /**
     * The key service is not running though the helper is: the helper switches it on. Android stops running it when
     * the launcher's process was killed (HyperOS does that behind a locked screen) and never starts it again by
     * itself; switching it off and on does. Tried a few times, a while apart, then left to the user.
     */
    private fun wantKeys() {
        val helper = helper ?: return
        val pkg = activity?.packageName ?: return
        if (TilingKeyService.connected) {
            keysAsked = 0
            return
        }
        val now = SystemClock.uptimeMillis()
        if (keysAsked >= 4 || now - keysAskedAt < KEYS_RETRY_MS) return
        keysAskedAt = now
        // The first time it may only be off; after that it is on and not running.
        runCatching { helper.enableAccessibilityService(TilingKeyService.component(pkg), keysAsked > 0) }
        keysAsked++
    }

    /** Asks the helper which tasks are on which display, and closes the tiles whose app has gone. */
    private fun poll() {
        main.postDelayed(::poll, POLL_MS)
        if (active) wantKeys()
        val helper = helper ?: return
        if (!active || workspaces.all { it.isEmpty() }) return
        background.execute {
            val text = runCatching { helper.tasks() }.getOrNull() ?: return@execute
            val byDisplay = HashMap<Int, ArrayList<Int>>()
            val packages = HashMap<Int, String>()
            for (line in text.lineSequence()) {
                val parts = line.split(' ')
                if (parts.size < 2) continue
                val display = parts[1].toIntOrNull() ?: continue
                byDisplay.getOrPut(display) { ArrayList() } += parts[0].toIntOrNull() ?: continue
                // The first task of a display is its top one.
                parts.getOrNull(2)?.substringBefore('/')?.let { packages.putIfAbsent(display, it) }
            }
            main.post { reconcile(byDisplay, packages) }
        }
    }

    private fun reconcile(
        byDisplay: Map<Int, List<Int>>,
        packages: Map<Int, String>,
    ) {
        val gone = ArrayList<Tile>()
        for (tile in workspaces.flatten()) {
            if (tile.displayId < 0) continue
            tile.tasks = byDisplay[tile.displayId].orEmpty()
            if (tile.tasks.isNotEmpty()) {
                packages[tile.displayId]?.let { tile.pkg = it }
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
    ): Boolean = add(Tile(nextId++, label, args))

    /**
     * One of the launcher's own activities as a window: a Linux window, which the launcher would otherwise start as a
     * task of its own on the main screen. False when the desktop is off.
     */
    fun openWindow(
        label: String,
        intent: Intent,
    ): Boolean = add(Tile(nextId++, label, emptyArray()).also { it.intent = intent })

    private fun add(tile: Tile): Boolean {
        if (!active || helper == null) return false
        workspaces[here] += tile
        fullscreen[here] = null
        setFocus(tile)
        menuOpen = false
        if (help) toggleHelp()
        if (homeRaised) host?.closeDrawer()
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
            if (ws == here) setFocus(lastFocused[ws])
            giveKeys()
        }
        changed()
    }

    private fun setFocus(tile: Tile?) {
        focused = tile
        if (tile == null) return
        workspaces.indexOfFirst { tile in it }.takeIf { it >= 0 }?.let { lastFocused[it] = tile }
        // A floating window that gets the keys comes to the top of the pile.
        if (tile.floating) tile.raised = ++raises
    }

    private var raises = 0

    /** Home, under the windows, was pressed: typing is for it now (its bar, a widget's field), not for a window. */
    internal fun homePressed() {
        if (focused == null) return
        focused = null
        changed()
    }

    /**
     * The mouse came over a window: the keys go to it, as on Hyprland, where focus follows the mouse. Not while the
     * app finder or home's app list has them, nor in the middle of a drag.
     */
    internal fun hovered(tile: Tile) {
        if (focused === tile || menuOpen || homeRaised || dragged != null) return
        setFocus(tile)
        changed()
        giveKeys()
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
        if (tile != null && tile.displayId >= 0 && !menuOpen && !homeRaised) {
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

    fun switchTo(workspace: Int) = switchTo(workspace, byPage = false)

    /** [byPage]: home's page was turned to already, by the swipe that brings the workspace. */
    private fun switchTo(
        workspace: Int,
        byPage: Boolean,
    ) {
        if (workspace !in 0 until WORKSPACES || workspace == current && !scratchShown) return
        scratchShown = false
        if (workspace != current) former = current
        current = workspace
        shift = if (byPage) (scroll - workspace).takeIf { abs(it) < 1f } ?: 0f else 0f
        setFocus(lastFocused[workspace]?.takeIf { it in workspaces[workspace] } ?: workspaces[workspace].lastOrNull())
        changed()
        giveKeys()
        // The windows are there with this frame; home turns its page, which it has to compose, in the next one. Not
        // for a swipe: asking for the page it is still on its way to would stop it where it is.
        if (!byPage) main.post { if (current == workspace) host?.workspaceShown(workspace) }
    }

    /** The next workspace or the one before, for the wheel and a swipe: among the ones the bar shows. */
    private fun step(by: Int) {
        val last = maxOf(ALWAYS_SHOWN - 1, current, (0 until WORKSPACES).lastOrNull { workspaces[it].isNotEmpty() } ?: 0)
        switchTo((current + by).coerceIn(0, last))
    }

    /** Where home's pages are scrolled to, in workspaces: 1.5 is halfway from the second to the third. */
    private var scroll = 0f

    /** How far the current workspace is pulled aside by a page swipe under way, in screens; its neighbour shows by as much. */
    internal var shift = 0f
        private set

    /** Home's pages moved under a finger or settled: the windows move with them. */
    fun pageScrolled(position: Float) {
        scroll = position
        if (!active) return
        val to = (position - current).takeIf { abs(it) < 1f } ?: 0f
        if (to == shift) return
        shift = to
        layout?.scrolled()
        covering()
    }

    /**
     * Home turned to one of its pages, by a swipe: the workspace of that number comes with it. Pages and workspaces
     * are one set of spaces; a page past the last workspace keeps the windows that were showing.
     */
    fun pageShown(page: Int) {
        if (active) switchTo(page - 1, byPage = true)
    }

    /** Takes the focused window along to another workspace, as Omarchy's Super+Shift+number does. */
    fun moveFocusedTo(workspace: Int) = moveFocusedTo(workspace, follow = true)

    /**
     * Without [follow] the window goes and this workspace stays: Omarchy's Super+Shift+Alt+number, and with
     * [SCRATCH] its Super+Alt+S.
     */
    private fun moveFocusedTo(
        workspace: Int,
        follow: Boolean,
    ) {
        val tile = focused ?: return
        val from = workspaces.indexOfFirst { tile in it }
        if (from < 0 || workspace !in workspaces.indices || workspace == from) return
        workspaces[from].remove(tile)
        if (fullscreen[from] === tile) fullscreen[from] = null
        lastFocused[from] = workspaces[from].lastOrNull()
        workspaces[workspace] += tile
        lastFocused[workspace] = tile
        fullscreen[workspace] = null
        tile.pinned = false
        if (follow && workspace != SCRATCH) {
            scratchShown = false
            former = current
            current = workspace
            shift = 0f
            host?.workspaceShown(workspace)
        } else {
            setFocus(lastFocused[here])
        }
        changed()
        giveKeys()
    }

    /** Super+S: the scratchpad over the current workspace, or away again. */
    fun toggleScratch() {
        scratchShown = !scratchShown
        setFocus(lastFocused[here]?.takeIf { it in workspaces[here] } ?: workspaces[here].lastOrNull())
        changed()
        giveKeys()
    }

    /** Super+Alt+S: the focused window to the scratchpad, or from it back to the current workspace. */
    private fun toScratch() {
        val tile = focused ?: return
        moveFocusedTo(if (tile in workspaces[SCRATCH]) current else SCRATCH, follow = false)
    }

    /** The windows on screen and where they are, as the layout last placed them: the ones [here], and the pinned. */
    internal var placedTiles: List<Tile> = emptyList()
    internal var placed: List<Area> = emptyList()

    private fun towards(
        dx: Int,
        dy: Int,
        swap: Boolean,
    ) {
        val shown = placedTiles
        val from = shown.indexOf(focused)
        if (from < 0) {
            setFocus(shown.lastOrNull() ?: return)
        } else {
            val other = shown[neighbour(placed, from, dx, dy) ?: return]
            if (swap) swap(shown[from], other) else setFocus(other)
        }
        changed()
        giveKeys()
    }

    /** Two tiled windows of one workspace change places. */
    private fun swap(
        one: Tile,
        other: Tile,
    ): Boolean {
        val tiles = workspaces.firstOrNull { one in it && other in it } ?: return false
        if (one.floating || other.floating || one === other) return false
        val a = tiles.indexOf(one)
        val b = tiles.indexOf(other)
        tiles[a] = other
        tiles[b] = one
        // The places keep their shares of the screen: the windows trade them too.
        val ratio = one.split.ratio
        val turned = one.split.turned
        one.split.ratio = other.split.ratio
        one.split.turned = other.split.turned
        other.split.ratio = ratio
        other.split.turned = turned
        return true
    }

    /** Super+F: the focused window alone over the whole screen. Super+Alt+F ([whole] false): alone, with the bar still there. */
    fun toggleFullscreen(whole: Boolean = true) {
        val tile = focused ?: return
        val ws = here
        if (fullscreen[ws] === tile && wholeScreen[ws] == whole) {
            fullscreen[ws] = null
        } else {
            fullscreen[ws] = tile
            wholeScreen[ws] = whole
        }
        changed()
    }

    /** Super+J: turns the split the focused window is cut from its neighbours by. */
    private fun toggleSplit() {
        val tiles = workspaces[here].filter { !it.floating }
        if (turnSplit(tiles.map { it.split }, tiles.indexOf(focused))) changed()
    }

    /** Super+T: the focused window out of the tiling, floating over it, or back in. */
    private fun toggleFloating() {
        val tile = focused ?: return
        if (tile.floating) land(tile) else lift(tile, pinned = false)
        changed()
    }

    /** Super+O: the focused window popped out: floating, and there on every workspace. Again puts it back. */
    private fun togglePop() {
        val tile = focused ?: return
        if (tile.floating && tile.pinned) {
            land(tile)
        } else {
            // Popped out it has most of the screen, wherever it floated before.
            tile.box = null
            lift(tile, pinned = true, share = POP_SHARE)
        }
        changed()
    }

    private fun lift(
        tile: Tile,
        pinned: Boolean,
        share: Float = FLOAT_SHARE,
    ) {
        val ws = workspaces.indexOfFirst { tile in it }
        if (ws >= 0 && fullscreen[ws] === tile) fullscreen[ws] = null
        tile.floating = true
        tile.pinned = pinned && ws != SCRATCH
        // In the middle, its share of the room each way, until it is moved.
        val room = workArea
        if (tile.box == null && room != null) {
            val w = (room.width * share).toInt()
            val h = (room.height * share).toInt()
            tile.box = Area(room.centerX - w / 2, room.centerY - h / 2, room.centerX + w / 2, room.centerY + h / 2)
        }
        tile.raised = ++raises
    }

    private fun land(tile: Tile) {
        tile.floating = false
        tile.pinned = false
    }

    private var resizedAt = 0L

    /**
     * Super+minus and equal, as Omarchy has them: the focused window narrower or wider, with Shift shorter or taller;
     * Alt for a little, Ctrl for a lot. A held key resizes in steps an app can keep up with.
     */
    private fun resizeFocused(
        grow: Boolean,
        vertical: Boolean,
        factor: Float,
    ) {
        val now = SystemClock.uptimeMillis()
        if (now - resizedAt < RESIZE_EVERY_MS) return
        val step = (RESIZE_DP * factor * (activity?.resources?.displayMetrics?.density ?: 1f)).toInt() * if (grow) 1 else -1
        if (resizeBy(focused ?: return, if (vertical) 0 else step, if (vertical) step else 0)) {
            resizedAt = now
            changed()
        }
    }

    /**
     * A window wider by [dx] and taller by [dy] pixels: a tiled one by its cuts, a floating one about its middle
     * (the keys) or with its top left corner staying put ([corner], the mouse, whose pointer the other corner follows).
     */
    private fun resizeBy(
        tile: Tile,
        dx: Int,
        dy: Int,
        corner: Boolean = false,
    ): Boolean {
        val ws = workspaces.indexOfFirst { tile in it }
        if (ws < 0) return false
        if (tile.floating) {
            val box = tile.box ?: return false
            val w = (box.width + dx).coerceAtLeast(MIN_FLOAT)
            val h = (box.height + dy).coerceAtLeast(MIN_FLOAT)
            val left = if (corner) box.left else box.centerX - w / 2
            val top = if (corner) box.top else box.centerY - h / 2
            tile.box = Area(left, top, left + w, top + h)
            return tile.box != box
        }
        if (aloneOn(ws) != null) return false
        val area = (if (ws == SCRATCH) scratchArea else workArea) ?: return false
        val tiles = workspaces[ws].filter { !it.floating }
        return resize(area, tiles.map { it.split }, gap, tiles.indexOf(tile), dx, dy)
    }

    /** Alt+Tab: the next window of the workspace, or the one before with Shift. */
    private fun cycle(back: Boolean) {
        val tiles = workspaces[here]
        if (tiles.isEmpty()) return
        val at = tiles.indexOf(focused)
        setFocus(tiles[(at + (if (back) -1 else 1) + tiles.size) % tiles.size])
        if (fullscreen[here] != null) fullscreen[here] = focused
        changed()
        giveKeys()
    }

    /** The workspace that was current before this one: Super+Ctrl+Tab goes back to it. */
    private var former = 0

    /** The mouse wheel with Super held: the next workspace, or the one before. */
    fun wheel(event: MotionEvent): Boolean {
        if (!active || event.actionMasked != MotionEvent.ACTION_SCROLL || event.metaState and KeyEvent.META_META_ON == 0) return false
        val turn = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        if (turn != 0f) step(if (turn < 0) 1 else -1)
        return true
    }

    /** Super+Shift+Backspace: the gaps between the windows and around them, off or on. */
    private fun toggleGaps() {
        gapless = !gapless
        activity?.let { prefs(it).edit().putBoolean("gapless", gapless).apply() }
        changed()
    }

    // ---- moving and resizing with the mouse ----

    private var dragged: Tile? = null
    private var dragResizes = false
    private var dragX = 0f
    private var dragY = 0f

    /**
     * Super with a mouse button on a window, as on Omarchy: the left button moves it, the right one resizes it. A
     * floating window follows the pointer; a tiled one changes places with the window it is let go over, and resizes
     * by its cuts.
     */
    internal fun dragStart(
        tile: Tile,
        resize: Boolean,
    ) {
        dragged = tile
        dragResizes = resize
        dragX = 0f
        dragY = 0f
        setFocus(tile)
        changed()
    }

    internal fun dragBy(
        dx: Float,
        dy: Float,
    ) {
        val tile = dragged ?: return
        dragX += dx
        dragY += dy
        if (!dragResizes) {
            val box = tile.box
            if (!tile.floating || box == null) return
            // Whole pixels now; what is left over goes into the next move.
            val x = dragX.toInt()
            val y = dragY.toInt()
            if (x == 0 && y == 0) return
            tile.box = box.moved(x, y)
            dragX -= x
            dragY -= y
            layout?.sync()
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - resizedAt < RESIZE_EVERY_MS) return
        if (resizeBy(tile, dragX.toInt(), dragY.toInt(), corner = true)) {
            resizedAt = now
            layout?.sync()
        }
        dragX -= dragX.toInt()
        dragY -= dragY.toInt()
    }

    /** Let go at ([x], [y]) of the desktop. */
    internal fun dragEnd(
        x: Float,
        y: Float,
    ) {
        val tile = dragged ?: return
        dragged = null
        if (dragResizes) {
            resizeBy(tile, dragX.toInt(), dragY.toInt(), corner = true)
        } else if (!tile.floating) {
            val over = placedTiles.zip(placed).firstOrNull { (other, box) -> other !== tile && x >= box.left && x < box.right && y >= box.top && y < box.bottom }?.first
            if (over != null) swap(tile, over)
        }
        changed()
        giveKeys()
    }

    // ---- keys that open apps ----

    private var bindings: Map<Chord, String> = emptyMap()
    private var bindingsRead = -1L

    /** `tiling.conf` as it is now: seeded by the launcher, read again when edited. Deleting it restores the built-in keys. */
    private fun bindings(): Map<Chord, String> {
        val file = File(host?.configDir ?: return bindings, "tiling.conf")
        runCatching {
            val stamp = file.lastModified()
            if (stamp != bindingsRead) {
                bindings = parseBindings(if (file.isFile) file.readText() else DEFAULT_BINDINGS)
                bindingsRead = stamp
            }
        }.onFailure { Log.w(TAG, "tiling.conf: $it") }
        return bindings
    }

    /**
     * Opens what a binding names: the terminal, Settings, the app Android has for a kind of app, or an app by its name
     * or package. An app that has a window already gets the keys there instead of a second window.
     */
    private fun openTarget(target: String) {
        val name = target.trim()
        when (name.lowercase()) {
            "terminal" -> host?.terminal()
            "settings" -> settings()
            "switch_language", "input_language" -> switchInputLanguage()
            else -> {
                val apps = host?.apps().orEmpty()
                val role = name.lowercase().takeIf { it in ROLES }
                val app =
                    if (role != null) {
                        appFor(role, apps)
                    } else {
                        apps.firstOrNull { it.label.equals(name, ignoreCase = true) }
                            ?: apps.firstOrNull { it.component.substringBefore('/') == name }
                            ?: apps.firstOrNull { it.label.startsWith(name, ignoreCase = true) }
                    }
                if (app == null) {
                    Log.i(TAG, "no app for \"$name\"")
                    return
                }
                val pkg = app.component.substringBefore('/')
                val window = workspaces.flatten().firstOrNull { it.pkg == pkg }
                if (window != null) show(window) else open(app)
            }
        }
    }

    /** Brings a window in front with the keys: its workspace (or the scratchpad) comes with it. */
    private fun show(tile: Tile) {
        val ws = workspaces.indexOfFirst { tile in it }
        if (ws < 0) return
        if (ws == SCRATCH) {
            scratchShown = true
        } else if (!tile.pinned && ws != current) {
            scratchShown = false
            former = current
            current = ws
            shift = 0f
            host?.workspaceShown(ws)
        }
        if (fullscreen[ws] != null) fullscreen[ws] = tile
        setFocus(tile)
        changed()
        giveKeys()
    }

    /**
     * The app Android has for a kind of app, among the ones the app finder lists: the one that opens that kind's
     * links when there are such (the default browser, mail app, …), else the first that says it is of that kind.
     */
    private fun appFor(
        role: String,
        apps: List<TilingApp>,
    ): TilingApp? {
        val pm = activity?.packageManager ?: return null
        val listed = { pkg: String? -> apps.firstOrNull { it.component.substringBefore('/') == pkg } }
        ROLE_LINKS[role]?.let { link ->
            val picked = runCatching { pm.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)), PackageManager.MATCH_DEFAULT_ONLY) }.getOrNull()
            // "android" answers when several apps could and none is picked: the chooser, not an app.
            listed(picked?.activityInfo?.packageName)?.let { return it }
        }
        val category = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, ROLES.getValue(role))
        val ofKind = runCatching { pm.queryIntentActivities(category, 0) }.getOrDefault(emptyList())
        return ofKind.firstNotNullOfOrNull { listed(it.activityInfo?.packageName) }
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

    /** Home's own app list is open over the desktop: the windows step aside for it, and the keys are home's. */
    var homeRaised by mutableStateOf(false)
        private set

    fun homeRaised(raised: Boolean) {
        if (raised == homeRaised) return
        homeRaised = raised
        if (!active) return
        changed()
        if (raised) keysToLauncher() else giveKeys()
    }

    /** The bar's start button: home's list of every app. */
    internal fun startMenu() {
        menuOpen = false
        host?.toggleDrawer()
    }

    /** The bar's gear: Android's Settings, in a window. */
    internal fun settings() = open("Settings", arrayOf("-a", "android.settings.SETTINGS"))

    /** A settings page from the bar, in a tile like any app. */
    internal fun openSettings(
        label: String,
        action: String,
    ) = open(label, arrayOf("-a", action))

    internal fun notifications() {
        if (TilingKeyService.showNotifications()) return
        runCatching { helper?.expandNotifications() }
    }

    /** Super+Shift+comma and Super+Alt+comma: every notification gone, or the latest. Without access to them, the shade opens instead. */
    private fun dismissNotifications(all: Boolean) {
        if (host?.dismissNotifications(all) != true) notifications()
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
                        step(if (dx < 0) 1 else -1)
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

    /** Omarchy resizes by 100 of its pixels (25 with Alt, 300 with Ctrl): about this much of a tablet's screen. */
    private const val RESIZE_DP = 60f
    private const val RESIZE_EVERY_MS = 90L
    private const val KEYS_RETRY_MS = 6_000L
    private const val FLOAT_SHARE = 0.6f
    private const val POP_SHARE = 0.8f

    /** The least a floating window is across, in pixels. */
    private const val MIN_FLOAT = 320

    // ---- keys ----

    /** The open app menu's own keys (arrows, Enter, Esc): true when it used the key. */
    internal var menuKeys: ((Int) -> Boolean)? = null

    /** Keys whose press was a shortcut: their repeats and release are ours too. */
    private val held = HashSet<Int>()

    /** Armed when a modifier-only language hotkey (e.g. Alt+Shift) is held without any non-modifier key. */
    private var langModArmed = false

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
        val langKey = languageHotkey
        if (langKey != null && langKey.isModifierOnly && active && inFront) {
            if (KeyHotkey.isModifierKey(event.keyCode)) {
                if (down) {
                    langModArmed = langKey.matchesModifiers(event.isMetaPressed, event.isCtrlPressed, event.isAltPressed, event.isShiftPressed)
                } else if (langModArmed) {
                    langModArmed = false
                    switchInputLanguage()
                }
            } else if (down) {
                langModArmed = false
            }
        } else if (!active || !inFront) {
            langModArmed = false
        }
        // Super on its own brings up the system's dock (a tap) and its list of shortcuts (held), and HyperOS decides
        // that before any app or service has the key: keeping the key from Android is not enough. It only does so
        // while no other key was pressed with Super, so one is, the moment Super goes down: a Shift, from the helper,
        // which means nothing to an app. The keys pressed with Super after it still say Super is down.
        if (event.keyCode == KeyEvent.KEYCODE_META_LEFT || event.keyCode == KeyEvent.KEYCODE_META_RIGHT) {
            if (fromLauncher || !active || !inFront) return false
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) runCatching { helper?.tapKey(KeyEvent.KEYCODE_SHIFT_LEFT) }
            return true
        }
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
        if (event.keyCode in held) {
            // A held resize key goes on resizing.
            if ((event.keyCode == KeyEvent.KEYCODE_MINUS || event.keyCode == KeyEvent.KEYCODE_EQUALS) && event.isMetaPressed && active && inFront) {
                shortcut(event)
            }
            return true
        }
        // Leaving is from the desktop; coming back is from anywhere, an app that is in front then comes along.
        if (event.isMetaPressed && event.isShiftPressed && event.keyCode == KeyEvent.KEYCODE_D && keyboard && (inFront || !enabled)) {
            turn(!enabled)
            menuOpen = false
            held += event.keyCode
            if (active) host?.workspaceShown(current)
            changed()
            return true
        }
        if (!active || !inFront) return false
        if (langKey != null && !langKey.isModifierOnly && event.repeatCount == 0 && langKey.matchesKey(event)) {
            switchInputLanguage()
            held += event.keyCode
            return true
        }
        // Esc arrives as Back from keyboards that send it so (HyperOS's does).
        if (help && (event.keyCode == KeyEvent.KEYCODE_ESCAPE || event.keyCode == KeyEvent.KEYCODE_BACK)) {
            toggleHelp()
            held += event.keyCode
            return true
        }
        // Alt+Tab is the one binding without Super: Android's own app switcher gives way to the windows here.
        val ours = if (event.isMetaPressed) shortcut(event) else event.isAltPressed && event.keyCode == KeyEvent.KEYCODE_TAB && run { cycle(event.isShiftPressed); true }
        if (!ours) return fromLauncher && forward(event)
        held += event.keyCode
        return true
    }

    /**
     * Omarchy's bindings (its default/hypr/bindings) where Android leaves them free. HyperOS does not leave Super
     * with a bare arrow (it splits the screen on it, and acts before any app or service can take the key), Super+Tab
     * (recents), Super+D, Super+L and Super+slash (the lock screen): focus moves with Super+Alt+arrow instead, and the
     * workspace layout and display scale bindings have no key here. A key that is none of the desktop's own may open
     * an app (`tiling.conf`, [bindings]).
     */
    private fun shortcut(event: KeyEvent): Boolean {
        val shift = event.isShiftPressed
        val alt = event.isAltPressed
        val ctrl = event.isCtrlPressed
        val plain = !shift && !alt && !ctrl
        val keyCode = event.keyCode
        when {
            keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 && !ctrl -> {
                // 1 to 9, then 0 for the tenth.
                val workspace = if (keyCode == KeyEvent.KEYCODE_0) 9 else keyCode - KeyEvent.KEYCODE_1
                if (shift) moveFocusedTo(workspace, follow = !alt) else if (!alt) switchTo(workspace) else return false
            }
            keyCode == KeyEvent.KEYCODE_SPACE && plain -> toggleMenu()
            (keyCode == KeyEvent.KEYCODE_W || keyCode == KeyEvent.KEYCODE_Q) && plain -> focused?.let(::close)
            keyCode == KeyEvent.KEYCODE_F && !shift -> toggleFullscreen(whole = !alt && !ctrl)
            keyCode == KeyEvent.KEYCODE_J && plain -> toggleSplit()
            keyCode == KeyEvent.KEYCODE_T && plain -> toggleFloating()
            keyCode == KeyEvent.KEYCODE_O && plain -> togglePop()
            keyCode == KeyEvent.KEYCODE_S && plain -> toggleScratch()
            keyCode == KeyEvent.KEYCODE_S && alt && !shift && !ctrl -> toScratch()
            keyCode == KeyEvent.KEYCODE_K && plain -> toggleHelp()
            keyCode == KeyEvent.KEYCODE_DEL && shift && !alt && !ctrl -> toggleGaps()
            keyCode == KeyEvent.KEYCODE_COMMA && plain -> notifications()
            keyCode == KeyEvent.KEYCODE_COMMA && shift && alt && !ctrl -> open("Notification history", arrayOf("-a", "android.settings.NOTIFICATION_HISTORY"))
            keyCode == KeyEvent.KEYCODE_COMMA && (shift || alt) && !ctrl -> dismissNotifications(all = shift)
            keyCode == KeyEvent.KEYCODE_MINUS || keyCode == KeyEvent.KEYCODE_EQUALS ->
                resizeFocused(grow = keyCode == KeyEvent.KEYCODE_EQUALS, vertical = shift, factor = if (alt) 0.25f else if (ctrl) 3f else 1f)
            keyCode == KeyEvent.KEYCODE_TAB && ctrl -> switchTo(former)
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!shift && !alt) return false
                val dx = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else 0
                val dy = if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else 0
                towards(dx, dy, swap = shift)
            }
            else -> openTarget(bindings()[Chord(keyCode, shift, alt, ctrl)] ?: return false)
        }
        return true
    }

    /**
     * The launcher's window has the keys though a tile is focused (a click on the bar took them): what is typed
     * goes on to the tile's app.
     */
    private fun forward(event: KeyEvent): Boolean {
        val tile = focused ?: return false
        if (!active || menuOpen || homeRaised || tile.displayId < 0) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> return false
        }
        val helper = helper ?: return false
        runCatching { helper.injectKey(tile.displayId, event) }
        return true
    }
}
