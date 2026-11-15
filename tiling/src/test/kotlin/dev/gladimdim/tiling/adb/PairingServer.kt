package dev.gladimdim.tiling.adb

import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.InetAddress
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * The phone's side of a pairing, as adbd's `pairing_connection` does it: what [AdbPairing] and the real `adb pair`
 * are tested against. It takes one client and keeps what it learned of it.
 */
internal class PairingServer(
    private val code: String,
    private val privateScalar: BigInteger? = null,
) : AutoCloseable {
    private val server =
        (AdbPairing.createTls13Context(AdbCrypto.generate()).serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket).apply {
            enabledProtocols = arrayOf("TLSv1.3")
            needClientAuth = true
        }
    val port: Int get() = server.localPort

    /** What SPAKE2 was run on, for a test vector. */
    var password: ByteArray? = null
    var clientMessage: ByteArray? = null
    var keyMaterial: ByteArray? = null

    /** The client's info, when its code was ours: a type byte and the client's adb key. */
    var peerInfo: ByteArray? = null
    var failure: Throwable? = null

    private val serving = thread { runCatching { serve() }.onFailure { failure = it } }

    private fun serve() {
        (server.accept() as SSLSocket).use { socket ->
            socket.soTimeout = 10_000
            socket.startHandshake()
            val password = AdbPairing.password(socket, code).also { password = it }
            val spake = Spake25519(Spake25519.Role.Bob, AdbPairing.SERVER_NAME, AdbPairing.CLIENT_NAME, password, privateScalar)
            val input = DataInputStream(socket.inputStream)
            val output = DataOutputStream(socket.outputStream)
            AdbPairing.writePacket(output, AdbPairing.TYPE_SPAKE2_MSG, spake.publicMessage())
            val theirs = AdbPairing.readPacket(input).second.also { clientMessage = it }
            val material = spake.finish(theirs).also { keyMaterial = it }
            val key = AdbPairing.aesKey(material)
            // A client with another code fails here, and is hung up on as adbd does.
            peerInfo = AdbPairing.open(key, AdbPairing.readPacket(input).second)
            val mine = ByteArray(AdbPairing.PEER_INFO_SIZE).also { it[0] = 1 } // ADB_DEVICE_GUID
            "adb-test-guid".toByteArray().copyInto(mine, 1)
            AdbPairing.writePacket(output, AdbPairing.TYPE_PEER_INFO, AdbPairing.seal(key, mine))
        }
    }

    fun await() = serving.join(15_000)

    override fun close() {
        runCatching { server.close() }
    }
}
