package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.NativeMedia
import java.io.Closeable

/** Worker-owned native clock controller; the calibrated Kotlin oracle lives only in test sources. */
internal class MediaClockTrim(sampleRate: Int, targetFrames: Long, integral: Double = learnedTrim,
    measuresTarget: Boolean = false) : Closeable {
    private var handle = run {
        NativeMedia.requireAvailable()
        NativeMedia.clockCreate(sampleRate, targetFrames, integral, measuresTarget).also { check(it != 0L) }
    }

    val lastSampleNs: Long get() = NativeMedia.clockQuery(handle, 1)
    val rate: Int get() = NativeMedia.clockQuery(handle, 0).toInt()

    fun measureAgain() {
        NativeMedia.clockQuery(handle, 2)
    }

    fun sample(queuedFrames: Long, nowNs: Long, compressedFrames: Long = 0L, headMoving: Boolean = true,
        arrivedFrameNs: Double = 0.0) {
        NativeMedia.clockSample(handle, queuedFrames, nowNs, compressedFrames, headMoving, arrivedFrameNs)
    }

    fun rateDue(nowNs: Long): Int? = NativeMedia.clockRateDue(handle, nowNs).takeIf { it != 0 }

    override fun close() {
        if (handle != 0L) NativeMedia.clockDestroy(handle)
        handle = 0L
    }

    companion object {
        var learnedTrim: Double
            get() {
                NativeMedia.requireAvailable()
                return NativeMedia.clockLearned(false, 0.0)
            }
            set(value) {
                NativeMedia.requireAvailable()
                NativeMedia.clockLearned(true, value)
            }
    }
}
