package com.shihab.diplay.media

import com.shihab.diplay.airplay.NativeMedia

/**
 * Whether this head unit can encode Opus for the CarPlay microphone uplink.
 *
 * Probe the bundled encoder used by the native capture pipeline before advertising Opus,
 * without opening the microphone or changing the audio mode.
 */
object OpusEncoderSupport {
    fun isAvailable(): Boolean {
        NativeMedia.requireAvailable()
        return isAvailable(software = { NativeOpusEncoder(48_000) })
    }

    internal fun isAvailable(
        software: () -> MicrophoneOpusEncoder,
    ): Boolean {
        return runCatching {
            software().use { encoder ->
                encoder.available && encoder.encode(ByteArray(NativeOpusEncoder.FRAME_BYTES))
                    .any { it.isNotEmpty() }
            }
        }.getOrDefault(false)
    }
}
