package com.shihab.diplay.media

import android.media.MediaCodec
import com.shihab.diplay.airplay.NativeMedia
import com.shihab.diplay.airplay.NativePacket
import java.nio.ByteBuffer
import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaCodec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [AacDecoderInputTest.InputCodec::class])
class AacDecoderInputTest {
    @Before fun reset() {
        InputCodec.indices = ArrayDeque(listOf(3))
        InputCodec.buffer = ByteBuffer.allocateDirect(32)
        InputCodec.queued.clear()
        InputCodec.dequeues = 0
        InputCodec.failure = null
    }

    @Test fun drainRetryAssemblyAndSubmissionPreserveBytesAndTimestamp() {
        InputCodec.indices = ArrayDeque(listOf(-1, 3))
        val host = Host()
        feed(host)
        assertEquals(3, host.drains)
        assertEquals(listOf(2 to 15), host.results)
        assertEquals(listOf(3L, 0L, 15L, 987_654_321L, 0L), InputCodec.queued.single())
        val expected = ByteArray(15)
        KotlinAacCodecReference.writeAdtsHeader(expected, 8, 48_000, 2)
        ByteArray(20) { it.toByte() }.copyInto(expected, 7, 12, 20)
        val buffer = InputCodec.buffer!!.duplicate().apply { flip() }
        assertArrayEquals(expected, ByteArray(buffer.remaining()).also { buffer.get(it) })
    }

    @Test fun insufficientCapacityReturnsTheInputSlotEmptyAndStillDrainsOutput() {
        InputCodec.buffer = ByteBuffer.allocate(10)
        val host = Host()
        feed(host)
        assertEquals(listOf(3L, 0L, 0L, 0L, 0L), InputCodec.queued.single())
        assertEquals(listOf(3 to 15), host.results)
        assertEquals(2, host.drains)
    }

    @Test fun stoppedWorkerDoesNotDequeueAndMissingSdkBufferDoesNotQueue() {
        val stopped = Host().apply { running = false }
        feed(stopped)
        assertEquals(listOf(1 to 15), stopped.results)
        assertEquals(0, InputCodec.dequeues)
        assertTrue(InputCodec.queued.isEmpty())
        InputCodec.buffer = null
        val missing = Host()
        feed(missing)
        assertEquals(1, missing.drains)
        assertTrue(missing.results.isEmpty())
        assertTrue(InputCodec.queued.isEmpty())
    }

    @Test fun sdkFailurePropagatesBeforeSuccessfulQueueDiagnostics() {
        val failure = IllegalStateException("queue failed")
        InputCodec.failure = failure
        val host = Host()
        assertSame(failure, assertThrows(IllegalStateException::class.java) { feed(host) })
        assertTrue(host.results.isEmpty())
        assertEquals(1, host.drains)
    }

    private fun feed(host: Host) {
        val codec = MediaCodec.createDecoderByType("audio/mp4a-latm")
        val bytes = ByteArray(20) { it.toByte() }
        val packet = NativePacket(NativeMedia.packetWrap(bytes, bytes.size), bytes.size)
        try {
            NativeAac.feedDecoder(packet, 12, 8, 48_000, 2, 987_654_321L, codec, host)
            assertArrayEquals(bytes, packet.toByteArray())
        } finally {
            packet.release()
            codec.release()
        }
    }

    class Host {
        var running = true
        var drains = 0
        val results = mutableListOf<Pair<Int, Int>>()
        fun aacInputRunning(): Boolean = running
        fun drainCodec(codec: MediaCodec) { drains++ }
        fun decoderInputResult(result: Int, length: Int) { results += result to length }
    }

    @Implements(MediaCodec::class)
    class InputCodec : ShadowMediaCodec() {
        @Implementation fun dequeueInputBuffer(timeout: Long): Int {
            assertEquals(10_000L, timeout)
            dequeues++
            return indices.removeFirst()
        }
        @Implementation fun getInputBuffer(index: Int): ByteBuffer? = buffer
        @Implementation fun queueInputBuffer(index: Int, offset: Int, size: Int, time: Long, flags: Int) {
            failure?.let { throw it }
            queued += listOf(index.toLong(), offset.toLong(), size.toLong(), time, flags.toLong())
        }
        companion object {
            var indices = ArrayDeque<Int>()
            var buffer: ByteBuffer? = null
            val queued = mutableListOf<List<Long>>()
            var dequeues = 0
            var failure: RuntimeException? = null
        }
    }
}
