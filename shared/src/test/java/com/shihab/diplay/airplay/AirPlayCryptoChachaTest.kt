package com.shihab.diplay.airplay

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException
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

    @Test fun nativeAndIndependentBouncyCastleOracleOpenEachOther() {
        for (size in listOf(0, 1, 63, 64, 65, 1400, 500_000, 17, 300_000, 2_000_001, 0)) {
            val key = bytes(32)
            val nonce = AirPlayCrypto.nonce64(size.toLong())
            val aad = if (size % 2 == 0) ByteArray(0) else bytes(12)
            val plain = bytes(size)
            val sealed = AirPlayCrypto.chachaSeal(key, nonce, plain, aad)
            assertArrayEquals(BouncyCryptoOracle.chachaSeal(key, nonce, plain, aad), sealed)
            assertArrayEquals(plain, BouncyCryptoOracle.chachaOpen(key, nonce, sealed, aad))
            assertArrayEquals(plain, AirPlayCrypto.chachaOpen(key, nonce, BouncyCryptoOracle.chachaSeal(key, nonce, plain, aad), aad))
        }
    }

    @Test fun diagnosticsIdentifyTheRequiredNativeImplementation() {
        val key = bytes(32)
        val nonce = AirPlayCrypto.nonce64(9)
        val plain = bytes(1400)
        assertTrue(NativeMedia.available)
        assertArrayEquals(plain, AirPlayCrypto.chachaOpen(key, nonce, AirPlayCrypto.chachaSeal(key, nonce, plain)))
        assertEquals("ring", AirPlayCrypto.chachaImplementation)
    }

    @Test fun aChangedByteOrAadFailsWithoutPoisoningTheNextOperation() {
        val key = bytes(32)
        val nonce = AirPlayCrypto.nonce64(1)
        val aad = bytes(8)
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, bytes(100), aad)
        val tampered = sealed.copyOf().apply { this[10] = (this[10].toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) { AirPlayCrypto.chachaOpen(key, nonce, tampered, aad) }
        assertThrows(AEADBadTagException::class.java) { AirPlayCrypto.chachaOpen(key, nonce, sealed, bytes(8)) }
        assertArrayEquals(BouncyCryptoOracle.chachaOpen(key, nonce, sealed, aad), AirPlayCrypto.chachaOpen(key, nonce, sealed, aad))
    }

    @Test fun selectedCiphertextAndAadRangesMatchTheIndependentOracle() {
        val key = bytes(32)
        val nonce = AirPlayCrypto.nonce64(4)
        val aad = bytes(12)
        val paddedAad = bytes(3) + aad + bytes(5)
        for (size in listOf(0, 1, 65, 500_000, 17)) {
            val plain = bytes(size)
            val sealed = BouncyCryptoOracle.chachaSeal(key, nonce, plain, aad)
            val padded = bytes(7) + sealed + bytes(11)
            assertArrayEquals(plain, BouncyCryptoOracle.chachaOpen(key, nonce, padded, paddedAad, 7, sealed.size, 3, aad.size))
            assertArrayEquals(plain, AirPlayCrypto.chachaOpen(key, nonce, padded, paddedAad, 7, sealed.size, 3, aad.size))
        }
    }

    @Test fun invalidRangesAreRejectedBeforeDecrypting() {
        val key = bytes(32)
        val nonce = AirPlayCrypto.nonce64(5)
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, bytes(1))
        for ((offset, length) in listOf(-1 to 1, 0 to -1, 1 to sealed.size, Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { AirPlayCrypto.chachaOpen(key, nonce, sealed, offset = offset, length = length) }
            assertThrows(IllegalArgumentException::class.java) { AirPlayCrypto.chachaOpen(key, nonce, sealed, sealed, aadOffset = offset, aadLength = length) }
        }
    }

    @Test fun sealingTheSameKeyAndNonceTwiceRemainsDeterministic() {
        val key = bytes(32)
        val nonce = AirPlayCrypto.nonceLabel("PV-Msg02")
        val first = AirPlayCrypto.chachaSeal(key, nonce, byteArrayOf(1, 2, 3))
        assertArrayEquals(first, AirPlayCrypto.chachaSeal(key, nonce, byteArrayOf(1, 2, 3)))
        assertEquals("ring", AirPlayCrypto.chachaImplementation)
        assertArrayEquals(byteArrayOf(1, 2, 3), AirPlayCrypto.chachaOpen(key, nonce, first))
    }

    @Test fun invalidKeysNoncesAndShortTagsAreRejectedAtTheBoundary() {
        for (size in listOf(0, 1, 31, 33)) {
            assertThrows(IllegalArgumentException::class.java) { AirPlayCrypto.chachaSeal(ByteArray(size), ByteArray(12), ByteArray(0)) }
            assertThrows(IllegalArgumentException::class.java) { AirPlayCrypto.chachaOpen(ByteArray(size), ByteArray(12), ByteArray(16)) }
        }
        for (size in listOf(0, 1, 11, 13)) {
            assertThrows(IllegalArgumentException::class.java) { AirPlayCrypto.chachaSeal(ByteArray(32), ByteArray(size), ByteArray(0)) }
            assertThrows(IllegalArgumentException::class.java) { AirPlayCrypto.chachaOpen(ByteArray(32), ByteArray(size), ByteArray(16)) }
        }
        for (size in 0..15) assertThrows(AEADBadTagException::class.java) {
            AirPlayCrypto.chachaOpen(ByteArray(32), ByteArray(12), ByteArray(size))
        }
    }

    @Test fun largeThenSmallOperationsDoNotMutateTheirInputs() {
        val key = bytes(32)
        val nonce = AirPlayCrypto.nonce64(3)
        for (size in listOf(2_000_001, 0, 31)) {
            val plain = bytes(size)
            val original = plain.copyOf()
            val sealed = AirPlayCrypto.chachaSeal(key, nonce, plain)
            val originalSealed = sealed.copyOf()
            assertArrayEquals(plain, AirPlayCrypto.chachaOpen(key, nonce, sealed))
            assertArrayEquals(original, plain); assertArrayEquals(originalSealed, sealed)
        }
    }

    @Test fun concurrentCallsKeepKeysAndAadIndependent() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val workers = (0..3).map { worker -> pool.submit {
                val key = ByteArray(32) { worker.toByte() }
                val aad = byteArrayOf(worker.toByte())
                repeat(20) { counter ->
                    val plain = ByteArray(if (counter % 2 == 0) 128 else 65_537) { (worker + it).toByte() }
                    val nonce = AirPlayCrypto.nonce64(counter.toLong())
                    assertArrayEquals(plain, AirPlayCrypto.chachaOpen(key, nonce, AirPlayCrypto.chachaSeal(key, nonce, plain, aad), aad))
                }
            } }
            workers.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
