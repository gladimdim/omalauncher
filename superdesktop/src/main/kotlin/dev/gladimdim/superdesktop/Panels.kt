package dev.gladimdim.superdesktop

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gladimdim.superdesktop.bridge.Bridge
import dev.gladimdim.superdesktop.bridge.HarnessType
import dev.gladimdim.superdesktop.live.Feed
import dev.gladimdim.superdesktop.live.SdLive

/** The page's settings, inside the tile: which computers, every harness or one, more pages, pairing. */
@Composable
internal fun SettingsPanel(
    config: PageConfig,
    bridges: List<Bridge>,
    feeds: Map<String, Feed>,
    host: SdHost,
    onConfig: (PageConfig) -> Unit,
    onNewPage: () -> Unit,
    onNewWidget: () -> Unit,
    onPair: () -> Unit,
    onForget: (Bridge) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = host.colors
    var forgetting by remember { mutableStateOf<String?>(null) }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Heading("computers on this page", host)
        val all = bridges.map { it.key }.toSet()
        for (b in bridges) {
            Choice(b.name, host, config.shows(b.key), note = feedNote(feeds[b.key] ?: Feed(Feed.State.CONNECTING))) {
                val now = (config.computers ?: all).let { if (b.key in it) it - b.key else it + b.key }
                // A page always shows a computer; all of them is "all", so computers paired later show up too.
                if (now.isNotEmpty()) onConfig(config.copy(computers = now.takeIf { it != all }, pinned = config.pinned?.takeIf { it.computer in now }))
            }
        }

        Heading("show", host)
        Choice("every harness", host, config.pinned == null) { onConfig(config.copy(pinned = null)) }
        for (b in bridges.filter { config.shows(it.key) }) {
            for (h in feeds[b.key]?.doc?.harnesses.orEmpty()) {
                val pin = PageConfig.Pinned(b.key, h.id)
                Choice("only ${h.sessionTitle ?: h.agentName}", host, config.pinned == pin, note = h.directoryDisplay ?: b.name) {
                    onConfig(config.copy(pinned = pin))
                }
            }
        }

        Heading("pages and widgets", host)
        Label("Each has settings of its own. A terminal widget locks to one harness; resize it like any widget.", host, c.muted, 11, lines = 3)
        Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action("+ page", host, onClick = onNewPage)
            Action("+ terminal widget", host, onClick = onNewWidget)
        }

        Heading("paired computers", host)
        for (b in bridges) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Label(b.name, host, c.foreground, 13, modifier = Modifier.weight(1f))
                if (forgetting == b.key) {
                    Action("keep", host) { forgetting = null }
                    Action("forget", host, color = c.red, strong = true) {
                        forgetting = null
                        onForget(b)
                    }
                } else {
                    Action("forget", host, color = c.muted) { forgetting = b.key }
                }
            }
        }
        if (forgetting != null) Label("Forgetting only removes it from this phone; revoke the device on the computer too.", host, c.muted, 11, lines = 2)
        Row(Modifier.padding(vertical = 6.dp)) { Action("pair another computer", host, onClick = onPair) }
    }
}

/** Starts a harness on one of [bridges]: pick the computer, what to run, and where. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CreatePanel(
    bridges: List<Bridge>,
    host: SdHost,
    onStart: (Bridge, HarnessType, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = host.colors
    var computer by remember { mutableStateOf(bridges.first()) }
    var types by remember(computer) { mutableStateOf<List<HarnessType>?>(null) }
    var folders by remember(computer) { mutableStateOf<List<String>>(emptyList()) }
    var type by remember(computer) { mutableStateOf<HarnessType?>(null) }
    var folder by remember(computer) { mutableStateOf("") }
    var error by remember(computer) { mutableStateOf<String?>(null) }
    LaunchedEffect(computer) {
        try {
            val api = SdLive.api(computer)
            val (list, workspace) = api.harnessTypes()
            types = list
            type = list.firstOrNull { it.available }
            folders = runCatching { api.workspaces() }.getOrDefault(listOf(workspace))
            folder = folders.firstOrNull() ?: workspace
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.message ?: "Can't reach ${computer.name}"
            types = emptyList()
        }
    }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (bridges.size > 1) {
            Heading("computer", host)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (b in bridges) Action(b.name, host, strong = b == computer) { computer = b }
            }
        }
        Heading("new harness on ${computer.name}", host)
        when (val list = types) {
            null -> Label("loading…", host, c.muted, 12)
            else ->
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (t in list) Action(t.name, host, strong = t == type, enabled = t.available) { type = t }
                }
        }
        error?.let { Label(it, host, c.red, 12, lines = 3) }
        Heading("folder", host)
        Field(folder, { folder = it }, host, "folder on ${computer.name}", Modifier.fillMaxWidth())
        val matches = folders.filter { it != folder && (folder.isBlank() || it.contains(folder.substringAfterLast('/'), ignoreCase = true)) }
        val shown = matches.ifEmpty { folders.filter { it != folder } }
        if (shown.isNotEmpty()) {
            // Every folder the computer offers, in a box of its own that scrolls inside the panel.
            val shape = host.shape(6.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .border(1.dp, c.border, shape)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                for (f in shown) {
                    Label(f, host, c.muted, 12, modifier = Modifier.fillMaxWidth().clickable { folder = f }.padding(vertical = 7.dp))
                }
            }
        }
        Row(Modifier.padding(top = 8.dp)) {
            Action("start ${type?.name ?: ""}".trim(), host, strong = true, enabled = type != null && folder.isNotBlank()) {
                type?.let { onStart(computer, it, folder.trim()) }
            }
        }
    }
}
