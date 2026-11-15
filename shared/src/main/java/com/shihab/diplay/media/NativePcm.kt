@file:Suppress("UNCHECKED_CAST")
package com.shihab.diplay.media

import android.media.MediaCodec
import com.shihab.diplay.airplay.AudioCodecKind
import com.shihab.diplay.airplay.MediaPacket
import com.shihab.diplay.airplay.NativeMedia
import com.shihab.diplay.airplay.NativePacket
import java.io.Closeable
import java.nio.ByteBuffer

/** The output worker owns this native decoder and PCM storage; callbacks borrow PCM synchronously. */
internal class NativePcm private constructor(private var handle: Long) : Closeable {
    data class Configuration(val mime: String, val adts: Boolean, val csd: Array<ByteArray>)

    fun configuration(): Configuration? = NativeMedia.pcmConfiguration(owner())?.let {
        Configuration(it[0] as String, it[1] as Boolean, it[2] as Array<ByteArray>)
    }

    fun feed(packet: MediaPacket, sample: Int, codec: MediaCodec?, host: Any) {
        val owner = owner()
        val nativePacket = if (packet is NativePacket) packet.nativeHandle()
            else NativeMedia.packetWrap(packet.toByteArray(), packet.size)
        if (nativePacket == 0L) return
        try { NativeMedia.pcmFeed(owner, nativePacket, packet.size, sample, codec, host) }
        finally { if (packet !is NativePacket) NativeMedia.packetRelease(nativePacket) }
    }

    fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo, host: Any) =
        NativeMedia.pcmDrain(owner(), codec, info, host)

    /** PCM is borrowed until the current callback returns; hardware writes must finish synchronously. */
    fun process(buffer: ByteBuffer, length: Int, flags: Int, attenuationDb: Int, peak: Int): Boolean =
        NativeMedia.pcmProcess(owner(), buffer, length, flags, attenuationDb, peak) == 1

    private fun owner(): Long = handle.also { check(it != 0L) { "Native PCM processor is closed" } }

    override fun close() {
        if (handle != 0L) NativeMedia.pcmDestroy(handle)
        handle = 0L
    }

    companion object {
        const val DETECT_AUDIBLE = 1
        const val ATTENUATE = 2
        const val FADE_IN = 4

        fun create(sampleRate: Int, channels: Int, voiceFilter: Boolean, codec: AudioCodecKind): NativePcm {
            NativeMedia.requireAvailable()
            val kind = when (codec) { AudioCodecKind.LPCM -> 0; AudioCodecKind.AAC_LC -> 1; AudioCodecKind.OPUS -> 2 }
            val handle = NativeMedia.pcmCreate(sampleRate, channels, voiceFilter, kind)
            check(handle != 0L) { "Native PCM processor could not be created" }
            return NativePcm(handle)
        }
    }
}
