package dev.omalauncher.ui

import android.app.Activity
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.text.font.FontFamily
import dev.gladimdim.tiling.Tiling
import dev.gladimdim.tiling.TilingApp
import dev.gladimdim.tiling.TilingColors
import dev.gladimdim.tiling.TilingHost
import dev.omalauncher.InstantActivity
import dev.omalauncher.LauncherData
import dev.omalauncher.Runtime
import dev.omalauncher.widgets.runtime.MediaListener
import java.io.File
import kotlinx.coroutines.awaitCancellation

// Fork: the tiling desktop. Adapts the launcher to the :tiling module, which knows nothing of it.

/** Puts the tiling desktop over home's content; it shows itself while a hardware keyboard is connected, and home stays under its windows. */
fun attachTiling(
    activity: Activity,
    runtime: Runtime,
    state: LauncherState,
) {
    val host =
        object : TilingHost {
            // Derived once per theme change, not on every read: the bar reads it as it draws.
            private val derived =
                derivedStateOf {
                    TilingColors(
                        background = Palette.background,
                        surface = Palette.surface,
                        foreground = Palette.foreground,
                        muted = Palette.muted,
                        accent = LauncherStyle.focusBorder,
                        border = LauncherStyle.tileBorder,
                    )
                }
            override val colors get() = derived.value
            override val font: FontFamily get() = LauncherStyle.font

            override fun apps(): List<TilingApp> {
                val catalog = runtime.androidApps
                // Work and cloned apps open through their profile, which a display's `am start` does not reach.
                return catalog.apps.filter { it.profile == null }.map { TilingApp(it.label, it.component.flattenToShortString(), catalog.icons[it.key]) }
            }

            // Workspace 1 shows home's page 1, and so on: a page of widgets for each, as far as there are pages.
            override fun workspaceShown(index: Int) {
                state.requestedPage = index + 1
            }

            override fun toggleDrawer() {
                state.toggle()
            }

            override fun closeDrawer() {
                state.close()
            }

            // Widgets stop refreshing under windows that hide them, as they do when home is not showing.
            override fun homeCovered(covered: Boolean) {
                runtime.widgets.covered = covered
            }

            // Beside the widgets, which Linux sees as ~/.config/omalauncher/widgets: a folder the editor and Files reach.
            override val configDir: File = LauncherData.widgetsDir(activity)

            override fun terminal() {
                runtime.terminal(null, "Terminal")
            }

            // Through the listener the launcher has for media and notification dots, when the user has let it in.
            override fun dismissNotifications(all: Boolean): Boolean {
                val listener = MediaListener.connected ?: return false
                return runCatching {
                    if (all) {
                        listener.cancelAllNotifications()
                    } else {
                        // Not a group's summary, which Android makes for an app's notifications and which takes them all with it.
                        listener.activeNotifications
                            .filter { it.isClearable && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
                            .maxByOrNull { it.postTime }
                            ?.let { listener.cancelNotification(it.key) }
                    }
                    true
                }.getOrDefault(false)
            }
        }
    // Apps opened from home itself (a pinned icon, its app list, a widget) become windows too.
    runtime.androidApps.opensInstead = { component ->
        val label = runtime.androidApps.apps.firstOrNull { it.component == component && it.profile == null }?.label ?: component.packageName
        Tiling.openFromHome(label, component.flattenToShortString())
    }
    Tiling.attach(activity, host)
}

/**
 * A Linux window that is about to open: a window of the tiling desktop while that is on, on a display of its own, and
 * otherwise (false) a task of its own as ever. Asked from whichever thread the compositor has the window on.
 */
fun opensAsTile(
    context: Context,
    label: String,
    intent: Intent,
): Boolean {
    if (!Tiling.active) return false
    if (Looper.myLooper() == Looper.getMainLooper()) return Tiling.openWindow(label, intent)
    Handler(Looper.getMainLooper()).post { if (!Tiling.openWindow(label, intent)) InstantActivity.startInstantly(context, intent) }
    return true
}

/**
 * Home under the tiling desktop. Its text fields (its bar, a widget's field, SUPER DESKTOP's) are typed into from
 * the hardware keyboard, which the desktop is only on with, without asking Android for its keyboard: that would bring
 * up the keyboard's toolbar and the navigation bar over the desktop each time a field is clicked. And the desktop is
 * told when home's own app list is open, so its windows step aside for it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TilingHome(
    state: LauncherState,
    content: @Composable () -> Unit,
) {
    LaunchedEffect(state) { snapshotFlow { state.drawer.value > 0.01f }.collect { Tiling.homeRaised(it) } }
    InterceptPlatformTextInput(
        interceptor = { request, next -> if (Tiling.active) awaitCancellation() else next.startInputMethod(request) },
        content = content,
    )
}
