package dev.gladimdim.superdesktop.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalViewportTest {
    @Test fun fitLeavesACellOfSlack() {
        assertEquals(TerminalViewportSize(49, 39), TerminalViewportGrid.fit(500f, 600f, 10f, 15f))
        assertNull("too narrow to size", TerminalViewportGrid.fit(150f, 600f, 10f, 15f))
    }

    @Test fun nothingIsSentBeforeTheComputerAdvertises() {
        val sent = mutableListOf<String>()
        val v = TerminalViewport { sent += it; true }
        v.update(TerminalViewportSize(40, 30), 0)
        assertTrue(sent.isEmpty())
        assertTrue(v.advertise(1))
        v.update(TerminalViewportSize(40, 30), 0)
        assertEquals(listOf("""{"type":"viewport","columns":40,"rows":30}"""), sent)
    }

    @Test fun sameSizeRenewsOnlyAfterTheInterval() {
        val sent = mutableListOf<String>()
        val v = TerminalViewport { sent += it; true }
        v.advertise(1)
        val s = TerminalViewportSize(40, 30)
        v.update(s, 0)
        v.update(s, 5_000)
        assertEquals(1, sent.size)
        v.update(s, TerminalViewport.RENEW_MS)
        assertEquals(2, sent.size)
        v.update(null, TerminalViewport.RENEW_MS)
        assertEquals("""{"type":"viewport","release":true}""", sent.last())
    }
}
