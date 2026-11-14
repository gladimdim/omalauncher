package dev.omalauncher

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.role.RoleManager
import android.content.Intent
import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import dev.omalauncher.ui.BrowserPages
import dev.omalauncher.ui.ImagePicker
import dev.omalauncher.ui.WidgetAsks
import dev.omalauncher.ui.Palette
import dev.omalauncher.theme.Style
import dev.omalauncher.ui.KeyIndicated
import dev.omalauncher.ui.keyPressed
import dev.omalauncher.ui.keysFocused
import dev.omalauncher.ui.keysStartAgain
import dev.omalauncher.ui.movesFocus
import dev.omalauncher.ui.panelOf
import dev.omalauncher.ui.LauncherStyle
import dev.omalauncher.ui.SystemBarsEffect
import dev.omalauncher.ui.applySystemBars
import dev.omalauncher.ui.showStatusBar
import dev.omalauncher.ui.LauncherRoot
import dev.omalauncher.ui.LauncherState
import dev.omalauncher.ui.TilingHome
import dev.omalauncher.ui.attachTiling
import dev.gladimdim.tiling.Tiling
import dev.omalauncher.ui.PromptRequest
import dev.omalauncher.widgets.market.Share

/** The home screen: widgets, pinned apps and the drawer; Linux windows open as their own tasks. */
class MainActivity : ComponentActivity() {
    private val runtime get() = (application as App).runtime
    private val state = LauncherState()
    private val imagePicker = ImagePicker(this) { runtime }
    private val asks = WidgetAsks(this)

    /** In the language picked in settings. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        AppLanguage.applyTo(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // While a backup or device transfer runs, the process has a plain Application and no runtime;
        // Home starts this activity in it anyway. Close until the process restarts as itself.
        if (application !is App) {
            finish()
            return
        }
        // Fork: opened as an app (its icon, "Open" after an update) while it is the home app, this would be an ordinary
        // task beside Android's home: one that shows in recents and, swiped away there, leaves the system's launcher
        // in front. Home itself is asked for instead, which brings this activity up as the home task.
        if (savedInstanceState == null && openedAsApp(intent)) {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        applySystemBars(this, Palette.isLight)
        runtime.start()
        state.onSettings = { page, homePage -> runtime.openSettings(page, homePage) }
        setContent {
            // Fork: under the tiling desktop home's text fields take the hardware keyboard alone, and its app list is seen.
            TilingHome(state) { KeyIndicated { LauncherRoot(runtime, state) } }
            SystemBarsEffect(this@MainActivity)
            // A face whose statusBar is false takes the status bar's room too.
            val fullScreen by remember { derivedStateOf { state.fullScreen && runtime.widgets.openScreen == null } }
            // Fork: the tiling desktop has a bar of its own.
            val statusBar = LauncherStyle.style[Style.STATUS_BAR_SHOWN] && !fullScreen && !Tiling.active
            LaunchedEffect(statusBar) { showStatusBar(this@MainActivity, statusBar) }
        }
        // Fork: the tiling desktop, over home while a keyboard is connected.
        attachTiling(this, runtime, state)
        handleDebugExec(intent)
        handleSharedWidget(intent)
        // Asked on arriving at the home screen, not over the welcome screen of a fresh install,
        // and once nothing else is in front: not over the notification permission dialog that
        // opting into Linux there brings up.
        lifecycleScope.launch {
            snapshotFlow { runtime.launcherReady }.first { it }
            withResumed { requestHomeRole() }
        }
    }

    /** Whether this start came from the app's icon rather than from Home, while the launcher is the home app. */
    private fun openedAsApp(intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_MAIN || !intent.hasCategory(Intent.CATEGORY_LAUNCHER) || intent.hasCategory(Intent.CATEGORY_HOME)) return false
        // Debug builds are driven through this intent's extras (handleDebugExec).
        if (BuildConfig.DEBUG && (intent.hasExtra("exec") || intent.hasExtra("theme"))) return false
        val roles = getSystemService(RoleManager::class.java)
        return roles.isRoleAvailable(RoleManager.ROLE_HOME) && roles.isRoleHeld(RoleManager.ROLE_HOME)
    }

    /** Asks once to become the default home app; Android shows its own chooser. */
    private fun requestHomeRole() {
        val roles = getSystemService(RoleManager::class.java)
        if (!roles.isRoleAvailable(RoleManager.ROLE_HOME) || roles.isRoleHeld(RoleManager.ROLE_HOME)) return
        val prefs = LauncherData.prefs(this)
        if (prefs.getBoolean("home_role_asked", false)) return
        prefs.edit().putBoolean("home_role_asked", true).apply()
        @Suppress("DEPRECATION")
        startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), 1)
    }

    /**
     * Debug builds: `adb shell am start -n dev.omalauncher/.MainActivity --es exec foot`,
     * or `--es theme kanagawa` to apply a theme (scripts/collage-captures.sh).
     */
    private fun handleDebugExec(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        intent.getStringExtra("exec")?.let(runtime::launch)
        intent.getStringExtra("theme")?.let { name ->
            lifecycleScope.launch {
                snapshotFlow { runtime.themes.loaded }.first { it }
                runtime.themes.themes.firstOrNull { it.name == name }?.let { runtime.themes.apply(it) }
                    ?: Log.w("debug", "no theme $name")
            }
        }
    }

    /**
     * A widget link (see [Share]): from the camera, a browser, or the store page's "open in Omalauncher" button,
     * which can't keep the link's fragment and passes the data as the `widget` extra instead.
     */
    private fun handleSharedWidget(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val data = intent.data
        val link =
            intent.getStringExtra("widget")
                ?: data?.takeIf { it.host == Share.HOST && it.path.orEmpty().startsWith(Share.PATH) }?.toString()
                ?: return
        state.shared = link
    }

    override fun onDestroy() {
        Tiling.detach(this)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        away = false
        runtime.wm.focus(null)
        runtime.keyboard.imagePicker = imagePicker
        asks.resumed(runtime)
        removeStaleWindowTasks()
        // Made the default home app (or no longer it) while away: only then does Android show us pinned shortcuts.
        runtime.androidApps.checkShortcutAccess()
        // The user may have set or replaced the wallpaper while we were in the background.
        lifecycleScope.launch { runtime.themes.refreshLiveWallpaper() }
    }

    /**
     * Drops Recents cards whose Linux window no longer exists (app exited or process restarted). Asking for the tasks
     * goes through the system for each one, a few ms on the main thread as home comes back: they're listed off it.
     */
    private fun removeStaleWindowTasks() {
        lifecycleScope.launch {
            val windows =
                withContext(Dispatchers.IO) {
                    getSystemService(ActivityManager::class.java).appTasks.mapNotNull { task ->
                        val data = runCatching { task.taskInfo?.baseIntent?.data }.getOrNull() ?: return@mapNotNull null
                        // By its data rather than its class: windows start through aliases too.
                        if (data.scheme == "omarchy" && data.host == "window") task to data.lastPathSegment else null
                    }
                }
            val stale = windows.filter { (_, handle) -> runtime.wm.windowByHandle(handle) == null }.map { it.first }
            // A window that just closed removes its own task meanwhile; finishing it again throws.
            if (stale.isNotEmpty()) withContext(Dispatchers.IO) { stale.forEach { runCatching { it.finishAndRemoveTask() } } }
        }
    }

    /** Whether the launcher was out of sight since it was last in front: Home then only brings it back. */
    private var away = false

    /**
     * Pressing Home while already home closes the menu, like other launchers; with nothing open, it goes back to
     * page 1 (an open folder closes first). Coming back from an app keeps the page it was left on.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val nothingOpen =
            state.drawer.value == 0f && state.pulled == null && !state.homeMenuOpen && state.shared == null &&
                state.sharing == null && runtime.widgets.openScreen == null && state.editing == null
        if (!away && nothingOpen && intent.hasCategory(Intent.CATEGORY_HOME)) state.onHomeAgain()
        state.close()
        // The swipe-down panel only for a Home pressed on home: coming back from an app opened from it keeps it.
        if (!away) state.closePull()
        state.closeScreens()
        runtime.widgets.openScreen = null
        state.editing = null
        handleDebugExec(intent)
        handleSharedWidget(intent)
    }

    /** Fork: three fingers swiped sideways switch the tiling desktop's workspace, wherever on the window they are. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val taken =
            Tiling.touch(event) {
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
            }
        return taken || super.dispatchTouchEvent(event)
    }

    /** Fork: the mouse wheel with Super held turns the tiling desktop's workspaces, over home as over a window. */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = Tiling.wheel(event) || super.dispatchGenericMotionEvent(event)

    // Lint flags super.dispatchKeyEvent: ComponentActivity marks its override @RestrictTo, but Activity's is public API.
    /**
     * Launcher shortcuts (Super+key), then typing into a terminal tile that has the keyboard. Back stays the launcher's,
     * and so does Esc that nothing else took ([escapeAsBack]): it closes the drawer, a panel or a widget's screen.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Fork: the tiling desktop's shortcuts, and typing for its focused window, before home's own.
        if (Tiling.key(event, fromLauncher = true)) return true
        if (event.action == KeyEvent.ACTION_DOWN) keyPressed(window.decorView)
        if (event.isMetaPressed && event.action == KeyEvent.ACTION_DOWN && shortcut(event.keyCode)) return true
        if (event.keyCode != KeyEvent.KEYCODE_BACK && runtime.terminals.active != null && runtime.keyboard.handle(event)) return true
        // Nothing in the window has the keys (yet, or since a tap), so an arrow has nowhere to move from: it starts in the
        // open panel, or at home's first tile. One a tap opened starts it too, though the keys are still behind it.
        if (movesFocus(event.keyCode) && event.action == KeyEvent.ACTION_DOWN) {
            if (keysStartAgain(window.decorView) || !keysFocused(window.decorView) && state.focusHome()) return true
        }
        return super.dispatchKeyEvent(event) || escapeAsBack(event)
    }

    /** Widgets only refresh while the home screen is visible. */
    override fun onStart() {
        super.onStart()
        runtime.widgets.homeStarted()
        runtime.screenStarted()
        BrowserPages.started(true)
    }

    /** A terminal tile's keys held as home goes, like Super, would stay down for it: Android sends their lift elsewhere. */
    override fun onPause() {
        super.onPause()
        runtime.keyboard.releaseHeld()
        if (runtime.keyboard.imagePicker === imagePicker) runtime.keyboard.imagePicker = null
        asks.paused(runtime)
    }

    override fun onStop() {
        super.onStop()
        away = true
        state.closeNow()
        // An app was just opened from home: it moves to the front of the strip now, out of sight.
        runtime.usage.settle()
        runtime.widgets.homeStopped()
        runtime.screenStopped()
        // Every Browser page's script waits, the ones on pages home isn't drawing too.
        BrowserPages.started(false)
    }

    private fun shortcut(keyCode: Int): Boolean {
        when (keyCode) {
            // Lets go of the terminal tile that has the keys, as Back or a tap outside does, where Esc itself is its own.
            KeyEvent.KEYCODE_ESCAPE -> if (runtime.terminals.active != null) runtime.terminals.deactivate() else return false
            // Super+D and Super+T, as Android keeps Omarchy's Super+Space (the Assistant on a Pixel) and Super+Enter (Home)
            // for itself; those stay for phones that pass them on.
            KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_SPACE -> state.toggle()
            KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_ENTER -> runtime.terminal(null, "Terminal")
            // The panel swiping down opens, and closed again: it comes from above, as the drawer from the bar.
            KeyEvent.KEYCODE_DPAD_DOWN ->
                if (state.pulled != null) {
                    state.closePull()
                } else {
                    state.openPull(runtime.panelOf(runtime.gestures.swipeDown) ?: return false)
                }
            KeyEvent.KEYCODE_A -> state.promptRequest = PromptRequest.Type
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> state.requestedPage = keyCode - KeyEvent.KEYCODE_0
            else -> return false
        }
        return true
    }
}
