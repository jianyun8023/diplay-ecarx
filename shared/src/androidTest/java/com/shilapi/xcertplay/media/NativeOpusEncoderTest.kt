package com.shilapi.xcertplay.media

import android.os.Debug
import org.concentus.OpusDecoder
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

class NativeOpusEncoderTest {
    @Test fun bundledLibraryEncodesDecodable20MsPacketsAtBothBitrates() {
        for (bitrate in listOf(24_000, 48_000)) for (signal in listOf("tone", "noise", "silence")) {
            MicrophoneOpusEncoders.createSoftware(bitrate).use { encoder ->
                assertEquals("The shipped ABI must use the native encoder", "opus-pure", encoder.implementation)
                val decoder = OpusDecoder(48_000, 1)
                val pcm = ByteArray(1_920)
                val decoded = ShortArray(960)
                val random = Random(20261110)
                var energy = 0L
                repeat(100) { frame ->
                    for (i in 0 until 960) {
                        val sample = when (signal) {
                            "tone" -> (8_000 * sin(2 * PI * 220 * (frame * 960 + i) / 48_000.0)).toInt()
                            "noise" -> random.nextInt(16_001) - 8_000
                            else -> 0
                        }
                        pcm[2 * i] = sample.toByte()
                        pcm[2 * i + 1] = (sample shr 8).toByte()
                    }
                    val packet = encoder.encode(pcm).single()
                    assertTrue(packet.size in 1..1_275)
                    assertEquals(960, decoder.decode(packet, 0, packet.size, decoded, 0, 960, false))
                    for (sample in decoded) energy += sample.toLong() * sample
                }
                if (signal != "silence") assertTrue(energy > 0)
            }
        }
    }

    @Test fun invalidFramesAndRepeatedCloseDoNotDamageTheEncoder() {
        val encoder = NativeOpusEncoder(48_000)
        assertTrue(encoder.available)
        for (size in listOf(0, 1_918, 1_922, 3_840)) assertTrue(encoder.encode(ByteArray(size)).isEmpty())
        assertEquals(1, encoder.encode(ByteArray(1_920)).size)
        encoder.close()
        encoder.close()
        assertFalse(encoder.available)
        assertTrue(encoder.encode(ByteArray(1_920)).isEmpty())
        NativeOpusEncoder(0).use { assertFalse(it.available) }
    }

    @Test fun capabilityProbesAndRepeatedSessionsReleaseNativeMemory() {
        assertTrue(OpusEncoderSupport.isAvailable(platformAvailable = { false },
            software = { MicrophoneOpusEncoders.createSoftware(48_000) }))
        val before = Debug.getNativeHeapAllocatedSize()
        repeat(1_000) {
            NativeOpusEncoder(48_000).use { encoder -> assertTrue(encoder.available) }
        }
        // One leaked encoder per session would retain over 30 MB; allow unrelated runtime bookkeeping.
        assertTrue("Native encoder state must be released",
            Debug.getNativeHeapAllocatedSize() - before < 2 * 1024 * 1024)
    }

    @Test fun teardownCannotFreeTheEncoderDuringAnEncodeCall() {
        val failure = AtomicReference<Throwable>()
        repeat(20) {
            val encoder = NativeOpusEncoder(48_000)
            val start = CountDownLatch(1)
            val worker = thread {
                runCatching {
                    start.await()
                    val pcm = ByteArray(1_920)
                    repeat(100) { encoder.encode(pcm) }
                }.onFailure { failure.set(it) }
            }
            start.countDown()
            encoder.close()
            worker.join(5_000)
            assertFalse(worker.isAlive)
            assertFalse(encoder.available)
        }
        assertNull(failure.get())
    }
}
