package com.shihab.diplay.media

import android.graphics.SurfaceTexture
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** v146 17:59:13: a 1.9 s configure filled the queue, and the overflow rebuilt the codec it had just made. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SlowConfigureOverflowTest {
    private val configured = CopyOnWriteArrayList<MediaFormat>()
    private val reports = CopyOnWriteArrayList<String>()
    private val surfaces = mutableListOf<Surface>()
    private val configuring = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private var sink: AndroidMediaSink? = null
    private val avcC = byteArrayOf(1, 0x42, 0xC0.toByte(), 0x1E, 0xFF.toByte(), 0xE1.toByte(),
        0, 4, 0x67, 0x42, 0xC0.toByte(), 0x1E, 1, 0, 2, 0x68, 0xCE.toByte())
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte(), 0x21)
    private val predicted = byteArrayOf(0, 0, 0, 1, 0x41, 1)

    @Before fun setUp() {
        ShadowMediaCodec.addDecoder(MediaFormat.MIMETYPE_VIDEO_AVC,
            ShadowMediaCodec.CodecConfig(64 * 1024, 64 * 1024, object : ShadowMediaCodec.CodecConfig.Codec {
                override fun onConfigured(format: MediaFormat?, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
                    configured += checkNotNull(format)
                    configuring.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
                override fun process(input: ByteBuffer, output: ByteBuffer) { output.put(input) }
            }))
    }

    @After fun tearDown() {
        release.countDown()
        sink?.let { it.close(); assertTrue(it.awaitVideoReleased(5_000)) }
        surfaces.forEach(Surface::release)
        ShadowMediaCodec.clearCodecs()
    }

    @Test fun anOverflowDuringTheFirstConfigureKeepsTheUnusedCodec() {
        val sink = AndroidMediaSink(videoWidth = 1920, videoHeight = 880).also { sink = it }
        sink.setVideoDiagnosticHandler(110) { reports += it }
        sink.setSurface(110, surface())
        sink.onVideoConfig(110, avcC)
        sink.onVideoFrame(110, idr, 1L, System.nanoTime())
        assertTrue(configuring.await(5, TimeUnit.SECONDS))
        repeat(70) { sink.onVideoFrame(110, predicted, it + 2L, System.nanoTime()) }
        release.countDown()
        barrier()
        sink.onVideoFrame(110, idr, 100L, System.nanoTime())
        barrier()
        assertEquals(1, configured.size)
        assertTrue(reports.toString(), reports.any { "video queue overflow" in it && "decoder kept" in it })
    }

    private fun barrier() { assertTrue(sink!!.beginSurfaceDetach(surface(), parkMain = false).await()) }
    private fun surface() = Surface(SurfaceTexture(0)).also { surfaces += it }
}
