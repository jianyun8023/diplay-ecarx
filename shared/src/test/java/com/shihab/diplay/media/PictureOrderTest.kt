package com.shihab.diplay.media

import android.graphics.SurfaceTexture
import android.media.MediaCrypto
import android.media.MediaFormat
import android.view.Surface
import com.shihab.diplay.airplay.VideoCodec
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodec
import org.robolectric.shadows.ShadowMediaCodecList
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/** Native configuration requests picture order by the installed Qualcomm OMX codec name. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class PictureOrderTest {
    private val formats = CopyOnWriteArrayList<MediaFormat>()
    private val reports = CopyOnWriteArrayList<String>()
    private val surfaces = mutableListOf<Surface>()
    private var sink: AndroidMediaSink? = null

    @After fun tearDown() {
        sink?.let { it.close(); assertTrue(it.awaitVideoReleased(5_000)) }
        surfaces.forEach(Surface::release)
        ShadowMediaCodec.clearCodecs()
        ShadowMediaCodecList.reset()
    }

    @Test fun smoothHevcAsksQualcommOmxForPictureOrder() {
        configure(VideoCodec.H265, smooth = true)
        assertEquals(reports.joinToString("\n"), 1, formats.last().getInteger(PICTURE_ORDER_PARAMETER, 0))
        assertTrue(reports.any { "mime=video/hevc" in it && "pictureOrder=true" in it })
    }

    @Test fun smoothH264KeepsPictureOrder() {
        configure(VideoCodec.H264, smooth = true)
        assertEquals(reports.joinToString("\n"), 1, formats.last().getInteger(PICTURE_ORDER_PARAMETER, 0))
    }

    @Test fun hevcWithoutSmoothVideoKeepsDecoderOrder() {
        configure(VideoCodec.H265, smooth = false)
        assertFalse(formats.single().containsKey(PICTURE_ORDER_PARAMETER))
    }

    @Test fun otherHevcDecodersReceiveNoUnlistedPictureOrder() {
        configure(VideoCodec.H265, smooth = true, name = "c2.qti.hevc.decoder")
        assertFalse(formats.single().containsKey(PICTURE_ORDER_PARAMETER))
        assertTrue(reports.any { "mime=video/hevc" in it && "pictureOrder=false" in it })
    }

    private fun configure(codec: VideoCodec, smooth: Boolean, name: String = "OMX.qcom.video.decoder.test") {
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName(name)
            .setCapabilities(*listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC).map {
                MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                    .setMediaFormat(MediaFormat.createVideoFormat(it, 1920, 1080))
                    .setProfileLevels(arrayOf()).setColorFormats(intArrayOf()).build()
            }.toTypedArray()).build())
        val mime = if (codec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        ShadowMediaCodec.addDecoder(name,
            ShadowMediaCodec.CodecConfig(64 * 1024, 64 * 1024, object : ShadowMediaCodec.CodecConfig.Codec {
                override fun onConfigured(format: MediaFormat?, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
                    formats += checkNotNull(format)
                }
                override fun process(input: ByteBuffer, output: ByteBuffer) { output.put(input) }
            }))
        sink = AndroidMediaSink(videoPacingDelayMillis = if (smooth) 90 else 0, mainVideoFrameRate = 60)
        sink!!.setVideoDiagnosticHandler(MAIN) { reports += it }
        sink!!.setSurface(MAIN, surface())
        sink!!.onVideoCodec(MAIN, codec)
        sink!!.onVideoConfig(MAIN, if (codec == VideoCodec.H265) HVCC else AVCC)
        sink!!.onVideoFrame(MAIN, if (codec == VideoCodec.H265) HEVC_IDR else AVC_IDR, 1L, System.nanoTime())
        assertTrue(sink!!.beginSurfaceDetach(surface(), parkMain = false).await())
    }

    private fun surface() = Surface(SurfaceTexture(0)).also { surfaces += it }

    private companion object {
        const val MAIN = 110
        val AVCC = byteArrayOf(1, 0x42, 0xC0.toByte(), 0x1E, 0xFF.toByte(), 0xE1.toByte(),
            0, 4, 0x67, 0x42, 0xC0.toByte(), 0x1E, 1, 0, 2, 0x68, 0xCE.toByte())
        val AVC_IDR = byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte(), 0x21)
        // The iPhone's 1280x720 HEVC config from the Mi 8 bench (sps_max_num_reorder_pics=0).
        val HVCC = ("010160000000b000000000005df000fcfdf8f800000b03a00001001840010c01ffff016000000300b0000003" +
            "000003005d1b0240a10001002a420101016000000300b0000003000003005da00280802d16206e49148cb9fc4fc2fe86fd" +
            "4a6e021a0201a2000100074401c0724c16c900000012636f6c726e636c630001000d000100000000")
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val HEVC_IDR = byteArrayOf(0, 0, 0, 1, 0x26, 0x01, 0xAF.toByte(), 0x06)
    }
}
