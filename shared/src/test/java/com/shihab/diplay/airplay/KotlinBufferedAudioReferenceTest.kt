package com.shihab.diplay.airplay

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

class KotlinBufferedAudioReferenceTest {
    private val config = AirPlayConfig(
        deviceName = "test",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:02",
        sourceVersion = "366.0",
        main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
    )
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val format = AudioFormat(AudioCodecKind.AAC_LC, 48_000, 2, KotlinBufferedAudioStream.STREAM_TYPE, "media")

    @Test
    fun theOfferIsMadeOnlyWhenEnabled() {
        val off = AirPlayInfoPlist.build(config)
        assertFalse(off.containsKey("mainBufferedInfo"))
        assertFalse((off["audioFormats"] as List<*>).any { (it as Map<*, *>)["type"] == 103 })
        assertFalse(MAIN_BUFFERED_FEATURE in setupEnabledFeatures(config, listOf("mainBuffered", "iAPChannel")))

        val on = config.copy(mainBufferedAudio = true)
        val info = AirPlayInfoPlist.build(on)
        assertEquals(emptyMap<String, Any?>(), info["mainBufferedInfo"])
        val entry = (info["audioFormats"] as List<*>).single { (it as Map<*, *>)["type"] == 103 } as Map<*, *>
        assertEquals("media", entry["audioType"])
        assertEquals(0x800000, entry["audioOutputFormats"]) // AAC-LC 48 kHz stereo
        assertTrue(MAIN_BUFFERED_FEATURE in setupEnabledFeatures(on, listOf("mainBuffered", "iAPChannel")))
        assertFalse(MAIN_BUFFERED_FEATURE in setupEnabledFeatures(on, listOf("iAPChannel")))
    }

    @Test
    fun disablingAudioOutputAlsoDisablesBufferedSetupNegotiation() {
        val muted = config.copy(mainBufferedAudio = true, disableAudioOutput = true)
        assertFalse(AirPlayInfoPlist.build(muted).containsKey("mainBufferedInfo"))
        assertFalse(MAIN_BUFFERED_FEATURE in setupEnabledFeatures(muted, listOf(MAIN_BUFFERED_FEATURE)))
    }

    @Test
    fun pausedTcpPreloadCannotExceedTheAdvertisedByteBudget() {
        val stream = KotlinBufferedAudioStream(key, format, RecordingSink())
        val executor = Executors.newSingleThreadExecutor()
        try {
            stream.start()
            Socket(InetAddress.getLoopbackAddress(), stream.port).use { socket ->
                val sending = executor.submit {
                    try {
                        val out = DataOutputStream(socket.getOutputStream())
                        repeat(180) { i ->
                            val body = seal(i, i * 1024L, ByteArray(60_000))
                            out.writeShort(body.size + 2)
                            out.write(body)
                        }
                        out.flush()
                    } catch (_: java.io.IOException) { /* Closing the stream interrupts a blocked writer. */ }
                }
                waitFor { queuedBytes(stream) >= KotlinBufferedAudioStream.AUDIO_BUFFER_BYTES - 120_000L }
                Thread.sleep(150)
                assertTrue("TCP preload exceeded its advertised buffer", queuedBytes(stream) <= KotlinBufferedAudioStream.AUDIO_BUFFER_BYTES)
                stream.close()
                sending.get(3, TimeUnit.SECONDS)
            }
        } finally {
            stream.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun theFillRunsFreeToTheFloorAndIsPacedBeyondIt() {
        // 15 s of 1024-sample frames at 48 kHz, the point past which the intake is held back.
        val floor = 48_000 * 15_000 / (1024 * 1000)
        val extra = 200
        val pauses = CopyOnWriteArrayList<Long>()
        val stream = KotlinBufferedAudioStream(key, format, RecordingSink(), pause = { pauses.add(it) })
        val executor = Executors.newSingleThreadExecutor()
        try {
            stream.start()
            Socket(InetAddress.getLoopbackAddress(), stream.port).use { socket ->
                val sending = executor.submit {
                    try {
                        val out = DataOutputStream(socket.getOutputStream())
                        repeat(floor + extra) { i ->
                            val body = seal(i, i * 1024L, byteArrayOf(0x21))
                            out.writeShort(body.size + 2); out.write(body)
                        }
                        out.flush()
                    } catch (_: java.io.IOException) { /* Closing the stream interrupts a blocked writer. */ }
                }
                waitFor { queuedFrames(stream) >= floor + extra }
                sending.get(3, TimeUnit.SECONDS)
            }
            assertEquals(floor + extra, queuedFrames(stream))
            // Music starts at once: nothing is held back until the floor is reached, and from
            // there every frame costs a quarter of its own duration, so the intake settles at
            // about four times real time instead of racing TCP to the two-minute cap.
            assertEquals(extra + 1, pauses.size)
            assertEquals(setOf(5L), pauses.toSet())
        } finally {
            stream.close()
            executor.shutdownNow()
        }
    }

    private fun queuedFrames(stream: KotlinBufferedAudioStream): Int {
        val lock = KotlinBufferedAudioStream::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(stream)!!
        return synchronized(lock) {
            val queue = KotlinBufferedAudioStream::class.java.getDeclaredField("queue").apply { isAccessible = true }.get(stream)
            (queue as java.util.ArrayDeque<*>).size
        }
    }

    private fun queuedBytes(stream: KotlinBufferedAudioStream): Long {
        val lock = KotlinBufferedAudioStream::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(stream)!!
        return synchronized(lock) {
            KotlinBufferedAudioStream::class.java.getDeclaredField("queuedBytes").apply { isAccessible = true }.getLong(stream)
        }
    }

    @Test
    fun framesOpenWithTheStreamKeyAndKeepTheirRtpHeader() {
        val payload = byteArrayOf(0x21, 0x6c, 0x4f, 0x55, 0x10)
        val body = seal(sequence = 7, timestamp = 0xfffffc00L, payload = payload)
        val frame = KotlinBufferedAudioStream.open(key, body)!!
        assertEquals(7, frame.sequence)
        assertEquals(0xfffffc00L, frame.timestamp)
        assertArrayEquals(payload, frame.rtp.copyOfRange(12, frame.rtp.size))
        assertNull(KotlinBufferedAudioStream.open(ByteArray(32), body))
        assertNull(KotlinBufferedAudioStream.open(key, body.copyOf(20)))
        assertNull(KotlinBufferedAudioStream.open(key, body.copyOf().apply { this[4] = (this[4].toInt() xor 1).toByte() }))
        assertNull(KotlinBufferedAudioStream.open(key, body.copyOf().apply { this[lastIndex - 8] = (this[lastIndex - 8].toInt() xor 1).toByte() }))
        for (invalidHeader in listOf(0x40, 0x81, 0x90, 0xa0)) {
            assertNull(KotlinBufferedAudioStream.open(key, body.copyOf().apply { this[0] = invalidHeader.toByte() }))
        }
    }

    @Test
    fun tinyAuthenticatedFramesCannotGrowAnUnboundedObjectQueue() {
        // The frame-count bound, not the paced fill: take the frames at full speed so the two
        // minutes this fills are reached inside the test's own deadline.
        val stream = KotlinBufferedAudioStream(key, format, RecordingSink(), pause = {})
        val executor = Executors.newSingleThreadExecutor()
        try {
            stream.start()
            Socket(InetAddress.getLoopbackAddress(), stream.port).use { socket ->
                val sending = executor.submit {
                    try {
                        val out = DataOutputStream(socket.getOutputStream())
                        repeat(5800) { i ->
                            val body = seal(i, i * 1024L, byteArrayOf(0x21))
                            out.writeShort(body.size + 2); out.write(body)
                        }
                        out.flush()
                    } catch (_: java.io.IOException) { }
                }
                waitFor { queuedBytes(stream) >= 5625L * 13 }
                Thread.sleep(100)
                assertEquals(5625L * 13, queuedBytes(stream))
                stream.close()
                sending.get(3, TimeUnit.SECONDS)
            }
        } finally {
            stream.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun reentrantPauseDuringSinkStartCannotDeliverStaleRtp() {
        val paused = CountDownLatch(1)
        val packets = CopyOnWriteArrayList<Int>()
        lateinit var stream: KotlinBufferedAudioStream
        val sink = object : MediaSink {
            override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
                stream.setRate(null, 0, BigInteger.ZERO)
                paused.countDown()
            }
            override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) { packets += sample }
        }
        stream = KotlinBufferedAudioStream(key, format, sink)
        try {
            stream.start()
            Socket(InetAddress.getLoopbackAddress(), stream.port).use { socket ->
                val body = seal(1, 1024L, byteArrayOf(0x21))
                DataOutputStream(socket.getOutputStream()).apply { writeShort(body.size + 2); write(body); flush() }
                stream.setRate(1024L, 1, BigInteger.ZERO)
                assertTrue(paused.await(3, TimeUnit.SECONDS))
                Thread.sleep(100)
                assertTrue(packets.isEmpty())
                assertEquals(13L, queuedBytes(stream))
            }
        } finally { stream.close() }
    }

    @Test
    fun theAnchorUsesThe1970EpochAndA64BitFraction() {
        // NTP seconds 2208988800 + 131959 (the iPhone's clock) and half a second.
        val ntp = BigInteger.valueOf(2_208_988_800L + 131_959L).shiftLeft(32).or(BigInteger.valueOf(0x8000_0000L))
        val anchor = KotlinBufferedAudioStream.anchorPlist(0x1_0000_0010L, ntp, 1)
        assertEquals(0x10L, anchor["rtpTime"])
        assertEquals(131_959L, anchor["networkTimeSecs"])
        assertEquals(BigInteger.ONE.shiftLeft(63), anchor["networkTimeFrac"])
        assertEquals(1, anchor["rate"])
    }

    @Test
    fun timestampsCompareAcrossTheWrap() {
        assertTrue(KotlinBufferedAudioStream.before(0xffff_ff00L, 0x100L))
        assertFalse(KotlinBufferedAudioStream.before(0x100L, 0xffff_ff00L))
        assertTrue(KotlinBufferedAudioStream.before(1_000L, 2_048L))
        assertFalse(KotlinBufferedAudioStream.before(2_048L, 2_048L))
    }

    private fun seal(sequence: Int, timestamp: Long, payload: ByteArray): ByteArray {
        val header = ByteArray(12)
        header[0] = 0x80.toByte()
        header[1] = 0x60
        header[2] = (sequence shr 8).toByte(); header[3] = sequence.toByte()
        for (i in 0 until 4) header[4 + i] = (timestamp shr (24 - 8 * i)).toByte()
        val nonceTail = ByteArray(8) { (sequence + it).toByte() }
        val nonce = ByteArray(12).also { nonceTail.copyInto(it, 4) }
        val sealed = BouncyCryptoOracle.chachaSeal(key, nonce, payload, header.copyOfRange(4, 12))
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
