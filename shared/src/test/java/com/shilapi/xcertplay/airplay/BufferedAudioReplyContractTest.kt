package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class BufferedAudioReplyContractTest {
    @Test fun nativeAnchorKeepsTheReferenceEpochFractionScalarTypesAndWireBytes() {
        NativeMedia.requireAvailable()
        for (seconds in listOf(0L, 2_208_988_800L, 3_999_999_999L, Long.MAX_VALUE)) {
            for (fraction in listOf(0L, 1L, 0x8000_0000L, 0xffff_ffffL)) {
                val ntp = BigInteger.valueOf(seconds).shiftLeft(32).add(BigInteger.valueOf(fraction))
                val expected = KotlinBufferedAudioStream.anchorPlist(0x1_0000_0010L, ntp, 1)
                val actual = requireNotNull(NativeMedia.bufferedAnchor(longArrayOf(0x1_0000_0010L, seconds, fraction, 1, 0)))
                assertEquals(expected, actual)
                assertEquals(expected.keys.toList(), actual.keys.toList())
                assertTrue(actual["networkTimeFrac"] is BigInteger)
                assertTrue(actual["rate"] is Int)
                if (seconds >= 2_208_988_800L) assertArrayEquals(BplistCodec.encode(expected), BplistCodec.encode(actual))
            }
        }
        assertNull(NativeMedia.bufferedAnchor(longArrayOf(-1, 0, 0, 0, 0)))
    }

    @Test fun nativeFeedbackPreservesExternalObjectsAndOmitsUnknownPositionFields() {
        NativeMedia.requireAvailable()
        val ntp = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
        val empty = NativeMedia.bufferedFeedback(103, 48_000, -1, null, ntp, 8)
        assertEquals(linkedMapOf<String, Any?>("type" to 103, "sampleRate" to 48_000), empty)
        for (raw in listOf<Any?>(null, -1L, -1, 42L, BigInteger.ONE.shiftLeft(63))) {
            val connection = unsignedPlistInteger(raw)
            val expected = linkedMapOf<String, Any?>("type" to 103, "sampleRate" to 48_000,
                "streamConnectionID" to connection, "timestamp" to ntp, "timestampRawNs" to 8L,
                "sampleTime" to 0xffff_ffffL)
            val actual = NativeMedia.bufferedFeedback(103, 48_000, 0xffff_ffffL, connection, ntp, 8)
            assertEquals(expected, actual)
            assertEquals(expected.keys.toList(), actual.keys.toList())
            assertSame(ntp, actual["timestamp"])
            assertSame(connection, actual["streamConnectionID"])
            assertArrayEquals(BplistCodec.encode(expected), BplistCodec.encode(actual))
        }
    }
}
