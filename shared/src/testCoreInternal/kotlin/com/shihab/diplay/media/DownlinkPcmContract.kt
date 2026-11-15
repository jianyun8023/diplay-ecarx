package com.shihab.diplay.media

import com.shihab.diplay.airplay.AudioCodecKind
import com.shihab.diplay.airplay.HeapPacket
import com.shihab.diplay.airplay.NativeMedia
import java.nio.ByteBuffer
import org.junit.Assert.*

internal object DownlinkPcmContract {
    fun lpcmBufferAndSynchronousBorrow() {
        NativePcm.create(48_000, 2, false, AudioCodecKind.LPCM).use { pcm ->
            assertNull(pcm.configuration())
            val host = Host(pcm)
            val payload = ByteArray(100_001) { it.toByte() }
            pcm.feed(HeapPacket(ByteArray(12) + payload), 0, null, host)
            assertEquals(100_000, host.written.single().size)
            for (i in 0 until 100_000 step 2) {
                assertEquals(payload[i + 1], host.written[0][i]); assertEquals(payload[i], host.written[0][i + 1])
            }
            pcm.feed(HeapPacket(ByteArray(12) + byteArrayOf(0x12, 0x34)), -1, null, host)
            assertArrayEquals(byteArrayOf(0x34, 0x12), host.written[1])
            assertTrue(host.events.isEmpty())
        }
    }

    fun codecPlansAndOpusPreSkip() {
        NativePcm.create(44_100, 2, false, AudioCodecKind.AAC_LC).use { pcm ->
            val config = pcm.configuration()!!
            assertEquals("audio/mp4a-latm", config.mime); assertTrue(config.adts)
            assertArrayEquals(byteArrayOf(0x12, 0x10), config.csd.single())
            val host = Host(pcm)
            pcm.feed(HeapPacket(ByteArray(12)), 0, null, host)
            assertTrue(host.events.isEmpty())
            pcm.feed(HeapPacket(ByteArray(12) + byteArrayOf(1)), -1, null, host)
            assertEquals(listOf(listOf(0, 1, 0), listOf(1, 1, 0)), host.events)
        }
        NativePcm.create(44_100, 1, false, AudioCodecKind.OPUS).use { pcm ->
            val config = pcm.configuration()!!
            assertEquals("audio/opus", config.mime); assertFalse(config.adts)
            assertEquals(3, config.csd.size)
            assertArrayEquals(byteArrayOf(0x38, 1, 0x44, 0xac.toByte(), 0, 0), config.csd[0].copyOfRange(10, 16))
        }
        NativePcm.create(48_000, 1, false, AudioCodecKind.OPUS).use { pcm ->
            assertNull(pcm.configuration())
            val host = Host(pcm)
            pcm.feed(HeapPacket(ByteArray(15)), 0, null, host)
            assertEquals(listOf(listOf(3, 3, 0)), host.events)
            val encoder = NativeMedia.opusEncoderCreate(64_000)
            check(encoder != 0L)
            try {
                val encoded = ByteArray(4096)
                val bytes = NativeMedia.opusEncode(encoder, ByteArray(1920) { (it % 71).toByte() }, encoded)
                assertTrue(bytes >= 4)
                val packet = HeapPacket(ByteArray(12) + encoded.copyOf(bytes))
                pcm.feed(packet, 0, null, host)
                pcm.feed(packet, 960, null, host)
                assertEquals(listOf(1296, 1920), host.written.map { it.size })
                pcm.feed(HeapPacket(ByteArray(12) + byteArrayOf(3, 0, 0, 0)), 1920, null, host)
                assertEquals(listOf(4, 4, -1), host.events.last())
                assertEquals(2, host.written.size)
            } finally { NativeMedia.opusEncoderDestroy(encoder) }
        }
        val closed = NativePcm.create(48_000, 2, false, AudioCodecKind.LPCM)
        closed.close(); closed.close()
        assertThrows(IllegalStateException::class.java) { closed.configuration() }
    }

    private class Host(val pcm: NativePcm) {
        val events = mutableListOf<List<Int>>()
        val written = mutableListOf<ByteArray>()
        @Suppress("unused") private fun pcmInputResult(event: Int, bytes: Int, result: Int) { events += listOf(event, bytes, result) }
        @Suppress("unused") private fun writePcm(buffer: ByteBuffer, length: Int) {
            assertTrue(buffer.isDirect)
            // Reenter the same owner for processing, as the actual AudioRenderer does.
            pcm.process(buffer, length, 0, 0, 64)
            written += ByteArray(length).also { buffer.duplicate().get(it) }
        }
    }
}
