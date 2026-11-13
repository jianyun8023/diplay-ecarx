package com.shihab.diplay.media

/**
 * Jitter buffer for streams mapped to the media channel. Wireless CarPlay delivers audio over the same Wi-Fi link as
 * video; radio gaps of several hundred milliseconds are normal, so music needs a buffer that
 * outlasts them. Calls, Siri and navigation prompts keep the small low-latency buffer.
 * Wireless and wired USB keep separate settings. Wireless offers the classic presets that outlast Wi-Fi gaps.
 * Wired USB has no radio gaps, so it also offers short presets that let music start and pause sooner.
 * The device minimum AudioTrack size can still make the real buffer longer than a short preset.
 */
object MediaAudioBuffer {
    const val DEFAULT_MILLIS = 300
    const val MOST_STABLE_MILLIS = 1000
    val wirelessPresets = listOf(DEFAULT_MILLIS, 500, MOST_STABLE_MILLIS)
    val wiredPresets = listOf(50, 100, 150, 200) + (DEFAULT_MILLIS..MOST_STABLE_MILLIS step 100)
    /** Every delay a session can use; [plan] accepts any of them. */
    val presets = (wiredPresets + wirelessPresets).distinct().sorted()

    fun sanitize(millis: Int): Int = NativeAudioScheduler.sanitizeDelay(millis, 0)
    fun sanitizeWireless(millis: Int): Int = NativeAudioScheduler.sanitizeDelay(millis, 1)
    fun sanitizeWired(millis: Int): Int = NativeAudioScheduler.sanitizeDelay(millis, 2)

    data class Plan(val trackBufferBytes: Int, val startBytes: Int)

    /** AudioTrack capacity and the amount to queue before play() for one output stream. */
    fun plan(isMedia: Boolean, sampleRate: Int, channels: Int, minBufferBytes: Int, mediaMillis: Int): Plan {
        val plan = NativeAudioScheduler.bufferPlan(isMedia, sampleRate, channels, minBufferBytes, mediaMillis)
        return Plan(plan[0], plan[1])
    }

    /**
     * The device may grant a smaller AudioTrack than requested. Writes block while the track is
     * paused and full, so the start level must stay below the real capacity or play() never runs.
     */
    fun startBytesFor(plannedStartBytes: Int, actualCapacityBytes: Int, writeChunkBytes: Int): Int =
        NativeAudioScheduler.startBytesFor(plannedStartBytes, actualCapacityBytes, writeChunkBytes)
}
