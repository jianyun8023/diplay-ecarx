package com.shihab.diplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MicrophoneNativeContractTest {
    @Test fun wrappingCountersAndPayloadMaskMatchIndependentWireReference() {
        val actual = MicrophoneCounters(65535, Int.MAX_VALUE, Long.MAX_VALUE)
        val expected = KotlinMicrophoneCounters(65535, Int.MAX_VALUE, Long.MAX_VALUE)
        val key = ByteArray(32) { it.toByte() }
        repeat(2) {
            assertArrayEquals(KotlinMicrophonePacketizer.sealPacket(key, 0x1ff, expected, byteArrayOf(3, 5, 8), 1),
                MicrophonePacketizer.sealPacket(key, 0x1ff, actual, byteArrayOf(3, 5, 8), 1))
            assertEquals(expected.sequence, actual.sequence)
            assertEquals(expected.timestamp, actual.timestamp)
            assertEquals(expected.nonce, actual.nonce)
        }
    }

    @Test fun failedSealDoesNotCommitCountersAndPcmKeepsAnOddTail() {
        val counters = MicrophoneCounters(7, 480, 3)
        assertThrows(IllegalArgumentException::class.java) {
            MicrophonePacketizer.sealPacket(ByteArray(31), 100, counters, byteArrayOf(1), 480)
        }
        assertEquals(7, counters.sequence)
        assertEquals(480, counters.timestamp)
        assertEquals(3L, counters.nonce)
        assertArrayEquals(byteArrayOf(2, 1, 3), MicrophonePacketizer.toWirePcm(byteArrayOf(1, 2, 3)))
    }
}
