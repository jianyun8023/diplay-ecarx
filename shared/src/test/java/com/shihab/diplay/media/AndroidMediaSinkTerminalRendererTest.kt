package com.shihab.diplay.media

import com.shihab.diplay.airplay.AudioCodecKind
import com.shihab.diplay.airplay.AudioFormat
import com.shihab.diplay.airplay.AudioStreamId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A renderer whose worker failed on its own can never play again; the sink must hand the same
 * stream a fresh renderer instead of silently discarding every later packet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AndroidMediaSinkTerminalRendererTest {
    @Test fun aFailedWorkerRendererIsReplacedOnTheNextUseOfTheSameStream() {
        val sink = AndroidMediaSink()
        try {
            val id = AudioStreamId(4, "media")
            val format = AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96, "media")
            val renderer = audioRenderer(sink, id, format)
            assertSame("An unchanged format reuses its renderer", renderer, audioRenderer(sink, id, format))

            markWorkerFailed(renderer)
            assertTrue(renderer.isTerminal)
            val replacement = audioRenderer(sink, id, format)
            assertFalse("The failed renderer must not be reused", replacement === renderer)
            assertSame(replacement, audioRenderer(sink, id, format))
            assertTrue("The failed renderer is closed, not leaked", rendererClosed(renderer))
        } finally {
            sink.close()
        }
    }

    @Test fun aDifferentFormatStillReplacesAndKeepsOneRendererPerStream() {
        val sink = AndroidMediaSink()
        try {
            val id = AudioStreamId(4, "media")
            val original = audioRenderer(sink, id, AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96, "media"))
            val replacement = audioRenderer(sink, id, AudioFormat(AudioCodecKind.AAC_LC, 44_100, 2, 96, "media"))
            assertFalse(replacement === original)
            assertTrue(rendererClosed(original))
        } finally {
            sink.close()
        }
    }

    private fun audioRenderer(sink: AndroidMediaSink, id: AudioStreamId, format: AudioFormat): AudioRenderer =
        AndroidMediaSink::class.java.getDeclaredMethod("audioRenderer", AudioStreamId::class.java, AudioFormat::class.java)
            .apply { isAccessible = true }.invoke(sink, id, format) as AudioRenderer

    private fun markWorkerFailed(renderer: AudioRenderer) {
        AudioRenderer::class.java.getDeclaredField("workerFailed").apply { isAccessible = true }.set(renderer, true)
    }

    private fun rendererClosed(renderer: AudioRenderer): Boolean =
        AudioRenderer::class.java.getDeclaredField("running").apply { isAccessible = true }.get(renderer) == false
}
