package com.shihab.diplay.media

import android.view.Surface

internal sealed interface VideoJob {
    data class Config(val codec: com.shihab.diplay.airplay.VideoCodec, val codecData: ByteArray) : VideoJob
    data class SurfaceChanged(val surface: Surface?) : VideoJob
    /** Also show the stream on [surface] under [key] (null stops it), drawn from this decoder's pictures. */
    data class MirrorChanged(val key: String, val surface: Surface?) : VideoJob
    /** Stop rendering to a surface that is going away; completes [request] once the codec has let go. */
    data class DetachSurface(val request: SurfaceDetachRequest) : VideoJob
    /** Ask the iPhone for a keyframe, so a surface that just came back gets a picture without waiting for motion. */
    data object RefreshPicture : VideoJob
    /** An event from the native decode loop ([NativeVideoPipe]), handled on the worker. */
    data class NativeEvent(val kind: Int, val detail: String?) : VideoJob
}

/**
 * The decoder worker's control mailbox: FIFO jobs, interruptible waits, a close that wakes the
 * worker, and a shutdown drain that hands out whatever is left. Video frames never enter this
 * mailbox; the Rust vpipe owns the real media queue, its backlog and its reference chains.
 */
internal class VideoControlMailbox {
    private val lock = Object()
    private val jobs = ArrayDeque<VideoJob>()

    fun offer(job: VideoJob) = synchronized(lock) {
        jobs.addLast(job)
        lock.notifyAll()
    }

    /** Interruptible even when a job is already available, like the queue it replaces. */
    @Throws(InterruptedException::class)
    fun poll(timeoutMillis: Long): VideoJob? = synchronized(lock) {
        if (Thread.interrupted()) throw InterruptedException()
        jobs.removeFirstOrNull()?.let { return@synchronized it }
        var remaining = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (remaining > 0) {
            val start = System.nanoTime()
            java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(lock, remaining)
            jobs.removeFirstOrNull()?.let { return@synchronized it }
            remaining -= System.nanoTime() - start
        }
        null
    }

    /** Removes and returns every queued job, for a worker that is shutting down. */
    fun drain(): List<VideoJob> = synchronized(lock) {
        ArrayList(jobs).also { jobs.clear() }
    }
}
