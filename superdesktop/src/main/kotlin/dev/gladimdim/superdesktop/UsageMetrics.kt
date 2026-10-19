package dev.gladimdim.superdesktop

import dev.gladimdim.superdesktop.bridge.Bridge
import dev.gladimdim.superdesktop.bridge.Harness
import dev.gladimdim.superdesktop.live.Feed
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** The computer's certificate key, provider and field identify a statistic, never its address or name. */
internal data class UsageMetric(
    val id: String,
    val computer: String?,
    val computerName: String,
    val provider: String,
    val providerName: String,
    val label: String,
    val value: String,
    val detail: String = "",
    val remaining: Float? = null,
)

internal fun compactUsage(value: Long): String = when {
    value >= 1_000_000_000 -> String.format(Locale.ROOT, "%.1fB", value / 1e9)
    value >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", value / 1e6)
    value >= 1_000 -> String.format(Locale.ROOT, "%.1fK", value / 1e3)
    else -> value.toString()
}

private fun usageTime(value: String?): String? = value?.let {
    runCatching {
        OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.getDefault()))
    }.getOrNull()
}

/** No provider totals are summed across PCs: two machines can report the same provider account. */
internal fun usageMetrics(bridges: List<Bridge>, feeds: Map<String, Feed>): List<UsageMetric> = buildList {
    fun live(computer: String?, name: String, harnesses: List<Harness>, online: Boolean, note: String) {
        val counts = listOf(
            Triple("total", "Active sessions", harnesses.size),
            Triple("running", "Running sessions", harnesses.count { it.status == "WORKING" || it.status == "RUNNING" }),
            Triple("finished", "Finished responses", harnesses.count { it.status == "FINISHED" }),
            Triple("idle", "Idle sessions", harnesses.count { it.status == "IDLE" }),
        )
        for ((field, label, value) in counts) {
            add(UsageMetric((computer?.let { "$it|" } ?: "") + "live/$field", computer, name, "live", "Live sessions", label, if (online) value.toString() else "—", note))
        }
    }

    val online = bridges.isNotEmpty() && bridges.all { feeds[it.key]?.state == Feed.State.LIVE && feeds[it.key]?.doc != null }
    live(null, "All computers", bridges.flatMap { feeds[it.key]?.doc?.harnesses.orEmpty() }, online,
        if (online) "Across all paired computers" else "Waiting for all computers; totals unavailable")
    for (b in bridges) {
        val feed = feeds[b.key] ?: Feed(Feed.State.CONNECTING)
        val connected = feed.state == Feed.State.LIVE && feed.doc != null
        val connectionNote = when (feed.state) {
            Feed.State.LIVE -> "Live on ${b.name}"
            Feed.State.CONNECTING -> "Connecting to ${b.name}…"
            Feed.State.OFFLINE -> "${b.name} is offline"
            Feed.State.NOT_PAIRED -> "Pair ${b.name} again"
        }
        live(b.key, b.name, feed.doc?.harnesses.orEmpty(), connected, connectionNote)
        for (u in feed.doc?.usage.orEmpty()) {
            val ready = connected && u.ready
            val updated = usageTime(u.updatedAt)?.let { "Updated $it" }
            val note = if (!connected) connectionNote else if (!u.ready) "Provider data unavailable" else updated ?: "Update time unavailable"
            for (limit in u.limits) {
                val remaining = limit.used?.takeIf { ready && it.isFinite() }?.let { (1.0 - it.coerceIn(0.0, 1.0)).toFloat() }
                val reset = usageTime(limit.resetsAt)?.let { "Resets $it" }
                add(UsageMetric("${b.key}|${u.id}/limit/${limit.label}", b.key, b.name, u.id, u.name, limit.label,
                    remaining?.let { "${(it * 100).roundToInt()}% left" } ?: "—",
                    listOfNotNull(reset.takeIf { ready }, note).joinToString("\n"), remaining))
            }
            for ((field, label, value) in listOf(
                Triple("todayTokens", "Tokens today", u.todayTokens),
                Triple("todaySessions", "Sessions today", u.todaySessions),
                Triple("todayPrompts", "Prompts today", u.todayPrompts),
                Triple("totalSessions", "Total sessions", u.totalSessions),
                Triple("totalPrompts", "Total prompts", u.totalPrompts),
            )) {
                add(UsageMetric("${b.key}|${u.id}/$field", b.key, b.name, u.id, u.name, label,
                    value?.takeIf { ready && it >= 0 }?.let(::compactUsage) ?: "—",
                    if (ready && value == null) "Not reported by this PC\n$note" else note))
            }
        }
    }
}

internal fun defaultUsageSlots(metrics: List<UsageMetric>): List<String> {
    val quota = metrics.firstOrNull { "/limit/" in it.id }
    val prefix = quota?.id?.substringBefore("/limit/")
        ?: metrics.firstOrNull { it.id.endsWith("/todayTokens") }?.id?.substringBeforeLast('/')
    return if (prefix != null) listOf(quota?.id.orEmpty(), "$prefix/todayTokens", "$prefix/todaySessions", "live/total")
        else listOf("live/total", "live/running", "live/finished", "live/idle")
}

/** A missing saved choice must stay attached to its PC, even when another PC has the same provider. */
internal fun selectedUsageMetric(id: String, metrics: List<UsageMetric>, bridges: List<Bridge>): UsageMetric {
    metrics.firstOrNull { it.id == id }?.let { return it }
    if (id.isBlank()) return UsageMetric("", null, "", "", "Choose a stat", "Tap to configure", "—")
    val computer = id.substringBefore('|', "").takeIf { it.isNotEmpty() }
    val b = bridges.firstOrNull { it.key == computer }
    val field = id.substringAfter('|')
    val provider = field.substringBefore('/')
    val label = when (val stat = field.substringAfter('/')) {
        "todayTokens" -> "Tokens today"
        "todaySessions" -> "Sessions today"
        "todayPrompts" -> "Prompts today"
        "totalSessions" -> "Total sessions"
        "totalPrompts" -> "Total prompts"
        else -> if (stat.startsWith("limit/")) stat.removePrefix("limit/") else "Saved statistic"
    }
    return UsageMetric(id, computer, b?.name ?: "Saved computer", provider, provider.replaceFirstChar { it.uppercase() },
        label, "—", if (computer != null && b == null) "Computer no longer paired" else "Statistic unavailable; waiting for its PC")
}
