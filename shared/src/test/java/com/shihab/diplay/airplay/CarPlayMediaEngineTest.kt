package com.shihab.diplay.airplay

import java.io.Closeable
import java.net.Socket
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayMediaEngineTest {
    @Test
    fun streamConnectionIdUsesUnsignedDecimalForHkdfSalt() {
        // The SETUP interpretation carries the unsigned decimal in the Rust owner's plan; the
        // reply-side encoding stays with the feedback/buffered owners that still use it.
        assertEquals(
            "18446744073709551615",
            connectionDecimal(-1L),
        )
        assertEquals(
            BigInteger("18446744073709551615"),
            unsignedPlistInteger(-1L),
        )
    }

    @Test
    fun dataStreamSetupInterpretsControlTypeSeedAndConnection() {
        val parsed = NativeAirPlayProtocol.dataStreamSetup(
            mapOf("controlType" to 1L, "seed" to -1L, "streamConnectionID" to 42L),
        )!!
        assertEquals(1, parsed["controlType"])
        assertEquals("18446744073709551615", parsed["seed"])
        assertEquals("42", parsed["connectionIdDecimal"])
        val absent = NativeAirPlayProtocol.dataStreamSetup(emptyMap<String, Any?>())!!
        assertEquals(0, absent["controlType"])
        assertNull(absent["seed"])
        assertNull(absent["connectionIdDecimal"])
    }

    @Test
    fun dataStreamKeysMatchTheKotlinHkdfAssembly() {
        val shared = ByteArray(32) { it.toByte() }
        val seed = "18446744073709551615"
        val label = "DataStream-Output-Encryption-Key"
        assertTrue(
            NativeAirPlayProtocol.dataStreamKey(shared, seed, label)!!.contentEquals(
                AirPlayCrypto.hkdfSha512(
                    shared,
                    "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII),
                    label.toByteArray(Charsets.US_ASCII),
                    32,
                ),
            ),
        )
    }

    @Test
    fun setupRepliesKeepFieldOrderAndUnsignedConnectionValues() {
        // A negative connection value keeps its unsigned decimal BigInteger representation.
        val negative = NativeAirPlayProtocol.setupReply(NativeAirPlayProtocol.REPLY_AUDIO, 100, 0L, 50_000, 50_001, -1L)!!
        assertEquals(listOf("type", "dataPort", "controlPort", "streamConnectionID"), negative.keys.toList())
        assertEquals(100, negative["type"])
        assertEquals(50_000, negative["dataPort"])
        assertEquals(50_001, negative["controlPort"])
        assertEquals(BigInteger("18446744073709551615"), negative["streamConnectionID"])
        val positive = NativeAirPlayProtocol.setupReply(NativeAirPlayProtocol.REPLY_AUDIO, 100, 0L, 50_000, 50_001, 42L)!!
        assertEquals(42L, positive["streamConnectionID"])
        val absent = NativeAirPlayProtocol.setupReply(NativeAirPlayProtocol.REPLY_AUDIO, 100, 0L, 50_000, 50_001, null)!!
        assertEquals(0L, absent["streamConnectionID"])

        val data = NativeAirPlayProtocol.setupReply(NativeAirPlayProtocol.REPLY_DATA, 130, 1L, 50_000, 0, 7L)!!
        assertEquals(listOf("type", "streamID", "dataPort", "streamConnectionID"), data.keys.toList())
        assertEquals(1L, data["streamID"])
        assertEquals(50_000, data["dataPort"])
        assertEquals(7L, data["streamConnectionID"])
        val withoutConnection = NativeAirPlayProtocol.setupReply(NativeAirPlayProtocol.REPLY_DATA, 130, 1L, 50_000, 0, null)!!
        assertEquals(listOf("type", "streamID", "dataPort"), withoutConnection.keys.toList())

        val remote = NativeAirPlayProtocol.setupReply(NativeAirPlayProtocol.REPLY_REMOTE_CONTROL, 130, 3L, 0, 0, null)!!
        assertEquals(listOf("type", "streamID"), remote.keys.toList())
        assertEquals(3L, remote["streamID"])
    }

    private fun connectionDecimal(value: Any?): String? =
        NativeAirPlayProtocol.dataStreamSetup(mapOf("streamConnectionID" to value))
            ?.get("connectionIdDecimal") as? String

    @Test
    fun screenStreamTeardownReportsInactive() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val sink = object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                events += type to active
            }
        }
        val session = testSession()

        try {
            val engine = CarPlayMediaEngine(sink)
            engine.onTeardown(session, 110)
            engine.onTeardown(session, 100)
        } finally {
            session.close()
        }

        assertEquals(listOf(110 to false), events)
    }

    @Test
    fun sessionCloseReportsAllScreenStreamsInactive() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val sink = object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                events += type to active
            }
        }
        val session = testSession()
        val engine = CarPlayMediaEngine(sink)
        val streamsField = CarPlayMediaEngine::class.java.getDeclaredField("streams").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val streams = streamsField.get(engine) as
            MutableMap<CarPlayMediaEngine.StreamKey, Closeable>
        streams[CarPlayMediaEngine.StreamKey(session, 110)] = Closeable {}
        streams[CarPlayMediaEngine.StreamKey(session, 111)] = Closeable {}
        streams[CarPlayMediaEngine.StreamKey(session, 100)] = Closeable {}

        engine.onSessionClosed(session)
        session.close()

        assertEquals(setOf(110 to false, 111 to false), events.toSet())
        assertTrue(streams.isEmpty())
    }

    @Test
    fun videoRemoteControlSessionsAreAcceptedOnlyWithVideoInCar() {
        val stream = mapOf("type" to 130L, "clientTypeUUID" to "A6B27562-B43A-4F2D-B75F-82391E250194", "controlType" to 1L)
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val plain = testSession()
        val video = testSession(videoInCar = true)
        try {
            assertNull(engine.onDataStream(plain, stream))
            val first = engine.onDataStream(video, stream)
            assertEquals(130, first?.get("type"))
            assertEquals(3L, first?.get("streamID"))
            assertEquals(4L, engine.onDataStream(video, stream)?.get("streamID"))
        } finally {
            plain.close()
            video.close()
        }
    }

    private fun testSession(videoInCar: Boolean = false): AirPlaySession = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            videoInCar = videoInCar,
        ),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = object : AirPlaySessionListener {},
        media = object : AirPlayMediaHandler {},
    )
}
