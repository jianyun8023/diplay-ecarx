package com.shihab.diplay.media

import org.junit.Assert.*
import org.junit.Test

/** The decoder worker's control mailbox: FIFO, interruptible, woken by close, drained on shutdown. */
class VideoControlMailboxTest {
    @Test fun jobsLeaveInOfferOrderAndTheWaitIsWokenByTheNextOffer() {
        val mailbox = VideoControlMailbox()
        val config = VideoJob.Config(com.shihab.diplay.airplay.VideoCodec.H264, byteArrayOf(1))
        val surface = VideoJob.SurfaceChanged(null)
        mailbox.offer(config)
        val taker = Thread { Thread.sleep(100); mailbox.offer(surface) }
        taker.start()
        assertSame(config, mailbox.poll(2_000))
        assertSame(surface, mailbox.poll(2_000))
        taker.join()
    }

    @Test fun interruptDoesNotConsumeAJobAlreadyInTheMailbox() {
        val mailbox = VideoControlMailbox()
        mailbox.offer(VideoJob.RefreshPicture)
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) { mailbox.poll(0) }
            assertFalse(Thread.currentThread().isInterrupted)
            assertSame(VideoJob.RefreshPicture, mailbox.poll(0))
        } finally { Thread.interrupted() }
    }

    @Test fun drainHandsOutEverythingOnceAndTheMailboxStillAcceptsLaterWork() {
        val mailbox = VideoControlMailbox()
        val detach = VideoJob.DetachSurface(SurfaceDetachRequest(Any(), park = true))
        mailbox.offer(VideoJob.RefreshPicture)
        mailbox.offer(detach)
        assertEquals(listOf<VideoJob>(VideoJob.RefreshPicture, detach), mailbox.drain())
        assertTrue(mailbox.drain().isEmpty())
        assertNull(mailbox.poll(0))
        val late = VideoJob.RefreshPicture
        mailbox.offer(late)
        assertSame(late, mailbox.poll(0))
    }
}
