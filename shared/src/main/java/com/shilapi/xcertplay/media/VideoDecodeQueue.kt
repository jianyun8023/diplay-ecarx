package com.shilapi.xcertplay.media

import android.view.Surface
import com.shilapi.xcertplay.airplay.HeapPacket
import com.shilapi.xcertplay.airplay.MediaPacket
import com.shilapi.xcertplay.airplay.VideoCodec
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

/** The reference-chain state is one Rust value; packet classification uses the shared wire codec. */
internal class VideoReferenceChain {
    private var owner = NativeVideoFlow.reference(0, 0)
    val needsKeyFrame: Boolean get() = NativeVideoFlow.referenceNeeds(owner)
    fun reset() { owner = NativeVideoFlow.reference(owner, 1) }
    fun accepts(bytes: ByteArray, codec: VideoCodec): Boolean =
        NativeVideoFlow.referenceAccepts(owner, needsKeyFrame && MediaCodecSupport.isRandomAccess(bytes, codec))
    fun accepts(annexB: MediaPacket, codec: VideoCodec): Boolean =
        NativeVideoFlow.referenceAccepts(owner, needsKeyFrame && annexB.isRandomAccess(codec))
    fun onQueued() { owner = NativeVideoFlow.reference(owner, 2) }
}

/** Rust owns admission, order and recovery; the GC carrier holds the actual Android jobs. */
internal class VideoDecodeQueue(maxFrames: Int = 60, maxBytes: Int = 8 * 1024 * 1024) {
    private val lock = Object()
    private val owner = NativeVideoFlow.queueCreate(maxFrames, maxBytes, VideoJob.Resync)
    data class Backlog(val pendingFrames: Int, val newestPendingReceivedNs: Long?)

    fun backlogAfterCurrent(): Backlog = synchronized(lock) {
        val values = NativeVideoFlow.queueBacklog(owner)
        Backlog(values[0].toInt(), if (values[2] != 0L) values[1] else null)
    }
    fun offer(job: VideoJob) = synchronized(lock) {
        val kind = when (job) { is VideoJob.Frame -> 1; VideoJob.Resync -> 2; else -> 0 }
        val dropped = NativeVideoFlow.queueOffer(owner, job, kind,
            (job as? VideoJob.Frame)?.packet?.size ?: 0, (job as? VideoJob.Frame)?.receivedNs ?: 0)
        releaseFrames(dropped)
        lock.notifyAll()
    }
    fun resyncPending(): Boolean = synchronized(lock) { NativeVideoFlow.queueResync(owner) }
    fun discardCurrentChain() = synchronized(lock) { releaseFrames(NativeVideoFlow.queueRemove(owner, 2)) }
    fun discardFrames() = synchronized(lock) { releaseFrames(NativeVideoFlow.queueRemove(owner, 1)) }

    @Throws(InterruptedException::class)
    fun poll(timeoutMillis: Long): VideoJob? = synchronized(lock) {
        // LinkedBlockingQueue.poll was interruptible even when a job was already available.
        if (Thread.interrupted()) throw InterruptedException()
        (NativeVideoFlow.queueRemove(owner, 0).firstOrNull() as? VideoJob)?.let { return@synchronized it }
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (remaining > 0) {
            val start = System.nanoTime()
            TimeUnit.NANOSECONDS.timedWait(lock, remaining)
            (NativeVideoFlow.queueRemove(owner, 0).firstOrNull() as? VideoJob)?.let { return@synchronized it }
            remaining -= System.nanoTime() - start
        }
        null
    }
    /** Worker releases frames and completes pending detach requests after taking the Rust drain. */
    fun drain(): List<VideoJob> = synchronized(lock) {
        NativeVideoFlow.queueRemove(owner, 3).map { it as VideoJob }
    }
    private fun releaseFrames(jobs: Array<Any?>) {
        jobs.forEach { (it as? VideoJob.Frame)?.packet?.release() }
    }
}

internal object NativeVideoFlow {
    init { com.shilapi.xcertplay.airplay.NativeMedia.requireAvailable() }
    @JvmStatic external fun queueCreate(maxFrames: Int, maxBytes: Int, resync: Any): Array<Any?>
    @JvmStatic external fun queueOffer(owner: Array<Any?>, job: Any, kind: Int, size: Int, received: Long): Array<Any?>
    @JvmStatic external fun queueRemove(owner: Array<Any?>, op: Int): Array<Any?>
    @JvmStatic external fun queueBacklog(owner: Array<Any?>): LongArray
    @JvmStatic external fun queueResync(owner: Array<Any?>): Boolean
    @JvmStatic external fun reference(owner: Long, event: Int): Long
    @JvmStatic external fun referenceNeeds(owner: Long): Boolean
    @JvmStatic external fun referenceAccepts(owner: Long, randomAccess: Boolean): Boolean
    @JvmStatic external fun obsolete(now: Long, received: Long, pending: Int, newest: Long, hasNewest: Boolean): Boolean
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
