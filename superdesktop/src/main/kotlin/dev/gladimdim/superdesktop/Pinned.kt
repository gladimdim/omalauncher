package dev.gladimdim.superdesktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gladimdim.superdesktop.bridge.Bridge
import dev.gladimdim.superdesktop.bridge.Harness
import dev.gladimdim.superdesktop.live.Feed

/**
 * One harness filling the page: its details, the terminal's last lines, and a prompt to send it.
 * The live terminal replaces the preview in phase 4.
 */
@Composable
internal fun PinnedHarness(
    b: Bridge?,
    h: Harness?,
    feed: Feed?,
    host: SdHost,
    onOpen: (Bridge, Harness) -> Unit,
    onSend: (Bridge, Harness, String, () -> Unit) -> Unit,
    onShowAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = host.colors
    if (b == null || h == null) {
        Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
            val waiting = b != null && feed?.state != Feed.State.LIVE
            Label(if (waiting) "connecting to ${b?.name}…" else "This harness has ended", host, c.muted, 13)
            if (!waiting) Action("show every harness", host, onClick = onShowAll)
        }
        return
    }
    val dot = statusColor(h.status, host)
    var prompt by remember(h.id) { mutableStateOf("") }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Spacer(Modifier.width(8.dp))
            Label(h.sessionTitle ?: h.agentName, host, c.foreground, 14, bold = true, modifier = Modifier.weight(1f))
            Label(h.status, host, dot, 11)
        }
        Label(listOf(b.name, h.details(), h.directoryDisplay ?: h.directory.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · "), host, c.muted, 11)
        h.lastPrompt?.let { Label("› $it", host, c.foreground, 12) }
        val shape = host.shape(8.dp)
        val scroll = rememberScrollState()
        val lines = h.preview?.trimEnd().orEmpty()
        // The newest lines are at the bottom: keep them in view as they change.
        LaunchedEffect(lines) { scroll.scrollTo(scroll.maxValue) }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(c.background.copy(alpha = 0.5f), shape)
                .border(1.dp, c.border, shape)
                .padding(10.dp),
        ) {
            Label(lines.ifEmpty { "no output yet" }, host, if (lines.isEmpty()) c.muted else c.foreground, 11, lines = Int.MAX_VALUE, modifier = Modifier.verticalScroll(scroll))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val send = { if (prompt.isNotBlank()) onSend(b, h, prompt) { prompt = "" } }
            Field(prompt, { prompt = it }, host, "prompt ${h.agentName}…", Modifier.weight(1f), onDone = send)
            Action("send", host, strong = true, enabled = prompt.isNotBlank(), onClick = send)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action("open in app", host) { onOpen(b, h) }
            Action("show every harness", host, onClick = onShowAll)
        }
    }
}
