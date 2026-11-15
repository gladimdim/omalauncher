package dev.gladimdim.tiling.adb

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The RSA-2048 identity of the launcher's own adb client: an X.509 certificate for TLS 1.3, and the same key in
 * the 524-byte form Android keeps of the computers it lets debug it.
 */
internal class AdbCrypto private constructor(
    val privateKey: PrivateKey,
    val publicKey: RSAPublicKey,
    val certificate: X509Certificate,
) {
    /** The key as adb writes it into a phone's list of allowed computers: `<base64> <name>`. */
    val adbPublicKeyString: String by lazy { Base64.getEncoder().encodeToString(encodeAndroidRsaPublicKey(publicKey)) + " sdlauncher@android" }

    companion object {
        private const val PREFS = "tiling_adb"
        private const val KEY_PRIV = "priv"
        private const val KEY_PUB = "pub"
        private const val KEY_CERT = "cert"

        /** The identity the phone was paired with, made on first use. Losing it means pairing again. */
        @Synchronized
        fun loadOrCreate(context: Context): AdbCrypto {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val privB64 = prefs.getString(KEY_PRIV, null)
            val pubB64 = prefs.getString(KEY_PUB, null)
            val certB64 = prefs.getString(KEY_CERT, null)
            if (privB64 != null && pubB64 != null && certB64 != null) {
                runCatching {
                    val decoder = Base64.getDecoder()
                    val kf = KeyFactory.getInstance("RSA")
                    val priv = kf.generatePrivate(PKCS8EncodedKeySpec(decoder.decode(privB64)))
                    val pub = kf.generatePublic(X509EncodedKeySpec(decoder.decode(pubB64))) as RSAPublicKey
                    val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(decoder.decode(certB64))) as X509Certificate
                    return AdbCrypto(priv, pub, cert)
                }
            }
            val made = generate()
            val encoder = Base64.getEncoder()
            prefs.edit()
                .putString(KEY_PRIV, encoder.encodeToString(made.privateKey.encoded))
                .putString(KEY_PUB, encoder.encodeToString(made.publicKey.encoded))
                .putString(KEY_CERT, encoder.encodeToString(made.certificate.encoded))
                .apply()
            return made
        }

        /** A new RSA-2048 key and a certificate of it signed by itself. */
        fun generate(): AdbCrypto {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val pub = kp.public as RSAPublicKey
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(buildSelfSignedX509(kp.private, pub))) as X509Certificate
            return AdbCrypto(kp.private, pub, cert)
        }

        /** Encodes [pub] in AOSP's 524-byte `RSAPublicKey` struct (little-endian). */
        fun encodeAndroidRsaPublicKey(pub: RSAPublicKey): ByteArray {
            val n = pub.modulus
            val r32 = BigInteger.ONE.shiftLeft(32)
            val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toInt()
            val r = BigInteger.ONE.shiftLeft(2048)
            val rr = r.multiply(r).mod(n)

            val buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(64) // modulus_size_words
            buf.putInt(n0inv)
            buf.put(toLittleEndian256(n))
            buf.put(toLittleEndian256(rr))
            buf.putInt(pub.publicExponent.toInt())
            return buf.array()
        }

        private fun toLittleEndian256(v: BigInteger): ByteArray {
            val be = v.toByteArray()
            val out = ByteArray(256)
            // Copy from the least-significant end of big-endian into little-endian out[0..255].
            var src = be.size - 1
            var dst = 0
            while (src >= 0 && dst < 256) {
                out[dst++] = be[src--]
            }
            return out
        }

        /** Minimal valid ASN.1 DER X.509 v3 self-signed certificate for TLS 1.3 mutual auth. */
        private fun buildSelfSignedX509(
            priv: PrivateKey,
            pub: RSAPublicKey,
        ): ByteArray {
            // sha256WithRSAEncryption: 1.2.840.113549.1.1.11
            val algId = derSeq(derOid(byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b)) + derNull())
            // CN=SD Launcher (OID 2.5.4.3)
            val name = derSeq(derSet(derSeq(derOid(byteArrayOf(0x55, 0x04, 0x03)) + derUtf8("SD Launcher"))))
            // Validity: 2024-01-01 to 2049-12-31
            val validity = derSeq(derUtcTime("240101000000Z") + derUtcTime("491231235959Z"))
            val version = derTagged(0, derInt(2)) // v3
            val serial = derInt(1)
            val tbs = derSeq(version + serial + algId + name + validity + name + pub.encoded)

            val sig = Signature.getInstance("SHA256withRSA").run {
                initSign(priv)
                update(tbs)
                sign()
            }
            return derSeq(tbs + algId + derBitString(sig))
        }

        private fun derTag(
            tag: Int,
            content: ByteArray,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            val len = content.size
            when {
                len < 128 -> out.write(len)
                len < 256 -> {
                    out.write(0x81)
                    out.write(len)
                }
                else -> {
                    out.write(0x82)
                    out.write(len shr 8)
                    out.write(len and 0xff)
                }
            }
            out.write(content)
            return out.toByteArray()
        }

        private fun derSeq(content: ByteArray) = derTag(0x30, content)
        private fun derSet(content: ByteArray) = derTag(0x31, content)
        private fun derTagged(num: Int, content: ByteArray) = derTag(0xA0 or num, content)
        private fun derOid(bytes: ByteArray) = derTag(0x06, bytes)
        private fun derNull() = byteArrayOf(0x05, 0x00)
        private fun derInt(v: Int) = derTag(0x02, byteArrayOf(v.toByte()))
        private fun derUtf8(s: String) = derTag(0x0C, s.toByteArray(Charsets.UTF_8))
        private fun derUtcTime(s: String) = derTag(0x17, s.toByteArray(Charsets.US_ASCII))
        private fun derBitString(bytes: ByteArray) = derTag(0x03, byteArrayOf(0x00) + bytes)
    }
}
