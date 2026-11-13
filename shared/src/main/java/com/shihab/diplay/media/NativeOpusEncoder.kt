package com.shihab.diplay.media

import com.shihab.diplay.airplay.NativeMedia

/**
 * The microphone's Opus encoder in `libdiplay_media` (statically linked C libopus): 48 kHz
 * mono, VoIP, complexity 5, voice signal. The uplink owns it; close may also run after its worker's
 * bounded join times out. [onError] reports a refused encoder configuration; a missing required core
 * library propagates its load failure.
 */
internal class NativeOpusEncoder(
    bitrate: Int,
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
) : MicrophoneOpusEncoder {
    private var handle = run { NativeMedia.requireAvailable(); NativeMedia.opusEncoderCreate(bitrate) }
    private val output = ByteArray(1_275)

    init {
        if (handle == 0L) {
            onError("native Opus encoder unavailable",
                IllegalStateException("opus encoder refused bitrate=$bitrate"))
        }
    }

    override val implementation = "libopus"
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

    companion object {
        const val FRAME_BYTES = 960 * 2
    }
}
