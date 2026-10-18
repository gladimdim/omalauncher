package dev.gladimdim.superdesktop.terminal

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gladimdim.superdesktop.Label
import dev.gladimdim.superdesktop.SdHost
import dev.gladimdim.superdesktop.bridge.Bridge
import dev.gladimdim.superdesktop.live.SdLive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

private const val TAG = "SuperDesktop"

/** The tile's text size: as large as fits the widest line, between these. */
private val MAX_SP = 11f
private val MIN_SP = 7f

/** A monospace cell is about this much of the font size wide. */
private const val CELL_EM = 0.6f

/** What a pane knows about its harness's terminal. */
private class Pane {
    var frame by mutableStateOf<String?>(null)
    var ended by mutableStateOf(false)
    var reconnecting by mutableStateOf(false)
}

private sealed interface Event {
    class Frame(
        val json: String,
    ) : Event

    class Closed(
        val httpCode: Int?,
    ) : Event
}

/**
 * Harness [id]'s live terminal on [b], fitted to the tile. Until [active] (a tap makes it so) it only
 * scrolls up and down, so swipes still turn home pages; once active it also pans sideways.
 */
@Composable
internal fun TerminalPane(
    b: Bridge,
    id: String,
    host: SdHost,
    active: Boolean,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = host.colors
    val pane = remember(b.key, id) { Pane() }
    LaunchedEffect(b.key, id) { stream(b, id, pane) }

    val palette = c.ansi.takeIf { it.size >= 16 } ?: DEFAULT_PALETTE
    val renderer = remember(palette, c.foreground, c.background) { AnsiRenderer(palette, c.foreground, c.background) }
    val raw = pane.frame.orEmpty()
    // tmux pads the pane with empty lines below the cursor.
    val lines = remember(raw) { raw.split('\n').dropLastWhile { TerminalAnsi.plain(it).isBlank() } }
    val text = remember(lines, renderer) { renderer.render(lines.joinToString("\n")) }
    val columns = remember(lines) { lines.maxOfOrNull { TerminalAnsi.plain(it).length }?.coerceIn(20, 300) ?: 80 }

    val shape = host.shape(8.dp)
    BoxWithConstraints(
        modifier
            .background(c.background.copy(alpha = 0.7f), shape)
            .border(if (active) 2.dp else 1.dp, if (active) c.focusBorder else c.border, shape),
    ) {
        val density = LocalDensity.current
        val fit = with(density) { ((maxWidth - 16.dp).toPx() / (columns * CELL_EM)).toSp().value }
        val size = fit.coerceIn(MIN_SP, MAX_SP).sp
        val vertical = rememberScrollState()
        val horizontal = rememberScrollState()
        // Follow new output while at the bottom; stay put once scrolled up to read.
        var follow by remember { mutableStateOf(true) }
        LaunchedEffect(vertical.isScrollInProgress) {
            if (!vertical.isScrollInProgress) follow = vertical.value >= vertical.maxValue - 80
        }
        LaunchedEffect(text, vertical.maxValue) { if (follow) vertical.scrollTo(vertical.maxValue) }

        Box(
            Modifier
                .fillMaxSize()
                .clickable(enabled = !active, indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onActivate)
                .verticalScroll(vertical),
        ) {
            BasicText(
                text,
                Modifier.horizontalScroll(horizontal, enabled = active).padding(8.dp),
                style = TextStyle(color = c.foreground, fontFamily = host.mono, fontSize = size, lineHeight = size * 1.25f),
                softWrap = false,
            )
        }
        when {
            pane.ended -> Badge("session ended", host, c.muted, Modifier.align(Alignment.Center))
            pane.frame == null -> Badge("connecting…", host, c.muted, Modifier.align(Alignment.Center))
            pane.reconnecting -> Badge("reconnecting…", host, c.yellow, Modifier.align(Alignment.TopEnd))
            !active -> Badge("tap to type", host, c.muted, Modifier.align(Alignment.BottomEnd))
        }
    }
}

@Composable
private fun Badge(
    text: String,
    host: SdHost,
    color: Color,
    modifier: Modifier,
) = Box(modifier.padding(6.dp).background(host.colors.background.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
    Label(text, host, color, 10)
}

/** Streams [id]'s frames into [pane] until the session ends or the pane leaves, reconnecting with backoff. */
private suspend fun stream(
    b: Bridge,
    id: String,
    pane: Pane,
) {
    var backoff = 1000L
    while (kotlinx.coroutines.currentCoroutineContext().isActive && !pane.ended) {
        val events = Channel<Event>(Channel.UNLIMITED)
        val socket =
            SdLive.api(b).terminalStream(
                id,
                object : WebSocketListener() {
                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        events.trySend(Event.Frame(text))
                    }

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        webSocket.close(1000, null)
                        events.trySend(Event.Closed(null))
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        Log.d(TAG, "terminal $id: ${response?.code ?: t.message}")
                        events.trySend(Event.Closed(response?.code))
                    }
                },
            )
        try {
            while (true) {
                when (val e = events.receive()) {
                    is Event.Frame -> {
                        val o = runCatching { JSONObject(e.json) }.getOrNull() ?: continue
                        val tail = if (o.isNull("tailAnsi")) (if (o.isNull("tail")) null else o.optString("tail")) else o.optString("tailAnsi")
                        if (o.optString("status") == "EXITED" && tail == null) {
                            pane.ended = true
                            return
                        }
                        if (tail != null) pane.frame = tail
                        pane.reconnecting = false
                        backoff = 1000L
                    }
                    is Event.Closed -> {
                        // An unknown session is gone for good.
                        if (e.httpCode == 404) pane.ended = true
                        break
                    }
                }
            }
        } finally {
            socket.cancel()
        }
        if (pane.ended) return
        pane.reconnecting = true
        delay(backoff)
        backoff = (backoff * 2).coerceAtMost(10_000L)
    }
}

/** Tokyo Night's terminal colors, for a launcher palette without all sixteen. */
private val DEFAULT_PALETTE =
    listOf(
        0xFF15161E, 0xFFF7768E, 0xFF9ECE6A, 0xFFE0AF68, 0xFF7AA2F7, 0xFFBB9AF7, 0xFF7DCFFF, 0xFFA9B1D6,
        0xFF414868, 0xFFF7768E, 0xFF9ECE6A, 0xFFE0AF68, 0xFF7AA2F7, 0xFFBB9AF7, 0xFF7DCFFF, 0xFFC0CAF5,
    ).map { Color(it) }
