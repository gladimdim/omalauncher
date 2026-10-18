package dev.gladimdim.superdesktop

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gladimdim.superdesktop.bridge.Bridge
import dev.gladimdim.superdesktop.bridge.Harness
import dev.gladimdim.superdesktop.bridge.Usage
import dev.gladimdim.superdesktop.live.Feed
import dev.gladimdim.superdesktop.live.SdLive
import dev.gladimdim.superdesktop.pair.PairingActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SuperDesktop"

/**
 * A SUPER DESKTOP page: one tile, usually filling a home page, listing every paired computer's
 * harnesses live. [id] will key the page's own settings (which computers, which terminal).
 */
@Composable
fun SuperDesktopTile(
    id: String?,
    host: SdHost,
    modifier: Modifier = Modifier,
) {
    val c = host.colors
    val context = LocalContext.current
    SideEffect {
        SdTheme.colors = c
        SdTheme.mono = host.mono
    }
    DisposableEffect(id) {
        Log.d(TAG, "page $id shown")
        onDispose { Log.d(TAG, "page $id hidden") }
    }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { SdStore.load(context) } }
    val bridges by SdStore.bridges.collectAsState()

    // One live feed per computer while this page shows; SdLive shares them between pages.
    val feeds = remember { mutableStateMapOf<String, Feed>() }
    val scope = rememberCoroutineScope()
    for (b in bridges.orEmpty()) {
        key(b.key) {
            DisposableEffect(b) {
                val flow = SdLive.acquire(b)
                val job = scope.launch { flow.collect { feeds[b.key] = it } }
                onDispose {
                    job.cancel()
                    SdLive.release(b)
                }
            }
        }
    }

    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("SUPER DESKTOP", host, c.accent, 15, bold = true)
            Spacer(Modifier.weight(1f))
            if (!bridges.isNullOrEmpty()) Action("＋ pair", host) { openPairing(context) }
        }
        val list = bridges
        when {
            list == null -> Unit
            list.isEmpty() -> NoComputers(host) { openPairing(context) }
            else -> {
                val usage = list.firstNotNullOfOrNull { feeds[it.key]?.doc?.usage?.takeIf { u -> u.isNotEmpty() } }
                if (usage != null) UsageStrip(usage, host)
                LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (b in list) {
                        val feed = feeds[b.key] ?: Feed(Feed.State.CONNECTING)
                        item(key = "pc:" + b.key) { ComputerHeader(b, feed, host) }
                        items(feed.doc?.harnesses.orEmpty(), key = { b.key + it.id }) { h ->
                            HarnessCard(h, host) { openInApp(context, b, h) }
                        }
                    }
                }
            }
        }
    }
}

private fun openPairing(context: Context) = context.startActivity(Intent(context, PairingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

/** Hands harness [h] to the SUPER DESKTOP app's terminal, when it's installed and paired with the same computer. */
private fun openInApp(
    context: Context,
    b: Bridge,
    h: Harness,
) {
    val intent =
        Intent("com.omarchy.ailauncher.widget.OPEN_TERMINAL")
            .setClassName("com.gladimdim.superdesktop", "com.omarchy.ailauncher.MainActivity")
            .putExtra("widget_bridge_url", b.baseUrl())
            .putExtra("widget_harness_id", h.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Log.d(TAG, "SUPER DESKTOP app not installed")
    }
}

@Composable
private fun NoComputers(
    host: SdHost,
    pair: () -> Unit,
) {
    val c = host.colors
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        Label("No computer paired", host, c.foreground, 14)
        Label("Pair a SUPER DESKTOP computer to see its AI terminals here.", host, c.muted, 12, lines = 3)
        Action("pair a computer", host, pair)
    }
}

@Composable
private fun UsageStrip(
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
private fun ComputerHeader(
    b: Bridge,
    feed: Feed,
    host: SdHost,
) {
    val c = host.colors
    val (dot, note) =
        when (feed.state) {
            Feed.State.LIVE -> c.green to liveNote(feed.doc?.harnesses.orEmpty())
            Feed.State.CONNECTING -> c.muted to "connecting…"
            Feed.State.OFFLINE -> c.red to "offline"
            Feed.State.NOT_PAIRED -> c.red to "not paired"
        }
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(dot, CircleShape))
        Spacer(Modifier.width(8.dp))
        Label(b.name, host, c.foreground, 13, bold = true)
        Spacer(Modifier.weight(1f))
        Label(note, host, c.muted, 11)
    }
}

/** "3 harnesses · 1 working", or "no harnesses". */
private fun liveNote(harnesses: List<Harness>): String {
    if (harnesses.isEmpty()) return "no harnesses"
    val working = harnesses.count { it.status == "WORKING" }
    val total = if (harnesses.size == 1) "1 harness" else "${harnesses.size} harnesses"
    return if (working > 0) "$total · $working working" else total
}

@Composable
private fun HarnessCard(
    h: Harness,
    host: SdHost,
    open: () -> Unit,
) {
    val c = host.colors
    val dot =
        when (h.status) {
            "WORKING" -> c.accent
            "WAITING" -> c.yellow
            "ERROR" -> c.red
            "FINISHED" -> c.green
            else -> c.muted
        }
    val shape = host.shape(10.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface.copy(alpha = 0.6f), shape)
            .border(1.dp, c.border, shape)
            .clickable(onClick = open)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Spacer(Modifier.width(8.dp))
            Label(h.sessionTitle ?: h.agentName, host, c.foreground, 13, bold = true, modifier = Modifier.weight(1f))
            Label(h.status, host, dot, 11)
        }
        listOfNotNull(h.agentName.takeIf { h.sessionTitle != null }, h.model, h.effort).takeIf { it.isNotEmpty() }?.let {
            Label(it.joinToString(" · "), host, c.muted, 11)
        }
        (h.directoryDisplay ?: h.directory)?.let { Label(it, host, c.muted, 11) }
        h.lastPrompt?.let { Label("› $it", host, c.foreground, 11) }
        h.preview?.lines()?.map { it.trimEnd() }?.filter { it.isNotBlank() }?.takeLast(3)?.takeIf { it.isNotEmpty() }?.let {
            Label(it.joinToString("\n"), host, c.muted, 10, lines = 3)
        }
    }
}

@Composable
private fun Action(
    text: String,
    host: SdHost,
    onClick: () -> Unit,
) = Box(Modifier.border(1.dp, host.colors.border, host.shape(6.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp)) {
    Label(text, host, host.colors.accent, 12)
}

@Composable
private fun Label(
    text: String,
    host: SdHost,
    color: Color,
    size: Int,
    bold: Boolean = false,
    lines: Int = 1,
    modifier: Modifier = Modifier,
) = BasicText(
    text,
    modifier,
    style = TextStyle(color = color, fontFamily = host.mono, fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
    maxLines = lines,
    overflow = TextOverflow.Ellipsis,
)
