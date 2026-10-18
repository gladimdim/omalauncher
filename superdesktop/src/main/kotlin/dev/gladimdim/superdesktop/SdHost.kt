package dev.gladimdim.superdesktop

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import java.io.File

/** The launcher's theme as SUPER DESKTOP draws it. Built anew when the theme changes. */
@Immutable
class SdColors(
    val isLight: Boolean,
    val background: Color,
    val surface: Color,
    val foreground: Color,
    val muted: Color,
    val accent: Color,
    val border: Color,
    val focusBorder: Color,
    val red: Color,
    val green: Color,
    val yellow: Color,
    /** The theme's color0..color15, for terminal output. */
    val ansi: List<Color>,
)

/**
 * What a SUPER DESKTOP tile needs from the launcher. The launcher implements it
 * (SdHook.kt in the app), so this module never depends on launcher classes.
 */
interface SdHost {
    /** Read in composition: follows theme changes. */
    val colors: SdColors

    /** The launcher's monospace font. */
    val mono: FontFamily

    /** A frame's shape with the launcher's corner style applied. */
    fun shape(radius: Dp): Shape

    /** The launcher's widget folder, where new SUPER DESKTOP pages are written. */
    val widgetsDir: File

    /** A text field in the tile took the keyboard; [release] takes its focus away. */
    fun fieldFocused(release: () -> Unit)

    /** That text field lost focus. */
    fun fieldUnfocused()
}
