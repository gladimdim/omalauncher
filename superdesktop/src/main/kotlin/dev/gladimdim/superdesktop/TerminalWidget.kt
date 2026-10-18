package dev.gladimdim.superdesktop

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.gladimdim.superdesktop.bridge.Bridge
import dev.gladimdim.superdesktop.bridge.Harness
import dev.gladimdim.superdesktop.live.Feed
import dev.gladimdim.superdesktop.live.SdLive
import dev.gladimdim.superdesktop.terminal.KeyRow
import dev.gladimdim.superdesktop.terminal.TerminalPane
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One harness's live terminal as a home widget of any size. Until it's locked to a harness it lists the
 * running ones on every paired computer and offers to start one; ⇄ unlocks it to pick again.
 */
@Composable
internal fun TerminalWidget(
    id: String,
    host: SdHost,
    modifier: Modifier,
) {
    val c = host.colors
    val context = LocalContext.current
    val bridges by SdStore.bridges.collectAsState()
    val pages by SdPages.pages.collectAsState()
    val config = pages[id] ?: PageConfig()
    val all = bridges.orEmpty()
    val pin = config.pinned
    // A locked widget follows only its computer.
    val feeds = rememberFeeds(if (pin != null) all.filter { it.key == pin.computer } else all)
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }

    val said = Notes.of(id)
    var note by remember { mutableStateOf(said?.first) }
    var failed by remember { mutableStateOf(said?.second ?: false) }
    LaunchedEffect(note) {
        if (note != null) {
            delay(5000)
            note = null
        }
    }

    fun act(
        what: String,
        block: suspend () -> Unit,
    ) = scope.launch {
        try {
            block()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            note = "$what: ${e.message ?: "failed"}"
            failed = true
        }
    }

    fun lock(to: PageConfig.Pinned?) = SdPages.set(context, id, config.copy(pinned = to))

    Column(modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val body = Modifier.fillMaxWidth().weight(1f)
        when {
            bridges == null -> Spacer(body)
            all.isEmpty() ->
                Column(body, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                    Label("No computer paired", host, c.muted, 12)
                    Action("pair a computer", host) { openPairing(context) }
                }
            pin == null && creating -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("Start a harness", host, c.accent, 13, bold = true, modifier = Modifier.weight(1f))
                    Action("cancel", host) { creating = false }
                }
                CreatePanel(
                    all,
                    host,
                    onStart = { b, type, folder ->
                        act("Start ${type.name}") {
                            val started = SdLive.api(b).createHarness(type.id, folder)
                            creating = false
                            lock(PageConfig.Pinned(b.key, started))
                        }
                    },
                    modifier = body,
                )
            }
            pin == null -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Label("Pick a terminal", host, c.accent, 13, bold = true, modifier = Modifier.weight(1f))
                    Action("＋ start", host) { creating = true }
                }
                Picker(all, feeds, host, onPick = { b, h -> lock(PageConfig.Pinned(b.key, h.id)) }, modifier = body)
            }
            else -> Locked(pin, all.firstOrNull { it.key == pin.computer }, feeds[pin.computer], host, onUnlock = { lock(null) }, act = ::act, modifier = body)
        }
        note?.let { Label(it, host, if (failed) c.red else c.muted, 10, lines = 2) }
    }
}

/** Every running harness, by computer, as one-line rows to lock to. */
@Composable
private fun Picker(
    bridges: List<Bridge>,
    feeds: Map<String, Feed>,
    host: SdHost,
    onPick: (Bridge, Harness) -> Unit,
    modifier: Modifier,
) {
    val c = host.colors
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (b in bridges) {
            val feed = feeds[b.key] ?: Feed(Feed.State.CONNECTING)
            item(key = "pc:" + b.key) {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(feedColor(feed, host), CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Label(b.name, host, c.muted, 11, modifier = Modifier.weight(1f))
                    Label(feedNote(feed), host, c.muted, 10)
                }
            }
            items(feed.doc?.harnesses.orEmpty(), key = { b.key + it.id }) { h ->
                Row(Modifier.fillMaxWidth().clickable { onPick(b, h) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(statusColor(h.status, host), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Label(h.sessionTitle ?: h.agentName, host, c.foreground, 12, modifier = Modifier.weight(1f))
                    Label(h.directoryDisplay?.substringAfterLast('/') ?: "", host, c.muted, 10)
                }
            }
        }
    }
}

/** The widget locked to [pin]: a one-line header and the live terminal; tapping it brings keys and a prompt. */
@Composable
private fun Locked(
    pin: PageConfig.Pinned,
    b: Bridge?,
    feed: Feed?,
    host: SdHost,
    onUnlock: () -> Unit,
    act: (String, suspend () -> Unit) -> Unit,
    modifier: Modifier,
) {
    val c = host.colors
    val context = LocalContext.current
    val h = feed?.doc?.harnesses?.firstOrNull { it.id == pin.harness }
    var active by remember(pin) { mutableStateOf(false) }
    val promptFocus = remember { FocusRequester() }
    var prompt by remember(pin) { mutableStateOf("") }
    BackHandler(enabled = active) { active = false }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(7.dp).background(statusColor(h?.status ?: "UNKNOWN", host), CircleShape))
        Label(h?.let { it.sessionTitle ?: it.agentName } ?: "SUPER DESKTOP", host, c.foreground, 12, bold = true, modifier = Modifier.weight(1f))
        Label(listOfNotNull(b?.name, h?.status?.lowercase()).joinToString(" · "), host, c.muted, 10)
        if (b != null && h != null) KeyboardButton(host, active, promptFocus) { active = it }
        Action("⇄", host, onClick = onUnlock)
        if (b != null && h != null) Action("↗", host) { openInApp(context, b, h) }
    }
    when {
        b == null ->
            Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                Label("Its computer was forgotten on this phone", host, c.muted, 11, lines = 2)
                Action("pick another", host, onClick = onUnlock)
            }
        h == null && feed?.state == Feed.State.LIVE ->
            Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                Label("This harness has ended", host, c.muted, 11)
                Action("pick another", host, onClick = onUnlock)
            }
        else -> {
            TerminalPane(b, pin.harness, host, active, onActivate = { active = true }, modifier = modifier)
            if (active) {
                KeyRow(host, onKey = { text, enter -> act("Send") { SdLive.api(b).sendKeys(pin.harness, text, enter) } }, onDone = { active = false })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val send = {
                        if (prompt.isNotBlank()) {
                            val text = prompt
                            act("Send") {
                                SdLive.api(b).sendKeys(pin.harness, text, true)
                                prompt = ""
                            }
                        }
                    }
                    Field(prompt, { prompt = it }, host, "prompt…", Modifier.weight(1f).focusRequester(promptFocus), onDone = send)
                    Action("send", host, strong = true, enabled = prompt.isNotBlank(), onClick = send)
                }
            }
        }
    }
}
