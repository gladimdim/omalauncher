package dev.gladimdim.superdesktop.bridge

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import kotlin.concurrent.thread

class ConnectionProbeTest {
    private fun bridge(port: Int) = Bridge("127.0.0.1", port, "t", "ab".repeat(32), "id", "rog")

    @Test fun ipLiterals() {
        assertTrue(ConnectionProbe.isIpLiteral("192.168.1.20"))
        assertTrue(ConnectionProbe.isIpLiteral("fd7a:115c::1"))
        assertTrue(ConnectionProbe.isIpLiteral("[fd7a:115c::1]"))
        assertFalse(ConnectionProbe.isIpLiteral("rog.tailnet.ts.net"))
        assertFalse(ConnectionProbe.isIpLiteral("300.1.1.1"))
    }

    @Test fun learnsTailscaleTurnedOnAfterPairing() {
        val lan = BridgeAddress("192.168.50.27", "lan", true)
        val tailscale = BridgeAddress("100.91.62.40", "tailscale", true)
        val paired = Bridge("192.168.50.27", 8759, "t", "ab".repeat(32), "id", "rog", listOf(lan))
        val learned = paired.withAddressesOf(PingInfo("rog", 3, "id", listOf(tailscale, lan)))!!
        assertEquals(listOf("192.168.50.27", "100.91.62.40"), learned.hosts())
        // Same served addresses (display-only IPv6 aside), another computer, or an old bridge: nothing to save.
        assertNull(learned.withAddressesOf(PingInfo("rog", 3, "id", listOf(lan, tailscale, BridgeAddress("fd7a::1", "tailscale", false)))))
        assertNull(paired.withAddressesOf(PingInfo("other", 3, "other-id", listOf(tailscale))))
        assertNull(paired.withAddressesOf(PingInfo("rog", 3, "", listOf(tailscale))))
    }

    @Test fun addressChoicesFollowTheTriedOrder() {
        val addresses = listOf(BridgeAddress("192.168.50.27", "lan", true), BridgeAddress("100.91.62.40", "tailscale", true),
            BridgeAddress("fd7a::1", "tailscale", false), BridgeAddress("127.0.0.1", "loopback", false))
        val lan = Bridge("192.168.50.27", 8759, "t", "ab".repeat(32), "id", "rog", addresses)
        assertEquals(listOf("192.168.50.27" to "lan", "100.91.62.40" to "tailscale"), lan.addressChoices())
        // Picking Tailscale makes it the saved address, tried first.
        assertEquals(listOf("100.91.62.40" to "tailscale", "192.168.50.27" to "lan"), lan.copy(host = "100.91.62.40").addressChoices())
        assertEquals("rog.local" to "saved", lan.copy(host = "rog.local").addressChoices().first())
    }

    @Test fun failuresInWords() {
        assertTrue("refused" in ConnectionProbe.explain(ConnectException("Connection refused")))
        assertTrue("No answer in time" in ConnectionProbe.explain(SocketTimeoutException("connect timed out")))
        val pinned = SSLHandshakeException("handshake").apply { initCause(CertificateException("changed")) }
        assertTrue("certificate doesn't match" in ConnectionProbe.explain(pinned))
    }

    @Test fun refusedAddressFailsAtTcp() =
        runBlocking {
            val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
            var steps = emptyList<ProbeStep>()
            val found = ConnectionProbe(bridge(port)) { steps = it }.run()
            assertNull(found)
            assertEquals(ProbeStep.State.INFO, steps[0].state)
            assertTrue(steps[1].text, steps[1].state == ProbeStep.State.FAIL && "refused" in steps[1].text)
            assertTrue(steps.last().text.startsWith("No address answered"))
        }

    @Test fun somethingThatIsNotTheBridgeFailsAtTls() =
        runBlocking {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                thread(isDaemon = true) { runCatching { while (true) server.accept().close() } }
                var steps = emptyList<ProbeStep>()
                assertNull(ConnectionProbe(bridge(server.localPort)) { steps = it }.run())
                assertEquals(ProbeStep.State.OK, steps[1].state)
                assertTrue(steps[1].text.startsWith("TCP connected"))
                assertEquals(ProbeStep.State.FAIL, steps[2].state)
                assertTrue(steps.none { it.state == ProbeStep.State.RUNNING })
            }
        }
}
