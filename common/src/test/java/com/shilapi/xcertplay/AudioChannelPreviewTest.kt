package com.shilapi.xcertplay

import android.media.AudioAttributes
import android.media.AudioManager
import com.shilapi.xcertplay.host.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AudioChannelPreviewTest {
    @Test fun bundledSpeechIsFiveSecondsOfMonoPcmWithTheExistingPeakLevel() {
        val context = RuntimeEnvironment.getApplication()
        val pcm = context.resources.openRawResource(R.raw.audio_channel_preview).use { it.readBytes() }
        assertEquals(0, pcm.size % 2)
        assertTrue(pcm.size in 48_000 * 2 * 4..48_000 * 2 * 6)
        var peak = 0
        var audibleSamples = 0
        for (index in pcm.indices step 2) {
            val sample = ((pcm[index].toInt() and 255) or (pcm[index + 1].toInt() shl 8)).toShort().toInt()
            val amplitude = kotlin.math.abs(sample)
            peak = maxOf(peak, amplitude)
            if (amplitude > 100) audibleSamples++
        }
        assertTrue("peak=$peak", peak in 13_000..15_000)
        assertTrue("Speech must not be an empty or mostly silent asset", audibleSamples > pcm.size / 10)
    }

    @Test @Config(shadows = [OemContentBuilder::class])
    fun acceptedOemContentIsUsedForBothPlaybackAndFocus() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(AudioManager::class.java)
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val reports = CopyOnWriteArrayList<String>()
        val released = CountDownLatch(1)
        AudioChannelPreview(context, report = {
            reports.add(it)
            if (it.startsWith("phase=focus-released")) released.countDown()
        }, onUnavailable = {}).use {
            it.play(0, navigation = true, requestFocus = true, oemContent = true)
            assertTrue(reports.toString(), released.await(2, TimeUnit.SECONDS))
        }
        val attributes = shadowOf(manager).lastAudioFocusRequest.audioFocusRequest.audioAttributes
        assertEquals(12, attributes.usage)
        assertEquals(20, attributes.contentType)
        assertTrue(reports.toString(), reports.any { it.contains("resolvedUsage=12 resolvedContentType=20 attributesSource=builder") })
        assertTrue(reports.toString(), reports.any { it.startsWith("phase=result") })
        assertTrue(reports.toString(), reports.any { it.contains("sample=spoken_zh_v1 sampleRate=48000 channels=1") })
    }

    @Implements(AudioAttributes.Builder::class)
    class OemContentBuilder {
        @RealObject private lateinit var builder: AudioAttributes.Builder
        @Implementation fun setContentType(value: Int): AudioAttributes.Builder {
            ReflectionHelpers.setField(builder, "mContentType", value)
            return builder
        }
    }

    @Test fun unsupportedOemContentIsReportedWithoutPlayingARewrittenTone() {
        val context = RuntimeEnvironment.getApplication()
        val reports = CopyOnWriteArrayList<String>()
        val failed = CountDownLatch(1)
        AudioChannelPreview(context, report = {
            reports.add(it)
            if (it.startsWith("phase=failed")) failed.countDown()
        }, onUnavailable = {}).use {
            it.play(0, navigation = true, requestFocus = true, oemContent = true)
            assertTrue(failed.await(2, TimeUnit.SECONDS))
        }
        assertTrue(reports.toString(), reports.any { it.contains("requested=12/20 resolved=12/0") })
        assertFalse(reports.toString(), reports.any { it.startsWith("phase=started") || it.startsWith("phase=result") })
    }

    @Test fun mediaAndNavigationTestsPlayTheSameSpeechAndReleaseTheirRespectiveFocus() {
        val context = RuntimeEnvironment.getApplication()
        val sampleBytes = context.resources.openRawResource(R.raw.audio_channel_preview).use { it.readBytes().size }
        val manager = context.getSystemService(AudioManager::class.java)
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        for (navigation in listOf(false, true)) {
            val usage = if (navigation) AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE else AudioAttributes.USAGE_MEDIA
            val contentType = if (navigation) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC
            val gain = if (navigation) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK else AudioManager.AUDIOFOCUS_GAIN
            val reports = CopyOnWriteArrayList<String>()
            val released = CountDownLatch(1)
            AudioChannelPreview(context, report = {
                reports.add(it)
                if (it.startsWith("phase=focus-released")) released.countDown()
            }, onUnavailable = {}).use { preview ->
                preview.play(0, navigation = navigation, requestFocus = true)
                assertTrue("Focus was not released: $reports", released.await(2, TimeUnit.SECONDS))
            }
            val sent = shadowOf(manager).lastAudioFocusRequest
            assertEquals(gain, sent.durationHint)
            assertEquals(usage, sent.audioFocusRequest.audioAttributes.usage)
            assertSame(sent.audioFocusRequest, shadowOf(manager).lastAbandonedAudioFocusRequest)
            assertTrue(reports.toString(), reports.any { it.startsWith("phase=result") })
            assertTrue(reports.toString(), reports.any { it.contains("sample=spoken_zh_v1") && it.contains("sampleBytes=$sampleBytes volume=0.6") })
            assertTrue(reports.toString(), reports.any { it.contains("writtenBytes=$sampleBytes") })
            assertTrue(reports.toString(), reports.any { it.contains("granted=true") })
            assertTrue(reports.toString(), reports.any { it.contains("configurationSource=attributes legacyStream=-1 configuredUsage=$usage configuredContentType=$contentType") })
            assertTrue(reports.toString(), reports.any { it.contains("focusGain=$gain") && it.contains("focusUsage=$usage focusContentType=$contentType") })
        }
    }
}
