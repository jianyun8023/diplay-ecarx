package com.shihab.diplay.media

import android.graphics.SurfaceTexture
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AndroidMediaSinkDetachTest {
    private val predicted = byteArrayOf(0, 0, 0, 1, 0x41, 1)

    @Test fun reapplyingTheSameMainMirrorSurfaceKeepsItsDecoder() {
        val surface = Surface(SurfaceTexture(0))
        val replacement = Surface(SurfaceTexture(0))
        val sink = AndroidMediaSink(surface = null)
        val field = AndroidMediaSink::class.java.getDeclaredField("mirrorDecoders").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val mirrors = field.get(sink) as Map<Pair<Int, String>, Any>
        try {
            sink.setMirrorSurface(110, "ecarx-cluster-main", surface)
            sink.onVideoFrame(110, predicted)
            val original = checkNotNull(mirrors[110 to "ecarx-cluster-main"])
            sink.setMirrorSurface(110, "ecarx-cluster-main", surface)
            assertSame("adopting a session reapplies its unchanged surface", original, mirrors[110 to "ecarx-cluster-main"])
            sink.setMirrorSurface(110, "ecarx-cluster-main", replacement)
            sink.onVideoFrame(110, predicted)
            assertNotSame(original, mirrors[110 to "ecarx-cluster-main"])
            sink.setMirrorSurface(110, "ecarx-cluster-main", null)
            assertFalse(mirrors.containsKey(110 to "ecarx-cluster-main"))
        } finally {
            sink.close()
        }
    }

    @Test fun retiredClusterDecoderCannotReportAsItsReplacement() {
        val sink = AndroidMediaSink(surface = null)
        val messages = ConcurrentLinkedQueue<String>()
        @Suppress("UNCHECKED_CAST")
        val decoders = AndroidMediaSink::class.java.getDeclaredField("videoDecoders")
            .apply { isAccessible = true }.get(sink) as Map<Int, Any>
        fun report(decoder: Any, message: String) {
            @Suppress("UNCHECKED_CAST")
            val callback = decoder.javaClass.getDeclaredField("report").apply { isAccessible = true }
                .get(decoder) as (String) -> Unit
            callback(message)
        }
        try {
            sink.setVideoDiagnosticHandler(111) { messages += it }
            sink.onVideoFrame(111, predicted)
            val first = checkNotNull(decoders[111])
            sink.setVideoDiagnosticHandler(111) { messages += it }
            report(first, "first frame rendered")
            assertTrue(messages.contains("first frame rendered")) // A live decoder may be reused.
            messages.clear()
            sink.onScreenStreamActive(111, false)
            sink.setVideoDiagnosticHandler(111) { messages += it }
            sink.onVideoFrame(111, predicted)
            val second = checkNotNull(decoders[111])
            assertNotSame(first, second)
            report(first, "first frame rendered")
            report(second, "first frame rendered")
            assertTrue(messages.contains("inactive decoder first frame rendered"))
            assertEquals(1, messages.count { it == "first frame rendered" })
        } finally { sink.close() }
    }

    @Test fun aDetachIsConfirmedWhileFramesAreQueued() {
        val surface = Surface(SurfaceTexture(0))
        val sink = AndroidMediaSink(surface = surface, videoPacingDelayMillis = 90)
        repeat(50) { sink.onVideoFrame(110, predicted, it * 16_666_667L + 1, System.nanoTime()) }
        val started = System.nanoTime()
        assertTrue(sink.beginSurfaceDetach(surface, parkMain = true).await())
        assertTrue(System.nanoTime() - started < 2_000_000_000L)
        sink.close()
    }

    @Test fun aDetachAfterTheDecoderShutDownReturnsAtOnce() {
        val surface = Surface(SurfaceTexture(0))
        val sink = AndroidMediaSink(surface = surface)
        sink.onVideoFrame(110, predicted)
        sink.onScreenStreamActive(110, false) // closes that stream's decoder worker
        assertTrue(sink.beginSurfaceDetach(surface, parkMain = true).await())
        sink.close()
        assertTrue(sink.beginSurfaceDetach(surface, parkMain = false).await())
    }

    @Test fun pacedFramesFromAReplacedAndANewStreamThreadDoNotRace() {
        // Callbacks of a replaced stream can overlap its successor's; pacing state lives on the worker.
        val sink = AndroidMediaSink(videoPacingDelayMillis = 90)
        val failures = ConcurrentLinkedQueue<String>()
        sink.setVideoDiagnosticHandler(110) { if ("failed" in it) failures += it }
        val errors = ConcurrentLinkedQueue<Throwable>()
        val senders = (0 until 2).map { stream ->
            thread {
                try {
                    repeat(20_000) { i ->
                        sink.onVideoFrame(110, predicted, (2L * i + stream) * 16_666_667L + 1, System.nanoTime())
                    }
                } catch (error: Throwable) {
                    errors += error
                }
            }
        }
        senders.forEach { it.join() }
        sink.close()
        assertTrue(errors.toString(), errors.isEmpty())
        assertTrue(failures.toString(), failures.isEmpty())
    }
}
