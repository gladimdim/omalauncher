package dev.gladimdim.superdesktop

import dev.gladimdim.superdesktop.bridge.RelaunchMode
import dev.gladimdim.superdesktop.bridge.relaunchFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperDesktop210Test {
    @Test
    fun harnessDraftShortcutsMatchExpectedHarnessesAndShellIsEmpty() {
        val agy = harnessDraftShortcuts("antigravity")
        assertEquals(listOf("@" to false, "/" to false, "/resume" to true, "/model" to true, "/compact" to true), agy)
        assertEquals(agy, harnessDraftShortcuts("AGY"))

        val claude = harnessDraftShortcuts("claude").map { it.first }
        assertTrue(claude.containsAll(listOf("@", "/", "/compact", "/clear", "/model", "/resume")))

        val codex = harnessDraftShortcuts("codex").map { it.first }
        assertTrue(codex.containsAll(listOf("@", "/", "/model", "/compact", "/diff", "/new")))

        assertTrue(harnessDraftShortcuts("grok").any { it.first == "/minimal" })
        assertTrue(harnessDraftShortcuts("hermes").any { it.first == "/queue " })
        assertTrue(harnessDraftShortcuts("pi").any { it.first == "/tree" })
        assertTrue(harnessDraftShortcuts("opencode").any { it.first == "/models" })
        assertTrue(harnessDraftShortcuts("amp").isNotEmpty())
        assertTrue(harnessDraftShortcuts("copilot").isNotEmpty())
        assertTrue(harnessDraftShortcuts("shell").isEmpty())
    }

    @Test
    fun relaunchFailureExplainsMissingRouteAndSessionStates() {
        assertEquals("FRESH", RelaunchMode.FRESH.name)
        assertEquals("resume", RelaunchMode.RESUME.wire)
        assertEquals(
            "Update SUPER DESKTOP on the computer to restart or resume terminals.",
            relaunchFailure(404, null),
        )
        assertEquals("This terminal is no longer running.", relaunchFailure(404, "no_such_session"))
        assertEquals("This terminal does not have a conversation to resume.", relaunchFailure(400, "unsupported_resume"))
        assertEquals("SUPER DESKTOP is not running on the computer.", relaunchFailure(503, "desktop_not_running"))
        assertEquals("Restart timed out. Check the computer before trying again.", relaunchFailure(504, "relaunch_timeout"))
    }

    @Test
    fun statusCuesRecognizeWaitingAndError() {
        assertTrue(needsYou("WAITING"))
        assertFalse(needsYou("ERROR"))
        assertTrue(isError("ERROR"))
        assertFalse(isError("WORKING"))
        assertEquals("NEEDS YOU", statusLabel("WAITING"))
        assertEquals("ERROR", statusLabel("ERROR"))
    }
}
