package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.media.reference.KotlinAudioChannelMapper
import com.shilapi.xcertplay.media.reference.KotlinCaptureClock
import com.shilapi.xcertplay.media.reference.KotlinEchoReference
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class NativeCallAudioCoreDeviceTest {
    @Test fun sharedEchoRingUsesRealJniForHeapAndDirectBuffersAndRetainsItsClockAfterRendererClose() {
        for (direct in listOf(false, true)) {
            val expected = KotlinEchoReference(1_000, 300)
            EchoReference(1_000, 300).use { renderer ->
                val microphone = requireNotNull(renderer.retain())
                microphone.use {
                    repeat(120) { step ->
                        val bytes = ByteArray(40) { ((step * 7 + it) % 256).toByte() }
                        val buffer = if (direct) ByteBuffer.allocateDirect(44) else ByteBuffer.allocate(44)
                        buffer.position(2); buffer.put(bytes); buffer.flip(); buffer.position(1)
                        val pending = if (step % 29 == 0) null else (step % 30 - 1).toLong()
                        expected.append(bytes, 0, bytes.size, 2, pending, step * 10_000_000L)
                        renderer.append(buffer, 2, bytes.size, 2, pending, step * 10_000_000L)
                        assertEquals(1, buffer.position())
                        val actualOut = ShortArray(10)
                        val expectedOut = ShortArray(10)
                        assertEquals(expected.read(expectedOut, step * 10_000_000L), microphone.read(actualOut, step * 10_000_000L))
                        assertArrayEquals(expectedOut, actualOut)
                    }
                    renderer.close()
                    assertNull(renderer.retain())
                    val actualOut = ShortArray(10)
                    val expectedOut = ShortArray(10)
                    assertEquals(expected.read(expectedOut, 1_200_000_000L), microphone.read(actualOut, 1_200_000_000L))
                    assertArrayEquals(expectedOut, actualOut)
                }
                val out = shortArrayOf(9, 9)
                assertFalse(microphone.read(out, 0))
                assertArrayEquals(shortArrayOf(0, 0), out)
            }
        }
    }

    @Test fun captureClockAndRoutesMatchIndependentReferencesOnTheDevice() {
        val actual = CaptureClock(48_000)
        val expected = KotlinCaptureClock(48_000)
        repeat(120) { step ->
            val stamped = step % 7 != 0
            val now = 1_000_000_000L + step * 20_000_000L
            val timestamp = if (step % 13 == 0) 0L else now - 10_000_000L
            expected.read(960, now, stamped, timestamp, step * 960L + 480)
            actual.read(960, now, stamped, timestamp, step * 960L + 480)
            assertEquals(expected.frameEndNs(480), actual.frameEndNs(480))
        }
        for (mode in AudioChannelMappingMode.entries) {
            for (type in listOf("telephony", "speechRecognition", "media", "compatibility", "未知😀")) {
                for (payload in listOf(100, 102)) {
                    val route = AudioChannelMapper.map(type, payload, mode, 7)
                    val reference = KotlinAudioChannelMapper.map(type, payload, mode, 7)
                    assertEquals(reference, route)
                    assertEquals(KotlinAudioChannelMapper.playCallOnSpeaker(reference), AudioChannelMapper.playCallOnSpeaker(route))
                }
            }
        }
    }
}
