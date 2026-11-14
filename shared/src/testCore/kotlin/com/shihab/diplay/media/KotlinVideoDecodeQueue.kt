@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.shihab.diplay.media

import com.shihab.diplay.airplay.HeapPacket
import com.shihab.diplay.airplay.MediaPacket
import com.shihab.diplay.airplay.VideoCodec
import java.util.concurrent.LinkedBlockingQueue

/** Do not resume dependent pictures after losing a reference frame. */
internal class KotlinVideoReferenceChain {
    var needsKeyFrame = true
        private set
    fun reset() { needsKeyFrame = true }
    fun accepts(bytes: ByteArray, codec: VideoCodec): Boolean =
        !needsKeyFrame || KotlinVideoCodecReference.isRandomAccess(bytes, codec)
    fun accepts(annexB: MediaPacket, codec: VideoCodec): Boolean = !needsKeyFrame ||
        if (annexB.flags and MediaPacket.FLAG_ANNEX_B != 0) {
            annexB.flags and (if (codec == VideoCodec.H265) MediaPacket.FLAG_RANDOM_ACCESS_H265 else MediaPacket.FLAG_RANDOM_ACCESS_H264) != 0
        } else KotlinVideoCodecReference.isRandomAccess(annexB.toByteArray(), codec)
    fun onQueued() { needsKeyFrame = false }
}

/**
 * The old Java frame queue, kept as the host pipe's test double: overflow drops frames and
 * inserts a resync barrier, a drain makes later frames release, and control jobs always keep
 * their order. Production video queues frames in the Rust vpipe instead; control jobs here are
 * the production mailbox types, which the double treats as opaque barriers.
 */
internal class KotlinVideoDecodeQueue(
    // Recovery observes sustained backlog; short delayed bursts keep their reference chain.
    private val maxFrames: Int = 60,
    private val maxBytes: Int = 8 * 1024 * 1024,
) {
    /** A frame the double owns until the worker takes it; whoever drops or feeds it releases [packet]. */
    data class Frame(
        val packet: MediaPacket,
        val receivedNs: Long = System.nanoTime(),
        /** The iPhone's frame time and when the frame arrived (System.nanoTime), 0 when unknown. */
        val senderNanos: Long = 0L,
        val arrivalNanos: Long = 0L,
    ) {
        constructor(nalus: ByteArray, receivedNs: Long = System.nanoTime(), senderNanos: Long = 0L, arrivalNanos: Long = 0L) :
            this(HeapPacket(nalus), receivedNs, senderNanos, arrivalNanos)

        val nalus: ByteArray get() = packet.toByteArray()
    }
    data object Resync

    private val jobs = LinkedBlockingQueue<Any>()
    // Set by [drain]: the worker is gone, so a frame offered later is released instead of kept forever.
    private var drained = false

    data class Backlog(val pendingFrames: Int, val newestPendingReceivedNs: Long?)

    /** Called by the sole consumer after taking its current frame. Control jobs end this chain. */
    @Synchronized fun backlogAfterCurrent(): Backlog {
        var frames = 0
        var newest: Long? = null
        for (job in jobs) {
            val frame = job as? Frame ?: break
            frames++
            newest = newest?.let { maxOf(it, frame.receivedNs) } ?: frame.receivedNs
        }
        return Backlog(frames, newest)
    }

    @Synchronized fun offer(job: VideoJob) { jobs.offer(job) }

    @Synchronized fun offer(resync: Resync) { jobs.offer(resync) }

    @Synchronized fun offer(frame: Frame) {
        if (drained) { frame.packet.release(); return }
        val frames = jobs.filterIsInstance<Frame>()
        if (frames.size >= maxFrames || frames.sumOf { it.packet.size.toLong() } + frame.packet.size > maxBytes) {
            discardFrames()
            jobs.offer(Resync)
        }
        // A single oversized frame is also a lost reference chain.
        if (frame.packet.size > maxBytes) { frame.packet.release(); return }
        jobs.offer(frame)
    }

    /** True when an overflow already dropped the chain the consumer's current frame belongs to. */
    @Synchronized fun resyncPending(): Boolean {
        for (job in jobs) {
            if (job === Resync) return true
            if (job !is Frame) return false // a later config or surface starts another chain
        }
        return false
    }

    /** Drop only the invalidated chain; the next config/surface owns later frames. */
    @Synchronized fun discardCurrentChain() {
        val iterator = jobs.iterator()
        while (iterator.hasNext()) {
            when (val job = iterator.next()) {
                is Frame -> { iterator.remove(); job.packet.release() }
                is Resync -> iterator.remove()
                else -> return
            }
        }
    }

    @Synchronized fun discardFrames() {
        jobs.removeIf { job ->
            (job is Frame || job is Resync).also { if (it) (job as? Frame)?.packet?.release() }
        }
    }

    fun poll(timeoutMillis: Long): Any? = jobs.poll(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)

    /** Removes and returns every queued job, for a worker that is shutting down; it releases their frames. */
    @Synchronized fun drain(): List<Any> {
        drained = true
        return ArrayList<Any>().also { jobs.drainTo(it) }
    }
}
