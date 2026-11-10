package com.shilapi.xcertplay.airplay

import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ControlCipherTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test fun emptySingleAndMultipleFramesRoundTrip() {
        for (size in listOf(0, 1, 0x4000, 0x4001, 0x8001)) {
            val plain = ByteArray(size) { it.toByte() }
            val wire = ControlCipher(key, key).encrypt(plain)
            val decoded = ControlCipher(key, key).decrypt(wire)
            assertArrayEquals(plain, decoded.data)
            assertArrayEquals(ByteArray(0), decoded.rest)
        }
    }

    @Test fun coalescedFramesKeepTheIncompleteTailAndNonce() {
        val encoder = ControlCipher(key, key)
        val first = encoder.encrypt(byteArrayOf(1, 2))
        val second = encoder.encrypt(byteArrayOf(3, 4, 5))
        val third = encoder.encrypt(byteArrayOf(6))
        val decoder = ControlCipher(key, key)
        val decoded = decoder.decrypt(first + second + third.copyOf(4))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), decoded.data)
        assertArrayEquals(third.copyOf(4), decoded.rest)
        val completed = decoder.decrypt(decoded.rest + third.copyOfRange(4, third.size))
        assertArrayEquals(byteArrayOf(6), completed.data)
        assertArrayEquals(ByteArray(0), completed.rest)
    }

    @Test fun incompleteFramesAreReturnedWithoutCopyingOrAdvancingTheNonce() {
        val plain = byteArrayOf(1, 2, 3)
        val wire = ControlCipher(key, key).encrypt(plain)
        val decoder = ControlCipher(key, key)
        for (length in wire.indices) {
            val fragment = wire.copyOf(length)
            val decoded = decoder.decrypt(fragment)
            assertArrayEquals(ByteArray(0), decoded.data)
            assertSame(fragment, decoded.rest)
        }
        assertArrayEquals(plain, decoder.decrypt(wire).data)
    }

    @Test fun failedAuthenticationDoesNotAdvanceTheNonce() {
        val wire = ControlCipher(key, key).encrypt(byteArrayOf(1, 2, 3))
        val bad = wire.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        val decoder = ControlCipher(key, key)
        assertThrows(AEADBadTagException::class.java) { decoder.decrypt(bad) }
        assertArrayEquals(byteArrayOf(1, 2, 3), decoder.decrypt(wire).data)
    }
}
