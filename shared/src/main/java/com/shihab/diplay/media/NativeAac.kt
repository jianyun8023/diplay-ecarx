package com.shihab.diplay.media

import com.shihab.diplay.airplay.NativeMedia
import com.shihab.diplay.airplay.MediaPacket
import com.shihab.diplay.airplay.NativePacket
import java.nio.ByteBuffer
import java.nio.ReadOnlyBufferException

/** Rust owns AAC bytes and decoder input flow; Android supplies its codec and output adapter. */
internal object NativeAac {
    init { NativeMedia.requireAvailable() }
    @JvmStatic external fun frequencyIndex(sampleRate: Int): Int
    /** -1 when complete, otherwise the original SDK array write's first failing index. */
    @JvmStatic external fun writeAdtsHeader(target: ByteArray, accessUnitBytes: Int, sampleRate: Int, channels: Int): Int
    @JvmStatic external fun audioSpecificConfig(sampleRate: Int, channels: Int): ByteArray
    @JvmStatic external fun accessUnit(payload: ByteArray): ByteArray

    /** Writes ADTS and the access unit at the SDK buffer's position, without staging native packets. */
    fun writeDecoderInput(packet: MediaPacket, offset: Int, length: Int, target: ByteBuffer, sampleRate: Int, channels: Int) {
        require(offset >= 0 && length >= 0 && offset <= packet.size - length)
        require(length <= target.remaining() - MediaCodecSupport.ADTS_HEADER_BYTES)
        if (target.isReadOnly) throw ReadOnlyBufferException()
        withPacket(packet) { handle ->
            val position = target.position()
            val written = writeDecoderInputNative(handle, offset, length, target, position, sampleRate, channels)
            check(written == MediaCodecSupport.ADTS_HEADER_BYTES + length) { "AAC decoder input write failed" }
            target.position(position + written)
        }
    }

    fun feedDecoder(packet: MediaPacket, offset: Int, length: Int, rate: Int, channels: Int,
        timeUs: Long, codec: Any, host: Any) = withPacket(packet) { handle ->
        feedDecoderNative(handle, offset, length, rate, channels, timeUs, codec, host)
    }

    // Array ingress joins the same native path; it does not assemble AAC on the Kotlin side.
    private inline fun <T> withPacket(packet: MediaPacket, action: (Long) -> T): T {
        if (packet is NativePacket) return action(packet.nativeHandle())
        val bytes = packet.toByteArray()
        val handle = NativeMedia.packetWrap(bytes, bytes.size)
        check(handle != 0L) { "AAC packet allocation failed" }
        return try { action(handle) } finally { NativeMedia.packetRelease(handle) }
    }

    @JvmStatic external fun feedDecoderNative(packet: Long, offset: Int, length: Int, rate: Int, channels: Int,
        timeUs: Long, codec: Any, host: Any)
    @JvmStatic external fun writeDecoderInputNative(packet: Long, offset: Int, length: Int, target: ByteBuffer,
        position: Int, sampleRate: Int, channels: Int): Int
}
