package com.shihab.diplay.media

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class VideoOutputTimingContractTest {
    @Test fun outputSlotsPauseHistoryParkingAndRenderDeadlineMatchTheOldSdkAlgorithm() {
        for (delay in listOf(0L, 90_000_000L)) {
            val actual = VideoOutputTiming(delay)
            val reference = KotlinVideoOutputTimingReference(delay)
            val random = Random(96)
            var local = 1_000_000_000L
            repeat(700) { i ->
                local += if (i % 53 == 0) 700_000_000 else 16_666_667
                val presentation = local / 1_000
                val queued = local + random.nextLong(40_000_000)
                val frameLocal = if (i % 13 == 0) 0 else local
                actual.queued(presentation, frameLocal, queued)
                reference.queued(presentation, frameLocal, queued)
                val now = queued + random.nextLong(220_000_000)
                val shown = i % 7 != 0
                assertEquals("frame $i delay $delay", reference.plan(presentation, now, shown), actual.plan(presentation, now, shown))
                assertEquals(queued, actual.lastQueuedNanos)
            }
        }
    }

    @Test fun signedNanoClockAndZeroPresentationRetainTheSdkContract() {
        val actual = VideoOutputTiming(90_000_000)
        val reference = KotlinVideoOutputTimingReference(90_000_000)
        for ((presentation, queued, now) in listOf(
            Triple(0L, 0L, -1L), Triple(0L, 100L, 200L),
            Triple(Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE),
        )) {
            actual.queued(presentation, presentation, queued)
            reference.queued(presentation, presentation, queued)
            assertEquals(reference.plan(presentation, now, true), actual.plan(presentation, now, true))
        }
        val requests = VideoKeyFrameRequests()
        assertTrue(requests.requestIfDue(0))
        requests.record(Long.MAX_VALUE - 100)
        assertFalse(requests.requestIfDue(Long.MIN_VALUE + 100))
        assertTrue(requests.requestIfDue(Long.MIN_VALUE + 1_000_000_000))
    }
}
