package com.shihab.diplay.media

import com.shihab.diplay.airplay.VideoCodec
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class VideoFlowContractTest {
    @Test fun gcCarrierKeepsTheSameJobIdentityAndControlBarriersAsIndependentQueue() {
        val actual = VideoDecodeQueue(maxFrames = 5, maxBytes = 17)
        val reference = KotlinVideoDecodeQueue(maxFrames = 5, maxBytes = 17)
        val random = Random(96)
        repeat(700) { i ->
            when (random.nextInt(8)) {
                0 -> assertSame(reference.poll(0), actual.poll(0))
                1 -> { reference.discardCurrentChain(); actual.discardCurrentChain() }
                2 -> { reference.discardFrames(); actual.discardFrames() }
                else -> {
                    val job = when (i % 11) {
                        0 -> VideoJob.Config(VideoCodec.H264, byteArrayOf(i.toByte()))
                        1 -> VideoJob.SurfaceChanged(null)
                        2 -> VideoJob.RefreshPicture
                        3 -> VideoJob.Resync
                        else -> VideoJob.Frame(ByteArray(random.nextInt(25)), receivedNs = random.nextLong(-1_000, 1_000))
                    }
                    reference.offer(job); actual.offer(job)
                }
            }
            assertEquals(reference.resyncPending(), actual.resyncPending())
            val expected = reference.backlogAfterCurrent()
            assertEquals(VideoDecodeQueue.Backlog(expected.pendingFrames, expected.newestPendingReceivedNs), actual.backlogAfterCurrent())
        }
        val expected = reference.drain()
        val drained = actual.drain()
        assertEquals(expected.size, drained.size)
        expected.indices.forEach { assertSame(expected[it], drained[it]) }
        // Late frames are rejected after drain, while control messages retain their original contract.
        val frame = VideoJob.Frame(byteArrayOf(7))
        reference.offer(frame); actual.offer(frame)
        assertNull(actual.poll(0)); assertNull(reference.poll(0))
        actual.offer(VideoJob.RefreshPicture); reference.offer(VideoJob.RefreshPicture)
        assertSame(reference.poll(0), actual.poll(0))
    }

    @Test fun interruptDoesNotConsumeAJobAlreadyInTheRustCarrier() {
        val queue = VideoDecodeQueue()
        queue.offer(VideoJob.RefreshPicture)
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) { queue.poll(0) }
            assertFalse(Thread.currentThread().isInterrupted)
            assertSame(VideoJob.RefreshPicture, queue.poll(0))
        } finally { Thread.interrupted() }
    }
}
