package dev.omalauncher.widgets.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import dev.gladimdim.superdesktop.SdColors
import dev.gladimdim.superdesktop.SdHost
import dev.gladimdim.superdesktop.SuperDesktopTile
import dev.omalauncher.LauncherData
import dev.omalauncher.ui.LauncherStyle
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.TileField
import java.io.File

// Fork: the SuperDesktop element. Adapts the launcher to the :superdesktop module, which knows nothing of it.

@Composable
internal fun SdElement(
    p: Props,
    mod: Modifier,
) {
    val context = LocalContext.current
    val tiles = LocalHomeTiles.current
    val host =
        remember(tiles) {
            object : SdHost {
                private var field: TileField? = null

                override val colors get() = sdColors()
                override val mono: FontFamily get() = LauncherStyle.font

                override fun shape(radius: Dp): Shape = LauncherStyle.shape(radius)

                override val widgetsDir: File = LauncherData.widgetsDir(context)

                override fun fieldFocused(release: () -> Unit) {
                    val f = TileField(p.inst, release)
                    field = f
                    tiles?.focused(f)
                }

                override fun fieldUnfocused() {
                    field?.let { tiles?.unfocused(it) }
                    field = null
                }
            }
        }
    DisposableEffect(host) { onDispose { host.fieldUnfocused() } }
    SuperDesktopTile(p.observe("id")?.toString(), host, mod.fillMaxSize())
}

private fun sdColors(): SdColors {
    val named = Palette.current.named
    fun c(
        key: String,
        fallback: Color,
    ) = named[key] ?: fallback
    return SdColors(
        isLight = Palette.isLight,
        background = Palette.background,
        surface = Palette.surface,
        foreground = Palette.foreground,
        muted = Palette.muted,
        accent = Palette.accent,
        border = LauncherStyle.tileBorder,
        focusBorder = LauncherStyle.focusBorder,
        red = Palette.red,
        green = Palette.green,
        yellow = c("yellow", Color(0xFFE0AF68)),
        ansi = List(16) { i -> c("color$i", Palette.foreground) },
    )
}
