package dev.gladimdim.superdesktop.live

import android.util.Log
import androidx.compose.runtime.Immutable
import dev.gladimdim.superdesktop.bridge.Bridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

private const val TAG = "SuperDesktop"

/** What a terminal pane knows about its harness. [tail] is the last 300 lines with their escapes, or null before the first frame. */
@Immutable
data class Terminal(
    val tail: String? = null,
    val ended: Boolean = false,
    val reconnecting: Boolean = false,
)

/**
 * Live terminals, one stream per harness however many tiles show it (a page and a widget, say).
 * Frames are parsed off the main thread, and an unchanged frame isn't published again. Like
 * [SdLive], a stream outlives its last tile by [GRACE_MS], so swiping away and back doesn't reconnect.
 */
object SdTerminals {
    private const val GRACE_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class Entry(
        val bridge: Bridge,
        val id: String,
    ) {
        val state = MutableStateFlow(Terminal())
        var users = 0
        var job: Job? = null
        var stop: Job? = null
    }

    private val entries = HashMap<String, Entry>()

    private fun key(
        bridge: Bridge,
        id: String,
    ) = bridge.key + "/" + id

    @Synchronized fun acquire(
        bridge: Bridge,
        id: String,
    ): StateFlow<Terminal> {
        val e = entries.getOrPut(key(bridge, id)) { Entry(bridge, id) }
        e.users++
        e.stop?.cancel()
        e.stop = null
        if (e.job == null) e.job = scope.launch { run(e) }
        return e.state
    }

    @Synchronized fun release(
        bridge: Bridge,
        id: String,
    ) {
        val k = key(bridge, id)
        val e = entries[k] ?: return
        e.users--
        if (e.users > 0) return
        e.stop =
            scope.launch {
                delay(GRACE_MS)
                synchronized(this@SdTerminals) {
                    if (e.users == 0) {
                        e.job?.cancel()
                        entries.remove(k)
                    }
                }
            }
    }

    private sealed interface Event {
        class Frame(
            val json: String,
        ) : Event

        class Closed(
            val httpCode: Int?,
        ) : Event
    }

    /** Streams frames into the entry until the session ends or nobody watches, reconnecting with 1–10 s backoff. */
    private suspend fun run(e: Entry) {
        var backoff = 1000L
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val events = Channel<Event>(Channel.CONFLATED)
            val socket =
                SdLive.api(e.bridge).terminalStream(
                    e.id,
                    object : WebSocketListener() {
                        // Conflated: while a frame waits to be parsed, a newer one replaces it.
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
                            Log.d(TAG, "terminal ${e.id}: ${response?.code ?: t.message}")
                            events.trySend(Event.Closed(response?.code))
                        }
                    },
                )
            try {
                while (true) {
                    when (val ev = events.receive()) {
                        is Event.Frame -> {
                            val o = runCatching { JSONObject(ev.json) }.getOrNull() ?: continue
                            val tail = if (o.isNull("tailAnsi")) (if (o.isNull("tail")) null else o.optString("tail")) else o.optString("tailAnsi")
                            if (o.optString("status") == "EXITED" && tail == null) {
                                e.state.value = e.state.value.copy(ended = true, reconnecting = false)
                                return
                            }
                            // An equal value isn't emitted, so an unchanged pane costs nothing downstream.
                            e.state.value = Terminal(tail ?: e.state.value.tail)
                            backoff = 1000L
                        }
                        is Event.Closed -> {
                            if (ev.httpCode == 404) {
                                e.state.value = e.state.value.copy(ended = true, reconnecting = false)
                                return
                            }
                            break
                        }
                    }
                }
            } finally {
                socket.cancel()
            }
            e.state.value = e.state.value.copy(reconnecting = true)
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(10_000L)
        }
    }
}
