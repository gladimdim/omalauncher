package dev.gladimdim.superdesktop.bridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ParseTest {
    private val pin = "ab".repeat(32)
    private val json = """{"v":3,"host":"192.168.1.42","port":8759,"fingerprint":"$pin","secret":"${"c".repeat(48)}","expiresIn":300}"""

    @Test fun invitationFromLinkAndJson() {
        val link = "superdesktop://pair?data=" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        val expected = Invitation("192.168.1.42", 8759, pin, "c".repeat(48))
        assertEquals(expected, Invitation.parse(link))
        assertEquals(expected, Invitation.parse("```\n$json\n```"))
    }

    @Test fun oldInvitationsAreRefused() {
        val e = runCatching { Invitation.parse(json.replace("\"v\":3", "\"v\":2")) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    @Test fun document() {
        val doc =
            parseHarnessDocument(
                JSONObject(
                    """{"protocolVersion":3,"harnesses":[{"id":"sd_term_1","agentType":"claude","agentName":"Claude Code","status":"WORKING",
                    "model":"opus","effort":null,"lastPrompt":"fix it","preview":"a\nb","directoryDisplay":"~/x"}],
                    "usage":[{"id":"claude","name":"Claude","ready":true,"limits":[{"label":"5h","percentUsed":0.25},{"label":"week","percentUsed":null}]}]}""",
                ),
            )
        val h = doc.harnesses.single()
        assertEquals("Claude Code", h.agentName)
        assertEquals("opus", h.model)
        assertNull(h.effort)
        assertEquals(0.25, doc.usage.single().limits[0].used!!, 0.0)
        assertNull(doc.usage.single().limits[1].used)
    }

    @Test fun hostsTrySavedThenTailscale() {
        val b = Bridge("192.168.1.42", 8759, "t", pin, "id", "pc",
            listOf(BridgeAddress("192.168.1.42", "lan", true), BridgeAddress("100.1.2.3", "tailscale", true), BridgeAddress("127.0.0.1", "loopback", true)))
        assertEquals(listOf("192.168.1.42", "100.1.2.3"), b.hosts())
    }

    @Test fun existingUsageCountersAndTimestampsAreOptionalAndKeepZeroDistinctFromUnknown() {
        val doc = parseHarnessDocument(JSONObject("""{"usage":[{"id":"codex","ready":true,
          "todayTokens":3000000000,"todaySessions":0,"todayPrompts":null,"totalSessions":-1,"totalPrompts":42,
          "updatedAt":"2026-10-19T10:00:00Z","limits":[{"label":"5h","percentUsed":0.2,"resetsAt":"2026-10-19T15:00:00Z"}]
        },{"id":"claude"}]}"""))
        val usage = doc.usage.first()
        assertEquals(3_000_000_000, usage.todayTokens)
        assertEquals(0L, usage.todaySessions)
        assertNull(usage.todayPrompts)
        assertNull(usage.totalSessions)
        assertEquals(42L, usage.totalPrompts)
        assertEquals("2026-10-19T10:00:00Z", usage.updatedAt)
        assertEquals("2026-10-19T15:00:00Z", usage.limits.single().resetsAt)
        assertNull(doc.usage.last().todayTokens)
        assertNull(doc.usage.last().updatedAt)
    }
}
