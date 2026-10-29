package com.shilapi.xcertplay

import android.app.ActivityManager
import android.os.Looper
import android.os.Process
import android.view.Surface
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSettings
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1920dp-h1080dp-land-mdpi", manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CenterMapLifecycleTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var active = true
    private var available = true
    private var sink = 1
    private val frames = mutableListOf<Triple<Int, String, Surface?>>()
    private lateinit var lifecycle: CenterMapLifecycle

    @Before fun setUp() {
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().apply()
        AirPlayPersistence.saveCenterMapOverlay(app, true)
        AirPlayPersistence.saveCenterMapFollowsDashboard(app, true)
        AirPlayPersistence.saveCenterMapAutoHide(app, false)
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app.getSystemService(ActivityManager::class.java)).setProcesses(listOf(
            ActivityManager.RunningAppProcessInfo().apply {
                pid = Process.myPid()
                importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE
            },
        ))
        lifecycle = CenterMapLifecycle(app, { available }, { active },
            { key, surface -> frames += Triple(sink, key, surface) }, {}, {}, {})
        lifecycle.attach()
    }

    @After fun tearDown() {
        MapMirrors.set(MapMirrors.CARD, null)
        MapMirrors.set("launcher:test", null)
        lifecycle.close()
        CenterMapOverlay.onDiPlayScreenShown()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun backgroundShowsAfter600MillisAndForegroundHidesImmediately() {
        lifecycle.onHostStarted()
        lifecycle.show()
        assertFalse(CenterMapOverlay.shown)
        lifecycle.onHostStopped(schedule = true)
        advance(599)
        assertFalse(CenterMapOverlay.shown)
        advance(1)
        assertTrue(CenterMapOverlay.shown)
        lifecycle.onHostStarted()
        assertFalse(CenterMapOverlay.shown)
    }

    @Test fun quickReturnToForegroundCancelsPendingShow() {
        lifecycle.onHostStopped(schedule = true)
        advance(300)
        lifecycle.onHostStarted()
        advance(600)
        assertFalse(CenterMapOverlay.shown)
    }

    @Test fun finishingOrChangingConfigurationDoesNotScheduleCard() {
        lifecycle.onHostStopped(schedule = false)
        advance(600)
        assertFalse(CenterMapOverlay.shown)
    }

    @Test fun navigationEndKeepsCardForThreeSecondsThenHides() {
        lifecycle.show()
        assertTrue(CenterMapOverlay.shown)
        active = false
        lifecycle.onStreamChanged(false)
        advance(2_999)
        assertTrue(CenterMapOverlay.shown)
        advance(1)
        assertFalse(CenterMapOverlay.shown)
        assertFalse(MapMirrors.streamActive)
    }

    @Test fun reconnectRetainsCardCancelsIdleHideAndRebindsExistingMirrorsToNewSink() {
        lifecycle.show()
        val surface = mock(Surface::class.java)
        MapMirrors.set(MapMirrors.CARD, surface)
        active = false
        lifecycle.onStreamChanged(false)
        advance(1_000)
        sink = 2
        MapMirrors.reapply() // Both host session adoption and fresh sink creation use this path.
        active = true
        lifecycle.onStreamChanged(true)
        advance(3_000)
        assertTrue(CenterMapOverlay.shown)
        assertTrue(MapMirrors.streamActive)
        assertEquals(listOf(Triple(1, MapMirrors.CARD, surface), Triple(2, MapMirrors.CARD, surface)), frames)
    }

    @Test fun streamReturningAfterIdleHideSchedulesCardAgain() {
        lifecycle.show()
        active = false
        lifecycle.onStreamChanged(false)
        advance(3_000)
        assertFalse(CenterMapOverlay.shown)
        active = true
        lifecycle.onStreamChanged(true)
        advance(600)
        assertTrue(CenterMapOverlay.shown)
    }

    @Test fun closeUnregistersCallbacksAndPendingShowCannotResurrectCard() {
        lifecycle.onHostStopped(schedule = true)
        active = false
        lifecycle.onStreamChanged(false)
        lifecycle.close()
        active = true
        advance(3_600)
        assertFalse(CenterMapOverlay.shown)
        assertNull(CenterMapOverlay.requestShow)
        assertNull(MapMirrors.sink)
        assertNull(MapMirrors.onChanged)
    }

    @Test fun missingStreamOrSinkAndLauncherEmbeddingKeepExistingDisplayGates() {
        active = false
        lifecycle.show()
        assertFalse(CenterMapOverlay.shown)
        active = true
        available = false
        lifecycle.show()
        assertFalse(CenterMapOverlay.shown)
        available = true
        lifecycle.show()
        assertTrue(CenterMapOverlay.shown)
        MapMirrors.set("launcher:test", mock(Surface::class.java))
        assertFalse(CenterMapOverlay.shown)
        lifecycle.show()
        assertFalse(CenterMapOverlay.shown)
    }

    private fun advance(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    }
}
