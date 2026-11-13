package com.shihab.diplay.media

import com.shihab.diplay.airplay.VideoCodec
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class VideoCodecContractTest {
    @Test fun sdkVideoRecordAndNalAdaptersMatchIndependentOldByteAlgorithms() {
        val random = Random(96)
        val inputs = listOf(
            byteArrayOf(), byteArrayOf(0, 0, 0, 1), byteArrayOf(0, 0, 0, 1, 0x65, 1, 9),
            byteArrayOf(1, 0, 0, 0, 0, 1, 0, 2, 0x67, 1, 1, 0, 1, 0x68),
            byteArrayOf(1, 0, 0, 0, 0, 1, 0, 2, 0x67, 1),
        ) + List(500) { random.nextBytes(random.nextInt(128)) }
        for (input in inputs) {
            val expectedAvc = KotlinVideoCodecReference.avcParameterSets(input)
            val actualAvc = MediaCodecSupport.avcParameterSets(input)
            assertArrayEquals(expectedAvc.first, actualAvc.first)
            assertArrayEquals(expectedAvc.second, actualAvc.second)
            assertArrayEquals(KotlinVideoCodecReference.hevcCodecSpecificData(input), MediaCodecSupport.hevcCodecSpecificData(input))
            assertArrayEquals(KotlinVideoCodecReference.toAnnexB(input), MediaCodecSupport.toAnnexB(input))
            for (codec in VideoCodec.entries) assertEquals(KotlinVideoCodecReference.isRandomAccess(input, codec), MediaCodecSupport.isRandomAccess(input, codec))
        }
    }

    @Test fun completeLengthPrefixedUnitsCopyWhileAlreadyAnnexBRetainsIdentity() {
        val wire = byteArrayOf(0, 0, 0, 2, 0x65, 9, 0, 0, 0, 1, 0x41)
        val original = wire.copyOf()
        val annexB = MediaCodecSupport.toAnnexB(wire)
        assertArrayEquals(KotlinVideoCodecReference.toAnnexB(wire), annexB)
        assertArrayEquals(original, wire)
        assertNotSame(wire, annexB)
        assertSame(annexB, MediaCodecSupport.toAnnexB(annexB))
        assertTrue(MediaCodecSupport.isRandomAccess(annexB, VideoCodec.H264))
        assertFalse(MediaCodecSupport.isRandomAccess(annexB, VideoCodec.H265))
        assertEquals(0, MediaCodecSupport.toAnnexB(wire + byteArrayOf(9)).size)
    }
}
