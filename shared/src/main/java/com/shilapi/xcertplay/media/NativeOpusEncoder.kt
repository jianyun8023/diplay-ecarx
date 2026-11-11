package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.NativeMedia

/**
 * The microphone's Opus encoder in `libdiplay_media` (opus-pure, a pure-Rust port of libopus): 48 kHz
 * mono, VoIP, complexity 5, voice signal. The uplink owns it; close may also run after its worker's
 * bounded join times out. [onError] receives why the native encoder is unavailable, so a head unit that
 * falls back to the Java encoder leaves the reason in its logs.
 */
internal class NativeOpusEncoder(
    bitrate: Int,
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
) : MicrophoneOpusEncoder {
    private var handle = if (NativeMedia.available) NativeMedia.opusEncoderCreate(bitrate) else 0L
    private val output = ByteArray(1_275)

    init {
        if (handle == 0L) {
            onError("native Opus encoder unavailable",
                NativeMedia.loadError ?: IllegalStateException("opus encoder refused bitrate=$bitrate"))
        }
    }

    override val implementation = "opus-pure"
    override val available: Boolean
        @Synchronized get() = handle != 0L

    @Synchronized
    override fun encode(pcm: ByteArray): List<ByteArray> {
        if (handle == 0L) return emptyList()
        val length = NativeMedia.opusEncode(handle, pcm, output)
        return if (length > 0) listOf(output.copyOf(length)) else emptyList()
    }

    @Synchronized
    override fun close() {
        if (handle == 0L) return
        NativeMedia.opusEncoderDestroy(handle)
        handle = 0L
    }
}
