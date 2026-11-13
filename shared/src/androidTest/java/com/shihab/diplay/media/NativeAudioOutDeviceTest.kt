package com.shihab.diplay.media

import android.media.AudioAttributes
import android.media.AudioTrack
import com.shihab.diplay.airplay.AudioCodecKind
import com.shihab.diplay.airplay.AudioFormat
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class NativeAudioOutDeviceTest {
    @Test fun pausedPartialWriteDrainsAfterStartWithoutAnotherInputPacket() {
        for (channels in listOf(1, 2)) {
            val out = requireNotNull(NativeAudioOut.open(48_000, channels,
                AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC, 960))
            try {
                out.setVolume(0.2f)
                assertEquals(0, out.setPlaybackRate(48_480))
                // More than the paused device buffer can accept: Rust must retain the tail.
                val frames = out.bufferSizeInFrames * 4 + 48_000
                val bytes = frames * channels * 2
                val pcm = ByteBuffer.allocateDirect(bytes)
                assertEquals(bytes, out.write(pcm, 0, bytes))
                out.play()
                // API 28 legacy writes block without honoring the timeout; time playback after submission.
                assertTrue(out.drain() >= 0)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (out.playbackHeadPosition < frames && System.nanoTime() < deadline) {
                    assertTrue(out.drain() >= 0)
                    Thread.sleep(10)
                }
                val played = out.playbackHeadPosition
                assertTrue("The final buffered block must play: channels=$channels played=$played expected=$frames " +
                    "buffer=${out.bufferSizeInFrames} underruns=${out.underrunCount}", played >= frames)
                out.pause()
            } finally {
                out.release()
            }
        }
    }

    @Test fun rendererClosesPlayingAAudioAndCanReopenIt() {
        val renderer = AudioRenderer(AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96, "media"),
            false, false, 0, 0, AudioFocusCoordinator(null, false), 0, 500, {})
        val nativeOut = AudioRenderer::class.java.getDeclaredField("nativeOut").apply { isAccessible = true }
        val releaseTrack = AudioRenderer::class.java.getDeclaredMethod("releaseTrack").apply { isAccessible = true }
        try {
            repeat(3) { cycle ->
                val out = requireNotNull(NativeAudioOut.open(48_000, 2,
                    AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC, 4800))
                nativeOut.set(renderer, out)
                try {
                    out.play()
                    val pcm = ByteBuffer.allocateDirect(out.bufferSizeInFrames * 4)
                    assertEquals(pcm.capacity(), out.write(pcm, 0, pcm.capacity()))
                    assertTrue(out.drain() >= 0)
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (out.playbackHeadPosition == 0 && System.nanoTime() < deadline) {
                        Thread.sleep(10)
                    }
                    assertTrue("AAudio must play before renderer cleanup: cycle=$cycle", out.playbackHeadPosition > 0)
                    // Exercise the production cleanup; the next cycle must open and play a new stream.
                    releaseTrack.invoke(renderer)
                    assertNull(nativeOut.get(renderer))
                    assertEquals(AudioTrack.STATE_UNINITIALIZED, out.state)
                } finally {
                    // Cleanup is safe after both a successful release and an assertion failure.
                    releaseTrack.invoke(renderer)
                }
            }
        } finally {
            renderer.close()
        }
    }

    @Test fun focusCallbacksDuringPlaybackAndAfterCloseDoNotAccessFreedOutput() {
        val out = requireNotNull(NativeAudioOut.open(48_000, 2,
            AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC, 4800))
        val closed = CountDownLatch(1)
        val focus = FutureTask {
            var muted = false
            while (!closed.await(1, TimeUnit.MILLISECONDS)) {
                muted = !muted
                assertTrue(out.setVolume(if (muted) 0.2f else 1f))
            }
            assertTrue(out.setVolume(1f))
        }
        Thread(focus, "focus-callback").start()
        try {
            out.play()
            val pcm = ByteBuffer.allocateDirect(3840)
            repeat(10) {
                assertEquals(pcm.capacity(), out.write(pcm, 0, pcm.capacity()))
                assertTrue(out.drain() >= 0)
            }
        } finally {
            out.release()
            closed.countDown()
        }
        focus.get(5, TimeUnit.SECONDS)
        assertTrue(out.setVolume(1f))
        out.release()
    }
}
