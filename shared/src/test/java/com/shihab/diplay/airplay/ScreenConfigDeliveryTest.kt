package com.shihab.diplay.airplay

import android.graphics.SurfaceTexture
import android.media.MediaCrypto
import android.media.MediaFormat
import android.view.Surface
import com.shihab.diplay.media.AndroidMediaSink
import java.io.Closeable
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaCodec

/**
 * Two sessions can share one screen type: a replaced session's stream stays open under its own
 * registry key while the sink keeps one decoder, one mirror fanout and one saved configuration per
 * type. Frames are already guarded (a replaced native route is disconnected, then retired in Rust);
 * this test pins the same delivery right for codec configuration, driven through the engine's
 * production screen registration and the sink's real configuration path, never a field write.
 */
@RunWith(RobolectricTestRunner::class) // CarPlayMediaEngine.onScreen logs through android.util.Log.
@Config(sdk = [29], manifest = Config.NONE)
class ScreenConfigDeliveryTest {
    /** Records every csd-0 the shadow SDK decoders were configured with, the decoder call record. */
    private object RecordingCodec : ShadowMediaCodec.CodecConfig.Codec {
        val configurations = CopyOnWriteArrayList<ByteArray>()
        override fun onConfigured(format: MediaFormat?, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
            configurations += format?.getByteBuffer("csd-0")?.let { csd ->
                ByteArray(csd.remaining()).also { copy -> csd.duplicate().get(copy) }
            } ?: ByteArray(0)
        }
        override fun process(input: ByteBuffer, output: ByteBuffer) {}
    }

    private val surfaces = mutableListOf<Surface>()
    private lateinit var sink: AndroidMediaSink

    @Before fun setUp() {
        RecordingCodec.configurations.clear()
        ShadowMediaCodec.addDecoder(MediaFormat.MIMETYPE_VIDEO_AVC,
            ShadowMediaCodec.CodecConfig(64 * 1024, 64 * 1024, RecordingCodec))
        sink = AndroidMediaSink(surface = null)
    }

    @After fun tearDown() {
        sink.close()
        assertTrue(sink.awaitVideoReleased(5_000))
        surfaces.forEach(Surface::release)
        ShadowMediaCodec.clearCodecs()
    }

    @Test fun aReplacedSessionsLateConfigurationStaysOutOfTheSharedTypeDecoderAndMirror() {
        val engine = CarPlayMediaEngine(sink)
        val first = testSession()
        val second = testSession()
        try {
            withSharedSecret(first)
            withSharedSecret(second)
            sink.setSurface(110, surface())
            sink.setMirrorSurface(110, "dash", surface())
            val streams = streamsOf(engine)

            // The first session's configuration reaches the shared type decoder and its mirror.
            engine.onScreen(first, 110, mapOf("streamConnectionID" to 1L))
            val firstListener = listenerOf(streams[CarPlayMediaEngine.StreamKey(first, 110)] as ScreenStream)
            firstListener.onCodec(VideoCodec.H264)
            firstListener.onConfig(config(MARKER_FIRST))
            awaitConfigurations(2)
            assertEquals("the decoder and the mirror each took the first configuration",
                2, configurationsWith(MARKER_FIRST))

            // The second session takes the same type over; its configuration replaces the saved one.
            engine.onScreen(second, 110, mapOf("streamConnectionID" to 2L))
            val secondListener = listenerOf(streams[CarPlayMediaEngine.StreamKey(second, 110)] as ScreenStream)
            secondListener.onCodec(VideoCodec.H264)
            secondListener.onConfig(config(MARKER_SECOND))
            awaitConfigurations(4)
            assertEquals(config(MARKER_SECOND).toList(), lastConfig(110)!!.second.toList())

            // The first session's late configuration: its own registry entry still names its stream,
            // but the type's decoder, mirror and saved configuration now belong to the successor.
            firstListener.onConfig(config(MARKER_FIRST_LATE))
            drain()
            assertEquals("the replaced session's late configuration replaced the saved one",
                config(MARKER_SECOND).toList(), lastConfig(110)!!.second.toList())
            assertFalse("the replaced session's late configuration reached the shared decoder or mirror",
                configurationsWith(MARKER_FIRST_LATE) > 0)
            assertEquals("no decoder or mirror was reconfigured after the takeover",
                4, RecordingCodec.configurations.size)
        } finally {
            engine.onSessionClosed(first)
            engine.onSessionClosed(second)
            first.close()
            second.close()
        }
    }

    @Test fun afterTheOldSessionClosesTheTypeOwnerStillConfiguresEachScreenType() {
        val engine = CarPlayMediaEngine(sink)
        val old = testSession()
        val current = testSession()
        try {
            withSharedSecret(old)
            withSharedSecret(current)
            sink.setSurface(110, surface())
            sink.setSurface(111, surface())
            val streams = streamsOf(engine)

            // The main screen (110) and the cluster (111) are different types; both start with the
            // old session's configuration.
            engine.onScreen(old, 110, mapOf("streamConnectionID" to 1L))
            engine.onScreen(old, 111, mapOf("streamConnectionID" to 3L))
            val oldMain = listenerOf(streams[CarPlayMediaEngine.StreamKey(old, 110)] as ScreenStream)
            val oldCluster = listenerOf(streams[CarPlayMediaEngine.StreamKey(old, 111)] as ScreenStream)
            oldMain.onCodec(VideoCodec.H264)
            oldMain.onConfig(config(MARKER_FIRST))
            oldCluster.onCodec(VideoCodec.H264)
            oldCluster.onConfig(config(MARKER_CLUSTER_FIRST))
            awaitConfigurations(2)

            // The new session takes both types over.
            engine.onScreen(current, 110, mapOf("streamConnectionID" to 2L))
            engine.onScreen(current, 111, mapOf("streamConnectionID" to 4L))
            val currentMain = listenerOf(streams[CarPlayMediaEngine.StreamKey(current, 110)] as ScreenStream)
            val currentCluster = listenerOf(streams[CarPlayMediaEngine.StreamKey(current, 111)] as ScreenStream)
            currentMain.onCodec(VideoCodec.H264)
            currentMain.onConfig(config(MARKER_SECOND))
            currentCluster.onCodec(VideoCodec.H264)
            currentCluster.onConfig(config(MARKER_CLUSTER_SECOND))
            awaitConfigurations(4)
            assertEquals(config(MARKER_SECOND).toList(), lastConfig(110)!!.second.toList())
            assertEquals(config(MARKER_CLUSTER_SECOND).toList(), lastConfig(111)!!.second.toList())

            // Late deliveries from the replaced session change neither type's saved configuration.
            oldMain.onConfig(config(MARKER_FIRST_LATE))
            oldCluster.onConfig(config(MARKER_CLUSTER_FIRST_LATE))
            drain()
            assertEquals("a late main-screen configuration replaced the successor's saved one",
                config(MARKER_SECOND).toList(), lastConfig(110)!!.second.toList())
            assertEquals("a late cluster configuration replaced the successor's saved one",
                config(MARKER_CLUSTER_SECOND).toList(), lastConfig(111)!!.second.toList())

            // The old session closes; the successor keeps both types configurable (a legal delivery).
            engine.onSessionClosed(old)
            old.close()
            currentMain.onConfig(config(MARKER_SECOND_NEXT))
            currentCluster.onConfig(config(MARKER_CLUSTER_SECOND_NEXT))
            awaitConfigurations(6)
            assertEquals(config(MARKER_SECOND_NEXT).toList(), lastConfig(110)!!.second.toList())
            assertEquals(config(MARKER_CLUSTER_SECOND_NEXT).toList(), lastConfig(111)!!.second.toList())

            // A delivery still racing in on the closed session's listener changes nothing.
            oldMain.onConfig(config(MARKER_FIRST_LATE))
            drain()
            assertEquals(config(MARKER_SECOND_NEXT).toList(), lastConfig(110)!!.second.toList())
            assertEquals("a decoder was reconfigured from the closed session's late delivery",
                6, RecordingCodec.configurations.size)
            assertEquals(0, configurationsWith(MARKER_FIRST_LATE))
            assertEquals(0, configurationsWith(MARKER_CLUSTER_FIRST_LATE))
        } finally {
            engine.onSessionClosed(old)
            engine.onSessionClosed(current)
            old.close()
            current.close()
        }
    }

    /** An avcC record whose SPS carries [marker]; the decoder receives it as Annex B csd-0. */
    private fun config(marker: Int): ByteArray = byteArrayOf(
        1, 0x42, 0xC0.toByte(), 0x1E, 0xFF.toByte(), 0xE1.toByte(), 0, 4,
        0x67, marker.toByte(), 0xC0.toByte(), 0x1E, 1, 0, 2, 0x68, 0xCE.toByte(),
    )

    /** How many decoder configurations ended in the SPS carrying [marker]. */
    private fun configurationsWith(marker: Int): Int =
        RecordingCodec.configurations.count { it.contains(intArrayOf(0, 0, 0, 1, 0x67, marker)) }

    private fun ByteArray.contains(bytes: IntArray): Boolean =
        (0..size - bytes.size).any { at -> bytes.indices.all { this[at + it] == bytes[it].toByte() } }

    /** The saved configuration the sink replays to rebuilt decoders and late-attached mirrors. */
    @Suppress("UNCHECKED_CAST")
    private fun lastConfig(type: Int): Pair<VideoCodec, ByteArray>? =
        (AndroidMediaSink::class.java.getDeclaredField("lastVideoConfig").apply { isAccessible = true }
            .get(sink) as ConcurrentHashMap<Int, Pair<VideoCodec, ByteArray>>)[type]

    private fun awaitConfigurations(expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (RecordingCodec.configurations.size < expected) {
            if (System.nanoTime() > deadline) {
                error("only ${RecordingCodec.configurations.size} of $expected codec configurations arrived")
            }
            Thread.sleep(10)
        }
    }

    /** Enqueues a detach behind everything the decoder workers were given and waits for all of it. */
    private fun drain() {
        assertTrue(sink.beginSurfaceDetach(surface(), parkMain = false).await())
    }

    private fun surface() = Surface(SurfaceTexture(0)).also { surfaces += it }

    private fun withSharedSecret(session: AirPlaySession) {
        session.pairVerify.handle(Tlv8Codec.encode(listOf(
            Tlv8Item(6, byteArrayOf(1)), Tlv8Item(3, AirPlayCrypto.x25519Generate().publicKey),
        )))
        assertTrue(session.sharedSecret != null)
    }

    @Suppress("UNCHECKED_CAST")
    private fun streamsOf(engine: CarPlayMediaEngine): Map<CarPlayMediaEngine.StreamKey, Closeable> =
        CarPlayMediaEngine::class.java.getDeclaredField("streams").apply { isAccessible = true }
            .get(engine) as Map<CarPlayMediaEngine.StreamKey, Closeable>

    private fun listenerOf(screen: ScreenStream): ScreenStream.Listener =
        ScreenStream::class.java.getDeclaredField("listener").apply { isAccessible = true }
            .get(screen) as ScreenStream.Listener

    // onScreen needs the control peer's address.
    private fun connectedSocket(): Socket = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
        Socket(server.inetAddress, server.localPort)
    }

    private fun testSession(): AirPlaySession = AirPlaySession(
        socket = connectedSocket(),
        config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
        ),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = object : AirPlaySessionListener {},
        media = object : AirPlayMediaHandler {},
    )

    private companion object {
        // Distinct SPS marker bytes: every configuration byte set and every decoder call record
        // below is identified by the marker byte its SPS carries.
        const val MARKER_FIRST = 0x11
        const val MARKER_SECOND = 0x22
        const val MARKER_SECOND_NEXT = 0x55
        const val MARKER_FIRST_LATE = 0x44
        const val MARKER_CLUSTER_FIRST = 0x66
        const val MARKER_CLUSTER_SECOND = 0x33
        const val MARKER_CLUSTER_SECOND_NEXT = 0x88
        const val MARKER_CLUSTER_FIRST_LATE = 0x77
    }
}
