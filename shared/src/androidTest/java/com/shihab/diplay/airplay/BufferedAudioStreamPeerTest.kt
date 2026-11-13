package com.shihab.diplay.airplay

import androidx.test.filters.SdkSuppress
import androidx.test.runner.AndroidJUnit4
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android descriptor/receiver contracts; this suite must never use a host receiver substitute. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
class BufferedAudioStreamPeerTest {
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val format = AudioFormat(AudioCodecKind.AAC_LC, 48_000, 2, BufferedAudioStream.STREAM_TYPE, "media")

    @Test fun pauseWaitsForAnInFlightSinkDelivery() = controlWaitsForDelivery { stream ->
        stream.setRate(null, 0, BigInteger.ZERO)
    }

    @Test fun flushWaitsForAnInFlightSinkDelivery() = controlWaitsForDelivery { stream ->
        stream.flush(2048L)
    }

    @Test fun closeWaitsForAnInFlightSinkDelivery() = controlWaitsForDelivery { stream ->
        stream.close()
    }

    @Test
    fun pauseAndResumeRetainFramesAlreadyFedAheadOfTheAudiblePosition() {
        val sink = RecordingSink()
        val stream = BufferedAudioStream(key, format, sink, nanoTime = { 0L })
        try {
            stream.start()
            Socket(InetAddress.getLoopbackAddress(), stream.port).use { socket ->
                val out = DataOutputStream(socket.getOutputStream())
                repeat(100) { i ->
                    val body = seal(i, 1000L + i * 1024L, byteArrayOf(0x21))
                    out.writeShort(body.size + 2); out.write(body)
                }
                out.flush()
                stream.setRate(1000L, 1, BigInteger.ZERO)
                waitFor { sink.rtp.size >= 47 }
                val paused = stream.setRate(null, 0, BigInteger.ZERO)!!
                assertEquals(1000L, paused["rtpTime"])
                stream.setRate(paused["rtpTime"] as Long, 1, BigInteger.ZERO)
                waitFor { sink.started.size == 2 }
                assertEquals("Resume skipped the one-second renderer preload", 1000, sink.started.last())
            }
        } finally { stream.close() }
    }

    @Test
    fun malformedLengthClosesTheTcpStreamWithoutReadingANewPrefix() {
        val stream = BufferedAudioStream(key, format, RecordingSink())
        try {
            stream.start()
            Socket(InetAddress.getLoopbackAddress(), stream.port).use { socket ->
                socket.soTimeout = 3000
                DataOutputStream(socket.getOutputStream()).apply { writeShort(1); flush() }
                assertEquals(-1, socket.getInputStream().read())
                assertNull(stream.setRate(0L, 1, BigInteger.ZERO))
            }
        } finally { stream.close() }
    }

    @Test
    fun splitAndCoalescedTcpFramesKeepPayloadAndOrdering() {
        val packets = CopyOnWriteArrayList<ByteArray>()
        val sink = object : MediaSink {
            override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) { packets += rtp }
        }
        val stream = BufferedAudioStream(key, format, sink)
        try {
            stream.start()
            Socket(InetAddress.getLoopbackAddress(), stream.port).use { socket ->
                val out = DataOutputStream(socket.getOutputStream())
                val first = seal(1, 1024L, byteArrayOf(0x21, 1, 2))
                out.writeByte((first.size + 2) shr 8); out.flush()
                out.writeByte(first.size + 2)
                out.write(first, 0, 5); out.flush()
                out.write(first, 5, first.size - 5)
                val second = seal(2, 2048L, byteArrayOf(0x21, 3, 4))
                out.writeShort(second.size + 2); out.write(second); out.flush()
                stream.setRate(1024L, 1, BigInteger.ZERO)
                waitFor { packets.size == 2 }
                assertArrayEquals(byteArrayOf(0x21, 1, 2), packets[0].copyOfRange(12, packets[0].size))
                assertArrayEquals(byteArrayOf(0x21, 3, 4), packets[1].copyOfRange(12, packets[1].size))
            }
        } finally { stream.close() }
    }

    @Test
    fun closeBeforeAcceptAndAfterAcceptCannotLeaveAReadableClientOrRestartPlayback() {
        val unconnected = BufferedAudioStream(key, format, RecordingSink())
        unconnected.start()
        unconnected.close()
        assertNull(unconnected.setRate(0L, 1, BigInteger.ZERO))
        unconnected.start()
        val connected = BufferedAudioStream(key, format, RecordingSink())
        try {
            connected.start()
            Socket(InetAddress.getLoopbackAddress(), connected.port).use { socket ->
                socket.soTimeout = 3000
                connected.close()
                try {
                    assertEquals(-1, socket.getInputStream().read())
                } catch (error: java.net.SocketException) {
                    // Closing a listener before its worker claims an already-connected client
                    // resets that connection rather than sending FIN. Both cancel the reader.
                    assertTrue(error.message.orEmpty().contains("reset", ignoreCase = true))
                }
            }
        } finally { connected.close() }
    }

    @Test
    fun aForeignSenderCannotClaimTheSingleTcpStream() {
        val stream = BufferedAudioStream(key, format, RecordingSink(),
            expectedPeer = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 2)))
        try {
            stream.start()
            repeat(2) {
                Socket(InetAddress.getLoopbackAddress(), stream.port).use { socket ->
                    socket.soTimeout = 3000
                    assertEquals(-1, socket.getInputStream().read())
                }
            }
            assertNull(stream.anchor())
        } finally { stream.close() }
    }

    @Test
    fun setRateStartsPlaybackFromItsTimeAndPausesAndFlushesStopTheOutput() {
        val sink = RecordingSink()
        val stream = BufferedAudioStream(key, format, sink)
        try {
            stream.start()
            assertNull(stream.anchor())
            Socket(InetAddress.getLoopbackAddress(), stream.port).use { socket ->
                val out = DataOutputStream(socket.getOutputStream())
                for (i in 0 until 4) {
                    val body = seal(sequence = i, timestamp = 1_000L + i * 1_024L, payload = byteArrayOf(0x21, i.toByte()))
                    out.writeShort(body.size + 2)
                    out.write(body)
                }
                out.flush()
                // Nothing plays before SETRATE.
                Thread.sleep(150)
                assertTrue(sink.rtp.isEmpty())

                val now = BigInteger.valueOf(2_208_988_800L + 100L).shiftLeft(32)
                val anchor = stream.setRate(rtpTime = 2_024L, newRate = 1, nowNtp = now)!!
                assertEquals(2_024L, anchor["rtpTime"])
                assertEquals(100L, anchor["networkTimeSecs"])
                assertEquals(anchor, stream.anchor())
                waitFor { sink.rtp.size == 3 }
                // The frame before rtpTime was dropped; playback starts at it.
                assertEquals(listOf(2_024L, 3_048L, 4_072L), sink.rtp.map { it.toLong() and 0xffff_ffffL })
                assertEquals(1, sink.started.size)

                val paused = stream.setRate(rtpTime = null, newRate = 0, nowNtp = now)!!
                assertEquals(0, paused["rate"])
                assertTrue(sink.stopped >= 1)
                val feedback = stream.feedback(now, 42L)
                assertEquals(103, feedback["type"])
                assertEquals(paused["rtpTime"], feedback["sampleTime"])

                val stopsBeforeFlush = sink.stopped
                stream.flush(untilTimestamp = 9_000L)
                assertTrue(sink.stopped >= stopsBeforeFlush)
            }
        } finally {
            stream.close()
        }
    }

    private fun controlWaitsForDelivery(control: (BufferedAudioStream) -> Unit) {
        val delivering = CountDownLatch(1)
        val release = CountDownLatch(1)
        val startedControl = CountDownLatch(1)
        val completedControl = CountDownLatch(1)
        val sink = object : MediaSink {
            override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
                delivering.countDown()
                assertTrue(release.await(3, TimeUnit.SECONDS))
            }
        }
        val stream = BufferedAudioStream(key, format, sink)
        val executor = Executors.newSingleThreadExecutor()
        try {
            stream.start()
            Socket(InetAddress.getLoopbackAddress(), stream.port).use { socket ->
                val body = seal(0, 1024L, byteArrayOf(0x21))
                DataOutputStream(socket.getOutputStream()).apply {
                    writeShort(body.size + 2); write(body); flush()
                }
                stream.setRate(1024L, 1, BigInteger.ZERO)
                assertTrue(delivering.await(3, TimeUnit.SECONDS))
                val action = executor.submit {
                    startedControl.countDown()
                    control(stream)
                    completedControl.countDown()
                }
                assertTrue(startedControl.await(3, TimeUnit.SECONDS))
                assertFalse("Control returned while a stale sink delivery was still in flight", completedControl.await(150, TimeUnit.MILLISECONDS))
                release.countDown()
                action.get(3, TimeUnit.SECONDS)
            }
        } finally {
            release.countDown()
            stream.close()
            executor.shutdownNow()
        }
    }

    private fun seal(sequence: Int, timestamp: Long, payload: ByteArray): ByteArray {
        val header = ByteArray(12)
        header[0] = 0x80.toByte()
        header[1] = 0x60
        header[2] = (sequence shr 8).toByte(); header[3] = sequence.toByte()
        for (i in 0 until 4) header[4 + i] = (timestamp shr (24 - 8 * i)).toByte()
        val nonceTail = ByteArray(8) { (sequence + it).toByte() }
        val nonce = ByteArray(12).also { nonceTail.copyInto(it, 4) }
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, payload, header.copyOfRange(4, 12))
        return header + sealed + nonceTail
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("condition not met in time", condition())
    }

    private class RecordingSink : MediaSink {
        val started = CopyOnWriteArrayList<Int>()
        val rtp = CopyOnWriteArrayList<Int>()
        @Volatile var stopped = 0
        override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) { started += firstSample }
        override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) { this.rtp += sample }
        override fun onAudioStopped(id: AudioStreamId) { stopped++ }
    }
}
