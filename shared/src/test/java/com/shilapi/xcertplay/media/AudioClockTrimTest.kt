package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** The music renderer trims its track's playback rate from the queued level (#610). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [AudioClockTrimTest.RateTrack::class])
class AudioClockTrimTest {
    private val reports = mutableListOf<String>()
    private val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
    private lateinit var renderer: Any
    private lateinit var track: AudioTrack
    private lateinit var native: RendererNativeFixture

    @Before fun setUp() {
        MediaClockTrim.learnedTrim = 0.0
        RateTrack.rates.clear()
        RateTrack.result = AudioTrack.SUCCESS
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().build())
            .setAudioFormat(android.media.AudioFormat.Builder()
                .setSampleRate(48_000)
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
                .build())
            .setBufferSizeInBytes(96_000)
            .build()
        renderer = AudioRenderer(AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96, "media"),
                false, false, 0, 0, AudioFocusCoordinator(null, false, false),
                0, 500, { message: String -> reports.add(message) }, false, null, false, false)
        set("debugLogsEnabled", { true })
        set("track", track)
        set("mappedChannel", AudioChannel.MEDIA)
        native = RendererNativeFixture(renderer)
        native.progress.configure(57_600, 96_000, 4096)
        native.progress.playback(true)
        set("bytesPerSecond", 48_000 * 4)
        // 300 ms of music is the target; createTrack sets this up for music only.
        set("clockTrim", MediaClockTrim(48_000, 14_400))
    }

    @After fun tearDown() {
        native.close()
        MediaClockTrim.learnedTrim = 0.0
    }

    private fun set(name: String, value: Any?) = type.getDeclaredField(name).apply { isAccessible = true }.set(renderer, value)
    private fun get(name: String): Any? = type.getDeclaredField(name).apply { isAccessible = true }.get(renderer)

    private fun write(bytes: Int) = native.write(bytes)

    @Test fun aLowLevelSlowsTheTrackAndTheStatsShowTheRate() {
        // 150 ms queued against a 300 ms target.
        write(28_800)
        assertEquals(listOf(47_280), RateTrack.rates)
        type.getDeclaredMethod("logStatsIfDue", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(renderer, true)
        assertTrue(reports.toString(), reports.any { it.startsWith("audio stats") && it.contains(" playbackRate=47280 ended=true") })
    }

    @Test fun periodicStatsFollowDebugWithoutDisablingClockTrim() {
        var debug = false
        set("debugLogsEnabled", { debug })
        val logStats = type.getDeclaredMethod("logStatsIfDue", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        write(28_800)
        repeat(12) { native.queue.push(com.shilapi.xcertplay.airplay.HeapPacket(ByteArray(112)), 0, System.nanoTime()) }
        native.drain()
        val received = LongArray(5)
        native.queue.stats(received, reset = false)
        assertEquals(12L, received[0])
        logStats.invoke(renderer, true)
        native.queue.stats(received, reset = false)
        assertEquals(0L, received[0])
        assertTrue(reports.none { it.startsWith("audio stats") })
        assertEquals(listOf(47_280), RateTrack.rates)
        debug = true
        logStats.invoke(renderer, true)
        assertTrue(reports.any { it.startsWith("audio stats") })
        reports.clear()
        debug = false
        logStats.invoke(renderer, true)
        assertTrue(reports.none { it.startsWith("audio stats") })
    }

    @Test fun aTrackThatRefusesTheRateKeepsPlayingWithoutTheTrim() {
        RateTrack.result = AudioTrack.ERROR_INVALID_OPERATION
        write(28_800)
        assertNull(get("clockTrim"))
        assertTrue(reports.toString(), reports.contains("Audio: clock trim off audioType=media rate=47280 result=-3"))
        write(28_800)
        assertEquals(listOf(47_280), RateTrack.rates)
    }

    @Test fun nothingIsTrimmedBeforeMusicPlays() {
        native.progress.playback(false)
        write(2_048)
        assertTrue(RateTrack.rates.isEmpty())
    }

    @Implements(AudioTrack::class)
    class RateTrack {
        @Implementation fun getPlaybackHeadPosition() = 0
        @Implementation fun getUnderrunCount() = 0
        @Implementation fun play() = Unit
        @Implementation fun pause() = Unit
        @Implementation fun write(buffer: java.nio.ByteBuffer, size: Int, mode: Int): Int = size
        @Implementation fun setPlaybackRate(rate: Int): Int {
            rates += rate
            return result
        }

        companion object {
            val rates = mutableListOf<Int>()
            var result = AudioTrack.SUCCESS
        }
    }
}
