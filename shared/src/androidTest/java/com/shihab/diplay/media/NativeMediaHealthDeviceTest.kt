package com.shihab.diplay.media

import com.shihab.diplay.airplay.NativeMedia
import org.junit.Assert.*
import org.junit.Test

/** Production JNI on Android; explicit monotonic times avoid sleeping through the report budget. */
class NativeMediaHealthDeviceTest {
    @Test fun normalSamplesShareOneMinuteBudgetAcrossTheNativeBoundary() {
        NativeMedia.requireAvailable()
        val token = NativeMedia.healthCreate()
        val previous = LongArray(3)
        val sample = longArrayOf(10, 0, 0, 0, 0, 0, 0, 0, 100, 0)
        assertNull(NativeMedia.healthStep(token, null, 0, 100, null))
        assertNull(NativeMedia.healthStep(token, previous, 1, 59_999, sample))
        assertNull(NativeMedia.healthStep(token, previous, 1, 60_099, sample))
        val due = checkNotNull(NativeMedia.healthStep(token, previous, 1, 60_100, sample))
        assertTrue(due, due.contains("audioRx=30"))
        assertTrue(due, due.contains("writtenFrames=300 pendingWorkers=1 closing=false final=false"))
        assertNull(NativeMedia.healthStep(token, previous, 1, 60_101, sample))
        val snapshot = checkNotNull(NativeMedia.healthStep(token, null, 4, 0, null))
        assertTrue(snapshot, snapshot.contains("audioRx=40"))
        assertEquals(snapshot, NativeMedia.healthStep(token, null, 4, 0, null))
    }

    @Test fun closeWaitsForLateAudioAndVideoSamplesBeforeReturningFinalTotals() {
        NativeMedia.requireAvailable()
        val token = NativeMedia.healthCreate()
        val previous = LongArray(3)
        NativeMedia.healthStep(token, null, 0, 1_000, null)
        NativeMedia.healthStep(token, null, 0, 1_000, null)
        val audio = longArrayOf(10, 0, 3, 1, 0, 0, 2, 1, 100, 0)
        assertNotNull("The first anomaly is immediate", NativeMedia.healthStep(token, previous, 1, 1_100, audio))
        assertNull("Repeated errors share the budget", NativeMedia.healthStep(token, previous, 1, 1_200, audio))
        val closing = checkNotNull(NativeMedia.healthStep(token, null, 3, 1_300, null))
        assertTrue(closing, closing.contains("pendingWorkers=2 closing=true final=false"))
        // These callbacks arrive after sink close; the last worker must still publish its totals.
        assertNull(NativeMedia.healthStep(token, previous, 1, 1_400,
            longArrayOf(2, 0, 3, 0, 0, 0, 2, 1, 20, 1)))
        val final = checkNotNull(NativeMedia.healthStep(token, null, 2, 1_500, longArrayOf(5, 4, 0, 0, 0, 1)))
        assertTrue(final, final.contains("audioRx=22 audioQueueDrops=0 underruns=3 writeErrors=2"))
        assertTrue(final, final.contains("decoderDrops=2 rebuffers=1 videoRx=5 videoRendered=4"))
        assertTrue(final, final.contains("audioEnded=1 videoEnded=1 writtenFrames=220 pendingWorkers=0 closing=true final=true"))
        assertNull(NativeMedia.healthStep(token, null, 3, 1_600, null))
        assertEquals(final, NativeMedia.healthStep(token, null, 4, 0, null))
    }

    @Test fun replacingTheTrackResetsOnlyItsUnderrunOriginWithoutAddingAReport() {
        NativeMedia.requireAvailable()
        val token = NativeMedia.healthCreate()
        val previous = LongArray(3)
        NativeMedia.healthStep(token, null, 0, 100, null)
        assertNotNull(NativeMedia.healthStep(token, previous, 1, 200,
            longArrayOf(0, 0, 2, 0, 0, 0, 4, 1, 0, 0)))
        assertNull(NativeMedia.healthStep(token, previous, 1, 300,
            longArrayOf(0, 0, 5, 0, 0, 0, 4, 1, 0, 0)))
        val before = NativeMedia.healthStep(token, null, 4, 0, null)
        assertNull(NativeMedia.healthStep(token, previous, 5, 0, null))
        assertEquals(before, NativeMedia.healthStep(token, null, 4, 0, null))
        assertNull(NativeMedia.healthStep(token, previous, 1, 400,
            longArrayOf(0, 0, 8, 0, 0, 0, 4, 1, 0, 0)))
        val after = checkNotNull(NativeMedia.healthStep(token, null, 4, 0, null))
        assertTrue(after, after.contains("underruns=13"))
        assertTrue(after, after.contains("decoderDrops=4 rebuffers=1"))
        assertTrue(after, after.contains("audioEnded=0"))
        assertTrue(after, after.contains("pendingWorkers=1 closing=false final=false"))
    }
}
