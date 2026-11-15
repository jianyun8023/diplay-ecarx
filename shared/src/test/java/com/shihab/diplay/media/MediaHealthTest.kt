package com.shihab.diplay.media

import com.shihab.diplay.airplay.NativeMedia
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MediaHealthTest {
    @Test fun shortStreamsAggregateAndOneMinuteHasAtMostOneNormalSummary() {
        NativeMedia.requireAvailable()
        val token = NativeMedia.healthCreate()
        val lines = mutableListOf<String>()
        repeat(120) { i ->
            NativeMedia.healthStep(token, null, 0, i * 1000L, null)
            NativeMedia.healthStep(token, LongArray(3), 1, i * 1000L,
                longArrayOf(10, 0, 0, 0, 0, 0, 0, 0, 100, 1))?.let(lines::add)
        }
        assertEquals(1, lines.size)
        val end = NativeMedia.healthStep(token, null, 3, 120_000, null)!!
        assertTrue(end, end.contains("audioRx=1200"))
        assertTrue(end, end.contains("audioEnded=120"))
        assertTrue(end, end.contains("final=true"))
        assertTrue(end.length < 700)
    }

    @Test fun firstErrorIsImmediateAndLateExitCompletesAClosingSnapshot() {
        val lines = mutableListOf<String>()
        val health = MediaHealth(lines::add)
        val before = LongArray(3)
        health.started()
        health.audio(before, longArrayOf(10, 0, 2, 1, 0, 0, 3, 1, 100, 0))
        assertEquals(1, lines.size)
        health.audio(before, longArrayOf(10, 0, 2, 0, 0, 0, 3, 1, 100, 0))
        assertEquals(1, lines.size)
        health.finish()
        assertTrue(lines.last().contains("pendingWorkers=1 closing=true final=false"))
        health.audio(before, longArrayOf(0, 0, 2, 0, 0, 0, 3, 1, 0, 1))
        assertTrue(lines.last().contains("pendingWorkers=0 closing=true final=true"))
        assertTrue(health.snapshot().contains("decoderDrops=3"))
    }
}
