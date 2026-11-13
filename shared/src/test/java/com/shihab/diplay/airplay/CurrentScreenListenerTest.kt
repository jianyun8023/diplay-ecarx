package com.shihab.diplay.airplay

import com.shihab.diplay.hud.NavigationOutputs
import java.io.Closeable
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A replaced screen stream's thread may still be delivering when its successor starts. Its codec, config and
 * frames must not reach the sink, which by then belongs to the successor's decoder.
 */
@RunWith(RobolectricTestRunner::class) // CarPlayMediaEngine.onScreen logs through android.util.Log.
@Config(sdk = [29], manifest = Config.NONE)
class CurrentScreenListenerTest {
    private sealed class Event {
        data class Codec(val type: Int, val codec: VideoCodec) : Event()
        data class CodecData(val type: Int, val data: List<Byte>) : Event()
        data class Frame(val type: Int, val data: List<Byte>) : Event()
        data class TimedFrame(val type: Int, val data: List<Byte>, val senderNanos: Long, val arrivalNanos: Long) : Event()
    }

    /** Records the four video deliveries; the 4-argument frame is recorded on its own, not via the 3-argument default. */
    private class RecordingSink : MediaSink {
        val events: MutableList<Event> = Collections.synchronizedList(mutableListOf())
        override fun onVideoCodec(type: Int, codec: VideoCodec) { events += Event.Codec(type, codec) }
        override fun onVideoConfig(type: Int, codecData: ByteArray) { events += Event.CodecData(type, codecData.toList()) }
        override fun onVideoFrame(type: Int, naluBytes: ByteArray) { events += Event.Frame(type, naluBytes.toList()) }
        override fun onVideoFrame(type: Int, naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) {
            events += Event.TimedFrame(type, naluBytes.toList(), senderNanos, arrivalNanos)
        }
        fun take(): List<Event> = synchronized(events) { events.toList().also { events.clear() } }
    }

    private val config = byteArrayOf(0, 0, 0, 1, 0x67, 0x42)
    private val frame = byteArrayOf(0, 0, 0, 1, 0x65, 0x11)
    private val timedFrame = byteArrayOf(0, 0, 0, 1, 0x41, 0x22)

    @Test fun nativeRouteAndReceivingNotificationOnlyReachTheCurrentStream() {
        val routes = mutableListOf<NativeVideoRoute>()
        val receiving = mutableListOf<Int>()
        val sink = object : MediaSink {
            override fun onNativeVideoRoute(type: Int, route: NativeVideoRoute) { routes += route }
            override fun onVideoReceiving(type: Int) { receiving += type }
        }
        val current = AtomicBoolean(true)
        val listener = currentScreenListener(111, sink) { current.get() }
        val route = NativeVideoRoute(0)
        listener.onNativeVideoRoute(route)
        listener.onReceiving()
        current.set(false)
        listener.onNativeVideoRoute(NativeVideoRoute(0))
        listener.onReceiving()
        assertEquals(listOf(route), routes)
        assertEquals(listOf(111), receiving)
    }

    private fun ScreenStream.Listener.deliverAll(codec: VideoCodec = VideoCodec.H265) {
        onCodec(codec)
        onConfig(config)
        onFrame(frame)
        onFrame(timedFrame, 1_234_567_890L, 9_876_543_210L)
    }

    private fun expected(type: Int, codec: VideoCodec = VideoCodec.H265) = listOf(
        Event.Codec(type, codec),
        Event.CodecData(type, config.toList()),
        Event.Frame(type, frame.toList()),
        Event.TimedFrame(type, timedFrame.toList(), 1_234_567_890L, 9_876_543_210L),
    )

    @Test
    fun currentStreamDeliversEveryCallbackWithItsTypeAndTimes() {
        val sink = RecordingSink()
        val current = AtomicBoolean(true)
        val listener = currentScreenListener(111, sink) { current.get() }

        listener.deliverAll()

        assertEquals(expected(111), sink.take())
    }

    @Test
    fun replacedStreamDeliversNothing() {
        val sink = RecordingSink()
        val current = AtomicBoolean(true)
        val listener = currentScreenListener(110, sink) { current.get() }
        listener.deliverAll(VideoCodec.H264)
        assertEquals(expected(110, VideoCodec.H264), sink.take())

        current.set(false) // A replacing stream for the same key has been registered.
        listener.deliverAll(VideoCodec.H264)

        assertEquals(emptyList<Event>(), sink.take())
    }

    @Test
    fun currencyIsCheckedAtEachDeliveryNotOnce() {
        val sink = RecordingSink()
        val current = AtomicBoolean(true)
        var checks = 0
        val listener = currentScreenListener(110, sink) { checks++; current.get() }

        listener.onCodec(VideoCodec.H264)
        listener.onConfig(config)
        current.set(false)
        listener.onFrame(frame)
        listener.onFrame(timedFrame, 1L, 2L)

        assertEquals(4, checks)
        assertEquals(
            listOf(Event.Codec(110, VideoCodec.H264), Event.CodecData(110, config.toList())),
            sink.take(),
        )
    }

    @Test
    fun onScreenStopsTheReplacedStreamsListenerForTheSameKey() {
        val sink = RecordingSink()
        val engine = CarPlayMediaEngine(sink)
        val session = testSession()
        try {
            withSharedSecret(session)
            val streams = streamsOf(engine)
            val mainKey = CarPlayMediaEngine.StreamKey(session, 110)

            assertNotNull(engine.onScreen(session, 110, mapOf("streamConnectionID" to 1L)))
            val first = streams[mainKey] as ScreenStream
            val firstListener = listenerOf(first)
            firstListener.deliverAll()
            assertEquals(expected(110), sink.take())

            // The cluster stream of the same session has a different key and is not affected by the replacement.
            assertNotNull(engine.onScreen(session, 111, mapOf("streamConnectionID" to 3L)))
            val clusterListener = listenerOf(streams[CarPlayMediaEngine.StreamKey(session, 111)] as ScreenStream)

            assertNotNull(engine.onScreen(session, 110, mapOf("streamConnectionID" to 2L)))
            val second = streams[mainKey] as ScreenStream
            assertNotSame(first, second)
            val secondListener = listenerOf(second)

            // The replaced stream's thread was already past its read when it was closed and still delivers.
            firstListener.deliverAll()
            assertEquals(emptyList<Event>(), sink.take())

            secondListener.deliverAll(VideoCodec.H264)
            assertEquals(expected(110, VideoCodec.H264), sink.take())
            clusterListener.deliverAll()
            assertEquals(expected(111), sink.take())
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

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

    @Test fun onlyTheCurrentMainStreamsRenderedFrameMarksTheSessionReady() {
        val diagnostics = mutableMapOf<Int, (String) -> Unit>()
        val sink = object : MediaSink {
            override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {
                diagnostics[type] = handler
            }
        }
        val rendered = mutableListOf<AirPlaySession>()
        val session = testSession(object : AirPlaySessionListener {
            override fun onVideoFrameRendered(session: AirPlaySession) { rendered += session }
        })
        val engine = CarPlayMediaEngine(sink)
        try {
            withSharedSecret(session)
            engine.onScreen(session, 111, mapOf("streamConnectionID" to 1L))
            diagnostics.getValue(111)("first frame rendered")
            assertFalse(session.hasRenderedMainFrame)
            assertTrue(rendered.isEmpty())

            engine.onScreen(session, 110, mapOf("streamConnectionID" to 2L))
            val replaced = diagnostics.getValue(110)
            engine.onScreen(session, 110, mapOf("streamConnectionID" to 3L))
            replaced("first frame rendered")
            assertFalse(session.hasRenderedMainFrame)
            diagnostics.getValue(110)("decoder configured")
            assertFalse(session.hasRenderedMainFrame)
            diagnostics.getValue(110)("first frame rendered")
            assertTrue(session.hasRenderedMainFrame)
            assertEquals(listOf(session), rendered)

            session.close()
            assertFalse(session.hasRenderedMainFrame)
            diagnostics.getValue(110)("first frame rendered")
            assertEquals(listOf(session), rendered)
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test fun clusterDecoderDiagnosticsOnlyReportCurrentStreamAndStructuredData() {
        val reports = mutableListOf<String>()
        NavigationOutputs.attachReport { reports += it }
        val diagnostics = mutableMapOf<Int, (String) -> Unit>()
        val sink = object : MediaSink {
            override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {
                diagnostics[type] = handler
            }
        }
        val engine = CarPlayMediaEngine(sink)
        val old = testSession()
        val current = testSession()
        val format = "output format requested=1600x600 coded=1600x608 crop=0,0,1599,599 " +
            "stride=1600 slice=608 color=null/null/null"
        try {
            withSharedSecret(old)
            withSharedSecret(current)
            engine.onScreen(old, 111, mapOf("streamConnectionID" to 1L))
            val oldCallback = diagnostics.getValue(111)
            engine.onScreen(current, 111, mapOf("streamConnectionID" to 2L))
            val currentCallback = diagnostics.getValue(111)
            engine.onScreen(current, 110, mapOf("streamConnectionID" to 3L))
            reports.clear()
            oldCallback(format)
            oldCallback("first frame rendered")
            diagnostics.getValue(110)(format)
            diagnostics.getValue(110)("first frame rendered")
            currentCallback("first frame rendered private-road")
            assertTrue(reports.isEmpty())
            currentCallback("$format private-road")
            currentCallback("first frame rendered")
            assertEquals(2, reports.size)
            assertTrue(reports[0].contains("stream=111 decoderFormat requested=1600x600 coded=1600x608 crop=0,0,1599,599"))
            assertTrue(reports[1].contains("stream=111 decoderFirstFrame=submitted"))
            assertTrue(reports.none { it.contains("private-road") })
            engine.onTeardown(current, 111)
            currentCallback("first frame rendered")
            assertEquals(2, reports.size)
        } finally {
            engine.onSessionClosed(old)
            engine.onSessionClosed(current)
            old.close()
            current.close()
        }
    }

    // onScreen needs the control peer's address.
    private fun connectedSocket(): Socket = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
        Socket(server.inetAddress, server.localPort)
    }

    private fun testSession(listener: AirPlaySessionListener = object : AirPlaySessionListener {}): AirPlaySession = AirPlaySession(
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
        listener = listener,
        media = object : AirPlayMediaHandler {},
    )
}
