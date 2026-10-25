package dev.gladimdim.superdesktop

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.gladimdim.superdesktop.bridge.Bridge
import dev.gladimdim.superdesktop.bridge.ConnectionProbe
import dev.gladimdim.superdesktop.bridge.ProbeStep
import dev.gladimdim.superdesktop.live.Feed
import dev.gladimdim.superdesktop.live.SdLive

/**
 * A popup over the page that tries [b]'s addresses step by step and says what each step did, then restarts its
 * live feed on the address that answered. Leaving it cancels the probe.
 */
@Composable
internal fun ProbePanel(
    b: Bridge,
    feed: Feed?,
    host: SdHost,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = host.colors
    val context = LocalContext.current
    var steps by remember(b.key) { mutableStateOf(emptyList<ProbeStep>()) }
    var round by remember(b.key) { mutableIntStateOf(0) }
    var running by remember(b.key) { mutableStateOf(true) }
    var found by remember(b.key) { mutableStateOf<String?>(null) }
    var localNetworkMissing by remember { mutableStateOf(LocalNetworkAsk.missing(context)) }
    val askLocalNetwork =
        rememberLocalNetworkRequest { granted ->
            localNetworkMissing = !granted
            if (granted) round++
        }
    LaunchedEffect(b.key, round) {
        running = true
        found = null
        steps = emptyList()
        val probe = ConnectionProbe(b) { steps = it }
        for (line in phoneNetwork(context)) probe.info(line)
        found = probe.run()
        found?.let { SdLive.retry(b, it) }
        running = false
    }
    val scroll = rememberScrollState()
    LaunchedEffect(steps.size) { scroll.animateScrollTo(scroll.maxValue) }

    Column(
        modifier
            .background(c.background, host.shape(8.dp))
            .border(1.dp, c.focusBorder, host.shape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("connecting to ${b.name}", host, c.accent, 13, bold = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Action("close", host, onClick = onClose)
        }
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (step in steps) StepLine(step, host)
            when {
                running -> Unit
                found == null -> Unit
                feed?.state == Feed.State.LIVE -> StepLine(ProbeStep("Live feed connected via $found", ProbeStep.State.OK), host)
                else -> StepLine(ProbeStep("Live feed reconnecting via $found…", ProbeStep.State.RUNNING), host)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action(if (running) "working…" else "run again", host, enabled = !running) { round++ }
            if (localNetworkMissing) Action("allow local network access", host, strong = true, onClick = askLocalNetwork)
        }
    }
}

@Composable
private fun StepLine(
    step: ProbeStep,
    host: SdHost,
) {
    val c = host.colors
    val (mark, color) =
        when (step.state) {
            ProbeStep.State.RUNNING -> "[ .. ]" to c.yellow
            ProbeStep.State.OK -> "[ ok ]" to c.green
            ProbeStep.State.FAIL -> "[fail]" to c.red
            ProbeStep.State.INFO -> "      " to c.muted
        }
    Row {
        Label(mark, host, color, 11)
        Spacer(Modifier.width(6.dp))
        Label(step.text, host, if (step.state == ProbeStep.State.INFO) c.muted else c.foreground, 11, lines = 6, modifier = Modifier.weight(1f))
    }
}

/** Which networks this phone is on, and what that means for reaching a computer. */
private fun phoneNetwork(context: Context): List<String> {
    val lines = ArrayList<String>()
    runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val all = connectivity.allNetworks.mapNotNull { connectivity.getNetworkCapabilities(it) }
        fun on(transport: Int) = all.any { it.hasTransport(transport) }
        val kinds =
            buildList {
                if (on(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
                if (on(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet")
                if (on(NetworkCapabilities.TRANSPORT_CELLULAR)) add("mobile data")
                if (on(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
            }
        lines += if (kinds.isEmpty()) "This phone has no network." else "This phone is on ${kinds.joinToString(" + ")}."
        if (!on(NetworkCapabilities.TRANSPORT_WIFI) && !on(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            lines += "Without Wi-Fi only Tailscale addresses can work."
        }
    }.onFailure { lines += "This phone's network is unknown: ${it.message}" }
    // Android 17 drops connections to 192.168.x and other local addresses until the launcher may use them.
    if (LocalNetworkAsk.missing(context)) {
        lines += "Local network access is off for the launcher, so LAN addresses time out. Press \"allow local network access\" below."
    }
    return lines
}
