package com.shilapi.xcertplay.media

import androidx.test.runner.AndroidJUnit4
import com.shilapi.xcertplay.airplay.NativeMedia
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The SDK array failure is delegated to ART, rather than emulating desktop JVM messages. */
@RunWith(AndroidJUnit4::class)
class NativeAacDeviceTest {
    @Test fun artArrayFailuresAndAacPayloadsMatchThePlatformReference() {
        assertTrue(NativeMedia.available)
        for (size in 0..10) {
            val expected = ByteArray(size) { 0x7e }; val actual = expected.copyOf()
            if (size < 7) {
                val old = assertThrows(ArrayIndexOutOfBoundsException::class.java) {
                    KotlinAacCodecReference.writeAdtsHeader(expected, Int.MAX_VALUE, 11025, -1)
                }
                val error = assertThrows(ArrayIndexOutOfBoundsException::class.java) {
                    MediaCodecSupport.writeAdtsHeader(actual, Int.MAX_VALUE, 11025, -1)
                }
                assertEquals(old.message, error.message)
            } else {
                KotlinAacCodecReference.writeAdtsHeader(expected, Int.MAX_VALUE, 11025, -1)
                MediaCodecSupport.writeAdtsHeader(actual, Int.MAX_VALUE, 11025, -1)
            }
            assertArrayEquals(expected, actual)
        }
        assertEquals(10, MediaCodecSupport.aacFrequencyIndex(11025))
        assertArrayEquals(byteArrayOf(0x12, 0x10), NativeAac.audioSpecificConfig(44100, 2))
        val payload = byteArrayOf(0, 16, 0, 24, 1, 2)
        val output = MediaCodecSupport.aacAccessUnit(payload)
        assertArrayEquals(byteArrayOf(1, 2), output)
        assertNotSame(payload, output)
        assertArrayEquals(byteArrayOf(0, 16, 0, 24, 1, 2), payload)
    }
}
