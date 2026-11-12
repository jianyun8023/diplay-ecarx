package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** Independent old algorithms exercise the production JNI facade, rather than a second active core. */
class VideoTimingContractTest {
    @Test fun customPacerTracksTheReferenceThroughJitterClockStepsAndWrap() {
        val reference = KotlinFramePacer(window = 11, percentile = 15, refreshEvery = 3,
            slewPerSecond = .003, resetNanos = 40_000_000, maxEarlyNanos = 90_000_000)
        val actual = FramePacer(window = 11, percentile = 15, refreshEvery = 3,
            slewPerSecond = .003, resetNanos = 40_000_000, maxEarlyNanos = 90_000_000)
        val random = Random(96)
        repeat(600) { i ->
            val sender = Long.MAX_VALUE - 2_000_000_000 + i * 16_666_667L
            val arrival = sender + 15_000_000 + random.nextInt(50_000_000) +
                (if (i in 160..240) 700_000_000 else 0)
            assertEquals("frame $i", reference.localTime(sender, arrival), actual.localTime(sender, arrival))
        }
    }

    @Test fun customDelayKeepsItsIndependentWindowAndLimits() {
        val reference = KotlinPacingDelay(400, minNanos = 10, maxNanos = 2_000, window = 11,
            percentile = 75, refreshEvery = 3, marginNanos = 77, risePerFrame = 19, fallPerFrame = 9)
        val actual = PacingDelay(400, minNanos = 10, maxNanos = 2_000, window = 11,
            percentile = 75, refreshEvery = 3, marginNanos = 77, risePerFrame = 19, fallPerFrame = 9)
        for (need in List(500) { (it * 93L) % 3_000 - 100 } + listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
            reference.onFrame(need)
            actual.onFrame(need)
            assertEquals(reference.nanos, actual.nanos)
        }
    }

    @Test fun recoveryPreservesStartupAcrossResetAndWrappedNanoTimes() {
        val reference = KotlinVideoBacklogRecovery()
        val actual = VideoBacklogRecovery()
        for (start in listOf(0L, -5_000_000_000, Long.MAX_VALUE - 5_000_000_000, Long.MIN_VALUE)) {
            reference.decoderStarted(start); actual.decoderStarted(start)
            reference.reset(); actual.reset()
            for (elapsed in listOf(0L, 1_000_000_000, 2_999_999_999, 3_000_000_000, 3_750_000_000)) {
                val now = start + elapsed
                val received = now - 400_000_000
                val newest = received + 200_000_000 + elapsed / 10
                assertEquals(reference.observe(now, received, 8, newest), actual.observe(now, received, 8, newest))
            }
            assertEquals(reference.observe(start, start, -1, null), actual.observe(start, start, -1, null))
        }
    }
}
