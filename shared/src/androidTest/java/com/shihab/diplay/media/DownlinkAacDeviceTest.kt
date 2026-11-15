package com.shihab.diplay.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.shihab.diplay.airplay.AudioCodecKind
import com.shihab.diplay.airplay.HeapPacket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

/** Real SDK buffers exercise the native input/output driver without playing through a speaker. */
class DownlinkAacDeviceTest {
    @Test fun sdkAacRoundTripUsesNativeOwnedPcm() {
        val units = mutableListOf<ByteArray>()
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val info = MediaCodec.BufferInfo()
            var samples = 0
            var submittedEnd = false
            var receivedEnd = false
            val deadline = System.nanoTime() + 4_000_000_000L
            while (!receivedEnd && System.nanoTime() < deadline) {
                if (!submittedEnd) {
                    val index = encoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val input = encoder.getInputBuffer(index)!!.apply { clear(); order(ByteOrder.LITTLE_ENDIAN) }
                        val count = minOf(4096 - samples, input.remaining() / 2)
                        repeat(count) { at -> input.putShort((kotlin.math.sin((samples + at) * 2.0 * Math.PI * 1000 / 48_000) * 8000).toInt().toShort()) }
                        submittedEnd = count == 0
                        encoder.queueInputBuffer(index, 0, count * 2, samples * 1_000_000L / 48_000,
                            if (submittedEnd) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        samples += count
                    }
                }
                val index = encoder.dequeueOutputBuffer(info, 10_000)
                if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val output = encoder.getOutputBuffer(index)!!.apply { position(info.offset); limit(info.offset + info.size) }
                            units += ByteArray(info.size).also { output.get(it) }
                        }
                        receivedEnd = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { encoder.releaseOutputBuffer(index, false) }
                }
            }
            assertTrue("encoder EOS", receivedEnd)
            assertTrue(units.isNotEmpty())
        } finally { runCatching { encoder.stop() }; encoder.release() }

        NativePcm.create(48_000, 1, false, AudioCodecKind.AAC_LC).use { pcm ->
            val configuration = pcm.configuration()!!
            val decoder = MediaCodec.createDecoderByType(configuration.mime)
            try {
                val format = MediaFormat.createAudioFormat(configuration.mime, 48_000, 1).apply {
                    if (configuration.adts) setInteger(MediaFormat.KEY_IS_ADTS, 1)
                    configuration.csd.forEachIndexed { index, bytes -> setByteBuffer("csd-$index", ByteBuffer.wrap(bytes)) }
                }
                decoder.configure(format, null, null, 0); decoder.start()
                val host = Host(pcm)
                units.forEachIndexed { index, bytes -> pcm.feed(HeapPacket(ByteArray(12) + bytes), index * 1024, decoder, host) }
                // This finite fixture needs EOS to flush the SDK decoder's retained tail.
                val end = decoder.dequeueInputBuffer(1_000_000)
                assertTrue("decoder EOS input", end >= 0)
                decoder.queueInputBuffer(end, 0, 0, units.size * 1024L * 1_000_000 / 48_000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                val deadline = System.nanoTime() + 4_000_000_000L
                while (host.bytes < 8192 && System.nanoTime() < deadline) { host.drainCodec(decoder); Thread.sleep(5) }
                assertEquals(units.size, host.queued)
                assertTrue("decoded PCM bytes=${host.bytes} units=${units.size}", host.bytes >= 8192)
                assertTrue("decoded tone", host.nonzero)
            } finally { runCatching { decoder.stop() }; decoder.release() }
        }
    }

    private class Host(val pcm: NativePcm) {
        var bytes = 0
        var queued = 0
        var nonzero = false
        @Suppress("unused") fun aacInputRunning() = true
        fun drainCodec(codec: MediaCodec) = pcm.drain(codec, MediaCodec.BufferInfo(), this)
        @Suppress("unused") fun decoderInputResult(result: Int, length: Int) { assertEquals(2, result); assertTrue(length > 7); queued++ }
        @Suppress("unused") fun pcmInputResult(event: Int, length: Int, result: Int) { assertTrue(event == 0 || event == 2) }
        @Suppress("unused") fun decoderOutputRunning() = true
        @Suppress("unused") fun decoderOutputFormat(codec: MediaCodec) { assertEquals(48_000, codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)) }
        @Suppress("unused") fun decoderOutputSize(size: Int) { assertTrue(size > 0) }
        @Suppress("unused") fun writePcm(buffer: ByteBuffer, length: Int) {
            assertTrue(buffer.isDirect)
            pcm.process(buffer, length, 0, 0, 64)
            bytes += length
            for (index in 0 until length) if (buffer.get(index).toInt() != 0) nonzero = true
        }
    }
}
