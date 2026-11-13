package com.shilapi.xcertplay.media.reference

/** Independent reference of the capture clock before its Rust migration. */
internal class KotlinCaptureClock(private val sampleRate: Int) {
    private var samplesRead = 0L
    private var lastReadSamples = 0
    private var lastReadEndNs = 0L

    fun read(samples: Int, nowNs: Long, stamped: Boolean, timestampNs: Long, framePosition: Long) {
        samplesRead += samples
        lastReadSamples = samples
        lastReadEndNs = if (stamped && timestampNs > 0) {
            timestampNs + (samplesRead - framePosition) * 1_000_000_000L / sampleRate
        } else {
            nowNs - 20_000_000L
        }
    }

    fun frameEndNs(samplesIntoRead: Int): Long =
        lastReadEndNs - (lastReadSamples - samplesIntoRead).coerceAtLeast(0) * 1_000_000_000L / sampleRate
}
