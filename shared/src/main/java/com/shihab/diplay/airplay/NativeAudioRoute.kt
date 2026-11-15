package com.shihab.diplay.airplay

/** A receiver's destination and receive-clock diagnostics; queue references are shared in Rust. */
class NativeAudioRoute internal constructor(clockRate: Int, direct: Boolean) : java.io.Closeable {
    init { NativeMedia.requireAvailable() }
    private var handle = NativeMedia.audioRouteCreate(clockRate, direct).also { check(it != 0L) }

    /** The producer retains under its own lifetime monitor; Rust consumes this queue reference. */
    @Synchronized internal fun setQueue(retainQueue: () -> Long) {
        if (handle != 0L) NativeMedia.audioRouteSetQueue(handle, retainQueue())
    }

    @Synchronized internal fun disconnect() {
        if (handle != 0L) NativeMedia.audioRouteSetQueue(handle, 0L)
    }

    @Synchronized internal fun start(fd: Int, key: ByteArray, streamType: Int,
        callbacks: NativeStreamCallbacks): Long =
        if (handle == 0L) 0L else NativeMedia.startAudio(fd, key, streamType, callbacks, handle)

    @Synchronized internal fun feedback(reported: Long, nowNs: Long): LongArray? =
        if (handle == 0L) null else NativeMedia.audioRouteFeedback(handle, reported, nowNs)

    /** Commits the first decrypted sample and its monotonic origin from the start callback. */
    @Synchronized internal fun anchor(firstSample: Int, originNs: Long) {
        if (handle != 0L) NativeMedia.audioRouteAnchor(handle, firstSample, originNs)
    }

    /** The complete single-stream feedback entry, computed by the native route owner. */
    @Synchronized internal fun feedbackEntry(
        type: Int, nowNs: Long, latencyMs: Int, connection: Any?, timestamp: Any?,
    ): Map<String, Any?>? =
        if (handle == 0L) null
        else @Suppress("UNCHECKED_CAST")
        (NativeMedia.audioRouteFeedbackEntry(handle, type, nowNs, latencyMs, connection, timestamp)
            as? Map<String, Any?>)

    @Synchronized override fun close() {
        if (handle == 0L) return
        NativeMedia.audioRouteDestroy(handle)
        handle = 0L
    }
}
