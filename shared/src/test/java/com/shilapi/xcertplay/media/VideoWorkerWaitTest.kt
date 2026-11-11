package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoWorkerWaitTest {
    private val now = 10_000_000_000L

    @Test fun theWorkerDrainsOftenRightAfterQueueingAFrame() {
        assertEquals(VIDEO_DRAIN_POLL_MS, videoWorkerWaitMillis(drainsOutput = true, lastQueuedNanos = now - 1_000_000L, nowNanos = now))
        assertEquals(VIDEO_DRAIN_POLL_MS, videoWorkerWaitMillis(true, now - VIDEO_STILL_GAP_NS + 1, now))
    }

    @Test fun aStillScreenLetsTheWorkerWaitLonger() {
        assertEquals(VIDEO_IDLE_POLL_MS, videoWorkerWaitMillis(true, now - VIDEO_STILL_GAP_NS, now))
        assertEquals(VIDEO_IDLE_POLL_MS, videoWorkerWaitMillis(true, lastQueuedNanos = 0L, nowNanos = now))
    }

    @Test fun withoutACodecToDrainTheWorkerOnlyRunsDueChecks() {
        // No codec yet, or an output thread drains it.
        assertEquals(VIDEO_IDLE_POLL_MS, videoWorkerWaitMillis(drainsOutput = false, lastQueuedNanos = now, nowNanos = now))
    }
}
