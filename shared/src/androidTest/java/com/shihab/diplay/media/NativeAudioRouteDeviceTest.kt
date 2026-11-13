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
