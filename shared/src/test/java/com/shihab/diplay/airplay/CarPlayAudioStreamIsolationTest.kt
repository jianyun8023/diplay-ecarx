package com.shihab.diplay.airplay

import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayAudioStreamIsolationTest {
    @Test fun silentInputStartsWithoutDownlinkWhileCallsWait() {
        val started = LinkedBlockingQueue<AudioStreamId>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
                started.add(id)
            }
        }, microphoneEnabled = true)
        val session = session(object : Socket() {
            override fun getInetAddress(): InetAddress = InetAddress.getLoopbackAddress()
            override fun getRemoteSocketAddress(): InetSocketAddress = InetSocketAddress(inetAddress, 50_000)
        })
        try {
            for (audioType in listOf("telephony", "speechrecognition", "default")) {
                assertNotNull(engine.onAudio(session, 100, setup(audioType) + ("dataPort" to 50_000L)))
            }
            // No RTP is sent: only input streams allowed to start silently reach the sink.
            assertEquals(
                setOf(AudioStreamId(100, "speechrecognition"), AudioStreamId(100, "default")),
                setOf(started.poll(3, TimeUnit.SECONDS), started.poll(3, TimeUnit.SECONDS)),
            )
            assertNull(started.poll(1, TimeUnit.SECONDS))
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test fun guidanceSetupAndMediaReplacementKeepTheOtherAudioStreamAlive() {
        val session = session()
        val engine = CarPlayMediaEngine(object : MediaSink {})
        try {
            assertNotNull(engine.onAudio(session, 100, setup("media")))
            val streams = streams(engine)
            val mediaKey = CarPlayMediaEngine.StreamKey(session, 100, "media")
            val firstMedia = streams[mediaKey]
            assertNotNull(engine.onAudio(session, 100, setup("default")))
            val guidanceKey = CarPlayMediaEngine.StreamKey(session, 100, "default")
            val guidance = streams[guidanceKey]
            assertSame(firstMedia, streams[mediaKey])
            assertEquals(2, streams.size)
            assertNotNull(engine.onAudio(session, 100, setup("MEDIA")))
            assertNotSame(firstMedia, streams[mediaKey])
            assertSame(guidance, streams[guidanceKey])
            assertEquals(2, streams.size)
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test fun typeTeardownClosesEveryVariantButKeepsOtherTypes() {
        val stopped = mutableListOf<AudioStreamId>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioStopped(id: AudioStreamId) { stopped.add(id) }
        })
        val session = session()
        try {
            assertNotNull(engine.onAudio(session, 100, setup("media")))
            assertNotNull(engine.onAudio(session, 100, setup("default")))
            assertNotNull(engine.onAudio(session, 102, setup("media")))
            stopped.clear()
            engine.onTeardown(session, 100)
            assertEquals(setOf(AudioStreamId(100, "media"), AudioStreamId(100, "default")), stopped.toSet())
            assertEquals(setOf(CarPlayMediaEngine.StreamKey(session, 102, "media")), streams(engine).keys)
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test fun aSessionClosedWithoutTeardownStopsItsAudio() {
        val stopped = mutableListOf<AudioStreamId>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioStopped(id: AudioStreamId) { stopped.add(id) }
        })
        val session = session()
        try {
            assertNotNull(engine.onAudio(session, 102, setup("media")))
            assertNotNull(engine.onAudio(session, 100, setup("default")))
            stopped.clear()
            engine.onSessionClosed(session)
            assertEquals(setOf(AudioStreamId(102, "media"), AudioStreamId(100, "default")), stopped.toSet())
        } finally {
            session.close()
        }
    }

    @Test fun aClosingSessionLeavesAudioANewerSessionPlays() {
        val stopped = mutableListOf<AudioStreamId>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioStopped(id: AudioStreamId) { stopped.add(id) }
        })
        val old = session()
        val current = session()
        try {
            assertNotNull(engine.onAudio(old, 102, setup("media")))
            assertNotNull(engine.onAudio(old, 100, setup("default")))
            assertNotNull(engine.onAudio(current, 102, setup("media")))
            stopped.clear()
            engine.onSessionClosed(old)
            assertEquals(listOf(AudioStreamId(100, "default")), stopped)
        } finally {
            engine.onSessionClosed(current)
            old.close()
            current.close()
        }
    }

    private fun setup(audioType: String): Map<String, Any?> = mapOf(
        "audioType" to audioType,
        "audioFormat" to 0x8000L,
        "streamConnectionID" to 42L,
    )

    @Suppress("UNCHECKED_CAST")
    private fun streams(engine: CarPlayMediaEngine): MutableMap<CarPlayMediaEngine.StreamKey, Closeable> =
        CarPlayMediaEngine::class.java.getDeclaredField("streams").apply { isAccessible = true }
            .get(engine) as MutableMap<CarPlayMediaEngine.StreamKey, Closeable>

    private fun session(socket: Socket = Socket()): AirPlaySession {
        val session = AirPlaySession(
            socket = socket,
            config = AirPlayConfig(
                deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
                sourceVersion = "1.0", main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            ),
            identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
            listener = object : AirPlaySessionListener {}, media = object : AirPlayMediaHandler {},
        )
        session.pairVerify.handle(Tlv8Codec.encode(listOf(
            Tlv8Item(6, byteArrayOf(1)), Tlv8Item(3, AirPlayCrypto.x25519Generate().publicKey),
        )))
        return session
    }
}
