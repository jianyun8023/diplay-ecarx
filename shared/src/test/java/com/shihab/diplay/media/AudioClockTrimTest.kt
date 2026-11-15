package com.shihab.diplay.media

import android.media.AudioAttributes
import android.media.AudioTrack
import com.shihab.diplay.airplay.AudioCodecKind
import com.shihab.diplay.airplay.AudioFormat
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
    private val type = Class.forName("com.shihab.diplay.media.AudioRenderer")
    private lateinit var renderer: Any
    private lateinit var track: AudioTrack
    private lateinit var native: RendererNativeFixture

    @Before fun setUp() {
        MediaClockTrim.learnedTrim = 0.0
        RateTrack.rates.clear()
        RateTrack.result = AudioTrack.SUCCESS
        RateTrack.underruns = 0
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
        repeat(12) { native.queue.push(com.shihab.diplay.airplay.HeapPacket(ByteArray(112)), 0, System.nanoTime()) }
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

    @Test fun routeChangeFlushesOldTrackCountersAndKeepsTheRendererRunning() {
        val summaries = mutableListOf<String>()
        val health = MediaHealth(summaries::add)
        set("health", health)
        set("debugLogsEnabled", { false })
        set("mappedChannel", AudioChannel.NAVIGATION)
        // The fixture enables native PCM; a legacy media route keeps its replacement on AudioTrack.
        set("mediaChannel", 3)
        set("inputDropped", 4)
        set("rebufferCount", 1)
        (renderer as AudioRenderer).navigationRouteWanted = { 3 }
        health.started()
        val sample = type.getDeclaredMethod("logStatsIfDue", Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        RateTrack.underruns = 2
        sample.invoke(renderer, true, false)
        RateTrack.underruns = 5
        type.getDeclaredMethod("switchNavigationRouteIfWanted").apply { isAccessible = true }.invoke(renderer)
        assertNull(get("nativeOut"))
        assertTrue(reports.toString(), get("track") is AudioTrack)
        assertTrue(health.snapshot().contains("underruns=5"))
        RateTrack.underruns = 8
        sample.invoke(renderer, true, false)
        val result = health.snapshot()
        assertTrue(result, result.contains("underruns=13"))
        assertTrue(result, result.contains("decoderDrops=4 rebuffers=1"))
        assertTrue(result, result.contains("audioEnded=0"))
        assertTrue(result, result.contains("pendingWorkers=1 closing=false final=false"))
        assertEquals(1, summaries.size)
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
        @Implementation fun getUnderrunCount() = underruns
        @Implementation fun play() = Unit
        @Implementation fun pause() = Unit
        @Implementation fun write(buffer: java.nio.ByteBuffer, size: Int, mode: Int): Int = size
        @Implementation fun setPlaybackRate(rate: Int): Int {
            rates += rate
            return result
        }

        companion object {
            @JvmStatic @Implementation fun getMinBufferSize(rate: Int, channelMask: Int, encoding: Int) = 4096
            val rates = mutableListOf<Int>()
            var result = AudioTrack.SUCCESS
            var underruns = 0
        }
    }
}
