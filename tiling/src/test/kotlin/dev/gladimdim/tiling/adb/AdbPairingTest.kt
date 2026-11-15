package dev.gladimdim.tiling.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.math.BigInteger
import java.util.concurrent.TimeUnit

class AdbPairingTest {
    @Test
    fun pairsWithTheRightCode() {
        val crypto = AdbCrypto.generate()
        PairingServer("482913").use { server ->
            AdbPairing.pair("127.0.0.1", server.port, "482913", crypto)
            server.await()
            assertNull(server.failure)
            val info = server.peerInfo!!
            assertEquals(AdbPairing.PEER_INFO_SIZE, info.size)
            assertEquals(0, info[0].toInt())
            // The phone is handed our key, as it will meet it in the certificate of every later connection.
            assertEquals(crypto.adbPublicKeyString, String(info, 1, crypto.adbPublicKeyString.length, Charsets.UTF_8))
        }
    }

    @Test
    fun aWrongCodeIsToldApart() {
        PairingServer("482913").use { server ->
            assertThrows(WrongPairingCode::class.java) { AdbPairing.pair("127.0.0.1", server.port, "482914", AdbCrypto.generate()) }
            server.await()
            assertNull(server.peerInfo)
        }
    }

    @Test
    fun anAdbKeyIsTheSizeAndroidExpects() {
        val key = AdbCrypto.generate().adbPublicKeyString
        assertTrue(key.endsWith(" sdlauncher@android"))
        assertEquals(524, java.util.Base64.getDecoder().decode(key.substringBefore(' ')).size)
    }

    /**
     * The real `adb pair` against [PairingServer]: what proves the SPAKE2, the key derivation and the framing here
     * are adbd's. It needs adb and starts its server, so it only runs when asked for with ADB_PAIR_INTEROP=1; the
     * vector in Spake25519Test was taken from a run of it.
     */
    @Test
    fun theRealAdbPairsWithIt() {
        assumeTrue(System.getenv("ADB_PAIR_INTEROP") == "1")
        PairingServer("482913", FIXED_SCALAR).use { server ->
            val adb = ProcessBuilder("adb", "pair", "127.0.0.1:${server.port}", "482913").redirectErrorStream(true).start()
            val said = adb.inputStream.bufferedReader().readText()
            assertTrue(adb.waitFor(20, TimeUnit.SECONDS))
            server.await()
            assertNull(server.failure)
            assertTrue(said, said.contains("Successfully paired"))
            val info = assertNotNullAnd(server.peerInfo)
            assertEquals(0, info[0].toInt())
            println("password=" + server.password!!.hex())
            println("alice=" + server.clientMessage!!.hex())
            println("key=" + server.keyMaterial!!.hex())
        }
    }

    private fun assertNotNullAnd(bytes: ByteArray?): ByteArray {
        assertNotNull(bytes)
        return bytes!!
    }

    companion object {
        /** A multiple of eight, as a SPAKE2 private key is. */
        val FIXED_SCALAR: BigInteger = BigInteger("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcd", 16).shiftLeft(3)

        fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}
