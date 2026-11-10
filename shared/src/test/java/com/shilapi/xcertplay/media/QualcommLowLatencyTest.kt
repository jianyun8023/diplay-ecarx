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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaCodec
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/** API 28 has neither the standard low-latency feature nor vendor-parameter enumeration. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [QualcommLowLatencyTest.NamedCodec::class])
class QualcommLowLatencyTest {
    private val formats = CopyOnWriteArrayList<MediaFormat>()
    private val reports = CopyOnWriteArrayList<String>()
    private val surfaces = mutableListOf<Surface>()
    private var sink: AndroidMediaSink? = null
    private var rejectConfigure = false
    private var rejectPictureOrder = false
    private val avcC = byteArrayOf(1, 0x42, 0xC0.toByte(), 0x1E, 0xFF.toByte(), 0xE1.toByte(),
        0, 4, 0x67, 0x42, 0xC0.toByte(), 0x1E, 1, 0, 2, 0x68, 0xCE.toByte())
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte(), 0x21)

    @Before fun setUp() {
        NamedCodec.name = "OMX.qcom.video.decoder.avc"
        NamedCodec.rejectStart = false
        NamedCodec.failInput = false
        NamedCodec.privateRequest = false
        ShadowMediaCodec.addDecoder(MediaFormat.MIMETYPE_VIDEO_AVC,
            ShadowMediaCodec.CodecConfig(64 * 1024, 64 * 1024, object : ShadowMediaCodec.CodecConfig.Codec {
                override fun onConfigured(format: MediaFormat?, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
                    formats += checkNotNull(format)
                    NamedCodec.privateRequest = format.containsKey(QTI_LOW_LATENCY_PARAMETER)
                    check(!rejectConfigure || !NamedCodec.privateRequest) { "private parameter rejected" }
                    check(!rejectPictureOrder || !format.containsKey(PICTURE_ORDER_PARAMETER)) { "picture order rejected" }
                }
                override fun process(input: ByteBuffer, output: ByteBuffer) { output.put(input) }
            }))
    }

    @After fun tearDown() {
        sink?.let { it.close(); assertTrue(it.awaitVideoReleased(5_000)) }
        surfaces.forEach(Surface::release)
        ShadowMediaCodec.clearCodecs()
    }

    @Test fun pieSubmitsOnlyLowLatencyAndDoesNotClaimItIsEffective() {
        configure()
        assertEquals(1, formats.single().getInteger(QTI_LOW_LATENCY_PARAMETER))
        assertFalse(formats.single().containsKey(PICTURE_ORDER_PARAMETER))
        assertFalse(formats.single().containsKey("low-latency"))
        assertTrue(reports.any { "configure/start returned" in it && "effectiveness=unverified" in it })
        assertTrue(reports.any { "qtiLowLatencyRequested=true qtiLowLatencySubmitted=true" in it })
        assertTrue(reports.any { "driver-reported" in it && "unreported" in it })
    }

    @Test fun configureRejectionRetriesSameTuningWithoutPrivateKeysAndRemembersFallback() {
        rejectConfigure = true
        configure()
        assertEquals(listOf(true, false), formats.map { it.containsKey(QTI_LOW_LATENCY_PARAMETER) })
        assertEquals(formats[0].getInteger(MediaFormat.KEY_OPERATING_RATE),
            formats[1].getInteger(MediaFormat.KEY_OPERATING_RATE))
        assertTrue(reports.any { "retry=same-format-without-private-parameters" in it })
        // A later codec rebuild in the same stream must keep the safe format.
        assertTrue(sink!!.beginSurfaceDetach(surfaces.first(), parkMain = false).await())
        sink!!.setSurface(110, surface())
        barrier()
        assertEquals(listOf(true, false, false), formats.map { it.containsKey(QTI_LOW_LATENCY_PARAMETER) })
    }

    @Test fun startRejectionAlsoFallsBack() {
        NamedCodec.rejectStart = true
        configure()
        assertEquals(listOf(true, false), formats.map { it.containsKey(QTI_LOW_LATENCY_PARAMETER) })
        assertTrue(reports.any { "phase=start" in it && "QTI low-latency fallback" in it })
    }

    @Test fun runtimeFailureDisablesPrivateRequestBeforeRecovery() {
        configure()
        NamedCodec.failInput = true
        sink!!.onVideoFrame(110, idr, 2L, System.nanoTime())
        barrier()
        sink!!.onVideoFrame(110, idr, 3L, System.nanoTime())
        barrier()
        assertEquals(listOf(true, false), formats.map { it.containsKey(QTI_LOW_LATENCY_PARAMETER) })
        assertTrue(reports.any { "QTI low-latency fallback: codec failed" in it })
    }

    @Test fun otherVendorsReceiveNoPrivateKeys() {
        NamedCodec.name = "OMX.MTK.VIDEO.DECODER.AVC"
        configure()
        assertFalse(formats.single().containsKey(QTI_LOW_LATENCY_PARAMETER))
        assertTrue(reports.any { "qtiLowLatencyRequested=true qtiLowLatencySubmitted=false" in it })
    }

    @Test fun disabledSwitchSendsNoPrivateRequest() {
        configure(enabled = false)
        assertFalse(formats.single().containsKey(QTI_LOW_LATENCY_PARAMETER))
        assertTrue(reports.any { "qtiLowLatencyRequested=false qtiLowLatencySubmitted=false" in it })
    }

    @Test fun smoothVideoSubmitsUnlistedPictureOrderToQualcommOmx() {
        configure(smooth = true)
        assertEquals(1, formats.single().getInteger(PICTURE_ORDER_PARAMETER))
        assertEquals(1, formats.single().getInteger(QTI_LOW_LATENCY_PARAMETER))
        assertTrue(reports.any { "pictureOrder=true" in it })
    }

    @Test fun pictureOrderWithoutTheLowLatencySwitchIsStillDroppedWhenRefused() {
        rejectPictureOrder = true
        configure(enabled = false, smooth = true)
        assertEquals(listOf(true, false), formats.map { it.containsKey(PICTURE_ORDER_PARAMETER) })
        assertTrue(formats.none { it.containsKey(QTI_LOW_LATENCY_PARAMETER) })
        assertTrue(reports.any { "retry=same-format-without-private-parameters" in it })
        assertTrue(reports.any { "pictureOrder=false" in it })
    }

    @Test fun runtimeFailureDropsAnUnlistedPictureOrder() {
        configure(enabled = false, smooth = true)
        NamedCodec.failInput = true
        sink!!.onVideoFrame(110, idr, 2L, System.nanoTime())
        barrier()
        sink!!.onVideoFrame(110, idr, 3L, System.nanoTime())
        barrier()
        assertEquals(listOf(true, false), formats.map { it.containsKey(PICTURE_ORDER_PARAMETER) })
        assertTrue(reports.any { "QTI low-latency fallback: codec failed" in it })
    }

    @Test fun otherVendorsGetNoUnlistedPictureOrder() {
        NamedCodec.name = "OMX.MTK.VIDEO.DECODER.AVC"
        configure(smooth = true)
        assertFalse(formats.single().containsKey(PICTURE_ORDER_PARAMETER))
    }

    private fun surface() = Surface(SurfaceTexture(0)).also { surfaces += it }
    private fun configure(enabled: Boolean = true, smooth: Boolean = false) {
        sink = AndroidMediaSink(surface = null, vendorLowLatencyDecoder = enabled, mainVideoFrameRate = 60,
            videoPacingDelayMillis = if (smooth) 90 else 0)
        sink!!.setVideoDiagnosticHandler(110) { reports += it }
        sink!!.setSurface(110, surface())
        sink!!.onVideoConfig(110, avcC)
        sink!!.onVideoFrame(110, idr, 1L, System.nanoTime())
        barrier()
    }
    private fun barrier() { assertTrue(sink!!.beginSurfaceDetach(surface(), parkMain = false).await()) }

    @Implements(MediaCodec::class)
    class NamedCodec : ShadowMediaCodec() {
        @Implementation fun getName(): String = name
        @Implementation override fun native_start() {
            check(!rejectStart || !privateRequest) { "private mode start rejected" }
            super.native_start()
        }
        @Implementation override fun native_dequeueInputBuffer(timeoutUs: Long): Int {
            if (failInput) { failInput = false; throw IllegalStateException("injected runtime failure") }
            return super.native_dequeueInputBuffer(timeoutUs)
        }
        companion object {
            var name = ""
            var privateRequest = false
            var rejectStart = false
            var failInput = false
        }
    }
}
