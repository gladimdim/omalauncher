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
