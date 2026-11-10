package com.shilapi.xcertplay.media

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCrypto
import android.media.MediaFormat
import android.view.Surface
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodec
import org.robolectric.shadows.ShadowMediaCodecList
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

class VideoDecoderSelectionTest {
    private val ecarx = listOf(
        InstalledDecoder("OMX.qcom.video.decoder.hevc", software = isSoftwareDecoderName("OMX.qcom.video.decoder.hevc")),
        InstalledDecoder("OMX.google.hevc.decoder", software = isSoftwareDecoderName("OMX.google.hevc.decoder")),
    )

    @Test fun androidsOwnDecodersAreSoftwareByName() {
        assertTrue(isSoftwareDecoderName("OMX.google.h264.decoder"))
        assertTrue(isSoftwareDecoderName("c2.android.hevc.decoder"))
        assertFalse(isSoftwareDecoderName("OMX.qcom.video.decoder.avc"))
        assertFalse(isSoftwareDecoderName("c2.qti.hevc.decoder"))
    }

    @Test fun theHardwareDecoderIsTheDefaultAndSoftwareTheFallback() {
        assertEquals("OMX.qcom.video.decoder.hevc", defaultHardwareDecoder(ecarx))
        assertEquals("OMX.google.hevc.decoder", softwareFallbackDecoder(ecarx))
        assertEquals("c2.qti.avc.decoder", defaultHardwareDecoder(listOf(
            InstalledDecoder("OMX.qcom.video.decoder.avc", software = false, alias = true),
            InstalledDecoder("c2.qti.avc.decoder", software = false),
        )))
        assertNull(defaultHardwareDecoder(ecarx.filter { it.software }))
    }

    @Test fun aRefusedCreateIsRetriedAfterEachWait() {
        val slept = mutableListOf<Long>()
        var calls = 0
        val result = createWithRetries(listOf(100L, 200L, 400L), sleep = { slept += it }) {
            if (++calls < 3) error("InsufficientResources") else "codec"
        }
        assertEquals("codec", result)
        assertEquals(listOf(100L, 200L), slept)
    }

    @Test fun theLastFailureIsThrownOnceTheRetriesRunOut() {
        val slept = mutableListOf<Long>()
        val failure = runCatching {
            createWithRetries(listOf(100L, 200L), sleep = { slept += it }) { error("busy") }
        }.exceptionOrNull()
        assertEquals("busy", failure?.message)
        assertEquals(listOf(100L, 200L), slept)
    }

    @Test fun failedHardwareChecksBackOffUpToTheLimit() {
        var probe = HardwareDecoderProbe.start("OMX.qcom.video.decoder.hevc", nowNanos = 0)
        assertEquals(2_000_000_000L, probe.dueNanos)
        val intervals = (1..6).map { probe = probe.failed(probe.dueNanos); probe.intervalMillis }
        assertEquals(listOf(4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), intervals)
    }
}

/** On API 28, a busy hardware decoder must not quietly become Android's software decoder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [VideoHardwareDecoderTest.BusyCodec::class])
class VideoHardwareDecoderTest {
    private val reports = CopyOnWriteArrayList<String>()
    private val surfaces = mutableListOf<Surface>()
    private var sink: AndroidMediaSink? = null
    private val avcC = byteArrayOf(1, 0x42, 0xC0.toByte(), 0x1E, 0xFF.toByte(), 0xE1.toByte(),
        0, 4, 0x67, 0x42, 0xC0.toByte(), 0x1E, 1, 0, 2, 0x68, 0xCE.toByte())
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte(), 0x21)

    @Before fun setUp() {
        BusyCodec.busyRemaining = 0
        BusyCodec.rejectStart = false
        BusyCodec.created.clear()
        listOf(HARDWARE, SOFTWARE).forEach { name ->
            ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder()
                .setName(name)
                .setCapabilities(MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                    .setMediaFormat(MediaFormat().apply { setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_AVC) })
                    .setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface))
                    .setProfileLevels(emptyArray())
                    .build())
                .build())
        }
        ShadowMediaCodec.addDecoder(MediaFormat.MIMETYPE_VIDEO_AVC,
            ShadowMediaCodec.CodecConfig(64 * 1024, 64 * 1024, object : ShadowMediaCodec.CodecConfig.Codec {
                override fun onConfigured(format: MediaFormat?, surface: Surface?, crypto: MediaCrypto?, flags: Int) = Unit
                override fun process(input: ByteBuffer, output: ByteBuffer) { output.put(input) }
            }))
    }

    @After fun tearDown() {
        sink?.let { it.close(); assertTrue(it.awaitVideoReleased(5_000)) }
        surfaces.forEach(Surface::release)
        ShadowMediaCodec.clearCodecs()
        ShadowMediaCodecList.reset()
    }

    @Test fun theHardwareDecoderIsCreatedByName() {
        configure()
        assertEquals(listOf(HARDWARE), BusyCodec.created)
        assertTrue(reports.any { "decoder=$HARDWARE" in it })
        assertTrue(reports.none { "software decoder fallback" in it })
    }

    @Test fun aBriefRefusalIsRetriedOnTheHardwareDecoder() {
        BusyCodec.busyRemaining = 2
        configure()
        assertEquals(listOf(HARDWARE, HARDWARE, HARDWARE), BusyCodec.created)
        assertEquals(2, reports.count { "hardware decoder $HARDWARE create failed" in it })
        assertTrue(reports.any { "decoder=$HARDWARE" in it })
    }

    @Test fun aBusyHardwareDecoderFallsBackToSoftwareOpenlyAndComesBack() {
        BusyCodec.busyRemaining = 4
        configure()
        // Four refusals (the first create and its three retries), then the software decoder, created once.
        assertEquals(List(4) { HARDWARE } + SOFTWARE, BusyCodec.created)
        assertTrue(reports.any { "decoder=$SOFTWARE" in it })
        assertTrue(reports.any { "software decoder fallback decoder=$SOFTWARE hardware=$HARDWARE" in it })

        // The worker finds the hardware free again and rebuilds on it at the next keyframe.
        val deadline = System.currentTimeMillis() + 5_000
        while (reports.none { "available again" in it } && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(reports.any { "hardware decoder $HARDWARE available again" in it })
        sink!!.onVideoFrame(110, idr, 2L, System.nanoTime())
        barrier()
        assertEquals(HARDWARE, BusyCodec.created.last())
        assertEquals(1, reports.count { "decoder=$HARDWARE mime=" in it })
    }

    @Test fun aDecoderThatRefusesToStartDoesNotTurnIntoSoftware() {
        BusyCodec.rejectStart = true
        configure()
        assertTrue(BusyCodec.created.none { it == SOFTWARE })
        assertTrue(reports.any { "decoder configuration failed" in it })
        assertTrue(reports.none { "software decoder fallback" in it })
    }

    private fun surface() = Surface(SurfaceTexture(0)).also { surfaces += it }
    private fun configure() {
        sink = AndroidMediaSink(surface = null)
        sink!!.setVideoDiagnosticHandler(110) { reports += it }
        sink!!.setSurface(110, surface())
        sink!!.onVideoConfig(110, avcC)
        sink!!.onVideoFrame(110, idr, 1L, System.nanoTime())
        barrier()
    }
    private fun barrier() { assertTrue(sink!!.beginSurfaceDetach(surface(), parkMain = false).await()) }

    @Implements(MediaCodec::class)
    class BusyCodec : ShadowMediaCodec() {
        private var name = ""
        @Implementation protected fun native_setup(name: String, nameIsType: Boolean, encoder: Boolean) {
            check(!nameIsType) { "created by type: $name" }
            created += name
            if (name == HARDWARE && busyRemaining > 0) {
                busyRemaining--
                throw IllegalStateException("InsufficientResources")
            }
            this.name = name
        }
        @Implementation fun getName(): String = name
        @Implementation override fun native_start() {
            check(!rejectStart) { "refused to start" }
            super.native_start()
        }
        companion object {
            @Volatile var busyRemaining = 0
            @Volatile var rejectStart = false
            val created = CopyOnWriteArrayList<String>()
        }
    }

    private companion object {
        const val HARDWARE = "OMX.qcom.video.decoder.avc"
        const val SOFTWARE = "OMX.google.h264.decoder"
    }
}
