package dev.gladimdim.tiling.adb

import org.conscrypt.Conscrypt
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/** The phone turned the pairing down: with the right port, that is a wrong code. Its pairing dialog has closed by then. */
internal class WrongPairingCode(cause: Throwable? = null) : IOException("the pairing code was not accepted", cause)

/**
 * The client side of Android 11's Wireless Debugging pairing (`_adb-tls-pairing._tcp`), as `adb pair` speaks it:
 * the launcher pairs with its own phone, with no computer and no Shizuku.
 */
internal object AdbPairing {
    private const val VERSION: Byte = 1
    internal const val TYPE_SPAKE2_MSG: Byte = 0
    internal const val TYPE_PEER_INFO: Byte = 1
    internal const val PEER_INFO_SIZE = 8192
    private const val ADB_RSA_PUB_KEY: Byte = 0

    // Each with the zero byte the C strings end in, which adbd counts.
    internal val CLIENT_NAME = "adb pair client\u0000".toByteArray(Charsets.US_ASCII)
    internal val SERVER_NAME = "adb pair server\u0000".toByteArray(Charsets.US_ASCII)
    private const val EXPORT_LABEL = "adb-label\u0000"
    private const val EXPORT_SIZE = 64
    private val HKDF_INFO = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII)

    /** Throws [WrongPairingCode] when the phone does not accept the code, and whatever the connection fails with otherwise. */
    fun pair(
        host: String,
        port: Int,
        code: String,
        crypto: AdbCrypto,
    ) {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), 5_000)
        raw.soTimeout = 10_000
        val ssl = createTls13Context(crypto).socketFactory.createSocket(raw, host, port, true) as SSLSocket
        ssl.use { sock ->
            sock.enabledProtocols = arrayOf("TLSv1.3")
            sock.startHandshake()
            val spake = Spake25519(Spake25519.Role.Alice, CLIENT_NAME, SERVER_NAME, password(sock, code))
            val input = DataInputStream(sock.inputStream)
            val output = DataOutputStream(sock.outputStream)

            writePacket(output, TYPE_SPAKE2_MSG, spake.publicMessage())
            val (type, theirs) = readPacket(input)
            require(type == TYPE_SPAKE2_MSG) { "expected a SPAKE2 message, got type $type" }
            val key = aesKey(spake.finish(theirs))

            // Our key, for the phone to let in from now on. From here a failure is the code: each side only learns
            // whether the other knew it by what the other's key makes of this.
            val peerInfo = ByteArray(PEER_INFO_SIZE)
            peerInfo[0] = ADB_RSA_PUB_KEY
            val keyBytes = crypto.adbPublicKeyString.toByteArray(Charsets.UTF_8)
            System.arraycopy(keyBytes, 0, peerInfo, 1, minOf(keyBytes.size, PEER_INFO_SIZE - 2))
            try {
                writePacket(output, TYPE_PEER_INFO, seal(key, peerInfo))
                val (peerType, sealed) = readPacket(input)
                require(peerType == TYPE_PEER_INFO) { "expected the peer's info, got type $peerType" }
                open(key, sealed)
            } catch (e: GeneralSecurityException) {
                throw WrongPairingCode(e)
            } catch (e: IOException) {
                throw WrongPairingCode(e)
            }
        }
    }

    /** The code and, after it, key material of the TLS session: a code overheard is of no use on another connection. */
    internal fun password(
        socket: SSLSocket,
        code: String,
    ): ByteArray {
        val exported = Conscrypt.exportKeyingMaterial(socket, EXPORT_LABEL, null, EXPORT_SIZE) ?: error("no TLS keying material")
        return code.trim().toByteArray(Charsets.UTF_8) + exported
    }

    internal fun writePacket(
        out: DataOutputStream,
        type: Byte,
        payload: ByteArray,
    ) {
        out.writeByte(VERSION.toInt())
        out.writeByte(type.toInt())
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    internal fun readPacket(input: DataInputStream): Pair<Byte, ByteArray> {
        val version = input.readByte()
        require(version == VERSION) { "pairing version $version" }
        val type = input.readByte()
        val len = input.readInt()
        require(len in 1..16384) { "pairing payload of $len bytes" }
        val payload = ByteArray(len)
        input.readFully(payload)
        return type to payload
    }

    /** adbd's `Aes128Gcm`: HKDF-SHA256 of all 64 bytes of SPAKE2 key material, without salt. */
    internal fun aesKey(keyMaterial: ByteArray): ByteArray {
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = hmac.doFinal(keyMaterial)
        hmac.init(SecretKeySpec(prk, "HmacSHA256"))
        hmac.update(HKDF_INFO)
        hmac.update(0x01)
        return hmac.doFinal().copyOf(16)
    }

    // Each side counts what it has sealed and what it has opened; one message goes each way.
    internal fun seal(
        key: ByteArray,
        plaintext: ByteArray,
    ): ByteArray = gcm(Cipher.ENCRYPT_MODE, key).doFinal(plaintext)

    internal fun open(
        key: ByteArray,
        sealed: ByteArray,
    ): ByteArray = gcm(Cipher.DECRYPT_MODE, key).doFinal(sealed)

    private fun gcm(
        mode: Int,
        key: ByteArray,
    ): Cipher {
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(0L).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }
    }

    /** TLS 1.3 with our certificate, taking whatever the other side shows: adb trusts by key, not by certificate. */
    internal fun createTls13Context(crypto: AdbCrypto): SSLContext {
        val km =
            object : X509ExtendedKeyManager() {
                override fun getClientAliases(
                    keyType: String?,
                    issuers: Array<out Principal>?,
                ) = arrayOf("adb")

                override fun chooseClientAlias(
                    keyType: Array<out String>?,
                    issuers: Array<out Principal>?,
                    socket: Socket?,
                ) = "adb"

                override fun getServerAliases(
                    keyType: String?,
                    issuers: Array<out Principal>?,
                ) = arrayOf("adb")

                override fun chooseServerAlias(
                    keyType: String?,
                    issuers: Array<out Principal>?,
                    socket: Socket?,
                ) = "adb"

                override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(crypto.certificate)

                override fun getPrivateKey(alias: String?): PrivateKey = crypto.privateKey
            }
        val tm =
            arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(
                        chain: Array<out X509Certificate>?,
                        authType: String?,
                    ) = Unit

                    override fun checkServerTrusted(
                        chain: Array<out X509Certificate>?,
                        authType: String?,
                    ) = Unit

                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                },
            )
        return SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider()).apply { init(arrayOf(km), tm, null) }
    }
}
