package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.NativeMedia
import java.io.Closeable

/** Worker-owned clock controller. Only the library-unavailable path keeps Kotlin clock state. */
internal class MediaClockTrim(sampleRate: Int, targetFrames: Long, integral: Double = learnedTrim,
    measuresTarget: Boolean = false) : Closeable {
    private val fallback = if (!NativeMedia.available) KotlinMediaClockTrim(sampleRate, targetFrames, integral, measuresTarget) else null
    private var handle = if (fallback == null) NativeMedia.clockCreate(sampleRate, targetFrames, integral, measuresTarget).also { check(it != 0L) } else 0L

    val lastSampleNs: Long get() = fallback?.lastSampleNs ?: NativeMedia.clockQuery(handle, 1)
    val rate: Int get() = fallback?.rate ?: NativeMedia.clockQuery(handle, 0).toInt()

    fun measureAgain() {
        if (fallback != null) fallback.measureAgain() else NativeMedia.clockQuery(handle, 2)
    }

    fun sample(queuedFrames: Long, nowNs: Long, compressedFrames: Long = 0L, headMoving: Boolean = true,
        arrivedFrameNs: Double = 0.0) {
        if (fallback != null) fallback.sample(queuedFrames, nowNs, compressedFrames, headMoving, arrivedFrameNs)
        else NativeMedia.clockSample(handle, queuedFrames, nowNs, compressedFrames, headMoving, arrivedFrameNs)
    }

    fun rateDue(nowNs: Long): Int? = if (fallback != null) fallback.rateDue(nowNs)
        else NativeMedia.clockRateDue(handle, nowNs).takeIf { it != 0 }

    override fun close() {
        if (handle != 0L) NativeMedia.clockDestroy(handle)
        handle = 0L
    }

    companion object {
        var learnedTrim: Double
            get() = if (NativeMedia.available) NativeMedia.clockLearned(false, 0.0) else KotlinMediaClockTrim.learnedTrim
            set(value) {
                if (NativeMedia.available) NativeMedia.clockLearned(true, value) else KotlinMediaClockTrim.learnedTrim = value
            }
    }
}
