package dev.gladimdim.superdesktop

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.gladimdim.superdesktop.bridge.Bridge
import dev.gladimdim.superdesktop.bridge.WakeTarget
import dev.gladimdim.superdesktop.bridge.macBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

// Ported from the SUPER DESKTOP app's WakeOnLan.kt (see bridge/SOURCE.md).

/**
 * Remembers each computer's network cards while it's online (SUPER DESKTOP 1.5.0 and later report them), and wakes
 * it later with the magic packet from this phone. A LAN broadcast: it reaches the computer only from the same
 * Wi-Fi or wired network, never over Tailscale or mobile data. Kept in the encrypted store, with the pairing.
 */
object SdWake {
    private val ports = intArrayOf(9, 7)

    private val lastSeen = HashMap<String, List<WakeTarget>>()

    /** [remember], unless the cards are the ones already remembered: every list document carries them. */
    fun rememberIfChanged(
        context: Context,
        bridge: Bridge,
        targets: List<WakeTarget>,
    ) {
        synchronized(lastSeen) {
            if (lastSeen[bridge.key] == targets) return
            lastSeen[bridge.key] = targets
        }
        remember(context, bridge, targets)
    }

    fun remember(
        context: Context,
        bridge: Bridge,
        targets: List<WakeTarget>,
    ) {
        val json =
            JSONArray().apply {
                for (t in targets) put(JSONObject().put("interface", t.networkInterface).put("kind", t.kind).put("mac", t.mac).put("address", t.address).put("broadcast", t.broadcast).put("wakeOnLan", t.wakeOnLan))
            }.toString()
        SdStore.putSecret(context, "wake:" + bridge.key, json)
    }

    /** Null when the computer never reported its cards. */
    fun targets(
        context: Context,
        bridge: Bridge,
    ): List<WakeTarget>? =
        SdStore.secret(context, "wake:" + bridge.key)?.let { raw ->
            runCatching { dev.gladimdim.superdesktop.bridge.parseWakeTargets(JSONObject().put("interfaces", JSONArray(raw))) }.getOrNull()
        }

    /** Sends the packets and says what happened, in one sentence. */
    suspend fun wake(
        context: Context,
        bridge: Bridge,
    ): String {
        val name = bridge.name
        val targets = targets(context, bridge) ?: return "Wake needs SUPER DESKTOP 1.5.0 or newer on $name, connected once while it was on."
        if (targets.isEmpty()) return "$name reported no network card that can wake it."
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val local = runCatching { localNetwork(connectivity) }.getOrNull() ?: return "Connect this phone to the same Wi-Fi as $name to wake it."
        val sent = runCatching { withContext(Dispatchers.IO) { send(local, targets) } }.getOrDefault(0)
        if (sent == 0) return "Couldn't send the wake packet. Check this phone's Wi-Fi."
        return if (targets.all { it.wakeOnLan == "disabled" }) {
            "Wake packet sent, but $name reported Wake-on-LAN is off on its network card."
        } else {
            "Wake packet sent to $name. It reconnects once the computer is up."
        }
    }

    /** A Wi-Fi or Ethernet network, so the broadcast never goes into a VPN or mobile data. */
    private fun localNetwork(connectivity: ConnectivityManager?): Network? {
        connectivity ?: return null
        @Suppress("DEPRECATION")
        return connectivity.allNetworks.firstOrNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    }

    /** Three rounds to each card's subnet broadcast and the limited broadcast, on ports 9 and 7. */
    private suspend fun send(
        network: Network,
        targets: List<WakeTarget>,
    ): Int {
        var sent = 0
        DatagramSocket().use { socket ->
            socket.broadcast = true
            network.bindSocket(socket)
            repeat(3) { round ->
                for (target in targets) {
                    val mac = macBytes(target.mac) ?: continue
                    val packet = ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { mac[it % 6] }
                    for (destination in listOf(target.broadcast, "255.255.255.255").filter { it.isNotBlank() }.distinct()) {
                        for (port in ports) {
                            val address = runCatching { InetAddress.getByName(destination) }.getOrNull() ?: continue
                            if (runCatching { socket.send(DatagramPacket(packet, packet.size, address, port)) }.isSuccess) sent++
                        }
                    }
                }
                if (round < 2) delay(100)
            }
        }
        return sent
    }
}
