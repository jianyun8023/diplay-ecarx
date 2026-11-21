package com.shihab.diplay.media

import android.graphics.SurfaceTexture
import com.shihab.diplay.airplay.VideoStreamEvent
import com.shihab.diplay.testcore.HostVideoPipe
import android.media.MediaCrypto
import android.media.MediaFormat
import android.view.Surface
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaCodec
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Low-latency decoding releases decoded frames from a thread of its own, which stops with the decoder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class NativeVideoLoopTest {
    private val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1E)
    private val pps = byteArrayOf(0x68, 0xCE.toByte())
    private val avcC = byteArrayOf(1, 0x42, 0xC0.toByte(), 0x1E, 0xFF.toByte(), 0xE1.toByte(), 0, sps.size.toByte()) +
        sps + byteArrayOf(1, 0, pps.size.toByte()) + pps
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte(), 0x21)
    private val predicted = byteArrayOf(0, 0, 0, 1, 0x41, 0x9A.toByte())
    private val uncaught = ConcurrentLinkedQueue<Throwable>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    private object PassThroughCodec : ShadowMediaCodec.CodecConfig.Codec {
        override fun process(input: ByteBuffer, output: ByteBuffer) = Unit
        override fun onConfigured(format: MediaFormat?, surface: Surface?, crypto: MediaCrypto?, flags: Int) = Unit
    }

    @Before fun setUp() {
        ShadowMediaCodec.addDecoder(MediaFormat.MIMETYPE_VIDEO_AVC, ShadowMediaCodec.CodecConfig(64 * 1024, 64 * 1024, PassThroughCodec))
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught += error }
    }

    @After fun tearDown() {
        HostVideoPipe.failCreate = false
        ShadowMediaCodec.clearCodecs()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    @Test fun nativeLoopOwnsRenderingAndReleasesWithTheSink() = assertNativeRendering(vendorLowLatency = false)

    @Test fun lowLatencyUsesTheSameNativeLoop() = assertNativeRendering(vendorLowLatency = true)

    @Test fun requestedFrameSampleDoesNotRequireDecoderRecreationOrRepeatPerFrame() {
        val first = CountDownLatch(1)
        val sampled = CountDownLatch(1)
        val samples = CopyOnWriteArrayList<String>()
        val typedEvents = CopyOnWriteArrayList<VideoStreamEvent>()
        val surface = Surface(SurfaceTexture(0))
        val sink = AndroidMediaSink()
        HostVideoPipe.postedFrameSamples.clear()
        try {
            assertFalse(sink.requestVideoFrameSample())
            sink.setVideoDiagnosticHandler(MAIN) {
                if (it.startsWith("frame sample ")) { samples += it; }
            }
            sink.setVideoEventHandler(MAIN) { event ->
                typedEvents += event
                if (event == VideoStreamEvent.FirstFrameRendered) {
                    assertTrue(sink.requestVideoFrameSample())
                    first.countDown()
                }
                if (event is VideoStreamEvent.FrameSampled) sampled.countDown()
            }
            sink.setSurface(MAIN, surface)
            sink.onVideoConfig(MAIN, avcC)
            sink.onVideoFrame(MAIN, idr)
            assertTrue(first.await(5, TimeUnit.SECONDS))
            sink.onVideoFrame(MAIN, idr)
            assertTrue(sampled.await(5, TimeUnit.SECONDS))
            repeat(5) { sink.onVideoFrame(MAIN, idr) }
            assertTrue(sink.beginSurfaceDetach(surface, parkMain = false).await())
            assertEquals(1, samples.size)
            assertEquals("1", samples.single().split(' ')[3])
            // The listener receives the decode loop's own fields, one per field, never reparsed text.
            val decoder = mainDecoder(sink) as VideoDecoder
            val sample = typedEvents.filterIsInstance<VideoStreamEvent.FrameSampled>().single()
            val posted = HostVideoPipe.postedFrameSamples.single()
            assertEquals(posted.first, sample.sampledAt)
            assertEquals(posted.second, sample.epoch)
            assertEquals(System.identityHashCode(decoder), sample.decoderId)
            assertEquals(1, typedEvents.count { it == VideoStreamEvent.FirstFrameRendered })
            assertEquals("frame sample ${posted.first} ${posted.second} ${System.identityHashCode(decoder)}", samples.single())
        } finally {
            sink.close()
            assertTrue(sink.awaitVideoReleased(5_000))
            surface.release()
        }
    }

    private fun assertNativeRendering(vendorLowLatency: Boolean) {
        val reports = CopyOnWriteArrayList<String>()
        val rendered = CountDownLatch(1)
        val before = HostVideoPipe.activeCount
        val sink = AndroidMediaSink(surface = null, vendorLowLatencyDecoder = vendorLowLatency)
        val surface = Surface(SurfaceTexture(0))
        try {
            sink.setVideoDiagnosticHandler(MAIN) { message ->
                reports += message
                if (message == "first frame rendered") rendered.countDown()
            }
            sink.setSurface(MAIN, surface)
            sink.onVideoConfig(MAIN, avcC)
            sink.onVideoFrame(MAIN, idr)
            assertTrue("a decoded frame was rendered", rendered.await(5, TimeUnit.SECONDS))
            assertEquals(before + 1, HostVideoPipe.activeCount)
            assertEquals(1L, mainDecoder(sink).let { it as VideoDecoder }.liveCounters().rendered)
            val handle = mainDecoder(sink).field<Any>("pipe").field<Long>("handle")
            assertNotNull(HostVideoPipe.codec(handle))
            assertTrue(sink.beginSurfaceDetach(surface, parkMain = false).await())
            assertNull(HostVideoPipe.codec(handle))
        } finally {
            sink.close()
            assertTrue(sink.awaitVideoReleased(5_000))
            surface.release()
        }
        assertEquals(before, HostVideoPipe.activeCount)
        assertTrue(uncaught.toString(), uncaught.isEmpty())
        assertFalse(reports.toString(), reports.any { it.startsWith("decoder failed") })
    }

    @Test fun nativeLoopCreationFailureIsExplicitAndDoesNotRegisterABackupDecoder() {
        val before = HostVideoPipe.activeCount
        val sink = AndroidMediaSink(surface = null)
        try {
            HostVideoPipe.failCreate = true
            val failure = assertThrows(IllegalStateException::class.java) { sink.onVideoConfig(MAIN, avcC) }
            assertEquals("Native video decode loop could not be created", failure.message)
            assertTrue(sink.field<Map<Int, Any>>("videoDecoders").isEmpty())
            assertEquals(before, HostVideoPipe.activeCount)
            HostVideoPipe.failCreate = false
            sink.onVideoConfig(MAIN, avcC)
            assertEquals(before + 1, HostVideoPipe.activeCount)
        } finally {
            HostVideoPipe.failCreate = false
            sink.close()
            assertTrue(sink.awaitVideoReleased(5_000))
        }
        assertEquals(before, HostVideoPipe.activeCount)
    }

    @Test @Config(sdk = [27]) fun prePieVideoFailsWithTheMinimumSupportedVersion() {
        val before = HostVideoPipe.activeCount
        val failure = assertThrows(IllegalStateException::class.java) { NativeVideoPipe.create(0, false) { _, _, _, _ -> } }
        assertEquals("Native video decoding requires Android 9 / API 28", failure.message)
        assertEquals(before, HostVideoPipe.activeCount)
    }

    @Test fun clearingSurfacePreventsSpuriousKeyframeRecoveryOnTrailingNonIdrFrames() {
        val rendered = CountDownLatch(1)
        val recoveries = AtomicInteger()
        val surface = Surface(SurfaceTexture(0))
        val sink = AndroidMediaSink()
        try {
            sink.setVideoDiagnosticHandler(111) { if (it == "first frame rendered") rendered.countDown() }
            sink.setVideoRecoveryHandler(111) { recoveries.incrementAndGet() }
            sink.setSurface(111, surface)
            sink.onVideoConfig(111, avcC)
            sink.onVideoFrame(111, idr)
            assertTrue(rendered.await(5, TimeUnit.SECONDS))
            sink.clearSurface(111, surface)
            assertFalse(sink.hasActiveOutputSurface(111))
            repeat(5) { sink.onVideoFrame(111, predicted) }
            val reattached = Surface(SurfaceTexture(0))
            try {
                sink.setSurface(111, reattached)
                assertTrue(sink.beginSurfaceDetach(reattached, parkMain = false).await())
            } finally {
                reattached.release()
            }
            assertEquals(0, recoveries.get())
        } finally {
            sink.close()
            assertTrue(sink.awaitVideoReleased(5_000))
            surface.release()
        }
    }

    @Test fun inFlightMainRecoveryDoesNotDropSimultaneousClusterRecovery() {
        val mainSurface = Surface(SurfaceTexture(0))
        val clusterSurface = Surface(SurfaceTexture(0))
        val mainRecovered = CountDownLatch(1)
        val clusterRecovered = CountDownLatch(1)
        val sink = AndroidMediaSink()
        try {
            val requestRecovery = AndroidMediaSink::class.java
                .getDeclaredMethod("requestVideoRecovery", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
            sink.setSurface(MAIN, mainSurface)
            sink.setSurface(111, clusterSurface)
            sink.setVideoRecoveryHandler(MAIN) {
                val pending = sink.field<MutableSet<Int>>("recoveryPending")
                assertTrue(pending.contains(MAIN))
                requestRecovery.invoke(sink, 111)
                assertTrue(pending.contains(111))
                mainRecovered.countDown()
            }
            sink.setVideoRecoveryHandler(111) {
                clusterRecovered.countDown()
            }
            requestRecovery.invoke(sink, MAIN)
            assertTrue(mainRecovered.await(5, TimeUnit.SECONDS))
            assertTrue(clusterRecovered.await(5, TimeUnit.SECONDS))
        } finally {
            sink.close()
            assertTrue(sink.awaitVideoReleased(5_000))
            mainSurface.release()
            clusterSurface.release()
        }
    }

    private fun mainDecoder(sink: AndroidMediaSink): Any =
        checkNotNull(sink.field<Map<Int, Any>>("videoDecoders")[MAIN]) { "no main decoder" }

    @Suppress("UNCHECKED_CAST")
    private fun <T> Any.field(name: String): T {
        var type: Class<*>? = javaClass
        while (type != null) {
            try {
                return type.getDeclaredField(name).apply { isAccessible = true }.get(this) as T
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        error("no field $name on $javaClass")
    }

    private companion object {
        const val MAIN = 110
    }
}
