package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class IapStreamQueueContractTest {
    private class Queues {
        val native = NativeIapStreamQueue.create()
        val reference = KotlinIapStreamQueue()
        fun offer(bytes: ByteArray): Int {
            val expected = reference.offer(bytes)
            assertEquals(expected, NativeIapStreamQueue.offer(native, bytes)); status()
            return expected
        }
        fun take(maximum: Int): ByteArray? {
            val expected = reference.take(maximum)
            assertArrayEquals(expected, NativeIapStreamQueue.take(native, maximum)); status()
            return expected
        }
        fun status() { assertEquals(reference.status(), NativeIapStreamQueue.status(native)) }
        fun fail() { assertEquals(reference.fail(), NativeIapStreamQueue.fail(native)); status() }
        fun close() { assertEquals(reference.close(), NativeIapStreamQueue.close(native)); status() }
    }

    @Test fun inputIsCopiedAndSplitTailRemainsBeforeLaterChunks() {
        val q = Queues(); val input = byteArrayOf(1, 2, 3, 4, 5)
        q.offer(input); input.fill(99)
        q.offer(byteArrayOf(6, 7))
        assertArrayEquals(byteArrayOf(1, 2), q.take(2))
        assertArrayEquals(byteArrayOf(3, 4, 5), q.take(100))
        assertArrayEquals(byteArrayOf(6, 7), q.take(100))
        assertNull(q.take(1))
    }

    @Test fun exactLimitRetainsQueuedBytesAcrossOverflowAndClose() {
        val q = Queues(); val full = ByteArray(1_048_576) { (it % 251).toByte() }
        assertEquals(1, q.offer(full)); assertEquals(2, q.offer(byteArrayOf(9)))
        q.fail(); q.close(); q.close()
        assertEquals(0, q.offer(byteArrayOf(8)))
        assertArrayEquals(full.copyOf(17), q.take(17))
        assertArrayEquals(full.copyOfRange(17, full.size), q.take(full.size))
        assertNull(q.take(1)); assertEquals(3, NativeIapStreamQueue.status(q.native))
    }

    @Test fun ringGrowthAndWrapKeepSingleChunkBoundaries() {
        val q = Queues()
        repeat(257) { q.offer(byteArrayOf(it.toByte())) }
        repeat(129) { q.take(1) }
        repeat(300) { q.offer(byteArrayOf(it.toByte(), (it + 1).toByte())) }
        repeat(428) { q.take(9) }
        assertNull(q.take(1))
    }

    @Test fun emptyLateAndRepeatedTerminationDoNotChangeQueueState() {
        val q = Queues(); assertEquals(0, q.offer(byteArrayOf()))
        q.offer(byteArrayOf(1)); q.close(); q.fail(); q.close()
        assertEquals(0, q.offer(byteArrayOf(2)))
        assertArrayEquals(byteArrayOf(1), q.take(1)); assertNull(q.take(1))
        val failed = Queues(); failed.offer(byteArrayOf(3)); failed.fail(); failed.fail(); failed.close()
        assertArrayEquals(byteArrayOf(3), failed.take(4)); assertNull(failed.take(1))
    }
}
