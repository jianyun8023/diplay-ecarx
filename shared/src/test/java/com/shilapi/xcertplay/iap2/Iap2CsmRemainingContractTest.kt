package com.shilapi.xcertplay.iap2

import com.shilapi.xcertplay.iap2.wire.Iap2CsmFramer
import org.junit.Assert.*
import org.junit.Test

/** Public-byte contracts of the original Kotlin CSM owner, independent of its encoder. */
class Iap2CsmRemainingContractTest {
    @Test fun garbageAndAnInvalidShortHeaderRetainTheNextMagicPrefix() {
        val framer = Iap2CsmFramer()
        assertTrue(framer.offer(byteArrayOf(0x41, 0x40)).isEmpty())
        val frames = framer.offer(byteArrayOf(0x40, 0, 5, 0x12, 0x34,
            0x40, 0x40, 0, 10, 0xbe.toByte(), 0xef.toByte(), 0, 0x40, 0x40, -1))
        assertEquals(1, frames.size)
        assertEquals(0xbeef, frames.single().messageId)
        assertArrayEquals(byteArrayOf(0, 0x40, 0x40, -1), frames.single().payload)
        assertTrue(framer.offer(byteArrayOf()).isEmpty())
    }

    @Test fun anIncompleteFrameOwnsHeaderLikeBytesInsideItsDeclaredPayload() {
        val bytes = byteArrayOf(0x40, 0x40, 0, 15, 0x12, 0x34,
            0x40, 0x40, 0, 6, 0, 1, 10, 11, 12)
        val framer = Iap2CsmFramer()
        assertTrue(framer.offer(bytes.copyOf(14)).isEmpty())
        val frames = framer.offer(bytes.copyOfRange(14, 15))
        assertEquals(listOf(0x1234), frames.map { it.messageId })
        assertArrayEquals(bytes.copyOfRange(6, 15), frames.single().payload)
    }

    @Test fun aBatchLargerThanTheReceiveBufferStillReturnsEveryCompleteFrame() {
        val frame = byteArrayOf(0x40, 0x40, 0, 6, 0, 1)
        val count = 10_924
        val batch = ByteArray(frame.size * count) { frame[it % frame.size] }
        val frames = Iap2CsmFramer().offer(batch)
        batch.fill(0)
        assertEquals(count, frames.size)
        assertTrue(frames.all { it.messageId == 1 && it.payload.isEmpty() })
    }

    @Test fun linkSplittingValidatesTheChunkSizeFirstAndReturnsIndependentCopies() {
        val invalid = assertThrows(IllegalArgumentException::class.java) {
            Iap2CsmFramer.splitForLink(byteArrayOf(), 0)
        }
        assertEquals("iAP2 link chunk size must be in 1..65525", invalid.message)
        val frame = byteArrayOf(0x40, 0x40, 0, 9, 0x12, 0x34, 1, 2, 3)
        val chunks = Iap2CsmFramer.splitForLink(frame, 4)
        assertEquals(listOf(4, 4, 1), chunks.map { it.size })
        assertArrayEquals(byteArrayOf(0x40, 0x40, 0, 9), chunks[0])
        assertArrayEquals(byteArrayOf(0x12, 0x34, 1, 2), chunks[1])
        assertArrayEquals(byteArrayOf(3), chunks[2])
        chunks[0].fill(0)
        assertEquals(0x40.toByte(), frame[0])
        frame.fill(0)
        assertEquals(0x12.toByte(), chunks[1][0])
    }

    @Test fun outerFrameAndSingleParameterHaveDifferentMaximumPayloads() {
        val frame = Iap2CsmFramer.encodeFrame(0xffff, ByteArray(65_529))
        assertEquals(65_535, frame.size)
        assertArrayEquals(byteArrayOf(0x40, 0x40, -1, -1, -1, -1), frame.copyOf(6))
        val param = Iap2CsmFramer.encodeParam(0xffff, ByteArray(65_531))
        assertEquals(65_535, param.size)
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1), param.copyOf(4))
        assertEquals("CSM payload exceeds 65529 bytes", assertThrows(IllegalArgumentException::class.java) {
            Iap2CsmFramer.encodeFrame(0xffff, ByteArray(65_530))
        }.message)
        assertEquals("CSM parameter payload exceeds 65531 bytes", assertThrows(IllegalArgumentException::class.java) {
            Iap2CsmFramer.encodeParam(0xffff, ByteArray(65_532))
        }.message)
    }
}
