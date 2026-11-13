package com.shihab.diplay.media

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCrypto
import android.media.MediaFormat
import android.view.Surface
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaCodec
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/** The dashboard map and its copies (centre card, launchers) come from one hardware decoder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [MapMirrorSharedDecoderTest.TrackedCodec::class])
class MapMirrorSharedDecoderTest {
    private val surfaces = mutableListOf<Surface>()
    private val fanouts = CopyOnWriteArrayList<FakeFanout>()
    private val reports = CopyOnWriteArrayList<String>()
    private lateinit var sink: AndroidMediaSink
    private val avcC = byteArrayOf(1, 0x42, 0xC0.toByte(), 0x1E, 0xFF.toByte(), 0xE1.toByte(),
        0, 4, 0x67, 0x42, 0xC0.toByte(), 0x1E, 1, 0, 2, 0x68, 0xCE.toByte())
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte(), 0x21)

    @Before fun setUp() {
        TrackedCodec.outputs.clear()
        TrackedCodec.live.set(0)
        ShadowMediaCodec.addDecoder(MediaFormat.MIMETYPE_VIDEO_AVC,
            ShadowMediaCodec.CodecConfig(64 * 1024, 64 * 1024, object : ShadowMediaCodec.CodecConfig.Codec {
                override fun onConfigured(format: MediaFormat?, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
                    TrackedCodec.outputs += surface
                }
                override fun process(input: ByteBuffer, output: ByteBuffer) { output.put(input) }
            }))
        sink = AndroidMediaSink(surface = null)
        sink.videoFanoutFactory = { _, _, _ -> FakeFanout(surface()).also { fanouts += it } }
        sink.setVideoDiagnosticHandler(MAP) { reports += it }
    }

    @After fun tearDown() {
        sink.close()
        assertTrue(sink.awaitVideoReleased(5_000))
        surfaces.forEach(Surface::release)
        ShadowMediaCodec.clearCodecs()
    }

    @Test fun mirrorsOfTheMapShareTheMapsDecoder() {
        val dashboard = surface()
        val card = surface()
        val launcher = surface()
        sink.setSurface(MAP, dashboard)
        start()
        assertSame(dashboard, TrackedCodec.outputs.single())

        sink.setMirrorSurface(MAP, "card", card)
        sink.setMirrorSurface(MAP, "launcher:1", launcher)
        frame()
        val fanout = fanouts.single()
        assertEquals("one decoder for the map and both copies", 1, TrackedCodec.live.get())
        assertSame("the codec moved to the fanout without a rebuild", fanout.input, TrackedCodec.lastOutputSurface)
        assertSame(dashboard, fanout.shownOn)
        assertEquals(mapOf("card" to card, "launcher:1" to launcher), fanout.mirrors)

        sink.setMirrorSurface(MAP, "card", null)
        frame()
        assertEquals(mapOf("launcher:1" to launcher), fanout.mirrors)
        sink.setMirrorSurface(MAP, "launcher:1", null)
        frame()
        assertTrue("the fanout freed the dashboard surface before the codec went back", fanout.closed)
        assertNull(fanout.shownOn)
        assertSame(dashboard, TrackedCodec.lastOutputSurface)
        assertEquals(1, TrackedCodec.live.get())
    }

    @Test fun aMirrorWithoutTheDashboardStillDecodesOnce() {
        val card = surface()
        sink.setMirrorSurface(MAP, "card", card)
        start()
        val fanout = fanouts.single()
        assertSame(fanout.input, TrackedCodec.outputs.single())
        assertNull(fanout.shownOn)
        assertEquals(1, TrackedCodec.live.get())

        // The dashboard appearing later only becomes another target.
        val dashboard = surface()
        sink.setSurface(MAP, dashboard)
        frame()
        assertSame(dashboard, fanout.shownOn)
        assertEquals(1, TrackedCodec.outputs.size)
    }

    @Test fun detachingTheDashboardSurfaceKeepsTheMirrorsRunning() {
        val dashboard = surface()
        sink.setSurface(MAP, dashboard)
        sink.setMirrorSurface(MAP, "card", surface())
        start()
        val fanout = fanouts.single()
        assertSame(dashboard, fanout.shownOn)
        assertTrue(sink.beginSurfaceDetach(dashboard, parkMain = false).await())
        assertNull("the fanout let go of the surface before the detach returned", fanout.shownOn)
        assertFalse(fanout.closed)
        assertEquals(1, TrackedCodec.live.get())
    }

    @Test fun aNewMapDecoderPicksUpTheMirrors() {
        val card = surface()
        sink.setMirrorSurface(MAP, "card", card)
        start()
        sink.onScreenStreamActive(MAP, false)
        sink.onScreenStreamActive(MAP, true)
        sink.setVideoDiagnosticHandler(MAP) { reports += it }
        start()
        assertEquals(2, fanouts.size)
        assertTrue(fanouts.first().closed)
        assertEquals(mapOf("card" to card), fanouts.last().mirrors)
    }

    @Test fun theMainScreenKeepsADecoderPerMirror() {
        assertFalse(mirrorsShareDecoder(MAIN_SCREEN_TYPE))
        assertTrue(mirrorsShareDecoder(MAP))
    }

    private fun start() {
        sink.onVideoConfig(MAP, avcC)
        frame()
    }

    private fun frame() {
        sink.onVideoFrame(MAP, idr)
        assertTrue(sink.beginSurfaceDetach(Surface(SurfaceTexture(0)).also { surfaces += it }, parkMain = false).await())
    }

    private fun surface() = Surface(SurfaceTexture(0)).also { surfaces += it }

    private class FakeFanout(override val input: Surface) : VideoFanout {
        @Volatile var shownOn: Surface? = null
        val mirrors = LinkedHashMap<String, Surface>()
        @Volatile var closed = false
        override fun setPrimary(surface: Surface?) { check(!closed); shownOn = surface }
        override fun setMirror(key: String, surface: Surface?) {
            check(!closed)
            if (surface == null) mirrors.remove(key) else mirrors[key] = surface
        }
        override fun close() { shownOn = null; closed = true }
    }

    @Implements(MediaCodec::class)
    class TrackedCodec : ShadowMediaCodec() {
        private var released = false
        @Implementation protected fun native_setup(name: String, nameIsType: Boolean, encoder: Boolean) {
            live.incrementAndGet()
        }
        @Implementation protected fun native_setSurface(surface: Surface) { lastOutputSurface = surface }
        @Implementation override fun native_configure(
            keys: Array<String>?, values: Array<Any>?, surface: Surface?, crypto: MediaCrypto?, flags: Int,
        ) {
            lastOutputSurface = surface
            super.native_configure(keys, values, surface, crypto, flags)
        }
        @Implementation protected fun native_release() {
            if (!released) { released = true; live.decrementAndGet() }
        }
        companion object {
            val outputs = CopyOnWriteArrayList<Surface?>()
            val live = java.util.concurrent.atomic.AtomicInteger()
            @Volatile var lastOutputSurface: Surface? = null
        }
    }

    private companion object {
        const val MAP = 111
    }
}

class FanoutStatsTest {
    private val second = 1_000_000_000L

    @Test fun oneLinePerWindowWithRateDrawTimeAndCpuShare() {
        val stats = FanoutStats(intervalNanos = 10 * second)
        var line: String? = null
        // 30 fps: frame k starts at k/30 s and takes 2 ms on the wall (the last one 8 ms) and 1 ms of CPU.
        for (k in 0..300) {
            val wall = if (k == 300) 8_000_000L else 2_000_000L
            line = stats.onFrame(k * second / 30 + wall, wall, 1_000_000L, targets = 3) ?: line
        }
        assertEquals("video fanout windowS=10 frames=301 fps=30.1 drawAvgMs=2.0 drawMaxMs=8.0 cpu=3.0% targets=3 dropped=0", line)
    }

    @Test fun nothingBeforeTheWindowEndsAndTheRestAtClose() {
        val stats = FanoutStats(intervalNanos = 300 * second)
        assertNull(stats.onFrame(second, 1_000_000L, -1, targets = 2))
        stats.onTargetDropped()
        assertEquals("video fanout windowS=1 frames=1 fps=1.0 drawAvgMs=1.0 drawMaxMs=1.0 cpu=unknown targets=1 dropped=1",
            stats.flush(2 * second - 1_000_000L, targets = 1))
        assertNull("an empty window writes nothing", stats.flush(3 * second, targets = 1))
    }
}
