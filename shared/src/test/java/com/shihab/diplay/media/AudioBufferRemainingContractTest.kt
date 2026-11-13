package com.shihab.diplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Original output-accounting contracts, independent of the new native scheduler implementation. */
class AudioBufferRemainingContractTest {
    @Test fun failedRebufferPrerequisitesDoNotConsumeThePlaybackHead() {
        val buffer = AudioBufferProgress(4)
        buffer.written(4000)
        assertFalse(buffer.shouldRebuffer(false, true, true, true, 1000, 0))
        assertFalse(buffer.shouldRebuffer(true, false, true, true, 1000, 0))
        assertFalse(buffer.shouldRebuffer(true, true, false, true, 1000, 0))
        assertFalse(buffer.shouldRebuffer(true, true, true, false, 1000, 0))
        assertEquals(3900L, buffer.queuedBytes(25))
        assertFalse(buffer.shouldRebuffer(true, true, true, true, 100, 3599))
        assertTrue(buffer.shouldRebuffer(true, true, true, true, 100, 3600))
    }

    @Test fun repeatedAndWrappedUnsignedHeadsChargeOnlyTheNewFrames() {
        val buffer = AudioBufferProgress(2)
        repeat(4) { buffer.written(Int.MAX_VALUE) }
        assertEquals(0L, buffer.queuedBytes(-2))
        buffer.written(100)
        assertEquals(98L, buffer.queuedBytes(-1))
        assertEquals(98L, buffer.queuedBytes(-1))
        assertEquals(96L, buffer.queuedBytes(0))
        assertEquals(0L, buffer.queuedBytes(48))
    }

    @Test fun theGrantedCapacityLeavesOneWriteChunkFreeWithTheOriginalLowerBound() {
        assertEquals(6144, MediaAudioBuffer.startBytesFor(8192, 8192, 2048))
        assertEquals(8192, MediaAudioBuffer.startBytesFor(8192, 0, 2048))
        assertEquals(8192, MediaAudioBuffer.startBytesFor(8192, -1, 2048))
        assertEquals(2048, MediaAudioBuffer.startBytesFor(8192, 1024, 2048))
    }
}
