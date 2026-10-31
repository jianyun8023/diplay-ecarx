package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class VideoStatsTest {
    private var now = 1_000_000_000L
    private val stats = VideoStats(nanoTime = { now })

    @Test fun longInputGapsAreVisibleSeparatelyFromTheExistingActiveGapMetric() {
        stats.onReceived(100)
        now += 30_000_000; stats.onReceived(100)
        now += 2_500_000_000; stats.onReceived(100)
        now += 2_470_000_000
        val line = stats.logIfDue()!!
        assertTrue(line, line.contains("maxGap=30ms"))
        assertTrue(line, line.contains("idleGaps=1 idleGapMax=2500ms"))
        assertTrue(line, line.contains("monotonicNs=$now"))
        now += 30_000_000; stats.onReceived(100)
        now += 5_000_000_000
        assertTrue(stats.logIfDue()!!.contains("idleGaps=1 idleGapMax=2500ms"))
    }

    @Test fun heldFramesRemainExcludedFromDecodePercentilesButTheirDelayIsReported() {
        stats.onReceived(100)
        stats.onDecodeLatency(20_000_000)
        stats.onDecodeLatency(2_800_000_000, heldOverGap = true)
        now += 5_000_000_000
        val line = stats.logIfDue()!!
        assertTrue(line, line.contains("decode p50=20ms p90=20ms"))
        assertTrue(line, line.contains("heldDecodeN=1 heldDecodeMax=2800ms"))
        stats.onRendered()
        now += 5_000_000_000
        assertTrue(stats.logIfDue()!!.contains("heldDecodeN=0 heldDecodeMax=0ms"))
    }

    @Test fun outputAfterInputStopsStillGetsLoggedWithoutInventingAnInputFrame() {
        stats.onReceived(100)
        now += 5_000_000_000
        assertNotNull(stats.logIfDue())
        stats.onDecodeLatency(3_000_000_000, heldOverGap = true)
        stats.onRendered()
        now += 5_000_000_000
        val line = stats.logIfDue()!!
        assertTrue(line, line.contains("rx=0.0fps shown=0.2fps"))
        assertTrue(line, line.contains("heldDecodeN=1 heldDecodeMax=3000ms"))
        now += 5_000_000_000
        assertNull(stats.logIfDue())
    }
}
