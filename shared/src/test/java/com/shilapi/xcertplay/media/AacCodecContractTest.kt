package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class AacCodecContractTest {
    private val rates = listOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350, -1, 0, 12345, Int.MIN_VALUE, Int.MAX_VALUE)
    private val channels = listOf(Int.MIN_VALUE, -1, 0, 1, 2, 7, 8, Int.MAX_VALUE)

    @Test fun samplingIndicesAndFreshAscMatchTheExistingContracts() {
        for (rate in rates) {
            assertEquals(KotlinAacCodecReference.frequencyIndex(rate), MediaCodecSupport.aacFrequencyIndex(rate))
            for (channels in channels) {
                val asc = NativeAac.audioSpecificConfig(rate, channels)
                assertArrayEquals(KotlinAacCodecReference.audioSpecificConfig(rate, channels), asc)
                assertNotSame(asc, NativeAac.audioSpecificConfig(rate, channels))
            }
        }
        assertArrayEquals(byteArrayOf(0x12, 0x10), NativeAac.audioSpecificConfig(44100, 2))
        assertEquals(10, MediaCodecSupport.aacFrequencyIndex(11025))
    }

    @Test fun adtsPreservesWrappingLengthsClampAndUntouchedTargetSuffix() {
        for (size in listOf(Int.MIN_VALUE, -8, -7, -1, 0, 2, 8191, 8192, Int.MAX_VALUE)) for (rate in rates) for (channels in channels) {
            val expected = ByteArray(10) { 0x7e }
            val actual = expected.copyOf()
            KotlinAacCodecReference.writeAdtsHeader(expected, size, rate, channels)
            MediaCodecSupport.writeAdtsHeader(actual, size, rate, channels)
            assertArrayEquals(expected, actual)
        }
        val accessUnit = byteArrayOf(1, 2)
        val framed = MediaCodecSupport.adtsFrame(accessUnit, 44100, 2)
        assertArrayEquals(byteArrayOf(0xff.toByte(), 0xf1.toByte(), 0x50, 0x80.toByte(), 1, 0x3f, 0xfc.toByte(), 1, 2), framed)
        assertNotSame(accessUnit, framed); assertArrayEquals(byteArrayOf(1, 2), accessUnit)
    }

    @Test fun shortHeadersKeepPartialWritesAndTheCurrentVmIndexException() {
        for (size in 0..6) {
            val expected = ByteArray(size) { 0x7e }; val actual = expected.copyOf()
            val oldError = assertThrows(ArrayIndexOutOfBoundsException::class.java) {
                KotlinAacCodecReference.writeAdtsHeader(expected, 100, 48000, 2)
            }
            val error = assertThrows(ArrayIndexOutOfBoundsException::class.java) {
                MediaCodecSupport.writeAdtsHeader(actual, 100, 48000, 2)
            }
            assertArrayEquals(expected, actual); assertEquals(oldError.message, error.message)
        }
    }

    @Test fun firstAuExtractionKeepsAvailableTruncatedPrefixAndNeverAliasesInput() {
        val farHeader = ByteArray(8197) { 0x55 }.also { it[0] = 0xff.toByte(); it[1] = 0xf0.toByte(); it[2] = 0xff.toByte(); it[3] = 0xf8.toByte() }
        val payloads = listOf(byteArrayOf(), byteArrayOf(1, 2, 3), byteArrayOf(0, 15, 0, 8, 9),
            byteArrayOf(0, 17, 0, 8, 9), byteArrayOf(0, 32, 0, 8, 9), byteArrayOf(0, 16, 0, 0, 9),
            byteArrayOf(0, 16, 0, 24, 1, 2), byteArrayOf(0, 16, 0, 24, 1, 2, 3, 4),
            byteArrayOf(0, 32, 0, 16, 0, 8, 1, 2, 3), farHeader)
        for (payload in payloads) {
            val saved = payload.copyOf(); val actual = MediaCodecSupport.aacAccessUnit(payload)
            assertArrayEquals(KotlinAacCodecReference.accessUnit(payload), actual)
            assertArrayEquals(saved, payload); assertNotSame(payload, actual)
            assertNotSame(actual, MediaCodecSupport.aacAccessUnit(payload))
        }
        assertArrayEquals(byteArrayOf(1, 2), MediaCodecSupport.aacAccessUnit(byteArrayOf(0, 16, 0, 24, 1, 2)))
    }
}
