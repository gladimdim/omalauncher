package dev.gladimdim.superdesktop.bridge

// Copied from the SUPER DESKTOP app's HarnessFeed.kt (see SOURCE.md).

/**
 * Transport choice for one computer's harness list: the pushed WebSocket
 * stream first, HTTP polling while it is unavailable. Pure logic: the caller
 * supplies the clock and does the network work.
 */
internal class HarnessFeedPolicy(
    private val now: () -> Long,
) {
    private var failures = 0
    private var pollOnlyUntil = 0L

    /** Whether the next attempt should open the list stream (else poll HTTP). */
    fun useStream(): Boolean = now() >= pollOnlyUntil

    /** Milliseconds of HTTP polling left before the stream should be retried. */
    fun pollOnlyRemaining(): Long = (pollOnlyUntil - now()).coerceAtLeast(0)

    /** A message arrived: the stream works, so later drops retry quickly. */
    fun onMessage() {
        failures = 0
    }

    /**
     * The stream ended. Returns how long to fall back to HTTP polling before
     * the next stream attempt (0 = reconnect now).
     */
    fun onStreamEnded(
        received: Boolean,
        httpCode: Int?,
        closeCode: Int?,
        closeReason: String?,
    ): Long {
        // The bridge rotates long-lived sockets with 1000 "reconnect".
        if (closeCode == 1000 && closeReason == "reconnect") {
            failures = 0
            return 0
        }
        // A bridge that refuses the upgrade predates the endpoint or disabled it: poll for a while before probing again.
        if (!received && httpCode != null && httpCode != 401 && httpCode != 408 && httpCode != 429 && httpCode < 500) {
            failures = 0
            pollOnlyUntil = now() + UNSUPPORTED_RETRY_MS
            return UNSUPPORTED_RETRY_MS
        }
        if (received) failures = 0
        failures = (failures + 1).coerceAtMost(16)
        return (INITIAL_BACKOFF_MS shl (failures - 1).coerceAtMost(5)).coerceAtMost(MAX_BACKOFF_MS)
    }

    companion object {
        /** The bridge sends at least every 20 s; silence beyond this is a dead socket. */
        const val DEAD_AFTER_MS = 45_000L
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val UNSUPPORTED_RETRY_MS = 5 * 60_000L
        const val POLL_ONLINE_MS = 2_000L
        const val POLL_OFFLINE_MS = 5_000L
    }
}
