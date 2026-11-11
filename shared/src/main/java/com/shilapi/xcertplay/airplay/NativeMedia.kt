package com.shilapi.xcertplay.airplay

import android.util.Log
import java.nio.ByteBuffer

/**
 * The media data plane in `libdiplay_media` (Rust; source in `shared/src/main/rust/diplay-media`, design
 * in docs/ARCHITECTURE.zh-CN.md). The screen and audio receivers hand it their socket and get pooled
 * [MediaPacket]s back; [chachaOpen] serves the Kotlin callers of ChaCha20-Poly1305. When the library
 * does not load (host unit tests, an unexpected ABI) every caller keeps its Kotlin path.
 */
internal object NativeMedia {
    val available: Boolean by lazy {
        runCatching { System.loadLibrary("diplay_media") }
            .onFailure { runCatching { Log.w("xcertplay-usb", "native media library unavailable: $it") } }
            .isSuccess
    }

    /** Plaintext length written at [out] + [outOffset]; -1 when the tag does not verify. */
    @JvmStatic external fun chachaOpen(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        aadOffset: Int,
        aadLength: Int,
        input: ByteArray,
        inputOffset: Int,
        inputLength: Int,
        out: ByteArray,
        outOffset: Int,
    ): Int

    /**
     * Receives one screen stream from [fd], which the receiver owns from now on, on its own
     * `airplay-screen` thread. Returns the handle for [stop], or 0 when it could not start (the caller
     * then still owns [fd]).
     */
    @JvmStatic external fun startVideo(fd: Int, key: ByteArray, callbacks: NativeStreamCallbacks): Long

    /** As [startVideo], for one RTP audio stream on an `airplay-audio-rx` thread at audio priority. */
    @JvmStatic external fun startAudio(fd: Int, key: ByteArray, streamType: Int, callbacks: NativeStreamCallbacks): Long

    /** Stops a stream and gives up [handle]; also safe from the stream's own callbacks. */
    @JvmStatic external fun stop(handle: Long)

    /** Opens one buffered-audio frame into a pooled RTP packet; 0 when it is malformed or does not open. */
    @JvmStatic external fun openRtp(key: ByteArray, body: ByteArray, length: Int): Long

    @JvmStatic external fun packetSize(handle: Long): Int
    @JvmStatic external fun packetRetain(handle: Long)
    @JvmStatic external fun packetRelease(handle: Long)
    /** Bytes copied into the direct [target] at [position], or -1 when it is not direct or too small. */
    @JvmStatic external fun packetCopyInto(handle: Long, offset: Int, length: Int, target: ByteBuffer, position: Int): Int
    @JvmStatic external fun packetRead(handle: Long, offset: Int, length: Int, out: ByteArray, outOffset: Int): Boolean
    /** A pooled copy of the first [length] bytes of [bytes]; 0 on bad arguments. */
    @JvmStatic external fun packetWrap(bytes: ByteArray, length: Int): Long

    /** An audio renderer's PCM state (see `NativePcm`); 0 when [opus] was asked for and libopus is missing. */
    @JvmStatic external fun pcmCreate(sampleRate: Int, channels: Int, voiceFilter: Boolean, opus: Boolean): Long
    @JvmStatic external fun pcmDestroy(handle: Long)
    /** PCM bytes written from the start of the direct [out], or a negative libopus error. */
    @JvmStatic external fun pcmDecodeOpus(handle: Long, packet: Long, offset: Int, length: Int, out: ByteBuffer): Int
    /** Big-endian LPCM bytes copied into the start of the direct [out] as little-endian, or -1. */
    @JvmStatic external fun pcmCopySwapped(packet: Long, offset: Int, length: Int, out: ByteBuffer): Int
    /** Runs the PCM steps on the first [length] bytes of the direct [buffer]; 1 when sound was detected. */
    @JvmStatic external fun pcmProcess(handle: Long, buffer: ByteBuffer, length: Int, flags: Int, attenuationDb: Int, peak: Int): Int
}

/**
 * Called by a native receiver from its own thread. [onPacket] hands over a packet handle the
 * receiver no longer owns: the implementation must wrap it in a [NativePacket] or release it.
 * For video, [a] is the iPhone's frame time and [b] the arrival time (System.nanoTime); for audio,
 * [a] is the RTP timestamp (unsigned) and [b] the arrival time.
 */
internal abstract class NativeStreamCallbacks {
    abstract fun onPacket(handle: Long, size: Int, flags: Int, a: Long, b: Long)
    open fun onConfig(bytes: ByteArray) {}
    /** Logcat: [level] 0 is info, 1 a warning. */
    abstract fun onLog(level: Int, line: String)
    /** A line for the session's diagnostic log (the `Receive:` stats). */
    abstract fun onReport(line: String)
    /** The receive loop ended on its own: [error] is null at end of stream. Not called after [NativeMedia.stop]. */
    abstract fun onEnded(error: String?)
}
