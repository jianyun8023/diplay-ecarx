package com.shihab.diplay.media

import com.shihab.diplay.airplay.*
import org.junit.Assert.*
import org.junit.Test
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NativeAudioStateDeviceTest {
    private val key = ByteArray(32) { it.toByte() }
    private val format = AudioFormat(AudioCodecKind.AAC_LC, 48_000, 2, BufferedAudioStream.STREAM_TYPE, "media")

    @Test fun encryptedBufferedMusicDecodesToPlatformOutputAcrossPauseResumeAndFlush() {
        assertTrue(NativeMedia.available)
        val reports = CopyOnWriteArrayList<String>()
        val sink = AndroidMediaSink(onAudioDiagnostic = { reports += it }, debugLogsEnabled = { true })
        val starts = CopyOnWriteArrayList<Int>()
        val stops = AtomicInteger()
        val stream = BufferedAudioStream(key, format, object : MediaSink by sink {
            override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
                starts += firstSample
                sink.onAudioStarted(id, format, firstSample)
            }
            override fun onAudioStopped(id: AudioStreamId) { stops.incrementAndGet(); sink.onAudioStopped(id) }
        })
        try {
            stream.start()
            Socket(InetAddress.getLoopbackAddress(), stream.port).use { socket ->
                val out = DataOutputStream(socket.getOutputStream())
                // ffmpeg AAC-LC 48 kHz stereo digital silence, with the ADTS header removed.
                val silence = byteArrayOf(0x21, 0x10, 0x04, 0x60, 0x8c.toByte(), 0x1c)
                repeat(150) { send(out, it, 1000L + it * 1024L, silence) }
                val ntp = BigInteger.valueOf(3_999_999_999L).shiftLeft(32).add(BigInteger.valueOf(0xf000_0000L))
                val anchor = stream.setRate(1000L, 1, ntp)!!
                assertEquals(1000L, anchor["rtpTime"])
                assertEquals(1_791_011_200L, anchor["networkTimeSecs"])
                assertEquals(anchor, stream.anchor())
                await { reports.any { it.startsWith("Audio: ready") } }
                Thread.sleep(600)
                val paused = stream.setRate(null, 0, ntp)!!
                assertEquals(0, paused["rate"])
                assertTrue(stops.get() >= 1)
                val position = paused["rtpTime"] as Long
                val feedback = stream.feedback(ntp, 7L)
                assertEquals(position, feedback["sampleTime"])
                assertEquals(ntp, feedback["timestamp"])
                stream.setRate(position, 1, ntp)
                await { starts.size == 2 }
                assertTrue(starts.last().toLong() >= position)
                stream.flush(1000L + 100 * 1024L)
                await { starts.size == 3 }
                assertEquals(1000 + 100 * 1024, starts.last())
                Thread.sleep(500)
                stream.close()
                await { reports.any { it.contains("audio stats") && it.contains("ended=true") &&
                    Regex("totalWrittenFrames=([1-9][0-9]*)").containsMatchIn(it) &&
                    Regex("playbackHeadFrames=([1-9][0-9]*)").containsMatchIn(it) } }
                assertTrue(reports.joinToString("\n"), reports.any { it.startsWith("Audio: decoder stats") &&
                    Regex("outputBuffersTotal=([1-9][0-9]*)").containsMatchIn(it) })
                assertFalse(reports.joinToString("\n"), reports.any { it.startsWith("Audio: renderer failed") })
                assertTrue(stream.outputCleanupComplete)
            }
        } finally { stream.close(); sink.close() }
    }

    @Test fun reentrantPauseAndCloseInvalidateNativeFeedWithoutReleasingItsPacketTooSoon() {
        for (close in listOf(false, true)) {
            val completed = CountDownLatch(1)
            val packets = AtomicInteger()
            lateinit var stream: BufferedAudioStream
            stream = BufferedAudioStream(key, format, object : MediaSink {
                override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
                    if (close) stream.close() else stream.setRate(null, 0, BigInteger.ZERO)
                    completed.countDown()
                }
                override fun onAudioPacket(id: AudioStreamId, format: AudioFormat, rtp: MediaPacket, sample: Int) {
                    rtp.release(); packets.incrementAndGet()
                }
            })
            try {
                stream.start()
                Socket(InetAddress.getLoopbackAddress(), stream.port).use { socket ->
                    send(DataOutputStream(socket.getOutputStream()), 0, 0xffff_fc00L)
                    stream.setRate(0xffff_fc00L, 1, BigInteger.ZERO)
                    assertTrue(completed.await(5, TimeUnit.SECONDS))
                    Thread.sleep(50)
                    assertEquals(0, packets.get())
                    if (close) assertTrue(stream.outputCleanupComplete)
                    else assertEquals(0xffff_fc00L, stream.anchor()!!["rtpTime"])
                }
            } finally { stream.close() }
        }
    }

    @Test fun flushWaitsForAnInflightDeliveryAndCloseWakesTheBoundedTcpReceiver() {
        val delivering = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stream = BufferedAudioStream(key, format, object : MediaSink {
            override fun onAudioPacket(id: AudioStreamId, format: AudioFormat, rtp: MediaPacket, sample: Int) {
                try { delivering.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
                finally { rtp.release() }
            }
        })
        try {
            stream.start()
            Socket(InetAddress.getLoopbackAddress(), stream.port).use { socket ->
                val out = DataOutputStream(socket.getOutputStream())
                send(out, 0, 0)
                stream.setRate(0L, 1, BigInteger.ZERO)
                assertTrue(delivering.await(5, TimeUnit.SECONDS))
                val entered = CountDownLatch(1)
                val control = FutureTask { entered.countDown(); stream.flush(1024L) }
                Thread(control).start()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                Thread.sleep(100)
                assertFalse(control.isDone)
                release.countDown()
                control.get(5, TimeUnit.SECONDS)
                stream.setRate(null, 0, BigInteger.ZERO)
                val sending = FutureTask {
                    try { repeat(600) { send(out, it + 1, (it + 1) * 1024L, ByteArray(60_000)) } }
                    catch (_: java.io.IOException) { }
                }
                Thread(sending).start()
                Thread.sleep(750)
                assertFalse("A paused stream must apply TCP backpressure", sending.isDone)
                stream.close()
                sending.get(5, TimeUnit.SECONDS)
                assertTrue(stream.outputCleanupComplete)
            }
        } finally { release.countDown(); stream.close() }
    }

    @Test fun malformedFramesCloseTheNativeStreamAndForeignPeersCannotClaimIt() {
        val malformed = BufferedAudioStream(key, format, object : MediaSink {})
        try {
            malformed.start()
            Socket(InetAddress.getLoopbackAddress(), malformed.port).use { socket ->
                socket.soTimeout = 5000
                DataOutputStream(socket.getOutputStream()).apply { writeShort(1); flush() }
                assertEquals(-1, socket.getInputStream().read())
                assertNull(malformed.setRate(0L, 1, BigInteger.ZERO))
            }
        } finally { malformed.close() }
        val foreign = BufferedAudioStream(key, format, object : MediaSink {},
            expectedPeer = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 2)))
        try {
            foreign.start()
            Socket(InetAddress.getLoopbackAddress(), foreign.port).use { socket ->
                socket.soTimeout = 5000
                assertEquals(-1, socket.getInputStream().read())
                assertNull(foreign.anchor())
            }
        } finally { foreign.close() }
    }

    @Test fun fragmentedTcpAndBadAuthenticationKeepRtpOrderAcrossTheWrap() {
        val samples = CopyOnWriteArrayList<Long>()
        val payloads = CopyOnWriteArrayList<Byte>()
        val stream = BufferedAudioStream(key, format, object : MediaSink {
            override fun onAudioPacket(id: AudioStreamId, format: AudioFormat, rtp: MediaPacket, sample: Int) {
                try { payloads += rtp.toByteArray()[12]; samples += sample.toLong() and 0xffff_ffffL }
                finally { rtp.release() }
            }
        }, nanoTime = { 0L })
        try {
            stream.start()
            Socket(InetAddress.getLoopbackAddress(), stream.port).use { socket ->
                val out = DataOutputStream(socket.getOutputStream())
                val first = sealed(0, 0xffff_fc00L, byteArrayOf(1))
                out.writeByte((first.size + 2) shr 8); out.flush()
                out.writeByte(first.size + 2)
                out.write(first, 0, 5); out.flush(); out.write(first, 5, first.size - 5)
                val bad = sealed(1, 0, byteArrayOf(9)).apply { this[12] = (this[12].toInt() xor 1).toByte() }
                out.writeShort(bad.size + 2); out.write(bad)
                val invalidHeader = sealed(2, 0, byteArrayOf(9)).apply { this[0] = 0x90.toByte() }
                out.writeShort(invalidHeader.size + 2); out.write(invalidHeader)
                send(out, 3, 0, byteArrayOf(2))
                stream.setRate(0xffff_fc00L, 1, BigInteger.ZERO)
                await { samples.size == 2 }
                assertEquals(listOf(0xffff_fc00L, 0L), samples.toList())
                assertEquals(listOf<Byte>(1, 2), payloads.toList())
            }
        } finally { stream.close() }
    }

    @Test fun nativeQueueBoundsOwnsAndReleasesPacketsAcrossCloseAndRecoversOnlySustainedLag() {
        val queue = requireNotNull(NativeAudioQueue.create(48_000, true))
        val reader = requireNotNull(queue.consumer())
        val out = LongArray(3)
        try {
            repeat(151) { assertEquals(1, queue.push(HeapPacket(ByteArray(13)), it * 1024, 100_000_000)) }
            assertEquals(257, queue.push(HeapPacket(ByteArray(13)), 151 * 1024, 1_600_000_000))
            assertEquals(24, queue.size)
            assertTrue(queue.pendingFrames() <= 24_000)
            val packet = requireNotNull(reader.poll(out, 0))
            queue.close()
            assertArrayEquals(ByteArray(13), packet.toByteArray())
            packet.release()
            assertNull(reader.poll(out, 0))
            assertEquals(0, queue.push(HeapPacket(ByteArray(13)), 0, 0))
        } finally { queue.close(); reader.close() }
        requireNotNull(NativeAudioQueue.create(48_000, false)).use { voice ->
            repeat(192) { assertEquals(1, voice.push(HeapPacket(ByteArray(13)), it * 48_000, it * 1_000_000_000L)) }
            assertEquals(0, voice.push(HeapPacket(ByteArray(13)), 0, 200_000_000_000))
            requireNotNull(voice.consumer()).use { voiceReader -> requireNotNull(voiceReader.poll(out, 0)).release() }
            assertEquals(0L, out[0])
        }
        for (close in listOf(false, true)) {
            requireNotNull(NativeAudioQueue.create(48_000, false)).use { waiting ->
                val waitingReader = requireNotNull(waiting.consumer())
                val entered = java.util.concurrent.CountDownLatch(1)
                val polled = FutureTask { waitingReader.use { entered.countDown(); it.poll(LongArray(3), 5000) } }
                Thread(polled).start()
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                if (close) waiting.close() else waiting.push(HeapPacket(ByteArray(13)), 0, 0)
                val packet = polled.get(1, TimeUnit.SECONDS)
                if (close) assertNull(packet) else requireNotNull(packet).release()
            }
        }
    }

    @Test fun nativeClockMatchesTheCalibratedKotlinAlgorithmIncludingTargetMeasurement() {
        for (measures in listOf(false, true)) {
            MediaClockTrim.learnedTrim = -0.0075
            val reference = KotlinMediaClockTrim(48_000, 14_400, -0.0075, measures)
            MediaClockTrim(48_000, 14_400, -0.0075, measures).use { native ->
                repeat(6000) { step ->
                    val now = step * 20_000_000L
                    if (step % 1500 == 0) { reference.measureAgain(); native.measureAgain() }
                    val compressed = if (step % 1000 in 800..950) 96 * 1024L else (step % 20) * 1024L
                    val queued = 18_000L + (step % 80) * 120L
                    val headMoving = step % 1500 > 20
                    val arrived = (step % 20) * 1024.0 * 10_000_000.0
                    reference.sample(queued, now, compressed, headMoving, arrived)
                    native.sample(queued, now, compressed, headMoving, arrived)
                    val expected = reference.rateDue(now)
                    val actual = native.rateDue(now)
                    assertEquals("step=$step measures=$measures", expected, actual)
                    assertEquals(reference.lastSampleNs, native.lastSampleNs)
                    assertEquals(reference.rate, native.rate)
                }
            }
        }
        MediaClockTrim.learnedTrim = 0.0
    }

    private fun send(out: DataOutputStream, sequence: Int, timestamp: Long, payload: ByteArray = byteArrayOf(0x21)) {
        val body = sealed(sequence, timestamp, payload)
        out.writeShort(body.size + 2); out.write(body); out.flush()
    }

    private fun sealed(sequence: Int, timestamp: Long, payload: ByteArray): ByteArray {
        val header = ByteArray(12)
        header[0] = 0x80.toByte(); header[1] = 0x60
        header[2] = (sequence shr 8).toByte(); header[3] = sequence.toByte()
        repeat(4) { header[4 + it] = (timestamp shr (24 - 8 * it)).toByte() }
        val tail = ByteArray(8) { (sequence.toLong() shr (it * 8)).toByte() }
        val nonce = ByteArray(12).also { tail.copyInto(it, 4) }
        return header + AirPlayCrypto.chachaSeal(key, nonce, payload, header.copyOfRange(4, 12)) + tail
    }

    private fun await(check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!check() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(check())
    }
}
