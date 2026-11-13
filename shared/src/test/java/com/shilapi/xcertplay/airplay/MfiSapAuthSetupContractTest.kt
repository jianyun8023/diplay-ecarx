package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.mfi.BaaCertificatePair
import com.shilapi.xcertplay.mfi.MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.MfiCertificateType
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class MfiSapAuthSetupContractTest {
    private val handlers = listOf(MfiSapAuthSetup::handle, KotlinMfiSapAuthSetup::handle)
    private class Chip(val type: MfiCertificateType = MfiCertificateType.MFI, val major: Int = 3, size: Int = 1) : MfiAuthenticator {
        val calls = mutableListOf<String>()
        val certificate = byteArrayOf(1, 2, 3)
        val chain = BaaCertificatePair(byteArrayOf(4, 5), ByteArray(size) { 6 })
        val signature = ByteArray(31) { it.toByte() }
        var challenge = byteArrayOf()
        var failure: Throwable? = null
        override val certificateType: MfiCertificateType get() { calls += "kind"; return type }
        override fun protocolMajor(): Int { calls += "major"; return major }
        override fun readCertificate(maximumOutputLength: Int): ByteArray {
            assertEquals(MfiAuthenticationClient.DEFAULT_MAXIMUM_CERTIFICATE_OUTPUT_LENGTH, maximumOutputLength)
            calls += "certificate"; return certificate
        }
        override fun baaCertificates(): BaaCertificatePair { calls += "baa"; return chain }
        override fun signChallenge(challenge: ByteArray): ByteArray {
            calls += "sign"; this.challenge = challenge.copyOf()
            certificate[0] = 99; chain.leaf[0] = 88; chain.intermediate[0] = 77
            failure?.let { throw it }
            return signature
        }
    }
    private fun digest(algorithm: String, vararg parts: ByteArray): ByteArray = MessageDigest.getInstance(algorithm).run {
        parts.forEach(::update); digest()
    }
    private fun decrypt(phone: AirPlayCrypto.X25519KeyPair, public: ByteArray, sealed: ByteArray): ByteArray {
        val shared = BouncyCryptoOracle.x25519Shared(phone.privateKey, public)
        val key = digest("SHA-1", "AES-KEY".toByteArray(Charsets.US_ASCII), shared).copyOf(16)
        val iv = digest("SHA-1", "AES-IV".toByteArray(Charsets.US_ASCII), shared).copyOf(16)
        return Cipher.getInstance("AES/CTR/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)); doFinal(sealed)
        }
    }
    private fun segment(buffer: ByteBuffer): ByteArray = ByteArray(buffer.int).also(buffer::get)

    @Test fun mfiMajor2And3KeepHashChoiceCallbackOrderAndLateCertificateContents() {
        for (handle in handlers) for (major in listOf(2, 3)) {
            val phone = AirPlayCrypto.x25519Generate(); val chip = Chip(major = major)
            val response = handle(byteArrayOf(1) + phone.publicKey, chip)!!
            val buffer = ByteBuffer.wrap(response); val public = ByteArray(32).also(buffer::get)
            assertArrayEquals(chip.certificate, segment(buffer))
            assertArrayEquals(chip.signature, decrypt(phone, public, segment(buffer)))
            assertEquals(0, buffer.remaining())
            assertArrayEquals(digest(if (major == 2) "SHA-1" else "SHA-256", public, phone.publicKey), chip.challenge)
            assertEquals(listOf("kind", "certificate", "major", "sign"), chip.calls)
        }
    }

    @Test fun baaTranscriptAndOpackLengthBoundariesMatchIndependentKotlinReference() {
        for (size in listOf(1, 32, 33, 255, 256, 65535, 65536)) {
            val phone = AirPlayCrypto.x25519Generate(); val baic = mutableListOf<ByteArray>()
            for (handle in handlers) {
                val chip = Chip(MfiCertificateType.BAA, size = size)
                val buffer = ByteBuffer.wrap(handle(byteArrayOf(1) + phone.publicKey, chip)!!)
                val public = ByteArray(32).also(buffer::get)
                assertArrayEquals(chip.chain.leaf, segment(buffer))
                assertArrayEquals(chip.signature, decrypt(phone, public, segment(buffer)))
                assertArrayEquals(public + phone.publicKey, chip.challenge)
                baic += segment(buffer)
                assertEquals(0, buffer.remaining())
                assertEquals(listOf("kind", "baa", "sign"), chip.calls)
            }
            assertArrayEquals(baic[0], baic[1])
        }
    }

    @Test fun malformedAndLowOrderRequestsNeverReachTheSdk() {
        for (handle in handlers) {
            val chip = Chip()
            assertNull(handle(byteArrayOf(), chip)); assertNull(handle(ByteArray(33), chip))
            assertThrows(IllegalStateException::class.java) { handle(byteArrayOf(1) + ByteArray(32), chip) }
            assertTrue(chip.calls.isEmpty())
        }
    }

    @Test fun sdkExceptionAndErrorArePropagatedWithoutReplacement() {
        val phone = AirPlayCrypto.x25519Generate()
        for (handle in handlers) for (failure in listOf(IOException("sdk"), AssertionError("sdk"))) {
            val chip = Chip().also { it.failure = failure }
            val actual = assertThrows(failure.javaClass) { handle(byteArrayOf(1) + phone.publicKey, chip) }
            assertSame(failure, actual)
            assertEquals(listOf("kind", "certificate", "major", "sign"), chip.calls)
        }
    }
}
