package com.shihab.diplay.media

import com.shihab.diplay.airplay.HeapPacket
import com.shihab.diplay.airplay.NativeMedia
import com.shihab.diplay.airplay.NativePacket
import java.nio.ByteBuffer
import java.nio.ReadOnlyBufferException
import org.junit.Assert.*
import org.junit.Test

/** Runs against real JNI on both the host JVM and ART. */
open class AacInputContract {
    @Test fun decoderInputMatchesTheOldCompositionWithoutChangingPacketOrBufferMargins() {
        for (native in listOf(false, true)) for (direct in listOf(false, true)) {
            for (size in listOf(0, 2, 1024, 8192)) for (rate in listOf(44100, 48000, -1)) {
                val raw = ByteArray(12 + size + 3) { (it * 73).toByte() }
                val packet = if (native) NativePacket(NativeMedia.packetWrap(raw, raw.size), raw.size) else HeapPacket(raw)
                try {
                    val total = size + MediaCodecSupport.ADTS_HEADER_BYTES
                    val storage = if (direct) ByteBuffer.allocateDirect(total + 11) else ByteBuffer.allocate(total + 11)
                    for (i in 0 until storage.capacity()) storage.put(i, 0x6d)
                    storage.position(3); storage.limit(storage.capacity() - 2)
                    val target = storage.slice().apply { position(2); limit(2 + total) }
                    val expected = ByteArray(storage.capacity()) { 0x6d }
                    val header = ByteArray(MediaCodecSupport.ADTS_HEADER_BYTES)
                    KotlinAacCodecReference.writeAdtsHeader(header, size, rate, 2)
                    header.copyInto(expected, 5)
                    raw.copyInto(expected, 12, 12, 12 + size)

                    NativeAac.writeDecoderInput(packet, 12, size, target, rate, 2)

                    assertEquals(2 + total, target.position())
                    assertEquals(2 + total, target.limit())
                    storage.clear()
                    assertArrayEquals(expected, ByteArray(storage.capacity()).also { storage.get(it) })
                    assertArrayEquals(raw, packet.toByteArray())
                } finally { packet.release() }
            }
        }
    }

    @Test fun rejectedWritesDoNotChangeTheTarget() {
        val raw = ByteArray(20) { it.toByte() }
        val packet = NativePacket(NativeMedia.packetWrap(raw, raw.size), raw.size)
        try {
            for (direct in listOf(false, true)) {
                val target = (if (direct) ByteBuffer.allocateDirect(32) else ByteBuffer.allocate(32))
                    .apply { put(ByteArray(32) { 0x6d }); position(3); limit(10) }
                assertThrows(IllegalArgumentException::class.java) {
                    NativeAac.writeDecoderInput(packet, 12, 8, target, 48000, 2)
                }
                target.limit(32)
                assertThrows(IllegalArgumentException::class.java) {
                    NativeAac.writeDecoderInput(packet, 13, 8, target, 48000, 2)
                }
                assertThrows(ReadOnlyBufferException::class.java) {
                    NativeAac.writeDecoderInput(packet, 12, 8, target.asReadOnlyBuffer(), 48000, 2)
                }
                assertEquals(3, target.position())
                target.clear()
                assertArrayEquals(ByteArray(32) { 0x6d }, ByteArray(32).also { target.get(it) })
            }
            val target = ByteBuffer.allocateDirect(16)
            assertEquals(-1, NativeAac.writeDecoderInputNative(packet.nativeHandle(), 12, 9, target, 0, 48000, 2))
            assertEquals(-1, NativeAac.writeDecoderInputNative(packet.nativeHandle(), 12, 8, target, 2, 48000, 2))
            assertEquals(-1, NativeAac.writeDecoderInputNative(0, 12, 8, target, 0, 48000, 2))
            assertArrayEquals(ByteArray(16), ByteArray(16).also { target.get(it) })
        } finally { packet.release() }
    }
}
