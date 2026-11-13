package com.shilapi.xcertplay.airplay

import javax.crypto.AEADBadTagException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCipherTest {
    private val key = ByteArray(32) { it.toByte() }
    private val owners = ArrayList<ControlCipher>()
    private fun owner(read: ByteArray = key, write: ByteArray = key) = ControlCipher(read, write).also { owners.add(it) }
    @After fun closeOwners() { owners.forEach { it.close() } }

    @Test fun emptySingleAndMultipleFramesRoundTrip() {
        for (size in listOf(0, 1, 0x4000, 0x4001, 0x8001)) {
            val plain = ByteArray(size) { it.toByte() }
            val wire = owner().encrypt(plain)
            val decoded = owner().decrypt(wire)
            assertArrayEquals(plain, decoded.data)
            assertArrayEquals(ByteArray(0), decoded.rest)
        }
    }

    @Test fun coalescedFramesKeepTheIncompleteTailAndNonce() {
        val encoder = owner()
        val first = encoder.encrypt(byteArrayOf(1, 2))
        val second = encoder.encrypt(byteArrayOf(3, 4, 5))
        val third = encoder.encrypt(byteArrayOf(6))
        val decoder = owner()
        val decoded = decoder.decrypt(first + second + third.copyOf(4))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), decoded.data)
        assertArrayEquals(third.copyOf(4), decoded.rest)
        val completed = decoder.decrypt(decoded.rest + third.copyOfRange(4, third.size))
        assertArrayEquals(byteArrayOf(6), completed.data)
        assertArrayEquals(ByteArray(0), completed.rest)
    }

    @Test fun incompleteFramesAreReturnedWithoutCopyingOrAdvancingTheNonce() {
        val plain = byteArrayOf(1, 2, 3)
        val wire = owner().encrypt(plain)
        val decoder = owner()
        for (length in wire.indices) {
            val fragment = wire.copyOf(length)
            val decoded = decoder.decrypt(fragment)
            assertArrayEquals(ByteArray(0), decoded.data)
            assertSame(fragment, decoded.rest)
        }
        assertArrayEquals(plain, decoder.decrypt(wire).data)
    }

    @Test fun failedAuthenticationDoesNotAdvanceTheNonce() {
        val wire = owner().encrypt(byteArrayOf(1, 2, 3))
        val bad = wire.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        val decoder = owner()
        assertThrows(AEADBadTagException::class.java) { decoder.decrypt(bad) }
        assertArrayEquals(byteArrayOf(1, 2, 3), decoder.decrypt(wire).data)
    }

    @Test fun successfulFramesBeforeAuthenticationFailureKeepTheirCounterProgress() {
        val encoder = owner()
        val first = encoder.encrypt(byteArrayOf(1, 2))
        val second = encoder.encrypt(byteArrayOf(3, 4))
        val bad = second.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        val decoder = owner()
        assertThrows(AEADBadTagException::class.java) { decoder.decrypt(first + bad) }
        assertArrayEquals(byteArrayOf(3, 4), decoder.decrypt(second).data)
    }

    @Test fun decodingAcceptsLengthsAboveTheEncoderChunkSize() {
        val plain = ByteArray(65535) { it.toByte() }
        val header = byteArrayOf(-1, -1)
        val sealed = BouncyCryptoOracle.chachaSeal(key, AirPlayCrypto.nonce64(0), plain, header)
        assertArrayEquals(plain, owner().decrypt(header + sealed).data)
    }

    @Test fun invalidKeysFailOnlyWhenThatDirectionUsesACompleteFrame() {
        for (size in listOf(0, 1, 31, 33)) {
            val invalidRead = owner(ByteArray(size), key)
            val fragment = byteArrayOf(0, 0)
            assertSame(fragment, invalidRead.decrypt(fragment).rest)
            assertTrue(invalidRead.encrypt(byteArrayOf(1)).isNotEmpty())
            // Provider wording varies; type and lazy direction-specific failure are the contract.
            assertThrows(IllegalArgumentException::class.java) { invalidRead.decrypt(ByteArray(18)) }
            val invalidWrite = owner(key, ByteArray(size))
            assertSame(fragment, invalidWrite.decrypt(fragment).rest)
            assertThrows(IllegalArgumentException::class.java) { invalidWrite.encrypt(ByteArray(0)) }
            assertArrayEquals(byteArrayOf(3), invalidWrite.decrypt(owner().encrypt(byteArrayOf(3))).data)
        }
    }

    @Test fun fixedReferenceVectorsKeepCountersAcrossEncryptCalls() {
        val cipher = owner(ByteArray(32) { 1 }, ByteArray(32) { 2 })
        val first = cipher.encrypt(ByteArray(16384) { 0x41 } + "tail".toByteArray())
        assertEquals(16424, first.size)
        assertEquals("d9a80f2f38ddd9c2af5f3ac536cd481e4dcfa9dd38c87b6d400289ccfc8cabd2",
            MessageDigest.getInstance("SHA-256").digest(first).joinToString("") { "%02x".format(it) })
        assertEquals("0600f46717c443a865be9990d158e4679a8fd58f23a1a863",
            cipher.encrypt("second".toByteArray()).joinToString("") { "%02x".format(it) })
    }

    @Test fun closeIsIdempotentAndRejectsEverySubsequentOperation() {
        val cipher = owner()
        cipher.close(); cipher.close()
        assertEquals("ControlCipher is closed", assertThrows(IllegalStateException::class.java) { cipher.encrypt(ByteArray(0)) }.message)
        assertThrows(IllegalStateException::class.java) { cipher.decrypt(ByteArray(0)) }
    }

    @Test(timeout = 10_000) fun encryptDecryptAndCloseShareOneHandleLock() {
        val cipher = owner()
        val first = CountDownLatch(2)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val workers = listOf(true, false).map { encrypt -> Thread {
            try {
                repeat(100) {
                    if (encrypt) cipher.encrypt(ByteArray(16385)) else cipher.decrypt(ByteArray(0))
                    if (it == 0) first.countDown()
                }
            } catch (_: IllegalStateException) {
                // Closing wins the monitor before the next operation.
            } catch (error: Throwable) { failures.add(error) }
        }.apply { start() } }
        try {
            assertTrue(first.await(3, TimeUnit.SECONDS))
            cipher.close()
            workers.forEach { it.join(3000); assertTrue(!it.isAlive) }
            assertTrue(failures.toString(), failures.isEmpty())
        } finally { cipher.close(); workers.forEach { it.join(3000) } }
    }
}
