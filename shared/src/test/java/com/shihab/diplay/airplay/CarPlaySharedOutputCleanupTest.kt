package com.shihab.diplay.airplay

import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real engine cleanup over registered resource facts, not native packet/renderer simulation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CarPlaySharedOutputCleanupTest {
    private class Rig : Closeable {
        val audioStops = mutableListOf<AudioStreamId>()
        val micStops = mutableListOf<AudioStreamId>()
        val screens = mutableListOf<Pair<Int, Boolean>>()
        val ducks = mutableListOf<Boolean>()
        val closed = mutableListOf<CarPlayMediaEngine.StreamKey>()
        val events = mutableListOf<String>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioStopped(id: AudioStreamId) { audioStops += id; events += "stop" }
            override fun onMicrophoneStopped(id: AudioStreamId) { micStops += id; events += "mic-stop" }
            override fun onScreenStreamActive(type: Int, active: Boolean) { screens += type to active; events += "screen" }
            override fun onAudioDuck(ducked: Boolean) { ducks += ducked }
        })
        val old = session()
        val current = session()
        val third = session()
        @Suppress("UNCHECKED_CAST")
        val streams = engine.javaClass.getDeclaredField("streams").apply { isAccessible = true }.get(engine)
            as MutableMap<CarPlayMediaEngine.StreamKey, Closeable>
        @Suppress("UNCHECKED_CAST")
        val microphones = engine.javaClass.getDeclaredField("pendingMicrophone").apply { isAccessible = true }.get(engine)
            as MutableMap<CarPlayMediaEngine.StreamKey, MicrophoneConfig>

        fun register(session: AirPlaySession, type: Int, audioType: String = "", microphone: Boolean = false): CarPlayMediaEngine.StreamKey {
            val key = CarPlayMediaEngine.StreamKey(session, type, audioType)
            streams[key] = Closeable { closed += key; events += "close" }
            val screen = type == 110 || type == 111
            NativeMedia.outputLeaseAcquire(engine.leaseEpoch, session.leaseHolderId,
                if (screen) NativeMedia.LEASE_SCREEN else NativeMedia.LEASE_AUDIO, type, if (screen) "" else audioType)
            if (microphone) {
                microphones[key] = MicrophoneConfig(audioType, 24_000, 1, type, 10,
                    InetAddress.getLoopbackAddress(), 50_000, ByteArray(32))
                NativeMedia.outputLeaseAcquire(engine.leaseEpoch, session.leaseHolderId,
                    NativeMedia.LEASE_MICROPHONE, type, audioType)
            }
            return key
        }
        private fun session() = AirPlaySession(object : Socket() {
            override fun getInetAddress(): InetAddress = InetAddress.getLoopbackAddress()
            override fun getRemoteSocketAddress() = InetSocketAddress(inetAddress, 50_000)
        }, AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity.generate(), PairingStore(), null, object : AirPlaySessionListener {}, engine)

        override fun close() { old.close(); current.close(); third.close() }
    }

    @Test fun oldAudioTeardownClosesOnlyItsResourceWithoutStoppingSharedId() = Rig().use { rig ->
        val old = rig.register(rig.old, 100, "media")
        val current = rig.register(rig.current, 100, "media")
        val surviving = rig.streams.getValue(current)
        rig.engine.onTeardown(rig.old, 100)
        assertEquals(listOf(old), rig.closed)
        assertSame(surviving, rig.streams[current])
        assertTrue("old TEARDOWN must not stop the current session's audio id", rig.audioStops.isEmpty())
        rig.engine.onTeardown(rig.current, 100)
        assertEquals(listOf(AudioStreamId(100, "media")), rig.audioStops)
    }

    @Test fun oldMicrophoneTeardownPreservesCurrentCaptureWithSameId() = Rig().use { rig ->
        val old = rig.register(rig.old, 100, "telephony", microphone = true)
        val current = rig.register(rig.current, 100, "telephony", microphone = true)
        val surviving = rig.microphones.getValue(current)
        rig.engine.onTeardown(rig.old, 100)
        assertFalse(rig.microphones.containsKey(old))
        assertSame(surviving, rig.microphones[current])
        assertTrue("old TEARDOWN must not stop the current session's microphone id", rig.micStops.isEmpty())
        rig.engine.onTeardown(rig.current, 100)
        assertEquals(listOf(AudioStreamId(100, "telephony")), rig.micStops)
    }

    @Test fun oldScreenTeardownDoesNotPublishInactiveWhileSameTypeIsRegistered() {
        val observed = mutableListOf<Pair<Int, Boolean>>()
        for (type in listOf(110, 111)) Rig().use { rig ->
            val old = rig.register(rig.old, type)
            val current = rig.register(rig.current, type)
            rig.engine.onTeardown(rig.old, type)
            assertEquals(listOf(old), rig.closed)
            assertTrue(rig.streams.containsKey(current))
            observed += rig.screens
        }
        assertEquals("old main/cluster TEARDOWN must leave the current output active", emptyList<Pair<Int, Boolean>>(), observed)
    }

    @Test fun oldScreenSessionCloseDoesNotPublishInactiveWhileSameTypeIsRegistered() {
        val observed = mutableListOf<Pair<Int, Boolean>>()
        for (type in listOf(110, 111)) Rig().use { rig ->
            val old = rig.register(rig.old, type)
            val current = rig.register(rig.current, type)
            rig.engine.onSessionClosed(rig.old)
            assertEquals(listOf(old), rig.closed)
            assertTrue(rig.streams.containsKey(current))
            observed += rig.screens
        }
        assertEquals("old session close must leave the current main/cluster output active", emptyList<Pair<Int, Boolean>>(), observed)
    }

    @Test fun staleUnduckCannotReleaseCurrentPromptWhetherItEndsByCommandOrClose() {
        val observed = mutableListOf<List<Boolean>>()
        for (endByClose in listOf(false, true)) Rig().use { rig ->
            rig.engine.onAudioDuck(rig.old, true)
            rig.engine.onAudioDuck(rig.current, true)
            rig.engine.onAudioDuck(rig.old, false)
            if (endByClose) rig.engine.onSessionClosed(rig.current) else rig.engine.onAudioDuck(rig.current, false)
            observed += rig.ducks.toList()
        }
        assertEquals(listOf(listOf(true, true, false), listOf(true, true, false)), observed)
    }

    @Test fun oldScreenEofDoesNotPublishInactiveForCurrentCluster() = Rig().use { rig ->
        for (session in listOf(rig.old, rig.current)) {
            session.pairVerify.handle(Tlv8Codec.encode(listOf(Tlv8Item(6, byteArrayOf(1)),
                Tlv8Item(3, AirPlayCrypto.x25519Generate().publicKey))))
            assertNotNull(rig.engine.onScreen(session, 111, mapOf("streamConnectionID" to 1L)))
        }
        val oldKey = CarPlayMediaEngine.StreamKey(rig.old, 111)
        val currentKey = CarPlayMediaEngine.StreamKey(rig.current, 111)
        val oldStream = rig.streams.getValue(oldKey) as ScreenStream
        val listenerField = oldStream.javaClass.getDeclaredField("listener").apply { isAccessible = true }
        val listener = listenerField.get(oldStream) as ScreenStream.Listener
        val currentStream = rig.streams.getValue(currentKey) as ScreenStream
        val currentListener = listenerField.get(currentStream) as ScreenStream.Listener
        rig.screens.clear()
        // Deliver the real registered callback; no TCP receiver or native frame is faked.
        try {
            listener.onClosed(null)
            assertTrue(rig.old.isClosed)
            assertTrue(rig.streams.containsKey(currentKey))
            assertTrue("old EOF must not clear the current cluster output", rig.screens.isEmpty())
            currentListener.onClosed(null)
            assertTrue(rig.current.isClosed)
            assertEquals(listOf(111 to false), rig.screens)
        } finally { oldStream.close(); currentStream.close() }
    }

    @Test fun phoneCleanupClosesTheSourceThenPlaybackThenMicrophone() {
        val stopPaths: List<(Rig) -> Unit> = listOf(
            { it.engine.onTeardown(it.old, 100) },
            { it.engine.onSessionClosed(it.old) },
            { it.engine.onAudio(it.old, 100, mapOf("audioType" to "telephony")) },
        )
        stopPaths.forEach { stop ->
            Rig().use { rig ->
                val key = rig.register(rig.old, 100, "telephony", microphone = true)
                stop(rig)
                assertEquals(listOf("close", "stop", "mic-stop"), rig.events)
                assertEquals(listOf(key), rig.closed)
                assertEquals(listOf(AudioStreamId(100, "telephony")), rig.audioStops)
                assertEquals(listOf(AudioStreamId(100, "telephony")), rig.micStops)
                assertTrue(rig.streams.isEmpty())
                assertTrue(rig.microphones.isEmpty())
            }
        }
    }

    @Test fun lastScreenStillPublishesInactiveOnTeardownAndSessionClose() {
        for (type in listOf(110, 111)) for (teardown in listOf(false, true)) Rig().use { rig ->
            rig.register(rig.old, type)
            if (teardown) rig.engine.onTeardown(rig.old, type) else rig.engine.onSessionClosed(rig.old)
            assertEquals(listOf(type to false), rig.screens)
        }
    }

    @Test fun noOwnerRepeatedUnduckStillReachesSink() = Rig().use { rig ->
        rig.engine.onAudioDuck(rig.old, false)
        rig.engine.onAudioDuck(rig.current, false)
        assertEquals(listOf(false, false), rig.ducks)
    }

    @Test fun threeSessionsShareOneAudioOutputUntilTheLastTeardown() = Rig().use { rig ->
        val first = rig.register(rig.old, 100, "media")
        val second = rig.register(rig.current, 100, "media")
        val third = rig.register(rig.third, 100, "media")
        rig.engine.onTeardown(rig.old, 100)
        assertTrue("two sessions still share the id", rig.audioStops.isEmpty())
        rig.engine.onTeardown(rig.current, 100)
        assertTrue("one session still shares the id", rig.audioStops.isEmpty())
        rig.engine.onTeardown(rig.third, 100)
        assertEquals(listOf(AudioStreamId(100, "media")), rig.audioStops)
        assertEquals(listOf(first, second, third), rig.closed)
    }

    @Test fun audioTeardownRetiresTheSourceBeforeStoppingTheSharedOutput() = Rig().use { rig ->
        val key = rig.register(rig.old, 100, "media")
        rig.engine.onTeardown(rig.old, 100)
        assertEquals(listOf(key), rig.closed)
        // The stream is detached and closed before the sink is asked to stop, so a final flush
        // lands on a live renderer.
        assertEquals(listOf("close", "stop"), rig.events)
        assertEquals(listOf(AudioStreamId(100, "media")), rig.audioStops)
    }

    @Test fun screenTeardownNeverEmitsASpuriousAudioStop() = Rig().use { rig ->
        val old = rig.register(rig.old, 110)
        val current = rig.register(rig.current, 110)
        rig.engine.onTeardown(rig.old, 110)
        assertTrue("a screen teardown must not release/stop an audio output", rig.audioStops.isEmpty())
        assertEquals(listOf(old), rig.closed)
        assertTrue(rig.streams.containsKey(current))
    }

    @Test fun dataStreamTeardownClosesTheSourceWithoutAnAudioStop() = Rig().use { rig ->
        val key = CarPlayMediaEngine.StreamKey(rig.old, 130)
        // A data stream holds no audio/microphone lease.
        rig.streams[key] = Closeable { rig.closed += key }
        rig.engine.onTeardown(rig.old, 130)
        assertTrue("a data teardown must not emit an audio stop", rig.audioStops.isEmpty())
        assertEquals(listOf(key), rig.closed)
        assertTrue(rig.streams.isEmpty())
    }

    @Test fun screenSessionCloseRetiresTheSourceBeforeStoppingTheOutput() = Rig().use { rig ->
        rig.register(rig.old, 110)
        rig.engine.onSessionClosed(rig.old)
        assertEquals(listOf("close", "screen"), rig.events)
        assertEquals(listOf(110 to false), rig.screens)
        assertTrue("a screen session close must not emit an audio stop", rig.audioStops.isEmpty())
    }

    @Test fun onlyClosingCurrentDuckOwnerReleasesPrompt() = Rig().use { rig ->
        rig.engine.onAudioDuck(rig.old, true)
        rig.engine.onAudioDuck(rig.current, true)
        rig.engine.onSessionClosed(rig.old)
        assertEquals(listOf(true, true), rig.ducks)
        rig.engine.onSessionClosed(rig.current)
        assertEquals(listOf(true, true, false), rig.ducks)
    }
}
