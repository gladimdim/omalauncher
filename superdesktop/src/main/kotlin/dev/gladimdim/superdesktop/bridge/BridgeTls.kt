package dev.gladimdim.superdesktop.bridge

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

// Ported from the SUPER DESKTOP app's BridgeTls.kt (see SOURCE.md), without its demo transport.

private val pinPattern = Regex("[0-9a-f]{64}")

/** Trust comes only from the pairing invitation's fingerprint, never from discovery or first use. */
internal object BridgeTls {
    private val clients =
        object : LinkedHashMap<String, OkHttpClient>(16, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OkHttpClient>?) = size > 16
        }

    fun fingerprint(cert: X509Certificate): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun isPin(pin: String) = pin.matches(pinPattern)

    @Synchronized fun client(pin: String): OkHttpClient {
        check(isPin(pin)) { "Pair with the computer's invitation to verify it." }
        return clients.getOrPut(pin) {
            val trust =
                object : X509TrustManager {
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

                    override fun checkClientTrusted(
                        chain: Array<out X509Certificate>?,
                        authType: String?,
                    ): Unit = throw CertificateException("Client certificates not supported")

                    override fun checkServerTrusted(
                        chain: Array<out X509Certificate>?,
                        authType: String?,
                    ) {
                        val cert = chain?.firstOrNull() ?: throw CertificateException("Missing bridge certificate")
                        cert.checkValidity()
                        if (!MessageDigest.isEqual(fingerprint(cert).toByteArray(), pin.toByteArray())) {
                            throw CertificateException("The computer's certificate changed. Pair again with a new invitation.")
                        }
                    }
                }
            val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            OkHttpClient
                .Builder()
                .sslSocketFactory(tls.socketFactory, trust)
                // Any address of the computer is fine, as long as it shows the pinned certificate.
                .hostnameVerifier { _, session ->
                    runCatching { fingerprint(session.peerCertificates.first() as X509Certificate) == pin }.getOrDefault(false)
                }.followRedirects(false)
                .followSslRedirects(false)
                .callTimeout(8, TimeUnit.SECONDS)
                .connectTimeout(5, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
        }
    }
}
