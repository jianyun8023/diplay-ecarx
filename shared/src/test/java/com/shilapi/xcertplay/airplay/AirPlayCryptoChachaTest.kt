package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.bouncycastle.crypto.InvalidCipherTextException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import kotlin.random.Random

class AirPlayCryptoChachaTest {
    private val random = Random(7)
    private fun bytes(size: Int) = random.nextBytes(size)

    @Test fun rfc8439VectorSealsTheSame() {
        val key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = hex("070000004041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val plaintext = ("Ladies and Gentlemen of the class of '99: If I could offer you only one tip " +
            "for the future, sunscreen would be it.").toByteArray(Charsets.US_ASCII)
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, plaintext, aad)
        assertEquals("1ae10b594f09e26a7e902ecbd0600691", sealed.copyOfRange(sealed.size - 16, sealed.size).hex())
        assertArrayEquals(plaintext, AirPlayCrypto.chachaOpen(key, nonce, sealed, aad))
    }

    @Test fun platformAndBouncyCastleOpenEachOther() {
        // Cover the old Android array path and the newer native direct-buffer path.
        for (direct in listOf(false, true)) for (size in listOf(0, 1, 63, 64, 65, 1400, 500_000, 17, 300_000, 2_000_001, 0)) {
            val key = bytes(32)
            val nonce = AirPlayCrypto.nonce64(size.toLong())
            val aad = if (size % 2 == 0) ByteArray(0) else bytes(12)
            val plaintext = bytes(size)
            // Call the platform path itself, so a silent fallback to BouncyCastle fails here.
            val sealed = AirPlayCrypto.platformChacha(Cipher.ENCRYPT_MODE, key, nonce, plaintext, aad, direct)
            assertNotNull("platform ChaCha20-Poly1305 refused to seal $size bytes", sealed)
            assertNotEquals("BouncyCastle", AirPlayCrypto.chachaImplementation)
            assertArrayEquals(AirPlayCrypto.chachaSealBouncyCastle(key, nonce, plaintext, aad), sealed)
            assertArrayEquals(plaintext, AirPlayCrypto.chachaOpenBouncyCastle(key, nonce, sealed!!, aad))
            val bouncySealed = AirPlayCrypto.chachaSealBouncyCastle(key, nonce, plaintext, aad)
            assertArrayEquals(plaintext, AirPlayCrypto.platformChacha(Cipher.DECRYPT_MODE, key, nonce, bouncySealed, aad, direct))
            assertArrayEquals(plaintext, AirPlayCrypto.chachaOpen(key, nonce, sealed, aad))
        }
    }

    @Test fun opensWithThePlatformCipherWhenTheNativeLibraryIsMissing() {
        // Unit tests run on the JVM, where libdiplay_chacha (an Android .so) cannot load.
        val key = bytes(32)
        val nonce = AirPlayCrypto.nonce64(9)
        val aad = bytes(8)
        val plaintext = bytes(1400)
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, plaintext, aad)
        assertTrue(!NativeMedia.available)
        assertArrayEquals(plaintext, AirPlayCrypto.chachaOpen(key, nonce, sealed, aad, offset = 0, length = sealed.size))
        assertNotEquals("ring", AirPlayCrypto.chachaImplementation)
    }

    @Test fun aChangedByteOrAadFailsToOpen() {
        val key = bytes(32)
        val nonce = AirPlayCrypto.nonce64(1)
        val aad = bytes(8)
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, bytes(100), aad)
        val tampered = sealed.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertFails { AirPlayCrypto.chachaOpen(key, nonce, tampered, aad) }
        assertFails { AirPlayCrypto.chachaOpen(key, nonce, sealed, bytes(8)) }
        for (direct in listOf(false, true)) {
            assertFails { AirPlayCrypto.platformChacha(Cipher.DECRYPT_MODE, key, nonce, tampered, aad, direct) }
            assertArrayEquals(AirPlayCrypto.chachaOpenBouncyCastle(key, nonce, sealed, aad),
                AirPlayCrypto.platformChacha(Cipher.DECRYPT_MODE, key, nonce, sealed, aad, direct))
        }
        // A failed tag must not leave the per-thread cipher unusable.
        assertArrayEquals(AirPlayCrypto.chachaOpenBouncyCastle(key, nonce, sealed, aad),
            AirPlayCrypto.chachaOpen(key, nonce, sealed, aad))
    }

    @Test fun selectedCiphertextAndAadRangesOpenOnEveryBackend() {
        val key = bytes(32)
        val nonce = AirPlayCrypto.nonce64(4)
        val aad = bytes(12)
        val paddedAad = bytes(3) + aad + bytes(5)
        for (size in listOf(0, 1, 65, 500_000, 17)) {
            val plain = bytes(size)
            val sealed = AirPlayCrypto.chachaSealBouncyCastle(key, nonce, plain, aad)
            val padded = bytes(7) + sealed + bytes(11)
            for (direct in listOf(false, true)) {
                assertArrayEquals(plain, AirPlayCrypto.platformChacha(
                    Cipher.DECRYPT_MODE, key, nonce, padded, paddedAad, direct,
                    inputOffset = 7, inputLength = sealed.size, aadOffset = 3, aadLength = aad.size,
                ))
            }
            assertArrayEquals(plain, AirPlayCrypto.chachaOpenBouncyCastle(
                key, nonce, padded, paddedAad, 7, sealed.size, 3, aad.size,
            ))
            assertArrayEquals(plain, AirPlayCrypto.chachaOpen(
                key, nonce, padded, paddedAad, 7, sealed.size, 3, aad.size,
            ))
        }
    }

    @Test fun invalidRangesAreRejectedBeforeDecrypting() {
        val key = bytes(32)
        val nonce = AirPlayCrypto.nonce64(5)
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, bytes(1))
        for ((offset, length) in listOf(-1 to 1, 0 to -1, 1 to sealed.size, Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                AirPlayCrypto.chachaOpen(key, nonce, sealed, offset = offset, length = length)
            }
            assertThrows(IllegalArgumentException::class.java) {
                AirPlayCrypto.chachaOpen(key, nonce, sealed, sealed, aadOffset = offset, aadLength = length)
            }
        }
    }

    @Test fun sealingTheSameKeyAndNonceTwiceStillWorks() {
        val key = bytes(32)
        val nonce = AirPlayCrypto.nonceLabel("PV-Msg02")
        val first = AirPlayCrypto.chachaSeal(key, nonce, byteArrayOf(1, 2, 3))
        assertArrayEquals(first, AirPlayCrypto.chachaSeal(key, nonce, byteArrayOf(1, 2, 3)))
        assertEquals("BouncyCastle", AirPlayCrypto.chachaImplementation)
        assertArrayEquals(byteArrayOf(1, 2, 3), AirPlayCrypto.chachaOpen(key, nonce, first))
        assertNotEquals("BouncyCastle", AirPlayCrypto.chachaImplementation)
    }

    @Test fun absentPlatformFallsBackWithoutAcceptingATamperedTag() {
        val state = platformState()
        val saved = state.get()
        state.set(null)
        try {
            val key = bytes(32)
            val nonce = AirPlayCrypto.nonce64(2)
            val aad = bytes(8)
            val plain = bytes(128)
            val sealed = AirPlayCrypto.chachaSeal(key, nonce, plain, aad)
            assertEquals("BouncyCastle", AirPlayCrypto.chachaImplementation)
            assertArrayEquals(AirPlayCrypto.chachaSealBouncyCastle(key, nonce, plain, aad), sealed)
            assertArrayEquals(plain, AirPlayCrypto.chachaOpen(key, nonce, sealed, aad))
            val tampered = sealed.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            try {
                AirPlayCrypto.chachaOpen(key, nonce, tampered, aad)
                fail("fallback accepted a changed tag")
            } catch (_: InvalidCipherTextException) { }
        } finally { state.set(saved) }
    }

    @Test fun largeFramesDoNotRetainLargeDirectBuffers() {
        val key = bytes(32)
        val nonce = AirPlayCrypto.nonce64(3)
        val plain = bytes(2_000_001)
        val sealed = AirPlayCrypto.platformChacha(Cipher.ENCRYPT_MODE, key, nonce, plain, ByteArray(0), true)
        assertNotNull(sealed)
        assertArrayEquals(plain,
            AirPlayCrypto.platformChacha(Cipher.DECRYPT_MODE, key, nonce, sealed!!, ByteArray(0), true))
        val state = platformState().get()!!
        for (field in listOf("input", "output")) {
            val retained = state.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(state) as ByteBuffer
            assertTrue("retained $field buffer exceeded the per-thread cap", retained.capacity() <= 1024 * 1024)
        }
    }

    @Test fun concurrentCallsKeepKeysAadAndBuffersOnTheirOwnThread() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val workers = (0..3).map { worker ->
                pool.submit {
                    val key = ByteArray(32) { worker.toByte() }
                    val aad = byteArrayOf(worker.toByte())
                    repeat(20) { counter ->
                        val plain = ByteArray(if (counter % 2 == 0) 128 else 65_537) { (worker + it).toByte() }
                        val nonce = AirPlayCrypto.nonce64(counter.toLong())
                        val direct = worker % 2 == 0
                        val sealed = AirPlayCrypto.platformChacha(Cipher.ENCRYPT_MODE, key, nonce, plain, aad, direct)
                        assertNotNull(sealed)
                        assertArrayEquals(plain,
                            AirPlayCrypto.platformChacha(Cipher.DECRYPT_MODE, key, nonce, sealed!!, aad, direct))
                    }
                }
            }
            workers.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun platformState(): ThreadLocal<Any?> = AirPlayCrypto::class.java
        .getDeclaredField("platformState").apply { isAccessible = true }.get(null) as ThreadLocal<Any?>

    /** The platform cipher is present on the test JVM, so a bad tag must surface as its exception. */
    private fun assertFails(block: () -> Unit) {
        try {
            block()
        } catch (_: AEADBadTagException) {
            return
        }
        fail("expected the platform tag check to throw AEADBadTagException")
    }

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
