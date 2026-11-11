package com.shilapi.xcertplay.media

import android.view.Surface
import com.shilapi.xcertplay.airplay.HeapPacket
import com.shilapi.xcertplay.airplay.MediaPacket
import com.shilapi.xcertplay.airplay.VideoCodec
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob
    /** A frame the queue owns until the worker takes it; whoever drops or feeds it releases [packet]. */
    data class Frame(
        val packet: MediaPacket,
        val receivedNs: Long = System.nanoTime(),
        /** The iPhone's frame time and when the frame arrived (System.nanoTime), 0 when unknown. */
        val senderNanos: Long = 0L,
        val arrivalNanos: Long = 0L,
    ) : VideoJob {
        constructor(nalus: ByteArray, receivedNs: Long = System.nanoTime(), senderNanos: Long = 0L, arrivalNanos: Long = 0L) :
            this(HeapPacket(nalus), receivedNs, senderNanos, arrivalNanos)

        val nalus: ByteArray get() = packet.toByteArray()
    }
    data class SurfaceChanged(val surface: Surface?) : VideoJob
    /** Also show the stream on [surface] under [key] (null stops it), drawn from this decoder's pictures. */
    data class MirrorChanged(val key: String, val surface: Surface?) : VideoJob
    /** Stop rendering to a surface that is going away; completes [request] once the codec has let go. */
    data class DetachSurface(val request: SurfaceDetachRequest) : VideoJob
    /** Ask the iPhone for a keyframe, so a surface that just came back gets a picture without waiting for motion. */
    data object RefreshPicture : VideoJob
    data object Resync : VideoJob
    /** An event from the native decode loop ([NativeVideoPipe]), handled on the worker. */
    data class NativeEvent(val kind: Int, val detail: String?) : VideoJob
}

/** Do not resume dependent pictures after losing a reference frame. */
internal class VideoReferenceChain {
    var needsKeyFrame = true
        private set
    fun reset() { needsKeyFrame = true }
    fun accepts(bytes: ByteArray, codec: VideoCodec): Boolean =
        !needsKeyFrame || MediaCodecSupport.isRandomAccess(bytes, codec)
    fun accepts(annexB: MediaPacket, codec: VideoCodec): Boolean = !needsKeyFrame || annexB.isRandomAccess(codec)
    fun onQueued() { needsKeyFrame = false }
}

/** Limit latency and memory without ever dropping a reference frame silently. */
internal class VideoDecodeQueue(
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

/** Drain output while waiting for input: full output buffers can otherwise starve input forever. Video and audio decoders share it. */
internal object DecoderInputPump {
    fun acquire(
        running: () -> Boolean,
        drain: () -> Unit,
        dequeue: () -> Int,
        nanoTime: () -> Long = System::nanoTime,
        timeoutNs: Long = TimeUnit.MILLISECONDS.toNanos(500),
    ): Int {
        val start = nanoTime()
        while (running()) {
            drain()
            val index = dequeue()
            if (index >= 0) return index
            if (nanoTime() - start >= timeoutNs) break
        }
        return -1
    }
}

/**
 * The frame as Annex B, or null when it is not a valid access unit. A native receiver converted it
 * already; a Kotlin one's length-prefixed units are converted here, as [MediaCodecSupport.toAnnexB].
 */
internal fun MediaPacket.annexBOrNull(): MediaPacket? = when {
    flags and MediaPacket.FLAG_ANNEX_B != 0 -> this
    this is HeapPacket -> MediaCodecSupport.toAnnexB(bytes).let { converted ->
        when {
            converted.isEmpty() -> null
            converted === bytes -> this
            else -> HeapPacket(converted)
        }
    }
    else -> null
}

/** Whether an Annex B frame starts an independently decodable chain: IDR (AVC) or IRAP (HEVC). */
internal fun MediaPacket.isRandomAccess(codec: VideoCodec): Boolean =
    if (flags and MediaPacket.FLAG_ANNEX_B != 0) {
        flags and (if (codec == VideoCodec.H265) MediaPacket.FLAG_RANDOM_ACCESS_H265 else MediaPacket.FLAG_RANDOM_ACCESS_H264) != 0
    } else MediaCodecSupport.isRandomAccess(toByteArray(), codec)
