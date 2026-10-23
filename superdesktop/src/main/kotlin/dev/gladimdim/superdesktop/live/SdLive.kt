package dev.gladimdim.superdesktop.live

import android.util.Log
import androidx.compose.runtime.Immutable
import dev.gladimdim.superdesktop.bridge.Bridge
import dev.gladimdim.superdesktop.bridge.HarnessDocument
import dev.gladimdim.superdesktop.bridge.HarnessFeedPolicy
import dev.gladimdim.superdesktop.bridge.NotPairedException
import dev.gladimdim.superdesktop.bridge.api
import dev.gladimdim.superdesktop.bridge.parseHarnessDocument
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
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

private const val TAG = "SuperDesktop"

/** What a page knows about one computer. [doc] is the last document seen, kept while offline. */
@Immutable
data class Feed(
    val state: State,
    val doc: HarnessDocument? = null,
    val error: String? = null,
) {
    enum class State { CONNECTING, LIVE, OFFLINE, NOT_PAIRED }
}

/**
 * Live harness lists, one connection per computer however many pages show it. A page
 * [acquire]s a computer while it is on screen and [release]s it when it leaves; the
 * connection outlives the last page by [GRACE_MS], so a swipe away and back doesn't reconnect.
 */
object SdLive {
    private const val GRACE_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Entry(
        var bridge: Bridge,
    ) {
        val feed = MutableStateFlow(Feed(Feed.State.CONNECTING))

        /** The address that answered last; actions go there too. */
        @Volatile var host: String = bridge.host
        var users = 0
        var job: Job? = null
        var stop: Job? = null
    }

    private val entries = HashMap<String, Entry>()

    @Synchronized fun acquire(bridge: Bridge): StateFlow<Feed> {
        val e = entries.getOrPut(bridge.key) { Entry(bridge) }
        e.users++
        e.stop?.cancel()
        e.stop = null
        if (e.bridge != bridge) {
            // Paired again: new token or addresses.
            e.bridge = bridge
            e.job?.cancel()
            e.job = null
        }
        if (e.job == null) e.job = scope.launch { run(e) }
        return e.feed
    }

    /**
     * Drops the backoff and tries every address again now, starting with [host] (one a probe found answering) when
     * given, keeping the last document on screen meanwhile.
     */
    @Synchronized fun retry(
        bridge: Bridge,
        host: String? = null,
    ) {
        val e = entries[bridge.key] ?: return
        if (e.users == 0) return
        e.job?.cancel()
        if (host != null) e.host = host
        e.feed.value = e.feed.value.copy(state = Feed.State.CONNECTING, error = null)
        e.job = scope.launch { run(e) }
    }

    /** [bridge]'s API at the address its feed reached last. */
    @Synchronized fun api(bridge: Bridge) = bridge.api(entries[bridge.key]?.host ?: bridge.host)

    @Synchronized fun release(bridge: Bridge) {
        val e = entries[bridge.key] ?: return
        e.users--
        if (e.users > 0) return
        e.stop =
            scope.launch {
                delay(GRACE_MS)
                synchronized(this@SdLive) {
                    if (e.users == 0) {
                        e.job?.cancel()
                        e.job = null
                        Log.d(TAG, "${e.bridge.name}: closed")
                    }
                }
            }
    }

    private class StreamEnd(
        val received: Boolean,
        val httpCode: Int?,
        val closeCode: Int?,
        val closeReason: String?,
    )

    private sealed interface Event {
        class Message(
            val text: String,
        ) : Event

        class Ended(
            val end: StreamEnd,
        ) : Event
    }

    /** Stream, poll while the stream is down, and move between the computer's addresses when one stops answering. */
    private suspend fun run(e: Entry) {
        val policy = HarnessFeedPolicy(System::currentTimeMillis)
        var hostIndex = e.bridge.hosts().indexOf(e.host).coerceAtLeast(0)
        while (scope.isActive) {
            val bridge = e.bridge
            val hosts = bridge.hosts()
            var host = hosts[hostIndex % hosts.size]
            val wait =
                if (policy.useStream()) {
                    val end = stream(e, bridge, host, policy)
                    if (end.httpCode == 401) {
                        e.feed.value = e.feed.value.copy(state = Feed.State.NOT_PAIRED, error = "This device was removed on the computer")
                        delay(30_000)
                        continue
                    }
                    // Unreachable: poll the next address, not this one again. Polling the dead one and moving on
                    // twice per round skipped every other address, so a LAN + Tailscale pair never tried Tailscale.
                    if (!end.received && end.httpCode == null) host = hosts[++hostIndex % hosts.size]
                    policy.onStreamEnded(end.received, end.httpCode, end.closeCode, end.closeReason)
                } else {
                    policy.pollOnlyRemaining()
                }
            if (wait > 0) poll(e, bridge, host, wait) { hostIndex++ }
        }
    }

    private suspend fun stream(
        e: Entry,
        bridge: Bridge,
        host: String,
        policy: HarnessFeedPolicy,
    ): StreamEnd {
        val events = Channel<Event>(Channel.UNLIMITED)
        val socket =
            bridge.api(host).harnessStream(
                object : WebSocketListener() {
                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        events.trySend(Event.Message(text))
                    }

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        webSocket.close(1000, null)
                        events.trySend(Event.Ended(StreamEnd(true, null, code, reason)))
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        Log.d(TAG, "${bridge.name} @ $host: stream failed: ${response?.code ?: t.message}")
                        events.trySend(Event.Ended(StreamEnd(false, response?.code, null, null)))
                    }
                },
            )
        var received = false
        try {
            while (true) {
                val event =
                    withTimeoutOrNull(HarnessFeedPolicy.DEAD_AFTER_MS) { events.receive() }
                        ?: return StreamEnd(received, null, null, null)
                when (event) {
                    is Event.Message -> {
                        val doc = runCatching { parseHarnessDocument(JSONObject(event.text)) }.getOrNull() ?: continue
                        if (!received) Log.d(TAG, "${bridge.name} @ $host: live, ${doc.harnesses.size} harnesses")
                        received = true
                        e.host = host
                        policy.onMessage()
                        e.feed.value = Feed(Feed.State.LIVE, doc)
                    }
                    is Event.Ended -> return StreamEnd(received || event.end.received, event.end.httpCode, event.end.closeCode, event.end.closeReason)
                }
            }
        } finally {
            socket.cancel()
        }
    }

    /** Polls GET /harnesses for [forMs]; [unreachable] moves on to the next address. */
    private suspend fun poll(
        e: Entry,
        bridge: Bridge,
        host: String,
        forMs: Long,
        unreachable: () -> Unit,
    ) {
        val until = System.currentTimeMillis() + forMs
        while (System.currentTimeMillis() < until) {
            val online =
                try {
                    e.feed.value = Feed(Feed.State.LIVE, bridge.api(host).harnesses())
                    e.host = host
                    true
                } catch (x: NotPairedException) {
                    e.feed.value = e.feed.value.copy(state = Feed.State.NOT_PAIRED, error = "This device was removed on the computer")
                    false
                } catch (x: Exception) {
                    if (x is kotlinx.coroutines.CancellationException) throw x
                    e.feed.value = e.feed.value.copy(state = Feed.State.OFFLINE, error = x.message)
                    unreachable()
                    delay(HarnessFeedPolicy.POLL_OFFLINE_MS)
                    return
                }
            delay(if (online) HarnessFeedPolicy.POLL_ONLINE_MS else HarnessFeedPolicy.POLL_OFFLINE_MS)
        }
    }
}
