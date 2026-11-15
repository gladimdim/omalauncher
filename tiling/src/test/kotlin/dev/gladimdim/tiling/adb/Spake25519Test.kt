package dev.gladimdim.tiling.adb

import dev.gladimdim.tiling.adb.Spake25519.Role
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class Spake25519Test {
    private val alice = "adb pair client\u0000".toByteArray()
    private val bob = "adb pair server\u0000".toByteArray()

    @Test
    fun bothSidesArriveAtTheSameKey() {
        val password = "123456".toByteArray() + ByteArray(64) { it.toByte() }
        val a = Spake25519(Role.Alice, alice, bob, password)
        val b = Spake25519(Role.Bob, bob, alice, password)
        val key = a.finish(b.publicMessage())
        assertEquals(64, key.size)
        assertArrayEquals(key, b.finish(a.publicMessage()))
    }

    @Test
    fun anotherPasswordGivesAnotherKey() {
        val a = Spake25519(Role.Alice, alice, bob, "123456".toByteArray())
        val b = Spake25519(Role.Bob, bob, alice, "123457".toByteArray())
        assertFalse(a.finish(b.publicMessage()).contentEquals(b.finish(a.publicMessage())))
    }

    @Test
    fun aMessageSurvivesDecodingAndEncoding() {
        val message = Spake25519(Role.Alice, alice, bob, "123456".toByteArray()).publicMessage()
        assertArrayEquals(message, Spake25519.Point.decode(message).encode())
    }

    /**
     * Taken from the real `adb pair` (BoringSSL) pairing with our side as the phone, in
     * AdbPairingTest.theRealAdbPairsWithIt: adb sent this message for this password, and went on to open what was
     * sealed with this key.
     */
    @Test
    fun agreesWithBoringSsl() {
        val password =
            Spake25519.hex(
                "343832393133b3078db20b8fa01c182d1829819f2cdc2ce3c2973c6b98d46eb08f00907af01276cb76427c3940f65578cbc86db6" +
                    "fdfbdd3888a49a1926b40dbd9a5d0860a46d",
            )
        val fromAdb = Spake25519.hex("13abff63f3171dad41f1690b41a035724980fa1d99157233933216e57c244d8f")
        val key = Spake25519(Role.Bob, bob, alice, password, AdbPairingTest.FIXED_SCALAR).finish(fromAdb)
        assertArrayEquals(
            Spake25519.hex(
                "2477f3ac45d8a2bd73daba97e676bc4859d322d8ad9f1a773cec63960e1bddcb11405bfca2ac2bd4d784d034632362c13f364869" +
                    "c2ede35c8cef9cbcace7b6e8",
            ),
            key,
        )
    }
}
