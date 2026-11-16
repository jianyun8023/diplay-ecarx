package com.shihab.diplay.airplay

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BufferedAudioHostContractTest {
    private val format = 0x800000L
    private fun setup() = linkedMapOf<String, Any?>("shk" to ByteArray(32), "audioFormat" to format,
        "ct" to 4, "spf" to 1024, "streamConnectionID" to 123L)

    private class TraceMap(private val source: Map<String, Any?>, private val log: MutableList<String>) : Map<String, Any?> by source {
        override fun get(key: String): Any? { log += "get:$key"; return source[key] }
    }
    private class TraceNumber(private val name: String, private val number: Number, private val log: MutableList<String>,
        private val fail: Boolean = false) : Number() {
        override fun toByte(): Byte = number.toByte()
        override fun toChar(): Char = number.toInt().toChar()
        override fun toShort(): Short = number.toShort()
        override fun toInt(): Int { log += "$name:int"; check(!fail); return number.toInt() }
        override fun toLong(): Long { log += "$name:long"; check(!fail); return number.toLong() }
        override fun toFloat(): Float = number.toFloat()
        override fun toDouble(): Double { log += "$name:double"; check(!fail); return number.toDouble() }
    }
    private fun session(engine: CarPlayMediaEngine, peer: Boolean = true): AirPlaySession = AirPlaySession(
        socket = if (peer) object : Socket() {
            override fun getRemoteSocketAddress(): SocketAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), 1234)
        } else Socket(),
        config = AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0", main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480), mainBufferedAudio = true),
        identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
        listener = object : AirPlaySessionListener {}, media = engine,
    )

    @Test fun setupRejectsInOriginalOrderWithoutReadingLaterFields() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val session = session(engine)
        val noPeer = session(engine, peer = false)
        try {
            val log = mutableListOf<String>()
            assertNull(engine.onBufferedAudio(session, TraceMap(setup() + ("shk" to ByteArray(31)), log)))
            assertEquals(listOf("get:shk"), log)
            log.clear()
            val badFormat = setup() + ("audioFormat" to TraceNumber("format", 0x8000L, log))
            assertNull(engine.onBufferedAudio(session, TraceMap(badFormat, log)))
            assertEquals(listOf("get:shk", "get:audioFormat", "format:long", "get:audioFormat"), log)
            log.clear()
            val badCt = setup() + ("ct" to TraceNumber("ct", 3.9, log))
            assertNull(engine.onBufferedAudio(session, TraceMap(badCt, log)))
            assertEquals(listOf("get:shk", "get:audioFormat", "get:ct", "ct:int"), log)
            log.clear()
            val badSpf = setup() + ("spf" to "1024")
            assertNull(engine.onBufferedAudio(session, TraceMap(badSpf, log)))
            assertEquals(listOf("get:shk", "get:audioFormat", "get:ct", "get:spf"), log)
            log.clear()
            assertNull(engine.onBufferedAudio(noPeer, TraceMap(setup(), log)))
            assertEquals(listOf("get:shk", "get:audioFormat", "get:ct", "get:spf"), log)
        } finally { session.close(); noPeer.close() }
    }

    @Test fun setupAcceptsJvmNumberConversionsAndPreservesReplyAndHealthyOwner() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val session = session(engine)
        try {
            val accepted = engine.onBufferedAudio(session, setup() + mapOf("ct" to 4.9, "spf" to 1024.9))!!
            assertEquals(listOf("type", "dataPort", "audioBufferSize"), accepted.keys.toList())
            assertTrue(accepted.values.all { it is Int })
            assertEquals(103, accepted["type"])
            assertTrue((accepted["dataPort"] as Int) > 0)
            assertEquals(8 * 1024 * 1024, accepted["audioBufferSize"])
            assertFalse(accepted.containsKey("streamConnectionID"))
            assertNotNull(engine.onBufferedAudio(session, setup() + mapOf("ct" to 0x1_0000_0004L, "spf" to null)))
            assertNotNull(engine.onBufferedAudio(session, setup() - "ct" - "spf"))
            assertNotNull(engine.onBufferedAudio(session, setup() + mapOf("ct" to null, "spf" to null)))
            val anchor = engine.onBufferedAudioControl(session, "SETRATE", mapOf("rate" to 1, "rtpTime" to -1L))!!
            assertEquals(0xffff_ffffL, anchor["rtpTime"])
            for (invalid in listOf(setup() + ("ct" to Double.NaN), setup() + ("spf" to Double.NaN),
                setup() + ("ct" to "4"), setup() + ("audioFormat" to null), setup() + ("shk" to "wrong"))) {
                assertNull(engine.onBufferedAudio(session, invalid))
                assertEquals(1, engine.onBufferedAudioControl(session, "GETANCHOR", emptyMap())!!["rate"])
            }
        } finally { session.close() }
    }

    @Test fun controlLooksUpSessionBeforeBodyAndReadsFieldsInOrderForEveryMethod() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val session = session(engine)
        val absent = session(engine)
        try {
            val log = mutableListOf<String>()
            val body = TraceMap(mapOf("rtpTime" to TraceNumber("rtp", -1L, log),
                "rate" to TraceNumber("rate", 1.0, log), "flushUntilTS" to TraceNumber("flush", -1L, log)), log)
            assertNull(engine.onBufferedAudioControl(absent, "SETRATE", body))
            assertTrue(log.isEmpty())
            assertNotNull(engine.onBufferedAudio(session, setup()))
            for (method in listOf("UNKNOWN", "GETANCHOR", "SETRATE")) {
                log.clear()
                engine.onBufferedAudioControl(session, method, body)
                assertEquals(listOf("get:rtpTime", "rtp:long", "get:rate", "rate:double"), log)
            }
            log.clear()
            assertNull(engine.onBufferedAudioControl(session, "FLUSHBUFFERED", body))
            assertEquals(listOf("get:rtpTime", "rtp:long", "get:rate", "rate:double", "get:flushUntilTS", "flush:long"), log)
        } finally { session.close(); absent.close() }
    }

    @Test fun rateAndFlushKeepJvmConversionsAndRejectWithoutChangingAnchor() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val session = session(engine)
        try {
            assertNotNull(engine.onBufferedAudio(session, setup()))
            assertEquals(0xffff_ffffL, engine.onBufferedAudioControl(session, "SETRATE", mapOf("rtpTime" to -1L, "rate" to 1.0))!!["rtpTime"])
            for (rate in listOf(null, "1", 0.5, Double.NaN, Double.POSITIVE_INFINITY)) {
                assertNull(engine.onBufferedAudioControl(session, "SETRATEANCHORTIME", mapOf("rtpTime" to 12, "rate" to rate)))
                val anchor = engine.onBufferedAudioControl(session, "GETANCHOR", emptyMap())!!
                assertEquals(1, anchor["rate"])
                assertEquals(0xffff_ffffL, anchor["rtpTime"])
            }
            for ((raw, unsigned) in listOf(Long.MIN_VALUE to 0L, Long.MAX_VALUE to 0xffff_ffffL)) {
                assertEquals(unsigned, engine.onBufferedAudioControl(session, "SETRATE",
                    mapOf("rtpTime" to raw, "rate" to 1))!!["rtpTime"])
            }
            assertEquals(0, engine.onBufferedAudioControl(session, "SETRATE", mapOf("rate" to -0.0))!!["rate"])
            assertNull(engine.onBufferedAudioControl(session, "FLUSHBUFFERED", mapOf("flushUntilTS" to "wrong")))
            assertNull(engine.onBufferedAudioControl(session, "FLUSHBUFFERED", mapOf("flushUntilTS" to -1L)))
        } finally { session.close() }
    }

    @Test @Config(shadows = [FlushSpy::class]) fun flushCallsOnlyForANumericTimestamp() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val session = session(engine)
        FlushSpy.values.clear()
        try {
            assertNotNull(engine.onBufferedAudio(session, setup()))
            assertNull(engine.onBufferedAudioControl(session, "FLUSHBUFFERED", emptyMap()))
            assertNull(engine.onBufferedAudioControl(session, "FLUSHBUFFERED", mapOf("flushUntilTS" to "wrong")))
            assertTrue(FlushSpy.values.isEmpty())
            assertNull(engine.onBufferedAudioControl(session, "FLUSHBUFFERED", mapOf("flushUntilTS" to -1L)))
            assertEquals(listOf(-1L), FlushSpy.values)
        } finally { session.close(); FlushSpy.values.clear() }
    }

    @Implements(value = BufferedAudioStream::class, isInAndroidSdk = false)
    class FlushSpy {
        @Implementation fun flush(timestamp: Long?) { values += timestamp }
        companion object { val values = mutableListOf<Long?>() }
    }

    @Test fun conversionExceptionStopsBeforeFollowingFieldsAndDoesNotChangeOwner() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val session = session(engine)
        try {
            assertNotNull(engine.onBufferedAudio(session, setup()))
            val log = mutableListOf<String>()
            val failedSetup = TraceMap(setup() + ("ct" to TraceNumber("ct", 4, log, fail = true)), log)
            try { engine.onBufferedAudio(session, failedSetup); fail("expected setup conversion error") }
            catch (_: IllegalStateException) { }
            assertEquals(listOf("get:shk", "get:audioFormat", "get:ct", "ct:int"), log)
            assertNotNull(engine.onFeedback(session))
            log.clear()
            val body = TraceMap(mapOf("rtpTime" to TraceNumber("rtp", 10L, log, fail = true),
                "rate" to TraceNumber("rate", 1.0, log)), log)
            try { engine.onBufferedAudioControl(session, "SETRATE", body); fail("expected conversion error") }
            catch (_: IllegalStateException) { }
            assertEquals(listOf("get:rtpTime", "rtp:long"), log)
            assertNotNull(engine.onFeedback(session))
        } finally { session.close() }
    }
}
