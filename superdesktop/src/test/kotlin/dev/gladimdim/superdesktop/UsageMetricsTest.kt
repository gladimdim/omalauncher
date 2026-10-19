package dev.gladimdim.superdesktop

import dev.gladimdim.superdesktop.bridge.Bridge
import dev.gladimdim.superdesktop.bridge.parseHarnessDocument
import dev.gladimdim.superdesktop.live.Feed
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UsageMetricsTest {
    private val laptop = Bridge("192.168.1.1", 8759, "t", "laptop-pin", "1", "Laptop")
    private val desktop = Bridge("100.1.1.1", 8759, "t", "desktop-pin", "2", "Desktop")

    private fun feed(tokens: Long = 1200, used: String = "0.25", ready: Boolean = true) = Feed(Feed.State.LIVE,
        parseHarnessDocument(JSONObject("""{
          "harnesses":[{"id":"one","agentType":"codex","status":"WORKING"},{"id":"two","agentType":"claude","status":"IDLE"}],
          "usage":[{"id":"codex","name":"Codex","ready":$ready,
            "todayTokens":$tokens,"todaySessions":0,"todayPrompts":3,"totalSessions":4,"totalPrompts":5,
            "updatedAt":"2026-10-19T10:00:00Z",
            "limits":[{"label":"Weekly","percentUsed":$used,"resetsAt":"2026-10-20T10:00:00Z"}]}]
        }""")))

    @Test fun sameProviderOnTwoPcsRemainsIndependentAndAddressChangesPreserveSelection() {
        val feeds = mapOf(laptop.key to feed(1200), desktop.key to feed(9200))
        val metrics = usageMetrics(listOf(laptop, desktop), feeds)
        val id = "${desktop.key}|codex/todayTokens"
        assertEquals("9.2K", selectedUsageMetric(id, metrics, listOf(laptop, desktop)).value)
        assertEquals("1.2K", metrics.single { it.id == "${laptop.key}|codex/todayTokens" }.value)
        val moved = desktop.copy(host = "192.168.100.1", name = "Renamed PC")
        val renamedMetrics = usageMetrics(listOf(laptop, moved), feeds)
        assertEquals("Renamed PC", selectedUsageMetric(id, renamedMetrics, listOf(laptop, moved)).computerName)
        assertEquals("4", metrics.single { it.id == "live/total" }.value)
        assertEquals("2", metrics.single { it.id == "${desktop.key}|live/total" }.value)
    }

    @Test fun offlineDataNeverLooksLiveAndPartialTotalsAreUnavailable() {
        val live = feed()
        val metrics = usageMetrics(listOf(laptop, desktop), mapOf(laptop.key to live, desktop.key to live.copy(state = Feed.State.OFFLINE)))
        assertEquals("—", metrics.single { it.id == "live/total" }.value)
        for (metric in metrics.filter { it.computer == desktop.key }) {
            assertEquals("—", metric.value)
            assertNull(metric.remaining)
            assertTrue(metric.detail.contains("offline"))
        }
        assertEquals("1.2K", metrics.single { it.id == "${laptop.key}|codex/todayTokens" }.value)
    }

    @Test fun missingOrUnreadyCountersStayUnknownButZeroIsReal() {
        val f = feed()
        val u = f.doc!!.usage.single().copy(todayTokens = null, todayPrompts = null)
        val metrics = usageMetrics(listOf(laptop), mapOf(laptop.key to f.copy(doc = f.doc.copy(usage = listOf(u)))))
        assertEquals("—", metrics.single { it.id.endsWith("/todayTokens") }.value)
        assertEquals("0", metrics.single { it.id.endsWith("/todaySessions") }.value)
        val unavailable = usageMetrics(listOf(laptop), mapOf(laptop.key to feed(ready = false)))
        assertTrue(unavailable.filter { it.provider == "codex" }.all { it.value == "—" && it.remaining == null })
    }

    @Test fun quotaIsRemainingClampedAndIncludesResetAndUpdateTimes() {
        val metrics = usageMetrics(listOf(laptop), mapOf(laptop.key to feed()))
        val quota = metrics.single { "/limit/" in it.id }
        assertEquals("75% left", quota.value)
        assertEquals(.75f, quota.remaining)
        assertTrue(quota.detail.contains("Resets "))
        assertTrue(quota.detail.contains("Updated "))
        for ((used, expected) in listOf("2" to "0% left", "-1" to "100% left", "null" to "—")) {
            assertEquals(expected, usageMetrics(listOf(laptop), mapOf(laptop.key to feed(used = used))).single { "/limit/" in it.id }.value)
        }
    }

    @Test fun defaultsFollowOneProviderAndMissingSavedChoicesNeverMoveToAnotherPc() {
        val metrics = usageMetrics(listOf(laptop, desktop), mapOf(laptop.key to feed(), desktop.key to feed(9000)))
        assertEquals(listOf("${laptop.key}|codex/limit/Weekly", "${laptop.key}|codex/todayTokens", "${laptop.key}|codex/todaySessions", "live/total"), defaultUsageSlots(metrics))
        val savedId = "${desktop.key}|codex/todayTokens"
        val gone = selectedUsageMetric(savedId, usageMetrics(listOf(laptop), mapOf(laptop.key to feed())), listOf(laptop))
        assertEquals(savedId, gone.id)
        assertEquals("—", gone.value)
        assertEquals("Computer no longer paired", gone.detail)
        assertEquals("Tokens today", gone.label)
        assertEquals("—", selectedUsageMetric(savedId, emptyList(), listOf(desktop)).value)
    }

    @Test fun oldBridgesWithoutUsageStillOfferLiveCounts() {
        val f = Feed(Feed.State.LIVE, parseHarnessDocument(JSONObject("""{"harnesses":[]}""")))
        val metrics = usageMetrics(listOf(laptop), mapOf(laptop.key to f))
        assertEquals("0", metrics.single { it.id == "live/total" }.value)
        assertEquals(listOf("live/total", "live/running", "live/finished", "live/idle"), defaultUsageSlots(metrics))
    }

    @Test fun usageSettingsRoundTripWithoutChangingPageOrTerminalSelections() {
        assertNull(SdPages.decode(JSONObject("""{"computers":["laptop-pin"],"pinned":{"computer":"laptop-pin","harness":"one"}}""")).usageSlots)
        val slots = listOf("laptop-pin|codex/todayTokens", "desktop-pin|codex/limit/Weekly", "live/total", "")
        val config = PageConfig(setOf(laptop.key), PageConfig.Pinned(laptop.key, "one"), slots)
        assertEquals(config, SdPages.decode(SdPages.encode(config)))
        assertEquals(listOf("a", "", "", ""), SdPages.decode(JSONObject("""{"usageSlots":["a",null]}""")).usageSlots)
    }

    @Test fun compactCountsDoNotOverflow() {
        assertEquals("0", compactUsage(0))
        assertEquals("999", compactUsage(999))
        assertEquals("1.2K", compactUsage(1200))
        assertEquals("2.5M", compactUsage(2_500_000))
        assertEquals("3.0B", compactUsage(3_000_000_000))
    }
}
