package com.shilapi.xcertplay.media

import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * Whether this head unit can encode Opus for the CarPlay microphone uplink.
 *
 * The platform codec list alone is not the capability: `c2.android.opus.encoder` only ships on
 * API 30+, so an 820A head unit on Android 9 has the Opus *decoder* but no encoder. Our bundled
 * libopus (fixed-point) covers that case, so a missing MediaCodec encoder must not remove the
 * wireless Opus formats. Probe it before advertising the microphone formats, without opening the
 * microphone or changing the audio mode.
 */
object OpusEncoderSupport {
    fun isAvailable(): Boolean = isAvailable(
        platformAvailable = ::platformAvailable,
        software = { OpusEncoder(bitrate = 48_000) },
    )

    internal fun isAvailable(
        platformAvailable: () -> Boolean,
        software: () -> OpusEncoder,
    ): Boolean {
        if (runCatching(platformAvailable).getOrDefault(false)) return true
        return runCatching {
            software().use { encoder ->
                encoder.available && encoder.encode(ByteArray(FRAME_BYTES)).any { it.isNotEmpty() }
            }
        }.getOrDefault(false)
    }

    private fun platformAvailable(): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any {
                it.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, ignoreCase = true)
            }
        }

    /** One 20 ms 48 kHz mono PCM frame: 960 samples at 16-bit. */
    private const val FRAME_BYTES = 960 * 2
}
