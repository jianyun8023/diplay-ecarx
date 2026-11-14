package com.shihab.diplay.media

import com.shihab.diplay.airplay.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

/** Real Android socket FDs and production receiver/route; no Robolectric or test ingress API. */
class NativeAudioRouteDeviceTest {
    // Public deterministic fixture, never a stored pairing or production key.
    private val key = ByteArray(32) { (it + 1).toByte() }

    private fun wire(sample: Int, sequence: Int, payload: ByteArray): Pair<ByteArray, ByteArray> {
        val header = byteArrayOf(0x80.toByte(), 0x60, (sequence ushr 8).toByte(), sequence.toByte(),
            (sample ushr 24).toByte(), (sample ushr 16).toByte(), (sample ushr 8).toByte(), sample.toByte(), 1, 2, 3, 4)
        val shortNonce = ByteArray(8).also { it[0] = sequence.toByte() }
        val nonce = ByteArray(12).also { shortNonce.copyInto(it, 4) }
        return (header + BouncyCryptoOracle.chachaSeal(key, nonce, payload, header.copyOfRange(4, 12)) + shortNonce) to
            (header + payload)
    }

    private fun send(port: Int, bytes: ByteArray) = DatagramSocket().use {
        it.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), port))
    }

    @Test fun firstStartBindsTheQueueAndRtpBypassesThePerPacketCallback() {
        assertTrue(NativeMedia.available)
        val queue = NativeAudioQueue.create(48_000, true)
        val reader = checkNotNull(queue.consumer())
        val stream = AudioStream(key, 100, clockRate = 48_000)
        val callbacks = AtomicInteger()
        val startedSample = AtomicInteger()
        val sample = 0xfffffff0.toInt()
        try {
            val ports = stream.listen(object : AudioStream.Listener {
                override fun onStarted(firstSample: Int) {
                    startedSample.set(firstSample)
                    stream.route.setQueue(queue::retain)
                }
                override fun onPacket(rtp: MediaPacket, sample: Int) {
                    callbacks.incrementAndGet()
                    rtp.release()
                }
            })
            val (sealed, plain) = wire(sample, 1, byteArrayOf(10, 20, 30, 40))
            send(ports.first, sealed)
            val info = LongArray(3)
            val packet = reader.poll(info, 5_000)
            assertNotNull("Native queue did not receive decrypted RTP", packet)
            try { assertArrayEquals(plain, packet!!.toByteArray()) } finally { packet?.release() }
            assertEquals(sample, startedSample.get())
            assertEquals(sample, info[0].toInt())
            assertEquals(plain.size.toLong(), info[2])
            assertEquals("Direct queue reception must not allocate a JVM packet callback", 0, callbacks.get())
            val baseline = stream.route.feedback(sample.toLong() and 0xffff_ffffL, 1_000)!!
            assertArrayEquals(longArrayOf(0xfffffff0L, 0, 0), baseline)
            val ahead = (sample.toLong() + 4801) and 0xffff_ffffL
            assertArrayEquals(longArrayOf(0xfffffff0L, 100, 1), stream.route.feedback(ahead, 2_000))
            assertNull(stream.route.feedback(ahead, 3_000))
        } finally {
            stream.close()
            queue.close()
            reader.close()
        }
    }

    @Test fun failedDirectRendererFallsBackOnceThenRebindsTheReplacementQueue() {
        val failWorker = AtomicBoolean(false)
        val sink = AndroidMediaSink(debugLogsEnabled = {
            if (failWorker.getAndSet(false)) throw IllegalStateException("injected worker failure")
            false
        })
        val id = AudioStreamId(100, "media")
        val format = AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 100, "media")
        val stream = AudioStream(key, 100, clockRate = 48_000)
        val started = CountDownLatch(1)
        val recovered = CountDownLatch(1)
        val callbacks = AtomicInteger()
        try {
            sink.onNativeAudioRoute(id, format, stream.route)
            val ports = stream.listen(object : AudioStream.Listener {
                override fun onStarted(firstSample: Int) {
                    sink.onAudioStarted(id, format, firstSample)
                    started.countDown()
                }
                override fun onPacket(rtp: MediaPacket, sample: Int) {
                    callbacks.incrementAndGet()
                    sink.onAudioPacket(id, format, rtp, sample)
                    recovered.countDown()
                }
            })
            send(ports.first, wire(0, 1, ByteArray(1920)).first)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val original = renderer(sink, id)
            // Prove the first packet took the native direct route before requesting failure.
            val originalQueue = AudioRenderer::class.java.getDeclaredField("nativeQueue")
                .apply { isAccessible = true }.get(original) as NativeAudioQueue
            val firstStats = LongArray(5)
            val firstDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            do {
                originalQueue.stats(firstStats, false)
                if (firstStats[0] >= 1) break
                Thread.sleep(10)
            } while (System.nanoTime() < firstDeadline)
            assertEquals(1L, firstStats[0])
            // The callback was installed before Thread.start(); only the atomic trigger changes.
            // Failure occurs at the ordinary five-second diagnostic tick.
            failWorker.set(true)
            worker(original).join(10_000)
            assertFalse("Configured failure must have run", failWorker.get())
            assertFalse(worker(original).isAlive)
            assertTrue(original.isTerminal)
            assertEquals(0, callbacks.get())

            send(ports.first, wire(480, 2, ByteArray(1920)).first)
            assertTrue("Next native receiver packet must return to the sink", recovered.await(5, TimeUnit.SECONDS))
            val replacement = renderer(sink, id)
            assertNotSame(original, replacement)
            assertTrue("Replacement must actually start", worker(replacement).isAlive)
            // A delayed exit notification from the old worker cannot disconnect the new queue.
            original.onExit(original)
            send(ports.first, wire(960, 3, ByteArray(1920)).first)
            val queue = AudioRenderer::class.java.getDeclaredField("nativeQueue").apply { isAccessible = true }
                .get(replacement) as NativeAudioQueue
            val stats = LongArray(5)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            do {
                queue.stats(stats, false)
                if (stats[0] >= 2) break
                Thread.sleep(10)
            } while (System.nanoTime() < deadline)
            assertEquals("Both recovery and next direct packet reached the new queue", 2L, stats[0])
            assertEquals("Recovered direct route must bypass JVM packet callbacks again", 1, callbacks.get())
            sink.close()
            original.onExit(original)
            sink.onAudioStarted(id, format, 1440)
            sink.onAudioPacket(id, format, HeapPacket(ByteArray(12)), 1440)
            assertTrue(renderers(sink).isEmpty())
        } finally {
            stream.close()
            sink.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderers(sink: AndroidMediaSink): Map<AudioStreamId, AudioRenderer> =
        AndroidMediaSink::class.java.getDeclaredField("audioRenderers").apply { isAccessible = true }
            .get(sink) as Map<AudioStreamId, AudioRenderer>

    private fun renderer(sink: AndroidMediaSink, id: AudioStreamId): AudioRenderer = checkNotNull(renderers(sink)[id])
    private fun worker(renderer: AudioRenderer): Thread =
        AudioRenderer::class.java.getDeclaredField("thread").apply { isAccessible = true }.get(renderer) as Thread

    @Test fun nativeWiretapPreservesRejectedDatagramsAndDisablesDirectDelivery() {
        assertTrue(NativeMedia.available)
        val queue = NativeAudioQueue.create(48_000, false)
        val reader = checkNotNull(queue.consumer())
        val stream = AudioStream(key, 100, preferNative = false)
        data class Captured(val wire: ByteArray, val opened: ByteArray?, val sample: Int?, val error: Throwable?)
        val captured = ConcurrentLinkedQueue<Captured>()
        val order = ConcurrentLinkedQueue<String>()
        val wireReady = CountDownLatch(3)
        val packetReady = CountDownLatch(1)
        try {
            val ports = stream.listen(object : AudioStream.Listener {
                override fun onStarted(firstSample: Int) {
                    order.add("start")
                    stream.route.setQueue(queue::retain)
                }
                override fun onPacket(wire: ByteArray, rtp: ByteArray?, sample: Int?, error: Throwable?) {
                    order.add("wire")
                    captured.add(Captured(wire, rtp, sample, error))
                    wireReady.countDown()
                }
                override fun onPacket(rtp: MediaPacket, sample: Int) {
                    order.add("packet")
                    rtp.release()
                    packetReady.countDown()
                }
            })
            val (valid, plain) = wire(0x12345678, 1, byteArrayOf(8, 7, 6))
            val short = byteArrayOf(1, 2, 3)
            val bad = wire(0x12345679, 2, byteArrayOf(5, 4, 3)).first.also { it[12] = (it[12].toInt() xor 1).toByte() }
            // A single sender preserves local datagram order for the capture-order assertion.
            DatagramSocket().use { sender ->
                for (bytes in listOf(valid, short, bad)) {
                    sender.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), ports.first))
                }
            }
            assertTrue(wireReady.await(5, TimeUnit.SECONDS))
            assertTrue(packetReady.await(5, TimeUnit.SECONDS))
            val rows = captured.toList()
            assertEquals(3, rows.size)
            assertArrayEquals(valid, rows[0].wire)
            assertArrayEquals(plain, rows[0].opened)
            assertEquals(0x12345678, rows[0].sample)
            assertNull(rows[0].error)
            assertArrayEquals(short, rows[1].wire)
            assertNull(rows[1].opened)
            assertNull(rows[1].sample)
            assertTrue(rows[1].error is java.io.IOException)
            assertArrayEquals(bad, rows[2].wire)
            assertNull(rows[2].opened)
            assertEquals(0x12345679, rows[2].sample)
            assertTrue(rows[2].error is javax.crypto.AEADBadTagException)
            assertEquals(listOf("wire", "start", "packet"), order.toList().take(3))
            assertNull("Capture must keep packet callbacks even when a queue was offered", reader.poll(LongArray(3), 0))
        } finally {
            stream.close()
            queue.close()
            reader.close()
        }
    }

    @Test fun closingTheProducerWakesTheRetainedConsumerWithoutFreeingItsReference() {
        assertTrue(NativeMedia.available)
        val queue = NativeAudioQueue.create(48_000, true)
        val reader = checkNotNull(queue.consumer())
        val entered = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "audio-close-contract").apply { isDaemon = true } }
        try {
            val result = worker.submit<MediaPacket?> {
                reader.use { entered.countDown(); it.poll(LongArray(3), 60_000) }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            queue.close()
            assertNull(result.get(5, TimeUnit.SECONDS))
            assertEquals(0L, queue.retain())
        } finally {
            queue.close()
            worker.shutdownNow()
        }
    }

    @Test fun formatBitsKeepTheOriginalPriorityExactPcmKeysAndUnknownDefaults() {
        assertTrue(NativeMedia.available)
        val bits = listOf(0L, -1L, Long.MIN_VALUE, 0x4, 0x8, 0x10, 0x20, 0x40, 0x80,
            0x100, 0x200, 0x400, 0x800, 0x4000, 0x8000, 0x400000, 0x800000,
            0x10000000, 0x20000000, 0x40000000, 0x70000000, 0x10800000, 0xc)
        for (value in bits) {
            assertEquals("audioFormat=$value", KotlinAudioStreamCodec.fromFormatBits(value, 100, "fixture"),
                AudioStreamCodec.fromFormatBits(value, 100, "fixture"))
        }
    }
}
