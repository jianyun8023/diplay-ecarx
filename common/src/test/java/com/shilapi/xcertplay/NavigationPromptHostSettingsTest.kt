package com.shilapi.xcertplay

import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.time.Duration
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** Real settings and host-created sink; the Android shadow observes the requested focus. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en")
@LooperMode(LooperMode.Mode.PAUSED)
class NavigationPromptHostSettingsTest {
    private lateinit var settings: DiPlayActivity
    private val hosts = mutableListOf<CarPlayHostActivity>()
    private val sinks = mutableListOf<AndroidMediaSink>()
    private lateinit var savedReports: Map<Any, Any?>
    private val manager get() = settings.applicationContext.getSystemService(AudioManager::class.java)

    @Before fun setUp() {
        settings = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        settings.setTheme(android.R.style.Theme_Material_NoActionBar)
        settings.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        CarPlayBackgroundSession.clear()
        PendingReconnect.clear()
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        savedReports = listOf("EcarxHudBridge", "EcarxMediaBridge", "EcarxDimApi", "EcarxProjectionChannel")
            .map { Class.forName("com.shilapi.xcertplay.hud.$it").getField("INSTANCE").get(null) }
            .associateWith { ReflectionHelpers.getField<Any?>(it, "reportSink") }
    }

    @After fun tearDown() {
        sinks.forEach { it.close(); assertTrue(it.awaitVideoReleased(2_000)) }
        hosts.forEach {
            ReflectionHelpers.getField<Handler>(it, "mainHandler").removeCallbacksAndMessages(null)
            ReflectionHelpers.getField<ExecutorService>(it, "teardownExecutor").shutdownNow()
            ReflectionHelpers.getField<ExecutorService>(it, "airPlayCommandExecutor").shutdownNow()
        }
        savedReports.forEach { (target, saved) -> ReflectionHelpers.setField(target, "reportSink", saved) }
        CarPlayBackgroundSession.clear()
        PendingReconnect.clear()
        ReflectionHelpers.getField<Handler>(settings, "handler").removeCallbacksAndMessages(null)
        settings.finish()
    }

    @Test fun defaultOffRemainsOffEvenWhenGeneralAudioFocusIsEnabled() {
        AirPlayPersistence.saveAudioFocusEnabled(settings, true)
        assertFalse(promptSwitch().isChecked)

        hostSink().onAudioDuck(true)

        assertFalse(AirPlayPersistence.loadNavigationPromptAudioFocus(settings))
        assertNull(shadowOf(manager).lastAudioFocusRequest)
    }

    @Test fun settingWaitsForReconnectAndTheNextHostSinkRequestsTemporaryDuckableFocus() {
        AirPlayPersistence.saveAudioFocusEnabled(settings, true)
        val runningSink = hostSink()
        val owner = mock(CarPlayController::class.java)
        CarPlayBackgroundSession.store(owner, runningSink, 1920, 720, Any(),
            CarPlaySessionDisplay(1920, 720, Surface.ROTATION_0, false, false, 1920, 720)) {
            fail("Saving the opt-in must not disconnect the running session")
        }

        promptSwitch().isChecked = true

        assertTrue(AirPlayPersistence.loadNavigationPromptAudioFocus(settings))
        assertTrue(PendingReconnect.isPending(owner))
        assertSame(runningSink, CarPlayBackgroundSession.snapshot()!!.sink)
        runningSink.onAudioDuck(true)
        assertNull("The old sink keeps its negotiated setting", shadowOf(manager).lastAudioFocusRequest)
        verifyNoInteractions(owner)
        assertNull(shadowOf(settings).nextStartedActivity)

        val reconnected = hostSink()
        reconnected.onAudioDuck(true)
        val request = shadowOf(manager).lastAudioFocusRequest
        assertNotNull(request)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, request.durationHint)
        reconnected.onAudioDuck(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
        assertSame(request.audioFocusRequest, shadowOf(manager).lastAbandonedAudioFocusRequest)
    }

    @Test fun generalFocusOffHidesTheOptInButRetainsItWithoutRequestingFocus() {
        AirPlayPersistence.saveNavigationPromptAudioFocus(settings, true)
        AirPlayPersistence.saveAudioFocusEnabled(settings, false)
        val prompt = promptSwitch()
        assertTrue(prompt.isChecked)
        assertEquals(View.GONE, (prompt.parent.parent as View).visibility)

        hostSink().onAudioDuck(true)

        assertTrue(AirPlayPersistence.loadNavigationPromptAudioFocus(settings))
        assertNull(shadowOf(manager).lastAudioFocusRequest)
    }

    private fun promptSwitch(): Switch {
        val controls = LinearLayout(settings)
        settings.javaClass.getDeclaredMethod("audioFocusControls", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(settings, controls)
        return views(controls).filterIsInstance<Switch>().single {
            it.contentDescription == settings.getString(R.string.settings_navigation_prompt_audio_focus)
        }
    }

    private fun hostSink(): AndroidMediaSink {
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get().also(hosts::add)
        return (host.javaClass.getDeclaredMethod("createMediaSink", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(host, 1920, 720, 1) as AndroidMediaSink).also(sinks::add)
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
