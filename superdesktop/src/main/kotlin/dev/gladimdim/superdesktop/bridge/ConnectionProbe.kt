package dev.gladimdim.superdesktop.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** One line of a [ConnectionProbe]: what it's doing, or what came of it. */
data class ProbeStep(
    val text: String,
    val state: State,
) {
    enum class State { RUNNING, OK, FAIL, INFO }
}

/**
 * Walks every address of [bridge] the way the live feed would, one stage at a time — name lookup, TCP, TLS with
 * the pinned certificate and `/api/v1/ping`, then the pairing token — and reports each stage to [onStep] as the
 * whole list so far. Stops at the first address that passes everything. Lives in `bridge/` so the SUPER DESKTOP
 * app can carry the same file (see SOURCE.md).
 */
class ConnectionProbe(
    private val bridge: Bridge,
    private val onStep: (List<ProbeStep>) -> Unit,
) {
    private val steps = ArrayList<ProbeStep>()

    /** Lines to show before the addresses, e.g. which network the phone is on. */
    fun info(text: String) = add(ProbeStep(text, ProbeStep.State.INFO))

    /** The address that answered with a valid token, or null when none did. */
    suspend fun run(): String? {
        val hosts = bridge.hosts()
        var removed = false
        for ((i, host) in hosts.withIndex()) {
            val network = if (host == bridge.host) "saved" else bridge.addresses.firstOrNull { it.address == host }?.network ?: "address"
            info("Address ${i + 1}/${hosts.size}: $host:${bridge.port} ($network)")
            when (tryHost(host)) {
                Outcome.OK -> return host
                Outcome.NOT_PAIRED -> {
                    removed = true
                    break
                }
                Outcome.FAILED -> Unit
            }
        }
        if (!removed) add(ProbeStep("No address answered. Is ${bridge.name} on, awake and running SUPER DESKTOP, and is this phone on its network (or Tailscale)?", ProbeStep.State.FAIL))
        return null
    }

    private enum class Outcome { OK, FAILED, NOT_PAIRED }

    private suspend fun tryHost(host: String): Outcome {
        val literal = isIpLiteral(host)
        val address: InetAddress
        if (literal) {
            address = InetAddress.getByName(host.trim('[', ']'))
        } else {
            val at = start("Looking up $host…")
            // getAllByName can't be cancelled, so the lookup runs on its own and is abandoned after the timeout.
            val lookup = lookups.async { runCatching { InetAddress.getAllByName(host).first() } }
            val result = withTimeoutOrNull(LOOKUP_MS) { lookup.await() }
            address =
                when {
                    result == null -> return fail(at, "No answer from DNS in ${LOOKUP_MS / 1000} s.")
                    result.isFailure -> return fail(at, "Can't resolve $host: ${result.exceptionOrNull()?.let(::explain)}")
                    else -> result.getOrThrow()
                }
            ok(at, "$host is ${address.hostAddress}")
        }

        var at = start("Opening TCP to ${address.hostAddress}:${bridge.port}…")
        val tcp =
            withContext(Dispatchers.IO) {
                runCatching { Socket().use { it.connect(InetSocketAddress(address, bridge.port), TCP_MS.toInt()) } }
            }
        if (tcp.isFailure) return fail(at, explain(tcp.exceptionOrNull()!!))
        ok(at, "TCP connected")

        at = start("TLS handshake and GET /api/v1/ping…")
        val api = bridge.api(host)
        val ping = runCatching { api.ping(PING_MS) }
        if (ping.isFailure) return fail(at, explain(ping.exceptionOrNull()!!))
        val info = ping.getOrThrow()
        ok(at, "SUPER DESKTOP on ${info.hostname} answered, protocol v${info.version}")
        if (bridge.bridgeId.isNotBlank() && info.bridgeId.isNotBlank() && info.bridgeId != bridge.bridgeId) {
            info("Its bridge id differs from the paired one; SUPER DESKTOP may have been reinstalled.")
        }

        at = start("Checking this device's pairing token…")
        val list = runCatching { api.harnesses() }
        list.exceptionOrNull()?.let { x ->
            if (x is NotPairedException) {
                fail(at, "401: this device was removed on the computer. Pair it again.")
                return Outcome.NOT_PAIRED
            }
            return fail(at, explain(x))
        }
        val count = list.getOrThrow().harnesses.size
        ok(at, "Token accepted, ${if (count == 1) "1 harness" else "$count harnesses"}")
        return Outcome.OK
    }

    private fun add(step: ProbeStep): Int {
        synchronized(steps) {
            steps += step
            onStep(steps.toList())
            return steps.size - 1
        }
    }

    private fun replace(
        at: Int,
        step: ProbeStep,
    ) {
        synchronized(steps) {
            steps[at] = step
            onStep(steps.toList())
        }
    }

    private var started = 0L

    private fun start(text: String): Int {
        started = System.currentTimeMillis()
        return add(ProbeStep(text, ProbeStep.State.RUNNING))
    }

    private fun took() = "${System.currentTimeMillis() - started} ms"

    private fun ok(
        at: Int,
        text: String,
    ) = replace(at, ProbeStep("$text (${took()})", ProbeStep.State.OK))

    private fun fail(
        at: Int,
        text: String,
    ): Outcome {
        replace(at, ProbeStep("$text (${took()})", ProbeStep.State.FAIL))
        return Outcome.FAILED
    }

    companion object {
        const val LOOKUP_MS = 5_000L
        const val TCP_MS = 4_000L
        const val PING_MS = 6_000L

        private val lookups = CoroutineScope(Dispatchers.IO)

        internal fun isIpLiteral(host: String): Boolean {
            val h = host.trim('[', ']')
            return ':' in h || h.split('.').let { parts -> parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true } }
        }

        /** A failure in words a person can act on. */
        internal fun explain(x: Throwable): String {
            val cause = generateSequence(x) { it.cause }.toList()
            fun has(type: Class<*>) = cause.any { type.isInstance(it) }
            val message = x.message.orEmpty()
            return when {
                has(CertificateException::class.java) || has(SSLPeerUnverifiedException::class.java) ->
                    "The certificate doesn't match this pairing: another computer answers at this address, or SUPER DESKTOP got a new certificate. Pair again."
                has(SSLHandshakeException::class.java) || has(SSLException::class.java) -> "TLS failed: $message"
                has(ConnectException::class.java) && "refused" in message.lowercase() ->
                    "Connection refused: the computer is reachable but nothing listens on that port. Is SUPER DESKTOP running?"
                has(NoRouteToHostException::class.java) || "EHOSTUNREACH" in message || "ENETUNREACH" in message ->
                    "No route to this address: the phone isn't on that network (Wi-Fi, LAN or Tailscale)."
                has(PortUnreachableException::class.java) -> "Port unreachable."
                has(SocketTimeoutException::class.java) || "timeout" in message.lowercase() || "timed out" in message.lowercase() ->
                    "No answer in time: the computer is off or asleep, a firewall drops the traffic, or it's on another network."
                has(UnknownHostException::class.java) -> "Unknown host."
                x is IOException && message == "This address is not a SUPER DESKTOP computer" -> "Something answered, but it isn't SUPER DESKTOP."
                else -> message.ifBlank { x.javaClass.simpleName }
            }
        }
    }
}
