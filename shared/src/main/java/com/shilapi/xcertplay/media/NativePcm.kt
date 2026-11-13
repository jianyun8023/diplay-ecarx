package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.MediaPacket
import com.shilapi.xcertplay.airplay.NativeMedia
import com.shilapi.xcertplay.airplay.NativePacket
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * An [AudioRenderer]'s PCM work in `libdiplay_media`: Opus decoding with libopus, wired
 * LPCM byte order, and the steps before each track write (call high-pass, navigation sound
 * detection, navigation attenuation, fade-in), all on the renderer's direct PCM buffer. Only the
 * renderer's worker uses it, and closes it.
 */
internal class NativePcm private constructor(private var handle: Long, val decodesOpus: Boolean) : Closeable {
    /** PCM bytes written from the start of [out]; negative is an Opus decode error. */
    fun decodeOpus(packet: MediaPacket, offset: Int, length: Int, out: ByteBuffer): Int =
        withHandle(packet) { NativeMedia.pcmDecodeOpus(handle, it, offset, length, out) }

    /** Big-endian LPCM from [packet] into the start of [out] as little-endian; -1 when it does not fit. */
    fun copySwapped(packet: MediaPacket, offset: Int, length: Int, out: ByteBuffer): Int =
        withHandle(packet) { NativeMedia.pcmCopySwapped(it, offset, length, out) }

    /** Runs the PCM steps on the first [length] bytes of [buffer]; true when [DETECT_AUDIBLE] found sound. */
    fun process(buffer: ByteBuffer, length: Int, flags: Int, attenuationDb: Int, peak: Int): Boolean =
        NativeMedia.pcmProcess(handle, buffer, length, flags, attenuationDb, peak) == 1

    override fun close() {
        if (handle != 0L) NativeMedia.pcmDestroy(handle)
        handle = 0L
    }

    /** Heap-backed SDK input is copied into a pooled packet at this boundary. */
    private inline fun withHandle(packet: MediaPacket, block: (Long) -> Int): Int {
        if (packet is NativePacket) return block(packet.nativeHandle())
        val copy = NativeMedia.packetWrap(packet.toByteArray(), packet.size)
        if (copy == 0L) return -1
        return try { block(copy) } finally { NativeMedia.packetRelease(copy) }
    }

    companion object {
        const val DETECT_AUDIBLE = 1
        const val ATTENUATE = 2
        const val FADE_IN = 4

        /** Core DSP is mandatory; an unavailable Opus decoder still uses the SDK MediaCodec. */
        fun create(sampleRate: Int, channels: Int, voiceFilter: Boolean, opus: Boolean): NativePcm {
            NativeMedia.requireAvailable()
            if (opus) NativeMedia.pcmCreate(sampleRate, channels, voiceFilter, true).let {
                if (it != 0L) return NativePcm(it, true)
            }
            val handle = NativeMedia.pcmCreate(sampleRate, channels, voiceFilter, false)
            check(handle != 0L) { "Native PCM processor could not be created" }
            return NativePcm(handle, false)
        }
    }
}
