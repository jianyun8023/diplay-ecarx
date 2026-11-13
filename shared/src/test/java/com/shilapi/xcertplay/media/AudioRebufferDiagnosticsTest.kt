package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [AudioRebufferDiagnosticsTest.TestTrack::class])
class AudioRebufferDiagnosticsTest {

    @Test
    fun rebufferingEmitsDiagnosticReportsAndResetsQueueOverflowFlag() {
        val reports = mutableListOf<String>()
        val plan = MediaAudioBuffer.plan(true, 48_000, 2, 4096, 500)
        val threshold = MediaAudioBuffer.startBytesFor(plan.startBytes, plan.trackBufferBytes, 4096)

        TestTrack.capacity = plan.trackBufferBytes
        TestTrack.queued = threshold / 2
        TestTrack.playing = true

        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().build())
            .setAudioFormat(
                android.media.AudioFormat.Builder()
                    .setSampleRate(48_000)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(plan.trackBufferBytes)
            .build()

        val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
        val renderer = AudioRenderer(
                AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96, "media"),
                false, false, 0, 0,
                AudioFocusCoordinator(null, false, false),
                0, 500, { msg: String -> reports.add(msg) }, false, null, false, false
            )

        fun set(name: String, value: Any?) = type.getDeclaredField(name).apply { isAccessible = true }.set(renderer, value)
        val native = RendererNativeFixture(renderer)

        try {
            set("track", track)
            set("mappedChannel", AudioChannel.MEDIA)
            native.progress.configure(threshold, plan.trackBufferBytes, 4096)
            native.progress.playback(true)
            set("bytesPerSecond", 48_000 * 4)

            native.progress.completedWrite(TestTrack.queued, System.nanoTime())

            // Trigger buffer maintenance rebuffering
            type.getDeclaredMethod("maintainPlaybackBuffer").apply { isAccessible = true }.invoke(renderer)

            assertTrue(reports.any { it.contains("Audio: rebuffer paused audioType=media rebufferCount=1") })

            // Write PCM to recover playback
            native.write(threshold)

            assertTrue(reports.any { it.contains("Audio: rebuffer recovered audioType=media rebufferCount=1") })

            // A real overflow sets the native warning latch. Draining the last packet re-arms it.
            val event = LongArray(3)
            native.fill()
            native.queue.push(com.shilapi.xcertplay.airplay.HeapPacket(ByteArray(112)), 0, System.nanoTime())
            assertTrue(native.queue.event(event))
            assertEquals(2L, event[0])
            assertEquals(1L, event[2])
            native.drain()
            native.fill()
            native.queue.push(com.shilapi.xcertplay.airplay.HeapPacket(ByteArray(112)), 0, System.nanoTime())
            assertTrue(native.queue.event(event))
            assertEquals(2L, event[0])
            assertFalse("queue overflow warning stayed suppressed after draining", event[2] == 0L)

        } finally {
            native.close()
        }
    }

    @Test
    fun rebufferAndQueueFullReportsAreCappedAtFiveEvents() {
        val reports = mutableListOf<String>()
        val plan = MediaAudioBuffer.plan(true, 48_000, 2, 4096, 500)
        val threshold = MediaAudioBuffer.startBytesFor(plan.startBytes, plan.trackBufferBytes, 4096)

        TestTrack.capacity = plan.trackBufferBytes
        TestTrack.queued = threshold / 2
        TestTrack.playing = true

        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().build())
            .setAudioFormat(
                android.media.AudioFormat.Builder()
                    .setSampleRate(48_000)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(plan.trackBufferBytes)
            .build()

        val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
        val renderer = AudioRenderer(
                AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96, "media"),
                false, false, 0, 0,
                AudioFocusCoordinator(null, false, false),
                0, 500, { msg: String -> reports.add(msg) }, false, null, false, false
            )

        fun set(name: String, value: Any?) = type.getDeclaredField(name).apply { isAccessible = true }.set(renderer, value)
        fun get(name: String) = type.getDeclaredField(name).apply { isAccessible = true }.get(renderer)
        val native = RendererNativeFixture(renderer)

        try {
            set("track", track)
            set("mappedChannel", AudioChannel.MEDIA)
            native.progress.configure(threshold, plan.trackBufferBytes, 4096)
            set("bytesPerSecond", 48_000 * 4)

            val bufferProgress = native.progress

            // Trigger 10 rebuffer pause and recovery cycles
            repeat(10) {
                bufferProgress.playback(true)
                set("underrunsAtPlaybackStart", 0)
                bufferProgress.completedWrite(threshold, System.nanoTime())

                // Simulate hardware underrun (head position equals written samples)
                bufferProgress.queuedBytes(threshold / 4) // Advance head

                type.getDeclaredMethod("maintainPlaybackBuffer").apply { isAccessible = true }.invoke(renderer)

                native.write(threshold)
            }

            val pauseReports = reports.filter { it.contains("Audio: rebuffer paused") }
            val recoverReports = reports.filter { it.contains("Audio: rebuffer recovered") }

            assertEquals(5, pauseReports.size)
            assertEquals(5, recoverReports.size)
            assertEquals(10, get("rebufferCount") as Int)

            // Test queue full cap over 10 overflow/drain cycles
            set("started", true)
            set("running", true)
            val dummyRtp = ByteArray(112)
            repeat(10) {
                native.fill()
                // The actual renderer consumes the native queue event and applies its report cap.
                type.getDeclaredMethod("submit", ByteArray::class.java, Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(renderer, dummyRtp, 0)
                native.drain()
            }

            val queueFullReports = reports.filter { it.contains("Audio: queue full") }
            assertEquals(5, queueFullReports.size)

        } finally {
            native.close()
        }
    }

    @Implements(AudioTrack::class)
    class TestTrack {
        @Implementation fun getPlaybackHeadPosition() = 100_000
        @Implementation fun getUnderrunCount() = 2
        @Implementation fun pause() { playing = false }
        @Implementation fun play() { playing = true }
        @Implementation fun write(buffer: java.nio.ByteBuffer, size: Int, mode: Int): Int {
            if (!playing) queued += size
            return size
        }
        companion object {
            var capacity = 0
            var queued = 0
            var playing = true
        }
    }
}
