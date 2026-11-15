package dev.gladimdim.tiling.adb

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

/** The phone no longer lets our key in: it was never paired, was removed, or went unused for longer than Android keeps one. */
internal class AdbUnauthorized(cause: Throwable? = null) : IOException("this phone does not accept the launcher's debugging key", cause)

/**
 * Minimal ADB client over `_adb-tls-connect._tcp` (`CNXN` + `STLS` TLS 1.3 upgrade + `AUTH` + `OPEN shell:...`).
 */
internal object AdbProtocol {
    private const val A_CNXN = 0x4e584e43
    private const val A_OPEN = 0x4e45504f
    private const val A_OKAY = 0x59414b4f
    private const val A_CLSE = 0x45534c43
    private const val A_WRTE = 0x45545257
    private const val A_AUTH = 0x48545541
    private const val A_STLS = 0x534c5453

    private const val A_VERSION = 0x01000001
    private const val A_STLS_VERSION = 0x01000000
    private const val MAX_DATA = 1024 * 1024

    private data class Packet(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray,
    )

    fun runShellCommand(
        host: String,
        port: Int,
        crypto: AdbCrypto,
        command: String,
    ): String {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), 5_000)
        raw.soTimeout = 10_000

        var input = DataInputStream(raw.inputStream)
        var output: OutputStream = raw.outputStream
        var tlsSocket: SSLSocket? = null

        try {
            // 1. Send CNXN on plain TCP; adbd on Wireless Debugging responds with STLS.
            writePacket(output, A_CNXN, A_VERSION, MAX_DATA, "host::\u0000".toByteArray(Charsets.US_ASCII))

            // adbd checks the key of our certificate in the handshake, and hangs up on one it does not know.
            var secured = false
            while (true) {
                val pkt =
                    try {
                        readPacket(input)
                    } catch (e: IOException) {
                        // A phone that is only slow has not turned the key down.
                        throw if (secured && e !is SocketTimeoutException) AdbUnauthorized(e) else e
                    }
                when (pkt.command) {
                    A_STLS -> {
                        writePacket(output, A_STLS, A_STLS_VERSION, 0, ByteArray(0))
                        val ssl = AdbPairing.createTls13Context(crypto).socketFactory.createSocket(raw, host, port, true) as SSLSocket
                        ssl.enabledProtocols = arrayOf("TLSv1.3")
                        try {
                            ssl.startHandshake()
                        } catch (e: SocketTimeoutException) {
                            throw e
                        } catch (e: IOException) {
                            throw AdbUnauthorized(e)
                        }
                        secured = true
                        tlsSocket = ssl
                        input = DataInputStream(ssl.inputStream)
                        output = ssl.outputStream
                    }
                    // Only an adbd without Wireless Debugging's TLS asks this way, and it would put a dialog on screen.
                    A_AUTH -> throw AdbUnauthorized()
                    A_CNXN -> break
                    else -> error("Unexpected ADB command during handshake: 0x${pkt.command.toString(16)}")
                }
            }

            // 2. Open shell stream.
            val localId = 1
            writePacket(output, A_OPEN, localId, 0, ("shell:$command\u0000").toByteArray(Charsets.UTF_8))

            val sb = StringBuilder()
            var remoteId = 0
            while (true) {
                val pkt = readPacket(input)
                when (pkt.command) {
                    A_OKAY -> if (remoteId == 0) remoteId = pkt.arg0
                    A_WRTE -> {
                        if (pkt.payload.isNotEmpty()) sb.append(String(pkt.payload, Charsets.UTF_8))
                        writePacket(output, A_OKAY, localId, if (remoteId != 0) remoteId else pkt.arg0, ByteArray(0))
                    }
                    A_CLSE -> {
                        writePacket(output, A_CLSE, localId, if (remoteId != 0) remoteId else pkt.arg0, ByteArray(0))
                        break
                    }
                }
            }
            return sb.toString()
        } finally {
            runCatching { tlsSocket?.close() }
            runCatching { raw.close() }
        }
    }

    private fun writePacket(
        out: OutputStream,
        command: Int,
        arg0: Int,
        arg1: Int,
        payload: ByteArray,
    ) {
        var sum = 0
        for (b in payload) sum += (b.toInt() and 0xFF)
        val header =
            ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command)
                .putInt(arg0)
                .putInt(arg1)
                .putInt(payload.size)
                .putInt(sum)
                .putInt(command xor -1)
                .array()
        out.write(header)
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
    }

    private fun readPacket(input: DataInputStream): Packet {
        val header = ByteArray(24)
        input.readFully(header)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = bb.getInt()
        val arg0 = bb.getInt()
        val arg1 = bb.getInt()
        val len = bb.getInt()
        bb.getInt() // checksum
        val magic = bb.getInt()
        require(magic == (command xor -1)) { "Bad ADB magic" }
        require(len in 0..MAX_DATA) { "Bad ADB payload length: $len" }
        val payload = ByteArray(len)
        if (len > 0) input.readFully(payload)
        return Packet(command, arg0, arg1, payload)
    }
}
