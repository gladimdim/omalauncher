package dev.gladimdim.tiling

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
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
    val apps = remember { host.apps().sortedBy { it.label.lowercase() } }
    val found =
        remember(query) {
            val q = query.trim().lowercase()
            if (q.isEmpty()) {
                apps
            } else {
                // Names that start with what was typed come before names that only hold it.
                apps.filter { it.label.lowercase().startsWith(q) } +
                    apps.filter { it.label.lowercase().let { name -> !name.startsWith(q) && q in name } }
            }
        }
    val field = remember { FocusRequester() }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { field.requestFocus() }
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
                            .focusRequester(field)
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionDown -> picked = (picked + 1).coerceAtMost((found.size - 1).coerceAtLeast(0))
                                    Key.DirectionUp -> picked = (picked - 1).coerceAtLeast(0)
                                    Key.Enter, Key.NumPadEnter -> found.getOrNull(picked)?.let(Tiling::open)
                                    Key.Escape -> Tiling.closeMenu()
                                    else -> return@onPreviewKeyEvent false
                                }
                                true
                            },
                )
            }
            Spacer(Modifier.size(8.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), state = list) {
                itemsIndexed(found, key = { _, app -> app.component }) { i, app ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(if (i == picked) Modifier.background(colors.accent.copy(alpha = 0.22f)) else Modifier)
                            .clickable { Tiling.open(app) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val icon = app.icon
                        if (icon != null) Image(icon, null, Modifier.size(26.dp)) else Spacer(Modifier.size(26.dp))
                        Spacer(Modifier.size(12.dp))
                        BasicText(app.label, style = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** What is still missing for the desktop to work, and the shortcuts when they are asked for ([Tiling.help]). */
@Composable
internal fun Notice(host: TilingHost) {
    val colors = host.colors
    val context = LocalContext.current
    val text = TextStyle(fontFamily = host.font, fontSize = 14.sp, color = colors.foreground)
    val quiet = text.copy(color = colors.muted)
    val helper = HelperLink.helper != null
    val keys = TilingKeyService.connected
    if (helper && keys && !Tiling.help) return
    Column(Modifier.background(colors.background.copy(alpha = 0.88f)).padding(horizontal = 22.dp, vertical = 16.dp)) {
        if (!helper) {
            BasicText(if (HelperLink.outdated) "the window helper is from an older build" else "the window helper is not running", style = text.copy(color = colors.accent))
            BasicText("start it from a computer: scripts/tiling-helper.sh", style = quiet)
            return@Column
        }
        if (Tiling.help) {
            for ((combo, what) in SHORTCUTS) {
                Row {
                    BasicText(combo, Modifier.width(210.dp), style = text)
                    BasicText(what, style = quiet)
                }
            }
        }
        if (!keys) {
            if (Tiling.help) Spacer(Modifier.size(10.dp))
            BasicText(
                "shortcuts need \"Tiling desktop shortcuts\" in Accessibility  ›",
                Modifier.clickable { runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                style = text.copy(color = colors.accent),
            )
        }
    }
}

private val SHORTCUTS =
    listOf(
        "super + space" to "open an app",
        "super + 1…5" to "workspace",
        "super + shift + 1…5" to "move window to workspace",
        "super + alt + arrows" to "focus window",
        "super + shift + arrows" to "swap windows",
        "super + f" to "full screen",
        "super + w" to "close window",
        "super + shift + d" to "leave the desktop",
        "right click on the bar" to "move it to the next edge",
    )
