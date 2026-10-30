package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Looper
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class SiriActionActivityTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val controller = mock(CarPlayController::class.java)

    @Before fun setup() {
        MediaSessionSettings.setMode(app, MediaSessionMode.ANDROID)
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        `when`(controller.requestSiri()).thenReturn(true)
        CarPlayMediaSession.attach(app, controller)
    }

    @After fun cleanup() {
        CarPlayMediaSession.detach(controller)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun actionResolvesToAnExportedNoDisplayEntryOutsideDiPlaysTasks() {
        val intent = Intent("${app.packageName}.action.SIRI").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolved = app.packageManager.resolveActivity(intent, 0)!!.activityInfo
        assertEquals(SiriActionActivity::class.java.name, resolved.name)
        val info = app.packageManager.getActivityInfo(ComponentName(app, SiriActionActivity::class.java), 0)
        assertTrue(info.exported)
        assertEquals(android.R.style.Theme_NoDisplay, info.theme)
        assertTrue(info.taskAffinity.isNullOrEmpty())
        assertTrue(info.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
    }

    @Test fun existingSessionReceivesSiriAndEntryFinishesWithoutStartingAnyScreen() {
        `when`(controller.activeAirPlaySession()).thenReturn(mock(AirPlaySession::class.java))
        val activity = Robolectric.buildActivity(SiriActionActivity::class.java,
            Intent("${app.packageName}.action.SIRI").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).create().get()
        assertTrue(activity.isFinishing)
        verify(controller).requestSiri()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun coldEntryAndAnotherVariantsActionNeverConnectOrOpenAScreen() {
        val cold = Robolectric.buildActivity(SiriActionActivity::class.java,
            Intent("${app.packageName}.action.SIRI")).create().get()
        `when`(controller.activeAirPlaySession()).thenReturn(mock(AirPlaySession::class.java))
        val wrongVariant = Robolectric.buildActivity(SiriActionActivity::class.java,
            Intent("another.variant.action.SIRI")).create().get()
        for (activity in listOf(cold, wrongVariant)) {
            assertTrue(activity.isFinishing)
            assertNull(shadowOf(activity).nextStartedActivity)
            assertNull(shadowOf(app).nextStartedService)
        }
        verify(controller, never()).requestSiri()
        verify(controller, never()).start()
    }
}
