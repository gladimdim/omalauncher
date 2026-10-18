package dev.gladimdim.superdesktop

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

    /** The home page this tile is on, when the launcher knows it; new widgets go there. */
    fun page(): Int?

    /** A text field in the tile took the keyboard; [release] takes its focus away. */
    fun fieldFocused(release: () -> Unit)

    /** That text field lost focus. */
    fun fieldUnfocused()
}

/** The colors the tile last drew with, for SUPER DESKTOP's own screens; Tokyo Night until a tile has drawn. */
internal object SdTheme {
    var colors by androidx.compose.runtime.mutableStateOf(
        SdColors(
            isLight = false,
            background = Color(0xFF1A1B26),
            surface = Color(0xFF24283B),
            foreground = Color(0xFFC0CAF5),
            muted = Color(0xFF565F89),
            accent = Color(0xFF7AA2F7),
            border = Color(0xFF414868),
            focusBorder = Color(0xFF7AA2F7),
            red = Color(0xFFF7768E),
            green = Color(0xFF9ECE6A),
            yellow = Color(0xFFE0AF68),
            ansi = emptyList(),
        ),
    )
    var mono by androidx.compose.runtime.mutableStateOf<FontFamily>(FontFamily.Monospace)
}
