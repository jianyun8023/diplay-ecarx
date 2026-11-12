package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.MediaPacket
import com.shilapi.xcertplay.airplay.VideoCodec
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Do not resume dependent pictures after losing a reference frame. */
internal class KotlinVideoReferenceChain {
    var needsKeyFrame = true
        private set
    fun reset() { needsKeyFrame = true }
    fun accepts(bytes: ByteArray, codec: VideoCodec): Boolean =
        !needsKeyFrame || MediaCodecSupport.isRandomAccess(bytes, codec)
    fun accepts(annexB: MediaPacket, codec: VideoCodec): Boolean = !needsKeyFrame || annexB.isRandomAccess(codec)
    fun onQueued() { needsKeyFrame = false }
}

/** Limit latency and memory without ever dropping a reference frame silently. */
internal class KotlinVideoDecodeQueue(
    // Recovery observes sustained backlog; short delayed bursts keep their reference chain.
    private val maxFrames: Int = 60,
    private val maxBytes: Int = 8 * 1024 * 1024,
) {
    private val jobs = LinkedBlockingQueue<VideoJob>()
    // Set by [drain]: the worker is gone, so a frame offered later is released instead of kept forever.
    private var drained = false

    data class Backlog(val pendingFrames: Int, val newestPendingReceivedNs: Long?)

    /** Called by the sole consumer after taking its current frame. Control jobs end this chain. */
    @Synchronized fun backlogAfterCurrent(): Backlog {
        var frames = 0
        var newest: Long? = null
        for (job in jobs) {
            if (job !is VideoJob.Frame) break
            frames++
            newest = newest?.let { maxOf(it, job.receivedNs) } ?: job.receivedNs
        }
        return Backlog(frames, newest)
    }

    @Synchronized fun offer(job: VideoJob) {
        if (job is VideoJob.Frame) {
            if (drained) { job.packet.release(); return }
            val frames = jobs.filterIsInstance<VideoJob.Frame>()
            if (frames.size >= maxFrames || frames.sumOf { it.packet.size.toLong() } + job.packet.size > maxBytes) {
                discardFrames()
                jobs.offer(VideoJob.Resync)
            }
            // A single oversized frame is also a lost reference chain.
            if (job.packet.size > maxBytes) { job.packet.release(); return }
        }
        jobs.offer(job)
    }

    /** True when an overflow already dropped the chain the consumer's current frame belongs to. */
    @Synchronized fun resyncPending(): Boolean {
        for (job in jobs) {
            if (job === VideoJob.Resync) return true
            if (job !is VideoJob.Frame) return false // a later config or surface starts another chain
        }
        return false
    }

    /** Drop only the invalidated chain; the next config/surface owns later frames. */
    @Synchronized fun discardCurrentChain() {
        val iterator = jobs.iterator()
        while (iterator.hasNext()) {
            when (val job = iterator.next()) {
                is VideoJob.Frame -> { iterator.remove(); job.packet.release() }
                VideoJob.Resync -> iterator.remove()
                else -> return
            }
        }
    }

    @Synchronized fun discardFrames() {
        jobs.removeIf { job ->
            (job is VideoJob.Frame || job is VideoJob.Resync).also { if (it) (job as? VideoJob.Frame)?.packet?.release() }
        }
    }

    fun poll(timeoutMillis: Long): VideoJob? = jobs.poll(timeoutMillis, TimeUnit.MILLISECONDS)

    /** Removes and returns every queued job, for a worker that is shutting down; it releases their frames. */
    @Synchronized fun drain(): List<VideoJob> {
        drained = true
        return ArrayList<VideoJob>().also { jobs.drainTo(it) }
    }
}

