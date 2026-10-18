package dev.gladimdim.superdesktop.terminal

import kotlin.math.floor

// Ported from the SUPER DESKTOP app's TerminalViewport.kt (see bridge/SOURCE.md); protocol in PROTOCOL.md,
// "Optional phone viewport sizing".

/** A terminal grid in cells. */
data class TerminalViewportSize(
    val columns: Int,
    val rows: Int,
)

internal object TerminalViewportGrid {
    /**
     * The grid that fits [width] x [height] pixels at the readable default font, never the fitted font from a
     * received grid (that would feed resizing back into itself). Null when it's too small to be worth sizing.
     */
    fun fit(
        width: Float,
        height: Float,
        cellWidth: Float,
        lineHeight: Float,
    ): TerminalViewportSize? {
        if (width <= 0 || height <= 0 || !cellWidth.isFinite() || !lineHeight.isFinite() || cellWidth <= 0 || lineHeight <= 0) return null
        // One cell of slack for font measurement and glyph overhang.
        val columns = floor(width / cellWidth).toInt() - 1
        val rows = floor(height / lineHeight).toInt() - 1
        if (columns < 20 || rows < 5) return null
        return TerminalViewportSize(columns.coerceAtMost(500), rows.coerceAtMost(300))
    }
}

/**
 * One output socket's sizing lease. Nothing is sent until the computer advertises viewport version 1, so
 * older computers get no new messages. The computer drops a lease 30 s after its last renewal.
 */
internal class TerminalViewport(
    private val send: (String) -> Boolean,
) {
    private var supported = false
    private var sent: TerminalViewportSize? = null
    private var sentAt = 0L

    /** Returns true when sizing just became possible, so the caller sends its size. */
    fun advertise(version: Int): Boolean {
        val before = supported
        supported = version == 1
        if (!supported) sent = null
        return supported && !before
    }

    /** Asks for [size], or gives the lease back for null; an unchanged size is only renewed every [RENEW_MS]. */
    fun update(
        size: TerminalViewportSize?,
        now: Long,
    ) {
        if (!supported) return
        if (size == null) {
            release()
            return
        }
        if (size == sent && now - sentAt < RENEW_MS) return
        if (send("""{"type":"viewport","columns":${size.columns},"rows":${size.rows}}""")) {
            sent = size
            sentAt = now
        }
    }

    private fun release() {
        if (sent != null) send("""{"type":"viewport","release":true}""")
        sent = null
    }

    fun close() {
        release()
        supported = false
    }

    companion object {
        const val RENEW_MS = 10_000L
    }
}
