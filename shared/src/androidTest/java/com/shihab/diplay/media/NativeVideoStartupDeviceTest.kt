package com.shihab.diplay.media

import android.graphics.SurfaceTexture
import android.media.MediaCodecList
import android.view.Surface
import com.shihab.diplay.airplay.HeapPacket
import com.shihab.diplay.airplay.VideoCodec
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NativeVideoStartupDeviceTest {
    @Test fun avcKeyframeReceivedBeforeWorkerStartsIsRendered() = firstFrameBeforeWorker(AVC)

    @Test fun hevcKeyframeReceivedBeforeWorkerStartsIsRendered() = firstFrameBeforeWorker(HEVC)

    @Test fun duplicateHevcConfigsStillRenderTheOnlyKeyframe() = firstFrameBeforeWorker(HEVC, configurations = 2)

    @Test fun newHevcConfigKeepsItsOnlyIdrWhenOlderConfigFails() = newerConfigSurvivesFailure(rebuild = false)

    @Test fun newHevcConfigKeepsItsOnlyIdrWhenPipeRebuildFails() = newerConfigSurvivesFailure(rebuild = true)

    private fun newerConfigSurvivesFailure(rebuild: Boolean) {
        requireHardware(AVC.mime)
        requireHardware(HEVC.mime)
        val texture = SurfaceTexture(false).apply { setDefaultBufferSize(WIDTH, HEIGHT) }
        val unavailableSurface = Surface(texture).apply { release() }
        val surface = Surface(texture)
        val failures = AtomicInteger()
        val requests = AtomicInteger()
        val replacementQueued = CountDownLatch(1)
        val replacementConfigured = CountDownLatch(1)
        val rendered = CountDownLatch(1)
        val reports = CopyOnWriteArrayList<String>()
        val expectedFailures = if (rebuild) 2 else 1
        lateinit var decoder: VideoDecoder
        decoder = VideoDecoder(
            streamType = 110, surface = unavailableSurface, width = WIDTH, height = HEIGHT,
            preferSoftwareHevcDecoder = false,
            requestKeyFrame = { requests.incrementAndGet() },
            report = { line ->
                reports.add(line)
                if (line.startsWith("decoder configuration failed ") && failures.incrementAndGet() == expectedFailures) {
                    // The report precedes failed-config cleanup. A receiver may deliver a newer Config
                    // and its only IDR here, including while a pipe-requested rebuild has no Config hold.
                    decoder.setSurface(surface)
                    decoder.configure(HEVC.codec, HEVC.config)
                    decoder.submit(HEVC.bytes)
                    replacementQueued.countDown()
                }
                if (line.startsWith("decoder=") && line.contains("mime=${HEVC.mime} ")) replacementConfigured.countDown()
                if (line == "first frame rendered") rendered.countDown()
            },
            nativeDecodeLoop = true,
        )
        // A non-null Surface without a native window deterministically fails every codec attempt.
        // The replacement uses a real Surface; no codec factory or cleanup implementation is mocked.
        decoder.configure(AVC.codec, AVC.config)
        // Detaching an unattached Surface changes nothing, but its acknowledgement is a FIFO worker barrier.
        val firstConfigDone = if (rebuild) decoder.requestDetach(surface, park = false) else null
        decoder.start()
        try {
            if (firstConfigDone != null) {
                val didComplete = decoder.awaitDetach(
                    firstConfigDone, System.nanoTime() + TimeUnit.SECONDS.toNanos(15),
                )
                assertTrue("The first failed Config did not complete: $reports", didComplete)
                assertEquals(DetachOutcome.NOT_RENDERING, firstConfigDone.outcome)
                assertEquals("The initial Config must fail before a native rebuild: $reports", 1, failures.get())
                // Its hold is complete. This old AVC IDR requests a real native rebuild, not a second HEVC IDR.
                decoder.submit(AVC.bytes)
            }
            val didQueue = replacementQueued.await(15, TimeUnit.SECONDS)
            assertTrue("The expected failed configure did not reach its cleanup boundary: $reports", didQueue)
            val didConfigure = replacementConfigured.await(15, TimeUnit.SECONDS)
            assertTrue("The replacement HEVC decoder must configure successfully: $reports", didConfigure)
            assertEquals("Unexpected configuration failures: $reports", expectedFailures, failures.get())
            val didRender = rendered.await(5, TimeUnit.SECONDS)
            assertTrue("An older failed configure discarded the newer Config's only HEVC IDR: $reports", didRender)
            assertEquals(if (rebuild) 2L else 1L, decoder.liveCounters().received)
            assertEquals(1L, decoder.liveCounters().rendered)
            assertEquals("The replacement must not need another keyframe", 0, requests.get())
        } finally {
            decoder.close()
            assertTrue(decoder.awaitExitUntil(System.nanoTime() + TimeUnit.SECONDS.toNanos(5)))
            unavailableSurface.release()
            surface.release()
            texture.release()
        }
    }

    private fun firstFrameBeforeWorker(frame: EncodedFrame, configurations: Int = 1) {
        requireHardware(frame.mime)
        val texture = SurfaceTexture(false).apply { setDefaultBufferSize(WIDTH, HEIGHT) }
        val surface = Surface(texture)
        val rendered = CountDownLatch(1)
        val requests = AtomicInteger()
        val reports = CopyOnWriteArrayList<String>()
        val decoder = VideoDecoder(
            streamType = 110, surface = surface, width = WIDTH, height = HEIGHT,
            preferSoftwareHevcDecoder = false,
            requestKeyFrame = { requests.incrementAndGet() },
            report = { reports.add(it); if (it == "first frame rendered") rendered.countDown() },
            nativeDecodeLoop = true,
        )
        // The receiver can deliver its only IDR before the worker ever handles Config.
        repeat(configurations) { decoder.configure(frame.codec, frame.config) }
        decoder.submit(frame.bytes)
        decoder.start()
        try {
            val didRender = rendered.await(10, TimeUnit.SECONDS)
            assertTrue("The only ${frame.codec} IDR was lost: $reports", didRender)
            assertEquals("Startup must not need a replacement keyframe", 0, requests.get())
            assertEquals(1L, decoder.liveCounters().received)
        } finally {
            decoder.close()
            assertTrue(decoder.awaitExitUntil(System.nanoTime() + TimeUnit.SECONDS.toNanos(5)))
            surface.release()
            texture.release()
        }
    }

    @Test fun decoderWaitsForANewKeyframeAfterItsSurfaceBecomesAvailable() {
        requireHardware(HEVC.mime)
        val texture = SurfaceTexture(false).apply { setDefaultBufferSize(WIDTH, HEIGHT) }
        val surface = Surface(texture)
        val refreshed = CountDownLatch(1)
        val rendered = CountDownLatch(1)
        val requests = AtomicInteger()
        val reports = CopyOnWriteArrayList<String>()
        val decoder = VideoDecoder(
            streamType = 110, surface = null, width = WIDTH, height = HEIGHT,
            preferSoftwareHevcDecoder = false,
            requestKeyFrame = { requests.incrementAndGet(); refreshed.countDown() },
            report = { reports.add(it); if (it == "first frame rendered") rendered.countDown() },
            nativeDecodeLoop = true,
        )
        decoder.configure(HEVC.codec, HEVC.config)
        decoder.submit(HEVC.bytes)
        decoder.setSurface(surface)
        // Refresh is processed after SurfaceChanged has configured the codec and updated acceptance.
        decoder.refreshPicture()
        decoder.start()
        try {
            val didRefresh = refreshed.await(10, TimeUnit.SECONDS)
            assertTrue("The worker did not finish attaching its Surface: $reports", didRefresh)
            assertEquals(1L, decoder.liveCounters().received)
            assertFalse("The frame received without a Surface must not render", rendered.await(300, TimeUnit.MILLISECONDS))
            assertEquals(0L, decoder.liveCounters().rendered)
            decoder.submit(HEVC.bytes)
            val didRender = rendered.await(5, TimeUnit.SECONDS)
            assertTrue("The new IDR did not render after Surface attachment: $reports", didRender)
            assertEquals(2L, decoder.liveCounters().received)
            assertEquals(1L, decoder.liveCounters().rendered)
            assertEquals("Only the explicit refresh should request a keyframe", 1, requests.get())
        } finally {
            decoder.close()
            assertTrue(decoder.awaitExitUntil(System.nanoTime() + TimeUnit.SECONDS.toNanos(5)))
            surface.release()
            texture.release()
        }
    }

    @Test fun closeBeforeWorkerStartsReleasesPendingConfigsAndFrame() {
        requireHardware(HEVC.mime)
        val texture = SurfaceTexture(false).apply { setDefaultBufferSize(WIDTH, HEIGHT) }
        val surface = Surface(texture)
        val exited = CountDownLatch(1)
        val rendered = AtomicInteger()
        val requests = AtomicInteger()
        val decoder = VideoDecoder(
            streamType = 110, surface = surface, width = WIDTH, height = HEIGHT,
            preferSoftwareHevcDecoder = false,
            requestKeyFrame = { requests.incrementAndGet() },
            report = { if (it == "first frame rendered") rendered.incrementAndGet() },
            onExit = { exited.countDown() }, nativeDecodeLoop = true,
        )
        repeat(2) { decoder.configure(HEVC.codec, HEVC.config) }
        decoder.submit(HEVC.bytes)
        // Closing before start leaves both Config holds and the IDR for the worker's finally block.
        decoder.close()
        decoder.start()
        try {
            assertTrue("The worker must release pending native state on close", exited.await(5, TimeUnit.SECONDS))
            assertTrue(decoder.awaitExitUntil(System.nanoTime() + TimeUnit.SECONDS.toNanos(5)))
            assertEquals(0, rendered.get())
            assertEquals(0, requests.get())
        } finally {
            decoder.close()
            assertTrue(decoder.awaitExitUntil(System.nanoTime() + TimeUnit.SECONDS.toNanos(5)))
            surface.release()
            texture.release()
        }
    }

    @Test fun overlappingConfigsKeepHevcFrameHeldUntilLastCompletion() {
        requireHardware(AVC.mime)
        requireHardware(HEVC.mime)
        PipeSession().use { session ->
            val pipe = session.pipe
            pipe.setAccepting(true, resetChain = false)
            pipe.hold(true)
            pipe.hold(true)
            pipe.submit(HeapPacket(HEVC.bytes), 0, 0)
            session.configure(AVC)
            pipe.hold(false)
            assertFalse("An older Config must not feed the newer Config's frame", session.input.await(300, TimeUnit.MILLISECONDS))
            pipe.release()
            session.configure(HEVC)
            pipe.hold(false)
            assertTrue("The held HEVC IDR must reach the codec", session.input.await(5, TimeUnit.SECONDS))
            assertTrue(session.logs.toString(), session.logs.any { it.contains("first input annexB=${HEVC.bytes.size} ") })
            assertEquals(0, session.keyframes.get())
        }
    }

    @Test fun noSurfaceDoesNotRetainStartupFrame() {
        requireHardware(HEVC.mime)
        PipeSession().use { session ->
            val pipe = session.pipe
            pipe.setAccepting(false, resetChain = false)
            pipe.hold(true)
            pipe.submit(HeapPacket(HEVC.bytes), 0, 0)
            session.configure(HEVC)
            pipe.hold(false)
            pipe.setAccepting(true, resetChain = false)
            assertFalse("A frame received without a Surface must have been dropped", session.input.await(300, TimeUnit.MILLISECONDS))
            pipe.submit(HeapPacket(HEVC.bytes), 0, 0)
            assertTrue("A new frame after Surface availability must feed", session.input.await(5, TimeUnit.SECONDS))
            assertEquals(0, session.keyframes.get())
        }
    }

    @Test fun failedInitialHevcCreateCanRetryWithTheNextKeyframe() {
        requireHardware(HEVC.mime)
        PipeSession().use { session ->
            val pipe = session.pipe
            pipe.setAccepting(true, resetChain = false)
            pipe.hold(true)
            pipe.submit(HeapPacket(HEVC.bytes), 0, 0)
            val failed = pipe.configure(
                "diplay.test.missing.decoder", HEVC.mime, WIDTH, HEIGHT, emptyMap(), HEVC.csd,
                session.surface, hevc = true, reportVendorFormat = false,
            )
            assertNotEquals("The deliberately absent decoder must fail creation", 0, failed.status)
            // Use VideoDecoder's failure boundary; this chain must not retry forever without a new IDR.
            pipe.configurationFailed(completingConfigHold = true)
            pipe.hold(false)
            assertFalse("Failed configuration must discard its old chain", session.needsDecoder.await(150, TimeUnit.MILLISECONDS))
            pipe.submit(HeapPacket(HEVC.bytes), 0, 0)
            assertTrue("The next HEVC IDR must request another decoder", session.needsDecoder.await(5, TimeUnit.SECONDS))
            session.configure(HEVC)
            assertTrue("The retry must consume its retained IDR", session.input.await(5, TimeUnit.SECONDS))
            assertEquals(0, session.keyframes.get())
        }
    }

    private class PipeSession : Closeable {
        private val texture = SurfaceTexture(false).apply { setDefaultBufferSize(WIDTH, HEIGHT) }
        val surface = Surface(texture)
        val input = CountDownLatch(1)
        val needsDecoder = CountDownLatch(1)
        val keyframes = AtomicInteger()
        val logs = CopyOnWriteArrayList<String>()
        val pipe = checkNotNull(NativeVideoPipe.create(0, false) { kind, detail ->
            if (kind == NativeVideoPipe.EVENT_REQUEST_KEYFRAME) keyframes.incrementAndGet()
            if (kind == NativeVideoPipe.EVENT_NEED_DECODER) needsDecoder.countDown()
            if (detail != null) logs.add(detail)
            if (kind == NativeVideoPipe.EVENT_LOG && detail?.contains("video decoder first input ") == true) input.countDown()
        })

        fun configure(frame: EncodedFrame) {
            val result = pipe.configure(
                null, frame.mime, WIDTH, HEIGHT, emptyMap(), frame.csd, surface,
                hevc = frame.codec == VideoCodec.H265, reportVendorFormat = false,
            )
            assertEquals("Native codec configuration failed: ${result.detail}", 0, result.status)
        }

        override fun close() {
            pipe.destroy()
            surface.release()
            texture.release()
        }
    }

    private class EncodedFrame(val codec: VideoCodec, parameterSets: List<String>, idr: String) {
        val mime = if (codec == VideoCodec.H265) "video/hevc" else "video/avc"
        private val nals = parameterSets.map(::hex)
        val bytes = START_CODE + hex(idr)
        val csd = if (codec == VideoCodec.H265) listOf(nals.fold(byteArrayOf()) { all, nal -> all + START_CODE + nal })
            else nals.map { START_CODE + it }
        val config: ByteArray = if (codec == VideoCodec.H265) {
            ByteBuffer.allocate(23 + nals.sumOf { 5 + it.size }).apply {
                put(ByteArray(23).apply { this[0] = 1; this[21] = 3; this[22] = nals.size.toByte() })
                nals.forEach { nal -> put(((nal[0].toInt() ushr 1) and 63).toByte()); putShort(1); putShort(nal.size.toShort()); put(nal) }
            }.array()
        } else {
            val (sps, pps) = nals
            byteArrayOf(1, sps[1], sps[2], sps[3], -1, -31, 0, sps.size.toByte()) +
                sps + byteArrayOf(1, 0, pps.size.toByte()) + pps
        }
    }

    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 240
        val START_CODE = byteArrayOf(0, 0, 0, 1)
        fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        fun requireHardware(mime: String) = assumeTrue("$mime hardware decoder required", MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any {
            !it.isEncoder && mime in it.supportedTypes && !isSoftwareDecoderName(it.name)
        })

        // Synthetic black 320x240 frame: Qualcomm hardware rejects the old 64x64 fixture.
        // ffmpeg -f lavfi -i color=c=black:s=320x240:r=30 -frames:v 1 -pix_fmt yuv420p -c:v libx264
        // -profile:v baseline -preset ultrafast -tune zerolatency
        // -x264-params keyint=30:bframes=0:repeat-headers=1:threads=1:sliced-threads=0 -f h264 frame.h264
        val AVC = EncodedFrame(VideoCodec.H264, listOf(
            "6742c00dda0507ec0440000003004000000f23c50aa8", "68ce0fc8",
        ),
            "6588843a2628000902c9c9c9c9c9c9c9c9c9c9c9c9c9c9c9c9c9c9c9d75d75d75d75d75d75d75d75d75d75d75d75d75d" +
            "75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d" +
            "75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d" +
            "75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d" +
            "75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75e0",
        )

        // ffmpeg -f lavfi -i color=c=black:s=320x240:r=30 -frames:v 1 -pix_fmt yuv420p -c:v libx265
        // -profile:v main -preset ultrafast -tune zerolatency
        // -x265-params keyint=30:bframes=0:repeat-headers=1:pools=1:frame-threads=1:wpp=0 -f hevc frame.hevc
        // Only parameter sets and exactly one IDR per codec are retained; there is no captured video.
        val HEVC = EncodedFrame(VideoCodec.H265, listOf(
            "40010c01ffff01600000030090000003000003003cba0240",
            "42010101600000030090000003000003003ca00a080f165ba4a4c2f0168080000003008000000f04",
            "4401c0718112",
        ), "2801ade0d117ffd3917322eebaeda718a00000030000030000030000030000030002bc000003007840")
    }
}
