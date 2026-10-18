package dev.gladimdim.superdesktop

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.gladimdim.superdesktop.bridge.Bridge
import dev.gladimdim.superdesktop.bridge.Harness
import dev.gladimdim.superdesktop.live.Feed
import dev.gladimdim.superdesktop.live.SdLive
import dev.gladimdim.superdesktop.pair.PairingActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SuperDesktop"

private enum class Panel { NONE, SETTINGS, CREATE }

/**
 * A SUPER DESKTOP page: one tile, usually filling a home page. It shows the harnesses of the
 * computers its settings pick (every paired one by default), or just one harness. [id] keys
 * those settings, so every page has its own.
 */
@Composable
fun SuperDesktopTile(
    id: String?,
    host: SdHost,
    modifier: Modifier = Modifier,
) {
    val c = host.colors
    val context = LocalContext.current
    val page = id ?: "home"
    SideEffect {
        SdTheme.colors = c
        SdTheme.mono = host.mono
    }
    DisposableEffect(page) {
        Log.d(TAG, "page $page shown")
        onDispose { Log.d(TAG, "page $page hidden") }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            SdStore.load(context)
            SdPages.load(context)
        }
    }
    val bridges by SdStore.bridges.collectAsState()
    val pages by SdPages.pages.collectAsState()
    val config = pages[page] ?: PageConfig()
    val all = bridges.orEmpty()
    val shown = all.filter { config.shows(it.key) }

    // A live feed per computer this page shows; SdLive shares them with other pages.
    val feeds = remember { mutableStateMapOf<String, Feed>() }
    val scope = rememberCoroutineScope()
    for (b in shown) {
        key(b.key) {
            DisposableEffect(b) {
                val flow = SdLive.acquire(b)
                val job = scope.launch { flow.collect { feeds[b.key] = it } }
                onDispose {
                    job.cancel()
                    SdLive.release(b)
                }
            }
        }
    }

    var panel by remember { mutableStateOf(Panel.NONE) }

    /** The harness opened from the list, shown in the tile until "back". */
    var opened by remember { mutableStateOf<PageConfig.Pinned?>(null) }
    var closing by remember { mutableStateOf<String?>(null) }
    // Kept outside the tile: writing a new page's file makes the launcher rebuild this one.
    val said = Notes.of(page)
    var note by remember { mutableStateOf(said?.first) }
    var failed by remember { mutableStateOf(said?.second ?: false) }

    /** A line under the page for five seconds: what an action did, or why it failed. */
    fun say(
        text: String,
        error: Boolean = false,
    ) {
        note = text
        failed = error
        Notes.put(page, text, error)
    }
    LaunchedEffect(note) {
        if (note != null) {
            delay(5000)
            note = null
        }
    }

    /** Runs a bridge call; a failure becomes the note under the page. */
    fun act(
        what: String,
        block: suspend () -> Unit,
    ) = scope.launch {
        try {
            block()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            say("$what: ${e.message ?: "failed"}", error = true)
        }
    }

    /** Types [text] into [h]; [done] runs once the computer took it. */
    fun keys(
        b: Bridge,
        h: Harness,
        text: String,
        enter: Boolean,
        done: () -> Unit,
    ) {
        act("Send") {
            SdLive.api(b).sendKeys(h.id, text, enter)
            done()
        }
    }

    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("SUPER DESKTOP", host, c.accent, 15, bold = true)
            Spacer(Modifier.weight(1f))
            when {
                all.isEmpty() -> Unit
                panel != Panel.NONE -> Action("done", host) { panel = Panel.NONE }
                else -> {
                    if (config.pinned == null && opened == null) Action("＋ new", host) { panel = Panel.CREATE }
                    Action("⚙", host) { panel = Panel.SETTINGS }
                }
            }
        }
        val body = Modifier.fillMaxWidth().weight(1f)
        when {
            bridges == null -> Spacer(body)
            all.isEmpty() -> NoComputers(host, body) { openPairing(context) }
            panel == Panel.SETTINGS ->
                SettingsPanel(
                    config,
                    all,
                    feeds,
                    host,
                    onConfig = { SdPages.set(context, page, it) },
                    onNewPage = {
                        act("New page") {
                            // The launcher shows a new widget's page as soon as its file lands, so the new page says what it is.
                            val id = SdPages.newId()
                            Notes.put(id, "A new SUPER DESKTOP page: ⚙ picks its computers and harness", error = false)
                            withContext(Dispatchers.IO) { SdPages.newPage(host.widgetsDir, id) }
                        }
                    },
                    onPair = { openPairing(context) },
                    onForget = { b -> act("Forget") { withContext(Dispatchers.IO) { SdStore.remove(context, b.key) } } },
                    modifier = body,
                )
            panel == Panel.CREATE ->
                CreatePanel(
                    shown,
                    host,
                    onStart = { b, type, folder ->
                        act("Start ${type.name}") {
                            SdLive.api(b).createHarness(type.id, folder)
                            panel = Panel.NONE
                            say("Started ${type.name} on ${b.name}")
                        }
                    },
                    modifier = body,
                )
            config.pinned != null -> {
                val pin = config.pinned
                val b = shown.firstOrNull { it.key == pin.computer }
                val feed = b?.let { feeds[it.key] }
                HarnessView(
                    b,
                    feed?.doc?.harnesses?.firstOrNull { it.id == pin.harness },
                    feed,
                    host,
                    leaveLabel = "show every harness",
                    onOpen = { pc, h -> openInApp(context, pc, h) },
                    onKeys = ::keys,
                    leave = { SdPages.set(context, page, config.copy(pinned = null)) },
                    modifier = body,
                )
            }
            else ->
                BoxWithConstraints(body) {
                    // A wide tile (an unfolded phone, a tablet, landscape) shows the list and the open harness side by side.
                    val wide = maxWidth >= 600.dp
                    val b = opened?.let { o -> shown.firstOrNull { it.key == o.computer } }
                    val feed = b?.let { feeds[it.key] }
                    val h = feed?.doc?.harnesses?.firstOrNull { it.id == opened?.harness }
                    val list = @Composable { m: Modifier ->
                        Overview(
                            shown,
                            feeds,
                            host,
                            closing,
                            onOpen = { pc, harness -> opened = PageConfig.Pinned(pc.key, harness.id) },
                            onAskClose = { closing = it },
                            onClose = { pc, harness ->
                                closing = null
                                act("Close") {
                                    SdLive.api(pc).closeHarness(harness.id)
                                    say("Closed ${harness.sessionTitle ?: harness.agentName}")
                                }
                            },
                            modifier = m,
                        )
                    }
                    val view = @Composable { m: Modifier ->
                        HarnessView(b, h, feed, host, leaveLabel = if (wide) "close" else "back", onOpen = { pc, harness -> openInApp(context, pc, harness) }, onKeys = ::keys, leave = { opened = null }, modifier = m)
                    }
                    when {
                        wide && opened != null ->
                            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                list(Modifier.weight(0.42f).fillMaxHeight())
                                view(Modifier.weight(0.58f).fillMaxHeight())
                            }
                        opened != null -> view(Modifier.fillMaxSize())
                        else -> list(Modifier.fillMaxSize())
                    }
                }
        }
        note?.let { Label(it, host, if (failed) c.red else c.muted, 11, lines = 2) }
    }
}

/** Each page's last note and when it was said, so a rebuilt tile shows it for the rest of its five seconds. */
private object Notes {
    private val notes = HashMap<String, Triple<String, Boolean, Long>>()

    @Synchronized fun put(
        page: String,
        text: String,
        error: Boolean,
    ) {
        notes[page] = Triple(text, error, System.currentTimeMillis())
    }

    @Synchronized fun of(page: String): Pair<String, Boolean>? = notes[page]?.takeIf { System.currentTimeMillis() - it.third < 5000 }?.let { it.first to it.second }
}

private fun openPairing(context: Context) = context.startActivity(Intent(context, PairingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

/** Hands harness [h] to the SUPER DESKTOP app's terminal, when it's installed and paired with the same computer. */
private fun openInApp(
    context: Context,
    b: Bridge,
    h: Harness,
) {
    val intent =
        Intent("com.omarchy.ailauncher.widget.OPEN_TERMINAL")
            .setClassName("com.gladimdim.superdesktop", "com.omarchy.ailauncher.MainActivity")
            .putExtra("widget_bridge_url", b.baseUrl())
            .putExtra("widget_harness_id", h.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Log.d(TAG, "SUPER DESKTOP app not installed")
    }
}

@Composable
private fun NoComputers(
    host: SdHost,
    modifier: Modifier,
    pair: () -> Unit,
) {
    val c = host.colors
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        Label("No computer paired", host, c.foreground, 14)
        Label("Pair a SUPER DESKTOP computer to see its AI terminals here.", host, c.muted, 12, lines = 3)
        Action("pair a computer", host, onClick = pair)
    }
}
