package com.shihab.diplay.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2FileTransferReceiverTest {
    @Test
    fun interleavedReplacementEvictionAndFailuresMatchIndependentKotlinReference() {
        val receiver = Iap2FileTransferReceiver(4)
        val reference = KotlinIap2FileTransferReceiver(4)
        val transcript = listOf(
            setup(1, 4), setup(2, 4), setup(3, 4), setup(4, 4),
            data(1, 0x80, byteArrayOf(9)), setup(5, 4), data(1, 0x40, byteArrayOf(1, 2, 3)),
            setup(2, 4), setup(6, 4), data(3, 0x40, byteArrayOf(1, 2, 3, 4)),
            data(4, 0, byteArrayOf(1, 2, 3, 4)), data(5, 0x40, byteArrayOf(1)),
            data(2, 2, byteArrayOf()), data(2, 2, byteArrayOf()),
            setup(6, 3, type = 7), data(6, 0x40, byteArrayOf(1, 2, 3)),
            setup(7, 5), setup(8, -1), setup(9, 0), byteArrayOf(8, 4), byteArrayOf(4),
            byteArrayOf(9, 15),
        )
        for ((index, frame) in transcript.withIndex()) {
            val expected = reference.accept(frame)
            val actual = receiver.accept(frame)
            assertEquals("frame $index", expected.replies.size, actual.replies.size)
            expected.replies.zip(actual.replies).forEach { (a, b) -> assertArrayEquals(a, b) }
            assertEquals("frame $index", expected.completed?.id, actual.completed?.id)
            assertArrayEquals(expected.completed?.bytes, actual.completed?.bytes)
        }
        receiver.clear()
        reference.clear()
        assertNull(receiver.accept(data(2, 0x40, byteArrayOf(1))).completed)
        assertNull(reference.accept(data(2, 0x40, byteArrayOf(1))).completed)
    }

    @Test
    fun inputAndCompletedBuffersDoNotAliasLaterTransfersAndClearAllowsReuse() {
        val receiver = Iap2FileTransferReceiver(4)
        receiver.accept(setup(1, 4))
        val first = data(1, 0x80, byteArrayOf(1, 2))
        receiver.accept(first)
        first.fill(99)
        val result = receiver.accept(data(1, 0x40, byteArrayOf(3, 4))).completed!!
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), result.bytes)
        result.bytes.fill(88)
        receiver.accept(setup(1, 4))
        receiver.clear()
        assertNull(receiver.accept(data(1, 0x40, byteArrayOf(1, 2, 3, 4))).completed)
        receiver.accept(setup(1, 2))
        assertArrayEquals(byteArrayOf(5, 6), receiver.accept(data(1, 0, byteArrayOf(5, 6))).completed!!.bytes)
    }

    @Test
    fun acceptsAndReassemblesNowPlayingArtwork() {
        val receiver = Iap2FileTransferReceiver()

        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x01), receiver.accept(setup(0x81, 5)).replies.single())
        assertNull(receiver.accept(data(0x81, 0x80, byteArrayOf(1, 2, 3))).completed)
        val completed = receiver.accept(data(0x81, 0x40, byteArrayOf(4, 5)))

        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x05), completed.replies.single())
        assertEquals(0x81, completed.completed?.id)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), completed.completed?.bytes)
    }

    @Test
    fun rejectsOversizedUnsupportedAndTruncatedTransfers() {
        val receiver = Iap2FileTransferReceiver(maximumArtworkBytes = 4)

        val oversized = receiver.accept(setup(1, 5))
        assertArrayEquals(byteArrayOf(1, 2), oversized.replies.single())
        assertEquals(0, oversized.completed?.bytes?.size)
        val unsupported = receiver.accept(setup(2, 3, type = 7))
        assertArrayEquals(byteArrayOf(2, 2), unsupported.replies.single())
        assertNull(unsupported.completed)
        assertNull(receiver.accept(byteArrayOf(4, 4)).completed)
        receiver.accept(setup(3, 4))
        val truncated = receiver.accept(data(3, 0x40, byteArrayOf(1, 2)))
        assertArrayEquals(byteArrayOf(3, 2), truncated.replies.single())
        assertEquals(3, truncated.completed?.id)
        assertEquals(0, truncated.completed?.bytes?.size)
    }

    @Test
    fun aSenderCancelClearsOnlyAPendingArtworkTransfer() {
        val receiver = Iap2FileTransferReceiver()
        receiver.accept(setup(5, 4))

        val cancelled = receiver.accept(data(5, 0x02, ByteArray(0)))
        assertTrue(cancelled.replies.isEmpty())
        assertEquals(5, cancelled.completed?.id)
        assertEquals(0, cancelled.completed?.bytes?.size)
        assertNull(receiver.accept(data(5, 0x02, ByteArray(0))).completed)
    }

    @Test
    fun zeroSizedArtworkClearsTheCurrentImage() {
        val outcome = Iap2FileTransferReceiver().accept(setup(0x80, 0))

        assertEquals(2, outcome.replies.size)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 1), outcome.replies[0])
        assertArrayEquals(byteArrayOf(0x80.toByte(), 5), outcome.replies[1])
        assertEquals(0, outcome.completed?.bytes?.size)
    }

    private fun setup(id: Int, size: Int, type: Int = 2): ByteArray = ByteArray(12).apply {
        this[0] = id.toByte()
        this[1] = 4
        var remaining = size.toLong()
        for (index in 9 downTo 2) {
            this[index] = remaining.toByte()
            remaining = remaining ushr 8
        }
        this[10] = (type ushr 8).toByte()
        this[11] = type.toByte()
    }

    private fun data(id: Int, flags: Int, bytes: ByteArray): ByteArray =
        byteArrayOf(id.toByte(), flags.toByte()) + bytes
}
