package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import com.shilapi.xcertplay.media.reference.KotlinEchoReference
import java.nio.ByteBuffer

class EchoReferenceTest {
    private val owners = mutableListOf<EchoReference>()
    private fun reference(sampleRate: Int, capacityMillis: Int = 2_000) =
        EchoReference(sampleRate, capacityMillis).also(owners::add)
    @After fun closeOwners() = owners.forEach(EchoReference::close)

    private fun pcm(vararg samples: Int) = ByteArray(samples.size * 2).also { out ->
        samples.forEachIndexed { i, s -> out[2 * i] = s.toByte(); out[2 * i + 1] = (s shr 8).toByte() }
    }

    @Test fun nothingIsReturnedBeforePlaybackStarts() {
        val reference = reference(sampleRate = 1_000)
        reference.append(pcm(1, 2, 3, 4), 0, 8, 1, pendingFrames = null, nowNs = 0)
        val out = ShortArray(2) { 9 }
        assertFalse(reference.read(out, endNs = 10_000_000))
        assertArrayEquals(shortArrayOf(0, 0), out)
    }

    @Test fun readsTheSamplesThatWerePlayingAtTheRequestedTime() {
        // 1 kHz: one sample per millisecond. Ten samples written, four still queued at t=0,
        // so sample 6 is reaching the speaker at t=0 and sample 8 at t=2 ms.
        val reference = reference(sampleRate = 1_000)
        reference.append(pcm(*IntArray(10) { it * 100 }), 0, 20, 1, pendingFrames = 4, nowNs = 0)
        val out = ShortArray(3)
        assertTrue(reference.read(out, endNs = 2_000_000))
        assertArrayEquals(shortArrayOf(500, 600, 700), out)
    }

    @Test fun samplesPastWhatWasWrittenOrAlreadyOverwrittenAreSilence() {
        val reference = reference(sampleRate = 1_000, capacityMillis = 4)
        reference.append(pcm(*IntArray(6) { (it + 1) * 10 }), 0, 12, 1, pendingFrames = 0, nowNs = 0)
        val out = ShortArray(8)
        assertTrue(reference.read(out, endNs = 2_000_000))
        // Samples 0-5 written, only 2-5 kept; the window covers samples 0-7.
        assertArrayEquals(shortArrayOf(0, 0, 30, 40, 50, 60, 0, 0), out)
    }

    @Test fun stereoIsDownmixedToMono() {
        val reference = reference(sampleRate = 1_000)
        reference.append(pcm(100, 300, -200, -400), 0, 8, 2, pendingFrames = 0, nowNs = 0)
        val out = ShortArray(2)
        assertTrue(reference.read(out, endNs = 0))
        assertArrayEquals(shortArrayOf(200, -300), out)
    }

    @Test fun resetForgetsThePlayedAudio() {
        val reference = reference(sampleRate = 1_000)
        reference.append(pcm(1, 2), 0, 4, 1, pendingFrames = 0, nowNs = 0)
        reference.reset()
        assertFalse(reference.read(ShortArray(2), endNs = 0))
    }

    // 1 kHz, sample n holds value n, so a window's first value is the sample position it started at.
    private fun ramp(samples: Int = 1_000) = reference(sampleRate = 1_000).also {
        it.append(pcm(*IntArray(samples) { n -> n }), 0, samples * 2, 1, pendingFrames = samples.toLong(), nowNs = 0)
    }

    /** Moves the speaker anchor without writing audio: [played] samples have reached the speaker at [nowMs]. */
    private fun EchoReference.anchor(played: Long, nowMs: Long, written: Long = 1_000) =
        append(ByteArray(0), 0, 0, 1, pendingFrames = written - played, nowNs = nowMs * 1_000_000)

    @Test fun jitteryClockEstimatesStillGiveContiguousWindows() {
        val reference = ramp()
        val out = ShortArray(10)
        val jitter = intArrayOf(0, 3, -4, 2, -1, 4, -3, 1, -2, 0, 3, -4, 2, -1, 4, -3, 1, -2, 0, 3)
        val starts = jitter.mapIndexed { frame, error ->
            val endMs = 10L * (frame + 1)
            // The true speaker position is endMs; the anchor reports it off by up to 4 ms.
            reference.anchor(played = endMs + error, nowMs = endMs)
            assertTrue(reference.read(out, endNs = endMs * 1_000_000))
            for (i in 1 until out.size) assertEquals(out[0] + i, out[i].toInt())
            out[0].toInt()
        }
        assertEquals(0, starts[0])
        for (frame in 1 until starts.size) assertEquals("frame $frame", starts[frame - 1] + 10, starts[frame])
    }

    @Test fun aSteadyOffsetIsCorrectedOnceTheEstimateSettles() {
        val reference = ramp()
        val out = ShortArray(10)
        // The first estimate happens to be 4 ms early; every later one is exact.
        val starts = (0 until 12).map { frame ->
            val endMs = 100L + 10 * frame
            reference.anchor(played = endMs + if (frame == 0) -4 else 0, nowMs = endMs)
            reference.read(out, endNs = endMs * 1_000_000)
            out[0].toInt()
        }
        assertEquals(86, starts[0])
        assertEquals(176, starts[9])
        assertEquals(190, starts[10])
        assertEquals(200, starts[11])
    }

    @Test fun aLargeSlipResynchronisesToTheEstimate() {
        val reference = ramp()
        val out = ShortArray(10)
        val starts = (0 until 30).map { frame ->
            val endMs = 10L * (frame + 1)
            // From frame 15 on, playback is 50 ms behind (an underrun the cursor did not see).
            reference.anchor(played = endMs - if (frame >= 15) 50 else 0, nowMs = endMs)
            reference.read(out, endNs = endMs * 1_000_000)
            out[0].toInt()
        }
        assertEquals(190, starts[19])
        assertEquals(200 - 50, starts[20])
        assertEquals(290 - 50, starts[29])
    }

    @Test fun playbackStoppingRestartsTheCursorFromTheNextEstimate() {
        val reference = ramp()
        val out = ShortArray(10)
        reference.anchor(played = 10, nowMs = 10)
        reference.read(out, endNs = 10_000_000)
        assertEquals(0, out[0].toInt())
        reference.append(ByteArray(0), 0, 0, 1, pendingFrames = null, nowNs = 20_000_000)
        assertFalse(reference.read(out, endNs = 20_000_000))
        reference.anchor(played = 500, nowMs = 600)
        assertTrue(reference.read(out, endNs = 600_000_000))
        assertEquals(490, out[0].toInt())
    }

    @Test fun nativeReferenceMatchesIndependentKotlinClockAcrossJitterSlipResetAndOddBuffers() {
        val expected = KotlinEchoReference(1_000, 300)
        val actual = reference(1_000, 300)
        val random = java.util.Random(647)
        repeat(500) { step ->
            val channels = step % 4 + 1
            val bytes = ByteArray((10 + step % 4) * channels * 2 + step % 2).also(random::nextBytes)
            val pending = if (step % 47 == 0) null else (step % 80 - 1).toLong()
            val now = step * 10_000_000L
            expected.append(bytes, 0, bytes.size, channels, pending, now)
            when (step % 3) {
                0 -> actual.append(bytes, 0, bytes.size, channels, pending, now)
                else -> {
                    val buffer = if (step % 3 == 1) ByteBuffer.allocateDirect(bytes.size + 4) else ByteBuffer.allocate(bytes.size + 4)
                    buffer.position(2); buffer.put(bytes); buffer.flip(); buffer.position(1)
                    actual.append(buffer, 2, bytes.size, channels, pending, now)
                    assertEquals(1, buffer.position())
                }
            }
            val end = now + (step % 9 - 4) * 1_000_000L
            val expectedOut = ShortArray(10)
            val actualOut = ShortArray(10)
            assertEquals("step=$step", expected.read(expectedOut, end), actual.read(actualOut, end))
            assertArrayEquals("step=$step", expectedOut, actualOut)
            if (step % 151 == 0) { expected.reset(); actual.reset() }
        }
    }

    @Test fun retainedMicrophoneSurvivesRendererCloseAndClosedOwnersAreInert() {
        val renderer = reference(1_000)
        renderer.append(pcm(1, 2, 3, 4), 0, 8, 1, 0, 0)
        val microphone = requireNotNull(renderer.retain()).also(owners::add)
        renderer.close()
        assertNull(renderer.retain())
        val out = ShortArray(2)
        assertTrue(microphone.read(out, 0))
        assertArrayEquals(shortArrayOf(3, 4), out)
        microphone.close()
        microphone.append(pcm(9), 0, 2, 1, 0, 0)
        assertFalse(microphone.read(out, 0))
        assertArrayEquals(shortArrayOf(0, 0), out)
    }

    @Test fun directBufferBoundsRespectTheSdkLimit() {
        val reference = reference(1_000)
        val bytes = ByteBuffer.allocateDirect(16).apply { limit(4) }
        assertThrows(IndexOutOfBoundsException::class.java) { reference.append(bytes, 0, 8, 1, 0, 0) }
        assertThrows(IndexOutOfBoundsException::class.java) { reference.append(bytes, -1, 4, 1, 0, 0) }
    }
}
