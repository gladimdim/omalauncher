package dev.gladimdim.tiling

import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.awaitCancellation

/** The app menu, Super+Space: type to narrow it, arrows to pick, Enter opens the app in a new tile. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun AppMenu(host: TilingHost) {
    val colors = host.colors
    val text = TextStyle(fontFamily = host.font, fontSize = 15.sp, color = colors.foreground)
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableIntStateOf(0) }
    // Every app by name, and after them what the desktop itself offers.
    val entries =
        remember {
            host.apps().sortedBy { it.label.lowercase() }.map { MenuEntry(it.label, it.component, it.icon) { Tiling.open(it) } } +
                MenuEntry("show keyboard shortcuts", "tiling:shortcuts", null) { Tiling.showHelp() }
        }
    val found =
        remember(query) {
            val q = query.trim().lowercase()
            if (q.isEmpty()) {
                entries
            } else {
                // Names that start with what was typed come before names that only hold it.
                entries.filter { it.label.lowercase().startsWith(q) } +
                    entries.filter { it.label.lowercase().let { name -> !name.startsWith(q) && q in name } }
            }
        }
    val field = remember { FocusRequester() }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { field.requestFocus() }
    // Back from a gesture, a mouse or the navigation bar closes it, as Esc does.
    BackHandler { Tiling.closeMenu() }
    // The menu's own keys come through [Tiling.key], ahead of home (which takes arrows to move among its tiles) and
    // whichever view has the focus. Esc arrives as Back from keyboards that send it so.
    val shown by rememberUpdatedState(found)
    DisposableEffect(Unit) {
        Tiling.menuKeys = { keyCode ->
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> picked = (picked + 1).coerceAtMost((shown.size - 1).coerceAtLeast(0))
                KeyEvent.KEYCODE_DPAD_UP -> picked = (picked - 1).coerceAtLeast(0)
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> shown.getOrNull(picked)?.let { it.open() }
                KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> Tiling.closeMenu()
                else -> null
            } != null
        }
        onDispose { Tiling.menuKeys = null }
    }
    LaunchedEffect(picked, found) { if (found.isNotEmpty()) list.scrollToItem(picked.coerceIn(0, found.lastIndex)) }
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background.copy(alpha = 0.35f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { Tiling.closeMenu() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(460.dp)
                .background(colors.background.copy(alpha = 0.96f))
                .border(2.dp, colors.accent)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(12.dp),
        ) {
            // Typed into from the hardware keyboard alone: asking Android for its keyboard would bring up its toolbar
            // and the navigation bar over the desktop.
            InterceptPlatformTextInput(interceptor = { _, _ -> awaitCancellation() }) {
                BasicTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        picked = 0
                    },
                    singleLine = true,
                    textStyle = text,
                    cursorBrush = SolidColor(colors.accent),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(colors.surface)
                            .padding(horizontal = 10.dp, vertical = 9.dp)
                            .focusRequester(field),
                )
            }
            Spacer(Modifier.size(8.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), state = list) {
                itemsIndexed(found, key = { _, app -> app.key }) { i, app ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(if (i == picked) Modifier.background(colors.accent.copy(alpha = 0.22f)) else Modifier)
                            .clickable { app.open() }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val icon = app.icon
                        if (icon != null) {
                            Image(icon, null, Modifier.size(26.dp))
                        } else {
                            BasicText("?", Modifier.width(26.dp), style = text.copy(color = colors.accent, textAlign = TextAlign.Center))
                        }
                        Spacer(Modifier.size(12.dp))
                        BasicText(app.label, style = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** A line of the app finder: an app, or something the desktop does. */
private class MenuEntry(
    val label: String,
    val key: String,
    val icon: androidx.compose.ui.graphics.ImageBitmap?,
    val open: () -> Unit,
)

/** What is still missing for the desktop to work, and the shortcuts when they are asked for ([Tiling.help]). */
@Composable
internal fun Notice(host: TilingHost) {
    val colors = host.colors
    val context = LocalContext.current
    val text = TextStyle(fontFamily = host.font, fontSize = 13.sp, color = colors.foreground)
    val quiet = text.copy(color = colors.muted)
    val helper = HelperLink.helper != null
    val keys = TilingKeyService.connected
    if (helper && keys && !Tiling.help) return
    Column(
        Modifier
            .background(colors.background.copy(alpha = 0.92f))
            .border(2.dp, colors.border)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (Tiling.help) Tiling.toggleHelp() }
            .padding(horizontal = 22.dp, vertical = 16.dp),
    ) {
        if (!helper) {
            BasicText(if (HelperLink.outdated) "the window helper is from an older build" else "the window helper is not running", style = text.copy(color = colors.accent))
            BasicText("start it from a computer: scripts/tiling-helper.sh", style = quiet)
            return@Column
        }
        if (Tiling.help) {
            for ((combo, what) in SHORTCUTS) {
                Row {
                    BasicText(combo, Modifier.width(300.dp), style = text)
                    BasicText(what, style = quiet)
                }
            }
        }
        if (!keys) {
            if (Tiling.help) Spacer(Modifier.size(10.dp))
            BasicText(
                "shortcuts need \"Tiling desktop shortcuts\" switched on: Accessibility › Downloaded apps  ›",
                Modifier.clickable { runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                style = text.copy(color = colors.accent),
            )
        }
    }
}

private val SHORTCUTS =
    listOf(
        "super + space" to "open an app",
        "super + return" to "terminal",
        "super + shift + b, f, c, e, m …" to "browser, files, calendar … (tiling.conf)",
        "super + w / q" to "close window",
        "super + 1…9, 0" to "switch workspace",
        "super + shift + 1…0" to "move window to workspace",
        "super + shift + alt + 1…0" to "move window there, stay here",
        "super + ctrl + tab" to "former workspace",
        "super + wheel, 3 finger swipe" to "next / previous workspace",
        "super + s" to "scratchpad",
        "super + alt + s" to "move window to the scratchpad",
        "super + alt + arrows" to "focus window",
        "alt + tab, alt + shift + tab" to "next / previous window",
        "super + shift + arrows" to "swap windows",
        "super + j" to "toggle window split",
        "super + t" to "float / tile window",
        "super + o" to "pop window out (float, on every workspace)",
        "super + f" to "full screen",
        "super + alt + f" to "full width, bar stays",
        "super + minus / equal" to "narrower / wider",
        "super + shift + minus / equal" to "shorter / taller",
        "… + alt, … + ctrl" to "resize a little, a lot",
        "super + left / right mouse drag" to "move / resize window",
        "super + shift + backspace" to "toggle window gaps",
        "super + comma" to "notifications",
        "super + shift + comma" to "dismiss all notifications",
        "super + alt + comma" to "dismiss last notification",
        "super + shift + alt + comma" to "notification history",
        "super + k" to "this list",
        "super + shift + d" to "leave the desktop",
        "right click on the bar" to "move it to the next edge",
    )
