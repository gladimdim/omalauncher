package dev.gladimdim.superdesktop

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.gladimdim.superdesktop.bridge.Bridge
import dev.gladimdim.superdesktop.live.Feed

/** The Android app's four usage slots, as a resizable launcher widget with settings of its own. */
@Composable
internal fun UsageWidget(id: String, host: SdHost, modifier: Modifier) {
    val context = LocalContext.current
    val bridges by SdStore.bridges.collectAsState()
    val pages by SdPages.pages.collectAsState()
    val loaded by SdPages.ready.collectAsState()
    val all = bridges.orEmpty()
    val config = pages[id] ?: PageConfig()
    // These are the same reference-counted feeds as pages and terminals, never another poller per card.
    val feeds = rememberFeeds(all)
    val metrics = usageMetrics(all, feeds)
    val slots = config.usageSlots ?: defaultUsageSlots(metrics)
    var settings by remember { mutableStateOf(false) }
    var editSlot by remember { mutableStateOf<Int?>(null) }

    // Fix the initial defaults once a PC answers; reconnecting or pairing a PC never changes a saved choice.
    LaunchedEffect(id, loaded, config, metrics) {
        if (loaded && config.usageSlots == null && all.any { feeds[it.key]?.state == Feed.State.LIVE && feeds[it.key]?.doc != null }) {
            SdPages.set(context, id, config.copy(usageSlots = slots))
        }
    }

    Column(modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Label("SD usage", host, host.colors.accent, 13, bold = true, modifier = Modifier.weight(1f))
            Action("⚙", host, enabled = loaded) { settings = true }
        }
        when {
            bridges == null || !loaded -> Label("Loading computers…", host, host.colors.muted, 11)
            all.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Label("Pair a PC to see tokens, sessions and limits", host, host.colors.muted, 11, lines = 3)
                Action("pair a computer", host) { openPairing(context) }
            }
            else -> BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val columns = if (maxWidth >= 280.dp) 2 else 1
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (row in (0..3).toList().chunked(columns)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (slot in row) {
                                UsageCard(selectedUsageMetric(slots.getOrElse(slot) { "" }, metrics, all), host, Modifier.weight(1f)) { editSlot = slot }
                            }
                        }
                    }
                }
            }
        }
    }

    if (settings || editSlot != null) {
        Dialog(onDismissRequest = { settings = false; editSlot = null }) {
            Column(
                Modifier.widthIn(max = 600.dp).fillMaxWidth().heightIn(max = 600.dp)
                    .background(host.colors.background, host.shape(12.dp)).border(1.dp, host.colors.border, host.shape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("Usage widget", host, host.colors.accent, 15, bold = true, modifier = Modifier.weight(1f))
                    Action("done", host) { settings = false; editSlot = null }
                }
                val slot = editSlot
                if (slot == null) {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Label("Each card can follow a different PC and provider. Tap a card to choose.", host, host.colors.muted, 12, lines = 3)
                        for (i in 0..3) {
                            Heading("card ${i + 1}", host)
                            UsageCard(selectedUsageMetric(slots.getOrElse(i) { "" }, metrics, all), host, Modifier.fillMaxWidth()) { editSlot = i }
                        }
                        Action("pair another computer", host) { settings = false; openPairing(context) }
                    }
                } else {
                    UsagePicker(slot, slots.getOrElse(slot) { "" }, all, feeds, metrics, host,
                        onBack = { editSlot = null },
                        onPick = { metric ->
                            SdPages.set(context, id, config.copy(usageSlots = List(4) { i -> if (i == slot) metric else slots.getOrElse(i) { "" } }))
                            editSlot = null
                        }, modifier = Modifier.weight(1f, fill = false))
                }
            }
        }
    }
}

@Composable
private fun UsageCard(metric: UsageMetric, host: SdHost, modifier: Modifier, onClick: () -> Unit) {
    val c = host.colors
    Column(
        modifier.background(c.surface, host.shape(6.dp)).border(1.dp, c.border, host.shape(6.dp)).clickable(onClick = onClick).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Label(metric.computerName, host, c.muted, 10)
        Label(metric.providerName, host, c.foreground, 12, bold = true)
        Label(metric.label, host, c.muted, 10, lines = 2)
        Label(metric.value, host, if (metric.remaining != null && metric.remaining <= .15f) c.red else c.accent, 21, bold = true)
        if ("/limit/" in metric.id) {
            Box(Modifier.fillMaxWidth().height(4.dp).background(c.border)) {
                metric.remaining?.let { Box(Modifier.fillMaxWidth(it).height(4.dp).background(if (it <= .15f) c.red else c.accent)) }
            }
        }
        Label(metric.detail, host, c.muted, 9, lines = 3)
    }
}

/** Computer → provider → statistic, so the same provider on different PCs cannot be confused. */
@Composable
private fun UsagePicker(
    slot: Int, selected: String, bridges: List<Bridge>, feeds: Map<String, Feed>, metrics: List<UsageMetric>, host: SdHost,
    onBack: () -> Unit, onPick: (String) -> Unit, modifier: Modifier,
) {
    var computer by remember(slot) { mutableStateOf<String?>(null) }
    var provider by remember(slot) { mutableStateOf<String?>(null) }
    val chosen = metrics.filter { (it.computer ?: "") == computer }
    fun back() {
        when {
            provider != null -> provider = null
            computer != null -> computer = null
            else -> onBack()
        }
    }
    BackHandler { back() }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action("back", host) { back() }
            Label("Card ${slot + 1}", host, host.colors.muted, 12)
        }
        when {
            computer == null -> {
                Heading("choose a computer", host)
                Choice("All computers", host, selected.startsWith("live/"), "live counts") { computer = "" }
                for (b in bridges) Choice(b.name, host, selected.startsWith("${b.key}|"), feedNote(feeds[b.key] ?: Feed(Feed.State.CONNECTING))) { computer = b.key }
                Action("clear card", host) { onPick("") }
            }
            provider == null -> {
                Heading(bridges.firstOrNull { it.key == computer }?.name ?: "All computers", host)
                Heading("choose a provider", host)
                for (group in chosen.groupBy { it.provider }.values) {
                    Choice(group.first().providerName, host, group.any { it.id == selected }) { provider = group.first().provider }
                }
                if (chosen.none { it.provider != "live" } && computer != "") {
                    Label("Provider statistics appear when this PC reports usage. Live counts are available while connected.", host, host.colors.muted, 11, lines = 4)
                }
            }
            else -> {
                Heading(bridges.firstOrNull { it.key == computer }?.name ?: "All computers", host)
                Heading(chosen.firstOrNull { it.provider == provider }?.providerName ?: "Statistics", host)
                for (metric in chosen.filter { it.provider == provider }) {
                    Choice(metric.label, host, metric.id == selected, metric.value) { onPick(metric.id) }
                    Label(metric.detail, host, host.colors.muted, 10, lines = 3)
                }
            }
        }
    }
}
