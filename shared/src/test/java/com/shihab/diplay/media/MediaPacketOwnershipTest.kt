package com.shihab.diplay.media

import com.shihab.diplay.airplay.HeapPacket
import com.shihab.diplay.airplay.MediaPacket
import com.shihab.diplay.airplay.VideoCodec
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every pooled packet is released exactly once, whichever way it leaves a queue. */
class MediaPacketOwnershipTest {
    private class CountingPacket(private val bytes: ByteArray, override val flags: Int = 0) : MediaPacket() {
        var releases = 0
        override val size: Int get() = bytes.size
        override fun copyInto(target: ByteBuffer, offset: Int, length: Int) { target.put(bytes, offset, length) }
        override fun toByteArray(): ByteArray = bytes
        override fun share(): MediaPacket = this
        override fun release() { releases++ }
    }

    @Test
    fun framesDroppedByTheVideoQueueAreReleased() {
        val queue = VideoDecodeQueue(maxFrames = 2)
        val dropped = listOf(CountingPacket(byteArrayOf(1)), CountingPacket(byteArrayOf(2)))
        dropped.forEach { queue.offer(VideoJob.Frame(it)) }
        val kept = CountingPacket(byteArrayOf(3))
        queue.offer(VideoJob.Frame(kept)) // overflow drops the two older frames
        assertEquals(listOf(1, 1), dropped.map { it.releases })
        assertEquals(0, kept.releases)

        val oversized = CountingPacket(ByteArray(16))
        VideoDecodeQueue(maxBytes = 8).offer(VideoJob.Frame(oversized))
        assertEquals(1, oversized.releases)
    }

    @Test
    fun aDiscardedChainAndAFrameOfferedAfterTheWorkerLeftAreReleased() {
        val queue = VideoDecodeQueue()
        val chain = CountingPacket(byteArrayOf(1))
        queue.offer(VideoJob.Frame(chain))
        queue.discardCurrentChain()
        assertEquals(1, chain.releases)

        val pending = CountingPacket(byteArrayOf(2))
        queue.offer(VideoJob.Frame(pending))
        val drained = queue.drain()
        assertEquals(1, drained.size)
        assertEquals(0, pending.releases) // the worker releases what it drained
        val late = CountingPacket(byteArrayOf(3))
        queue.offer(VideoJob.Frame(late))
        assertEquals(1, late.releases)
        assertNull(queue.poll(0))
    }

    @Test
    fun theLagGuardHandsEveryPacketItDropsToItsOwner() {
        val guard = MediaPacketLagGuard(48_000)
        val queue = LinkedBlockingQueue<Pair<Int, CountingPacket>>()
        val packets = List(200) { CountingPacket(byteArrayOf(0)) }
        packets.forEachIndexed { index, packet -> queue.offer(index * 960 to packet) }
        val newest = 200 * 960
        guard.recover(queue, newest, { it.first }, nowNs = 1) { it.second.release() }
        val removed = guard.recover(queue, newest, { it.first }, nowNs = 2_000_000_000L) { it.second.release() }
        assertTrue(removed > 0)
        assertEquals(200 - removed, queue.size)
        assertEquals(List(removed) { 1 } + List(200 - removed) { 0 }, packets.map { it.releases })
    }

    @Test
    fun aNativeAnnexBFrameIsUsedAsIsAndItsFlagsDecideRandomAccess() {
        val idr = CountingPacket(byteArrayOf(0, 0, 0, 1, 0x65), MediaPacket.FLAG_ANNEX_B or MediaPacket.FLAG_RANDOM_ACCESS_H264)
        assertSame(idr, idr.annexBOrNull())
        assertTrue(idr.isRandomAccess(VideoCodec.H264))
        assertFalse(idr.isRandomAccess(VideoCodec.H265))
        // A native frame without the Annex B flag did not parse: it is invalid, never re-scanned.
        assertNull(CountingPacket(byteArrayOf(0, 0, 0, 9, 1), flags = 0).let { NativeLike(it) }.annexBOrNull())
    }

    @Test
    fun aKotlinFrameIsConvertedAsBefore() {
        val lengthPrefixed = HeapPacket(byteArrayOf(0, 0, 0, 2, 0x65, 0x11))
        val converted = lengthPrefixed.annexBOrNull()!!
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 0x11), converted.toByteArray())
        assertTrue(converted.isRandomAccess(VideoCodec.H264))
        val annexB = HeapPacket(byteArrayOf(0, 0, 0, 1, 0x41))
        assertSame(annexB, annexB.annexBOrNull())
        assertNull(HeapPacket(byteArrayOf(0, 0, 0, 9, 1)).annexBOrNull())
    }

    /** Stands in for a NativePacket: anything that is not a HeapPacket. */
    private class NativeLike(private val inner: MediaPacket) : MediaPacket() {
        override val size get() = inner.size
        override val flags get() = inner.flags
        override fun copyInto(target: ByteBuffer, offset: Int, length: Int) = inner.copyInto(target, offset, length)
        override fun toByteArray() = inner.toByteArray()
        override fun share() = this
        override fun release() = inner.release()
    }
}
