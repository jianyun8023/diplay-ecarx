package com.shilapi.xcertplay.media

import java.io.Closeable

/**
 * In-process libopus encoder for the CarPlay microphone uplink.
 *
 * Android's MediaCodec Opus *encoder* (`c2.android.opus.encoder`) only ships on API 30+, so a 820A
 * head unit running Android 9 has a decoder but no encoder: `MediaCodec.createEncoderByType` throws
 * and the whole telephony uplink goes silent ("microphone silent / the other side can't hear you").
 * This class drives a bundled libopus (fixed-point) through JNI, giving the same 48 kHz mono 20 ms
 * Opus access units without any platform codec.
 */
internal class OpusNativeEncoder(
    sampleRate: Int = SAMPLE_RATE,
    channels: Int = CHANNELS,
    bitrate: Int = DEFAULT_BITRATE,
    voice: Boolean = true,
) : Closeable {
    private var handle: Long = 0

    val available: Boolean get() = handle != 0L

    init {
        if (loaded) {
            handle = nativeCreate(sampleRate, channels, bitrate, voice)
        }
    }

    /** Encodes [frameSize] 16-bit PCM samples (interleaved) into one Opus access unit, or null on error. */
    fun encode(pcm: ShortArray, frameSize: Int, maxBytes: Int = MAX_OUTPUT_BYTES): ByteArray? =
        if (handle == 0L) null else nativeEncode(handle, pcm, frameSize, maxBytes)

    override fun close() {
        if (handle == 0L) return
        nativeDestroy(handle)
        handle = 0
    }

    private external fun nativeCreate(sampleRate: Int, channels: Int, bitrate: Int, voice: Boolean): Long
    private external fun nativeEncode(handle: Long, pcm: ShortArray, frameSize: Int, maxBytes: Int): ByteArray?
    private external fun nativeDestroy(handle: Long)

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val CHANNELS = 1
        private const val DEFAULT_BITRATE = 48_000
        private const val MAX_OUTPUT_BYTES = 4_096

        /** True once the bundled libopus JNI shim is loaded; false means the uplink falls back to nothing. */
        val loaded: Boolean = runCatching { System.loadLibrary("opus_encoder"); true }.getOrDefault(false)
    }
}
