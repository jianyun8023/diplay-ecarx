package com.shihab.diplay.airplay

import java.math.BigInteger
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class Srp6aContractTest {
    private val golden = PairSetupSrpClient.golden
    private fun bytes(name: String) = golden.getProperty(name).bytesFromHex()
    private fun fixedSession() = Srp6aSession(bytes("salt"), BigInteger(1, bytes("B")),
        "Pair-Setup".toByteArray(), BigInteger(1, bytes("v")), BigInteger(1, bytes("b")))

    @Test fun fixedPublicTranscriptMatchesIndependentGoldenAndPinnedLiviSaltAndB() {
        assertEquals(BigInteger(1, bytes("n")), Srp6a.modulus())
        assertEquals(BigInteger.valueOf(5), Srp6a.generator())
        val session = fixedSession()
        assertEquals(384, session.publicKey.size)
        assertArrayEquals(ByteArray(16) { 0x11 }, session.salt)
        assertEquals("9f1e3e2f30504e46d1af262800b47ca35075602be0bf000102d449b4d8d9c9ce",
            MessageDigest.getInstance("SHA-256").digest(session.publicKey).joinToString("") { "%02x".format(it) })
        val client = PairSetupSrpClient(session.salt, session.publicKey)
        assertArrayEquals(bytes("A"), client.publicA)
        assertArrayEquals(bytes("M1"), client.proof)
        assertArrayEquals(bytes("K"), client.key)
        assertArrayEquals(bytes("M2"), client.serverProof)
        val verified = session.verify(bytes("A"), bytes("M1"))
        assertTrue(verified.ok)
        assertArrayEquals(bytes("K"), verified.sessionKey)
        assertArrayEquals(bytes("M2"), verified.serverProof)
    }

    @Test fun unsignedAndPaddingPreserveZeroHighBitsAndWiderThanGroupIntegers() {
        assertArrayEquals(byteArrayOf(0), Srp6a.toBytes(BigInteger.ZERO))
        assertArrayEquals(byteArrayOf(0x80.toByte()), Srp6a.toBytes(BigInteger.valueOf(128)))
        assertEquals(BigInteger.valueOf(255), Srp6a.toBigInt(byteArrayOf(0xff.toByte())))
        assertEquals(BigInteger.ZERO, Srp6a.toBigInt(byteArrayOf()))
        assertEquals(384, Srp6a.pad(BigInteger.valueOf(5)).size)
        val wide = BigInteger.ONE.shiftLeft(3080)
        assertEquals(386, Srp6a.pad(wide).size)
        assertArrayEquals(PairSetupSrpClient.unsigned(wide), Srp6a.pad(wide))
    }

    @Test fun zeroAndModulusMultiplesFailWithoutConsumingTheSession() {
        val session = fixedSession()
        for (a in listOf(byteArrayOf(), byteArrayOf(0), bytes("n"), PairSetupSrpClient.unsigned(Srp6a.modulus() * BigInteger.TWO))) {
            val rejected = session.verify(a, bytes("M1"))
            assertFalse(rejected.ok); assertNull(rejected.sessionKey); assertNull(rejected.serverProof)
        }
        assertFalse(session.verify(bytes("A"), ByteArray(63)).ok)
        assertFalse(session.verify(bytes("A"), ByteArray(64)).ok)
        assertTrue(session.verify(bytes("A"), bytes("M1")).ok)
        assertTrue(session.verify(bytes("A"), bytes("M1")).ok)
    }

    @Test fun leadingZeroPresentationNormalizesButAboveModulusAndWideAStayAccepted() {
        val session = fixedSession()
        assertTrue(session.verify(byteArrayOf(0, 0) + bytes("A"), bytes("M1")).ok)
        for (offset in listOf(Srp6a.modulus(), Srp6a.modulus().shiftLeft(16))) {
            val client = PairSetupSrpClient(session.salt, session.publicKey, publicOffset = offset)
            val result = session.verify(client.publicA, client.proof)
            assertTrue(result.ok); assertArrayEquals(client.key, result.sessionKey)
            assertArrayEquals(client.serverProof, result.serverProof)
        }
    }

    @Test fun randomServerAcceptsTheRealCodeAndRejectsWrongCodeThenCanRetry() {
        val session = Srp6a.start("Pair-Setup", "3939")
        assertEquals(16, session.salt.size); assertEquals(384, session.publicKey.size)
        val wrong = PairSetupSrpClient(session.salt, session.publicKey, password = "0000")
        assertFalse(session.verify(wrong.publicA, wrong.proof).ok)
        val client = PairSetupSrpClient(session.salt, session.publicKey)
        val result = session.verify(client.publicA, client.proof)
        assertTrue(result.ok); assertArrayEquals(client.key, result.sessionKey)
        assertArrayEquals(client.serverProof, result.serverProof)
    }
}
