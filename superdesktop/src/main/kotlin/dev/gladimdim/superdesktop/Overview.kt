package dev.gladimdim.superdesktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.gladimdim.superdesktop.bridge.Bridge
import dev.gladimdim.superdesktop.bridge.Harness
import dev.gladimdim.superdesktop.bridge.Usage
import dev.gladimdim.superdesktop.live.Feed

/** Every harness of [computers], grouped by computer, under the usage left. */
@Composable
internal fun Overview(
    computers: List<Bridge>,
    feeds: Map<String, Feed>,
    host: SdHost,
    closing: String?,
    onOpen: (Bridge, Harness) -> Unit,
    onAskClose: (String?) -> Unit,
    onClose: (Bridge, Harness) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val usage = computers.firstNotNullOfOrNull { feeds[it.key]?.doc?.usage?.takeIf { u -> u.isNotEmpty() } }
        if (usage != null) UsageStrip(usage, host)
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (b in computers) {
                val feed = feeds[b.key] ?: Feed(Feed.State.CONNECTING)
                item(key = "pc:" + b.key) { ComputerHeader(b, feed, host) }
                items(feed.doc?.harnesses.orEmpty(), key = { b.key + it.id }) { h ->
                    val key = b.key + h.id
                    HarnessCard(
                        h,
                        host,
                        confirmClose = closing == key,
                        onOpen = { onOpen(b, h) },
                        onAskClose = { onAskClose(if (closing == key) null else key) },
                        onClose = { onClose(b, h) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun UsageStrip(
    usage: List<Usage>,
    host: SdHost,
) {
    val c = host.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (u in usage.filter { it.ready && it.limits.isNotEmpty() }.take(3)) {
            val used = u.limits.mapNotNull { it.used }.maxOrNull() ?: continue
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Label("${u.name} ${(100 * (1 - used)).toInt()}% left", host, c.muted, 10)
                Box(Modifier.fillMaxWidth().height(3.dp).background(c.border)) {
                    Box(Modifier.fillMaxWidth((1 - used).toFloat()).height(3.dp).background(if (used > 0.85) c.red else c.accent))
                }
            }
        }
    }
}

@Composable
internal fun ComputerHeader(
    b: Bridge,
    feed: Feed,
    host: SdHost,
) {
    val c = host.colors
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(feedColor(feed, host), CircleShape))
        Spacer(Modifier.width(8.dp))
        Label(b.name, host, c.foreground, 13, bold = true, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Label(feedNote(feed), host, c.muted, 11)
    }
}

internal fun feedColor(
    feed: Feed,
    host: SdHost,
) = when (feed.state) {
    Feed.State.LIVE -> host.colors.green
    Feed.State.CONNECTING -> host.colors.muted
    Feed.State.OFFLINE, Feed.State.NOT_PAIRED -> host.colors.red
}

/** "3 harnesses · 1 working", "offline", … */
internal fun feedNote(feed: Feed): String =
    when (feed.state) {
        Feed.State.CONNECTING -> "connecting…"
        Feed.State.OFFLINE -> "offline"
        Feed.State.NOT_PAIRED -> "not paired"
        Feed.State.LIVE -> {
            val harnesses = feed.doc?.harnesses.orEmpty()
            val working = harnesses.count { it.status == "WORKING" }
            val total =
                when (harnesses.size) {
                    0 -> "no harnesses"
                    1 -> "1 harness"
                    else -> "${harnesses.size} harnesses"
                }
            if (working > 0) "$total · $working working" else total
        }
    }

internal fun statusColor(
    status: String,
    host: SdHost,
): Color =
    when (status) {
        "WORKING" -> host.colors.accent
        "WAITING" -> host.colors.yellow
        "ERROR" -> host.colors.red
        "FINISHED" -> host.colors.green
        else -> host.colors.muted
    }

/** The model, effort and, under a session title, the agent: "Claude Code · opus · high". */
internal fun Harness.details() = listOfNotNull(agentName.takeIf { sessionTitle != null }, model, effort).joinToString(" · ")

/** The terminal's last non-blank lines. */
internal fun Harness.tail(lines: Int) =
    preview
        ?.lines()
        ?.map { it.trimEnd() }
        ?.filter { it.isNotBlank() }
        ?.takeLast(lines)
        .orEmpty()

@Composable
private fun HarnessCard(
    h: Harness,
    host: SdHost,
    confirmClose: Boolean,
    onOpen: () -> Unit,
    onAskClose: () -> Unit,
    onClose: () -> Unit,
) {
    val c = host.colors
    val dot = statusColor(h.status, host)
    val shape = host.shape(10.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface.copy(alpha = 0.6f), shape)
            .border(1.dp, if (confirmClose) c.red else c.border, shape)
            .clickable(onClick = onOpen)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Spacer(Modifier.width(8.dp))
            Label(h.sessionTitle ?: h.agentName, host, c.foreground, 13, bold = true, modifier = Modifier.weight(1f))
            Label(h.status, host, dot, 11)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.clickable(onClick = onAskClose).padding(horizontal = 4.dp)) { Label("✕", host, c.muted, 13) }
        }
        h.details().takeIf { it.isNotEmpty() }?.let { Label(it, host, c.muted, 11) }
        (h.directoryDisplay ?: h.directory)?.let { Label(it, host, c.muted, 11) }
        h.lastPrompt?.let { Label("› $it", host, c.foreground, 11) }
        h.tail(3).takeIf { it.isNotEmpty() }?.let { Label(it.joinToString("\n"), host, c.muted, 10, lines = 3) }
        if (confirmClose) {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("Close it on the computer?", host, c.red, 12, modifier = Modifier.weight(1f))
                Action("keep", host, onClick = onAskClose)
                Action("close", host, color = c.red, strong = true, onClick = onClose)
            }
        }
    }
}
