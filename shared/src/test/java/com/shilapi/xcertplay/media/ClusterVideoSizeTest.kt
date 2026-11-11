package com.shilapi.xcertplay.media

import android.graphics.SurfaceTexture
import android.media.MediaCodec
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

/** The instrument cluster (111) is negotiated at its own size; its decoder must not take the main screen's. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ClusterVideoSizeTest {
    private val formats = CopyOnWriteArrayList<MediaFormat>()
    private val surfaces = mutableListOf<Surface>()
    private var sink: AndroidMediaSink? = null
    private val avcC = byteArrayOf(1, 0x42, 0xC0.toByte(), 0x1E, 0xFF.toByte(), 0xE1.toByte(),
        0, 4, 0x67, 0x42, 0xC0.toByte(), 0x1E, 1, 0, 2, 0x68, 0xCE.toByte())
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte(), 0x21)

    @Before fun setUp() {
        ShadowMediaCodec.addDecoder(MediaFormat.MIMETYPE_VIDEO_AVC,
            ShadowMediaCodec.CodecConfig(64 * 1024, 64 * 1024, object : ShadowMediaCodec.CodecConfig.Codec {
                override fun onConfigured(format: MediaFormat?, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
                    formats += checkNotNull(format)
                }
                override fun process(input: ByteBuffer, output: ByteBuffer) { output.put(input) }
            }))
    }

    @After fun tearDown() {
        sink?.let { it.close(); assertTrue(it.awaitVideoReleased(5_000)) }
        surfaces.forEach(Surface::release)
        ShadowMediaCodec.clearCodecs()
    }

    @Test fun clusterDecoderUsesTheClusterSize() {
        sink = AndroidMediaSink(videoWidth = 1920, videoHeight = 880, clusterVideoWidth = 1920, clusterVideoHeight = 720)
        assertEquals("1920x880", configuredSize(110))
        assertEquals("1920x720", configuredSize(111))
    }

    @Test fun withoutAClusterSizeTheMainSizeStays() {
        sink = AndroidMediaSink(videoWidth = 1920, videoHeight = 880)
        assertEquals("1920x880", configuredSize(111))
    }

    private fun configuredSize(type: Int): String {
        formats.clear()
        sink!!.setSurface(type, surface())
        sink!!.onVideoConfig(type, avcC)
        sink!!.onVideoFrame(type, idr, 1L, System.nanoTime())
        assertTrue(sink!!.beginSurfaceDetach(surface(), parkMain = false).await())
        val format = formats.last()
        return "${format.getInteger(MediaFormat.KEY_WIDTH)}x${format.getInteger(MediaFormat.KEY_HEIGHT)}"
    }

    private fun surface() = Surface(SurfaceTexture(0)).also { surfaces += it }
}
