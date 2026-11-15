package dev.gladimdim.tiling.adb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * BoringSSL's `spake25519` (SPAKE2 over Ed25519, `crypto/curve25519/spake25519.cc`), which Android's Wireless
 * Debugging pairing (`packages/modules/adb/pairing_auth`) is built on. It is not RFC 9382: the M and N points, the
 * transcript and the key length are BoringSSL's own, and adbd accepts nothing else.
 *
 * The client of a pairing is [Role.Alice]; [Role.Bob] is the phone's side, here for the tests.
 */
internal class Spake25519(
    private val role: Role,
    private val myName: ByteArray,
    private val theirName: ByteArray,
    password: ByteArray,
    /** A multiple of eight below 8·l. Random when null; given by the tests. */
    privateScalar: BigInteger? = null,
) {
    enum class Role { Alice, Bob }

    private val passwordHash: ByteArray = MessageDigest.getInstance("SHA-512").digest(password)
    private val passwordScalar: BigInteger
    private val privateKey: BigInteger
    private val myMessage: ByteArray

    init {
        // BoringSSL never cleared the cofactor of the password scalar, and makes up for it by adding the group
        // order until the scalar is a multiple of eight. The same sum here keeps the messages byte for byte alike.
        var scalar = fromLittleEndian(passwordHash).mod(L)
        if (scalar.testBit(0)) scalar += L
        if (scalar.testBit(1)) scalar += L.shiftLeft(1)
        if (scalar.testBit(2)) scalar += L.shiftLeft(2)
        passwordScalar = scalar

        privateKey =
            privateScalar ?: run {
                val random = ByteArray(64).also { SecureRandom().nextBytes(it) }
                // Times eight, so the peer's point loses any part of small order.
                fromLittleEndian(random).mod(L).shiftLeft(3)
            }
        val mask = (if (role == Role.Alice) M else N).mul(passwordScalar)
        myMessage = B.mul(privateKey).add(mask).encode()
    }

    fun publicMessage(): ByteArray = myMessage.copyOf()

    /** The 64 bytes of key material both sides arrive at when their passwords are the same. */
    fun finish(theirMessage: ByteArray): ByteArray {
        require(theirMessage.size == 32) { "SPAKE2 message of ${theirMessage.size} bytes" }
        val peersMask = (if (role == Role.Alice) N else M).mul(passwordScalar)
        val shared = Point.decode(theirMessage).add(peersMask.negate()).mul(privateKey).encode()

        val sha = MessageDigest.getInstance("SHA-512")

        fun feed(bytes: ByteArray) {
            sha.update(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(bytes.size.toLong()).array())
            sha.update(bytes)
        }
        // Alice's name and message first, whoever computes it.
        if (role == Role.Alice) {
            feed(myName)
            feed(theirName)
            feed(myMessage)
            feed(theirMessage)
        } else {
            feed(theirName)
            feed(myName)
            feed(theirMessage)
            feed(myMessage)
        }
        feed(shared)
        feed(passwordHash)
        return sha.digest()
    }

    /** A point of edwards25519 in affine coordinates: slow and not constant-time, which a pairing once a year can afford. */
    internal data class Point(
        val x: BigInteger,
        val y: BigInteger,
    ) {
        fun add(other: Point): Point {
            val x1y2 = x.multiply(other.y).mod(P)
            val y1x2 = y.multiply(other.x).mod(P)
            val y1y2 = y.multiply(other.y).mod(P)
            val x1x2 = x.multiply(other.x).mod(P)
            val dxxyy = D.multiply(x1x2).mod(P).multiply(y1y2).mod(P)
            // -x² + y² = 1 + d·x²·y²: the curve's a is -1, so y3's numerator is y1·y2 + x1·x2.
            val x3 = x1y2.add(y1x2).multiply(BigInteger.ONE.add(dxxyy).modInverse(P)).mod(P)
            val y3 = y1y2.add(x1x2).multiply(BigInteger.ONE.subtract(dxxyy).mod(P).modInverse(P)).mod(P)
            return Point(x3, y3)
        }

        fun negate(): Point = Point(P.subtract(x).mod(P), y)

        fun mul(scalar: BigInteger): Point {
            var result = IDENTITY
            var doubled = this
            var k = scalar
            while (k.signum() > 0) {
                if (k.testBit(0)) result = result.add(doubled)
                doubled = doubled.add(doubled)
                k = k.shiftRight(1)
            }
            return result
        }

        fun encode(): ByteArray {
            val out = toLittleEndian32(y)
            if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
            return out
        }

        companion object {
            fun decode(bytes: ByteArray): Point {
                require(bytes.size == 32)
                val negative = (bytes[31].toInt() ushr 7) and 1 == 1
                val y = fromLittleEndian(bytes.copyOf().also { it[31] = (it[31].toInt() and 0x7F).toByte() })
                require(y < P) { "not a point: y is too large" }
                val y2 = y.multiply(y).mod(P)
                val x = sqrtRatio(y2.subtract(BigInteger.ONE).mod(P), D.multiply(y2).add(BigInteger.ONE).mod(P)) ?: throw IllegalArgumentException("not a point of the curve")
                return Point(if (x.testBit(0) != negative) P.subtract(x).mod(P) else x, y)
            }

            /** sqrt(u / v) mod p, or null when there is none. */
            private fun sqrtRatio(
                u: BigInteger,
                v: BigInteger,
            ): BigInteger? {
                val v3 = v.modPow(BigInteger.valueOf(3), P)
                val v7 = v3.multiply(v3).mod(P).multiply(v).mod(P)
                val x = u.multiply(v3).mod(P).multiply(u.multiply(v7).mod(P).modPow(P.subtract(BigInteger.valueOf(5)).shiftRight(3), P)).mod(P)
                val vx2 = v.multiply(x).mod(P).multiply(x).mod(P)
                return when (vx2) {
                    u -> x
                    P.subtract(u).mod(P) -> x.multiply(SQRT_M1).mod(P)
                    else -> null
                }
            }
        }
    }

    companion object {
        private val P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))

        /** The order of the prime-order subgroup. */
        internal val L = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
        private val D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
        private val SQRT_M1 = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)
        private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE)

        private val B =
            Point(
                BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202"),
                BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960"),
            )

        // BoringSSL's own points, as its spake25519.cc gives them.
        private val M = Point.decode(hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"))
        private val N = Point.decode(hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"))

        private fun fromLittleEndian(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

        private fun toLittleEndian32(v: BigInteger): ByteArray {
            val be = v.toByteArray()
            val out = ByteArray(32)
            var src = be.size - 1
            var dst = 0
            while (src >= 0 && dst < 32) out[dst++] = be[src--]
            return out
        }

        internal fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
