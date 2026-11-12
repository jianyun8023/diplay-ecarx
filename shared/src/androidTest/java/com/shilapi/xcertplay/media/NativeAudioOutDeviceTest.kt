package com.shilapi.xcertplay.media

import android.media.AudioAttributes
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
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (out.playbackHeadPosition < frames && System.nanoTime() < deadline) {
                    assertTrue(out.drain() >= 0)
                    Thread.sleep(10)
                }
                assertTrue("The final buffered block must play", out.playbackHeadPosition >= frames)
                out.pause()
                out.flush()
            } finally {
                out.release()
            }
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
