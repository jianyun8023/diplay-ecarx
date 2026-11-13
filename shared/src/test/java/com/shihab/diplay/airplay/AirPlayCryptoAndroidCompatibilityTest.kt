package com.shihab.diplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import javax.crypto.AEADBadTagException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The ChaCha20-Poly1305 path (including its direct-buffer staging) now lives in the Rust core, so the
 * old Kotlin `platformState` ThreadLocal no longer exists. Keep the behavioral guarantee on every
 * supported API level: sealing and opening round-trips the plaintext.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 30], manifest = Config.NONE)
class AirPlayCryptoAndroidCompatibilityTest {
    @Test fun olderSupportedAndroidRoundTrips() {
        roundTrip()
    }

    @Test
    @Config(sdk = [31])
    fun android12RoundTrips() {
        roundTrip()
    }

    private fun roundTrip() {
        val key = ByteArray(32)
        val nonce = AirPlayCrypto.nonce64(10)
        val plain = byteArrayOf(1, 2, 3)
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, ByteArray(0))
        // Use a separate nonce after the empty message so each encryption is unique.
        val dataNonce = AirPlayCrypto.nonce64(11)
        val data = AirPlayCrypto.chachaSeal(key, dataNonce, plain)
        assertEquals(16, sealed.size)
        assertEquals(plain.size + 16, data.size)
        assertArrayEquals(ByteArray(0), AirPlayCrypto.chachaOpen(key, nonce, sealed))
        assertArrayEquals(plain, AirPlayCrypto.chachaOpen(key, dataNonce, data))
        data[data.lastIndex] = (data.last().toInt() xor 1).toByte()
        assertThrows(AEADBadTagException::class.java) { AirPlayCrypto.chachaOpen(key, dataNonce, data) }
    }
}
