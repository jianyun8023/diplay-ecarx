package com.shilapi.xcertplay.media

import android.media.MediaCodecList
import android.media.MediaFormat
import com.shilapi.xcertplay.airplay.NativeMedia

/**
 * Whether this head unit can encode Opus for the CarPlay microphone uplink.
 *
 * The platform codec list alone is not the capability: the bundled software encoder also works on
 * head units without a platform Opus encoder. Probe it before advertising the microphone formats,
 * without opening the microphone or changing the audio mode.
 */
object OpusEncoderSupport {
    fun isAvailable(): Boolean {
        NativeMedia.requireAvailable()
        return isAvailable(
            platformAvailable = ::platformAvailable,
            software = { MicrophoneOpusEncoders.createSoftware(bitrate = 48_000) },
        )
    }

    internal fun isAvailable(
        platformAvailable: () -> Boolean,
        software: () -> MicrophoneOpusEncoder,
    ): Boolean {
        if (runCatching(platformAvailable).getOrDefault(false)) return true
        return runCatching {
            software().use { encoder ->
                encoder.available && encoder.encode(ByteArray(NativeOpusEncoder.FRAME_BYTES))
                    .any { it.isNotEmpty() }
            }
        }.getOrDefault(false)
    }

    private fun platformAvailable(): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any {
                it.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, ignoreCase = true)
            }
        }
}
