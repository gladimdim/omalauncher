package dev.omalauncher.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.text.font.FontFamily
import dev.gladimdim.tiling.Tiling
import dev.gladimdim.tiling.TilingApp
import dev.gladimdim.tiling.TilingColors
import dev.gladimdim.tiling.TilingHost
import dev.omalauncher.Runtime
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
        }
    // Apps opened from home itself (a pinned icon, its app list, a widget) become windows too.
    runtime.androidApps.opensInstead = { component ->
        val label = runtime.androidApps.apps.firstOrNull { it.component == component && it.profile == null }?.label ?: component.packageName
        Tiling.openFromHome(label, component.flattenToShortString())
    }
    Tiling.attach(activity, host)
}

/**
 * Home's text fields under the tiling desktop (its bar, a widget's field, SUPER DESKTOP's): typed into from the
 * hardware keyboard, which the desktop is only on with, without asking Android for its keyboard. That would bring up
 * the keyboard's toolbar and the navigation bar over the desktop each time a field is clicked.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HardwareTyping(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, next -> if (Tiling.active) awaitCancellation() else next.startInputMethod(request) },
        content = content,
    )
}
