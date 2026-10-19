package dev.gladimdim.superdesktop

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import dev.gladimdim.superdesktop.bridge.Bridge
import dev.gladimdim.superdesktop.bridge.Harness
import dev.gladimdim.superdesktop.live.Feed
import dev.gladimdim.superdesktop.terminal.KeyRow
import dev.gladimdim.superdesktop.terminal.TerminalPane

/**
 * One harness in the tile: its details, its live terminal, keys and a prompt. A page pinned to it shows
 * only this; the overview opens it in place of the list, or beside it on a wide tile. [leave] goes back
 * to every harness, labelled [leaveLabel].
 */
@Composable
internal fun HarnessView(
    b: Bridge?,
    h: Harness?,
    feed: Feed?,
    host: SdHost,
    leaveLabel: String,
    onOpen: (Bridge, Harness) -> Unit,
    onKeys: (Bridge, Harness, String, Boolean, () -> Unit) -> Unit,
    leave: (() -> Unit)?,
    onWidget: ((Bridge, Harness) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val c = host.colors
    if (b == null || h == null) {
        Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
            val waiting = b != null && feed?.state != Feed.State.LIVE
            Label(if (waiting) "connecting to ${b?.name}…" else "This harness has ended", host, c.muted, 13)
            if (!waiting && leave != null) Action(leaveLabel, host, onClick = leave)
        }
        return
    }
    val context = LocalContext.current
    val flash = rememberDoneFlash(h.id, h.status)
    val dot = lerp(statusColor(h.status, host), c.green, flash)
    // The draft survives leaving the page, a restart and a failed send; it's never sent by itself.
    var prompt by remember(b.key, h.id) { mutableStateOf(SdDrafts.get(context, b.key, h.id)) }
    LaunchedEffect(prompt) { SdDrafts.put(context, b.key, h.id, prompt) }
    var history by remember(h.id) { mutableStateOf(false) }
    var active by remember(h.id) { mutableStateOf(false) }
    val promptFocus = remember { FocusRequester() }
    // Back first lets go of the terminal, then leaves the harness: the home screen gets it after that.
    BackHandler(enabled = history) { history = false }
    BackHandler(enabled = active && !history) { active = false }
    BackHandler(enabled = !active && !history && leave != null) { leave?.invoke() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Spacer(Modifier.width(8.dp))
            Label(h.sessionTitle ?: h.agentName, host, c.foreground, 14, bold = true, modifier = Modifier.weight(1f))
            Label(statusLabel(h.status), host, dot, 11)
        }
        Label(listOf(b.name, h.details(), h.directoryDisplay ?: h.directory.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · "), host, c.muted, 11)
        if (history) {
            PromptHistoryPanel(b, h, host, onInsert = { prompt = it; history = false }, modifier = Modifier.fillMaxWidth().weight(1f))
        } else {
            TerminalPane(b, h.id, host, active, onActivate = { active = true }, modifier = Modifier.fillMaxWidth().weight(1f))
        }
        if (active) KeyRow(host, onKey = { text, enter -> onKeys(b, h, text, enter) {} }, onDone = { active = false })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val send = { if (prompt.isNotBlank()) onKeys(b, h, prompt, true) { prompt = "" } }
            Field(prompt, { prompt = it }, host, "prompt ${h.agentName}…", Modifier.weight(1f).focusRequester(promptFocus), onDone = send)
            KeyboardButton(host, active, promptFocus) { active = it }
            Action("send", host, strong = true, enabled = prompt.isNotBlank(), onClick = send)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (leave != null) Action(leaveLabel, host, onClick = leave)
            Action("history", host, strong = history) { history = !history }
            Action("open in app", host) { onOpen(b, h) }
            if (onWidget != null) Action("+ widget", host) { onWidget(b, h) }
        }
    }
}
