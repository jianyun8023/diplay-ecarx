package com.shilapi.xcertplay.media

import android.util.Log
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes 20 ms chunks of 48 kHz mono PCM into raw Opus access units for the CarPlay
 * microphone uplink.
 *
 * The Android MediaCodec Opus *encoder* ships only on API 30+ (`c2.android.opus.encoder`); a 820A
 * head unit on Android 9 has the decoder but not the encoder, so `createEncoderByType` throws and
 * the uplink stays silent. This class therefore encodes through a bundled libopus (fixed-point) via
 * [OpusNativeEncoder], which is always available and costs a negligible amount of CPU for a mono
 * 20 ms frame.
 */
internal class OpusEncoder(bitrate: Int) : Closeable {
    private val native = OpusNativeEncoder(bitrate = bitrate)
    private var closed = false

    val available: Boolean get() = native.available && !closed

    /**
     * Queues one 20 ms PCM frame and returns all Opus access units produced (always one for the
     * fixed frame size).
     */
    fun encode(pcm: ByteArray): List<ByteArray> {
        if (closed || !native.available) return emptyList()
        // libopus consumes interleaved 16-bit samples; the PCM frame is little-endian s16le.
        val samples = pcm.size / 2
        val shorts = ShortArray(samples)
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        val accessUnit = native.encode(shorts, samples) ?: return emptyList()
        return if (accessUnit.isEmpty()) emptyList() else listOf(accessUnit)
    }

    override fun close() {
        if (closed) return
        closed = true
        native.close()
    }

    private companion object {
        const val TAG = "xcertplay-usb"
    }
}
