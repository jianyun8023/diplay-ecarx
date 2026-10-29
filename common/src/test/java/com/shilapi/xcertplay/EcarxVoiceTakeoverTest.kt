package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.media.AudioManager
import android.os.Looper
import android.os.SystemClock
import android.os.Handler
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
internal class EcarxVoiceTakeoverTest(private val mode: MediaSessionMode) {
    private val app get() = RuntimeEnvironment.getApplication()
    private val controller = mock(CarPlayController::class.java)
    private val phone = mock(AirPlaySession::class.java)
    private var factoryPresses = 0
    private val factory = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { factoryPresses++ }
    }

    @Before fun setup() {
        MediaSessionSettings.setMode(app, mode)
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = "com.ecarx.xiaoka" })
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        `when`(controller.requestSiri()).thenReturn(true)
        CarPlayMediaSession.attach(app, controller)
        // Higher than DiPlay's old priority of 1000, lower than HY's requested maximum.
        app.registerReceiver(factory, IntentFilter(ACTION).apply {
            priority = 1001
            addCategory(Intent.CATEGORY_DEFAULT)
        })
    }

    @After fun cleanup() {
        CarPlayMediaSession.detach(controller)
        MediaSessionSettings.setMode(app, MediaSessionMode.ANDROID)
        app.unregisterReceiver(factory)
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        CarPlayBackgroundSession.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun connectingDoesNotTakeTheFactoryKey() {
        CarPlayMediaSession.onSessionActive(controller, phone)
        assertFalse(CarPlayMediaSession.ecarxVoiceKeyActive())
        send(0)
        assertEquals(1, factoryPresses)
        verify(controller, never()).requestSiri()
    }

    @Test fun shortPressIsAbortedAndRequestsSiriOnceAcrossRawCallbacks() {
        `when`(controller.activeAirPlaySession()).thenReturn(phone)
        CarPlayMediaSession.onSessionActive(controller, phone)
        CarPlayMediaSession.onSessionActive(controller, phone)
        assertTrue(CarPlayMediaSession.ecarxVoiceKeyActive())
        assertFalse(CarPlayMediaSession.onExternalSiriKey(200231))
        send(0)
        send(0)
        assertFalse(CarPlayMediaSession.onExternalSiriKey(200231))
        verify(controller, times(1)).requestSiri()
        assertEquals(0, factoryPresses)
        SystemClock.sleep(WheelSiriKey.REPEAT_GAP_MILLIS)
        send(0)
        verify(controller, times(2)).requestSiri()
    }

    @Test fun androidMediaModeTakesTheVoiceBroadcastWithoutTakingVehicleMediaKeys() {
        CarPlayMediaSession.detach(controller)
        MediaSessionSettings.setMode(app, MediaSessionMode.ANDROID)
        CarPlayMediaSession.attach(app, controller)
        `when`(controller.activeAirPlaySession()).thenReturn(phone)
        CarPlayMediaSession.onSessionActive(controller, phone)
        assertTrue(CarPlayMediaSession.ecarxVoiceKeyActive())
        assertFalse(CarPlayMediaSession.managesVehicleKeys())
        assertFalse(CarPlayMediaSession.isMediaCenterActive())
        send(0)
        verify(controller).requestSiri()
        assertEquals(0, factoryPresses)
    }

    @Test fun customVoiceKeyWinsOverTheDefaultReceiverWithoutRequestingTwice() {
        `when`(controller.activeAirPlaySession()).thenReturn(phone)
        val down = KeyEvent(KeyEvent.ACTION_DOWN, 200231)
        WheelZoomSettings.setSiriKey(app, true)
        WheelZoomSettings.assign(app, WheelZoomSettings.Role.SIRI, WheelKey.of(down))
        CarPlayMediaSession.onSessionActive(controller, phone)
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        try {
            ReflectionHelpers.setField(host, "controller", controller)
            ReflectionHelpers.setField(host, "activeAirPlaySession", phone)
            assertTrue(host.dispatchKeyEvent(down))
            assertTrue(host.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, 200231)))
            verify(controller, never()).requestSiri()
            send(0)
            verify(controller, times(1)).requestSiri()
            assertEquals(0, factoryPresses)
        } finally {
            ReflectionHelpers.getField<Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
            for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
                ReflectionHelpers.getField<ExecutorService>(host, name).shutdownNow()
            }
        }
    }

    @Test fun assignedVoiceKeyOpensSiriFromTheBroadcastWithoutAnyKeyEvent() {
        `when`(controller.activeAirPlaySession()).thenReturn(phone)
        WheelZoomSettings.setSiriKey(app, true)
        WheelZoomSettings.assign(app, WheelZoomSettings.Role.SIRI, WheelKey(200231, 0, "?"))
        CarPlayMediaSession.onSessionActive(controller, phone)
        send(0)
        assertEquals(0, factoryPresses)
        verify(controller).requestSiri()
    }

    @Test fun assignedDoubleVoiceUsesBroadcastsAndIgnoresDuplicateRawKeyPaths() {
        `when`(controller.activeAirPlaySession()).thenReturn(phone)
        WheelZoomSettings.setSiriKey(app, true)
        WheelZoomSettings.assign(app, WheelZoomSettings.Role.SIRI, WheelKey.of(KeyEvent(KeyEvent.ACTION_DOWN, 200231)))
        WheelZoomSettings.setSiriDoublePress(app, true)
        WheelZoomSettings.setSiriDoublePressIntervalMillis(app, 600)
        WheelZoomSettings.setSiriTriggerDelayMillis(app, 300)
        CarPlayMediaSession.onSessionActive(controller, phone)
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val serviceController = Robolectric.buildService(WheelKeyService::class.java).create()
        try {
            ReflectionHelpers.setField(host, "controller", controller)
            ReflectionHelpers.setField(host, "activeAirPlaySession", phone)
            val service = serviceController.get()
            service.session = { phone }
            service.requestSiri = { controller.requestSiri() }
            val receive = service.javaClass.getDeclaredMethod("onKeyEvent", KeyEvent::class.java).apply { isAccessible = true }
            repeat(2) {
                for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    val key = KeyEvent(action, 200231)
                    assertTrue(receive.invoke(service, key) as Boolean)
                    assertTrue(host.dispatchKeyEvent(key))
                }
                send(0)
                if (it == 0) {
                    send(0) // A duplicate callback is not the second physical press.
                    shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)
                }
                verify(controller, never()).requestSiri()
            }
            shadowOf(Looper.getMainLooper()).idleFor(299, TimeUnit.MILLISECONDS)
            verify(controller, never()).requestSiri()
            shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
            verify(controller, times(1)).requestSiri()
            assertEquals(0, factoryPresses)
        } finally {
            serviceController.destroy()
            ReflectionHelpers.getField<Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
            for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
                ReflectionHelpers.getField<ExecutorService>(host, name).shutdownNow()
            }
        }
    }

    @Test fun broadcastDoublePressExpiresAndRequiresTwoFreshPresses() {
        assignDoubleVoice()
        send(0)
        shadowOf(Looper.getMainLooper()).idleFor(601, TimeUnit.MILLISECONDS)
        send(0)
        shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)
        verify(controller, never()).requestSiri()
        send(0)
        shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
        verify(controller, times(1)).requestSiri()
        assertEquals(0, factoryPresses)
    }

    @Test fun disconnectCancelsTheDelayedBroadcastRequest() {
        assignDoubleVoice()
        send(0)
        shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)
        send(0)
        CarPlayMediaSession.onSessionEnded(phone)
        shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
        verify(controller, never()).requestSiri()
        send(0)
        assertEquals(1, factoryPresses)
    }

    @Test fun aCallOrChangedAssignmentCancelsTheDelayedBroadcastRequest() {
        assignDoubleVoice()
        send(0)
        shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)
        send(0)
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_CALL
        shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
        verify(controller, never()).requestSiri()
        send(0)
        assertEquals(1, factoryPresses)
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        send(0)
        shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)
        send(0)
        WheelZoomSettings.setSiriKey(app, false)
        shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
        verify(controller, never()).requestSiri()
    }

    @Test fun longUnknownMarkedAndCallEventsStayWithTheFactory() {
        `when`(controller.activeAirPlaySession()).thenReturn(phone)
        CarPlayMediaSession.onSessionActive(controller, phone)
        send(1)
        send(2)
        send(null)
        send(0, marked = true)
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_CALL
        send(0)
        assertEquals(5, factoryPresses)
        verify(controller, never()).requestSiri()
    }

    @Test fun unorderedShortPressDoesNotOpenTwoAssistants() {
        `when`(controller.activeAirPlaySession()).thenReturn(phone)
        CarPlayMediaSession.onSessionActive(controller, phone)
        send(0, ordered = false)
        assertEquals(1, factoryPresses)
        verify(controller, never()).requestSiri()
        assertTrue(WheelKeyLog.report(false).any { it.contains("ordered=false queued=false") })
    }

    @Test fun activityKeyCleanupDoesNotRemoveTheSessionReceiver() {
        `when`(controller.activeAirPlaySession()).thenReturn(phone)
        CarPlayMediaSession.onSessionActive(controller, phone)
        val registration = shadowOf(app).registeredReceivers.single {
            it.intentFilter.hasAction(ACTION) && it.broadcastReceiver !== factory
        }
        assertSame(app, registration.context.applicationContext)
        assertEquals(Int.MAX_VALUE, registration.intentFilter.priority)
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val serviceController = Robolectric.buildService(WheelKeyService::class.java).create()
        try {
            host.javaClass.getDeclaredMethod("unregisterEcarxSteeringWheel").apply { isAccessible = true }.invoke(host)
            val service = serviceController.get()
            val kept = service.javaClass.getDeclaredMethod("onKeyEvent", KeyEvent::class.java)
                .apply { isAccessible = true }.invoke(service, KeyEvent(KeyEvent.ACTION_DOWN, 200231)) as Boolean
            assertFalse(kept)
            send(0)
            verify(controller, times(1)).requestSiri()
            assertEquals(0, factoryPresses)
        } finally {
            serviceController.destroy()
            ReflectionHelpers.getField<Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
            for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
                ReflectionHelpers.getField<ExecutorService>(host, name).shutdownNow()
            }
        }
    }

    @Test fun aDisconnectedCarPlayWindowDoesNotConsumeTheFactoryVoiceKey() {
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        try {
            host.window.decorView
            ReflectionHelpers.setField(host, "controller", controller)
            assertFalse(host.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, 200231)))
            assertFalse(host.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, 200231)))
            verify(controller, never()).requestSiri()
        } finally {
            ReflectionHelpers.getField<Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
            for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
                ReflectionHelpers.getField<ExecutorService>(host, name).shutdownNow()
            }
        }
    }

    @Test fun disconnectReleasesAndAnOldSessionCannotReleaseTheReplacement() {
        `when`(controller.activeAirPlaySession()).thenReturn(phone)
        CarPlayMediaSession.onSessionActive(controller, phone)
        CarPlayMediaSession.onSessionEnded(phone)
        assertFalse(CarPlayMediaSession.ecarxVoiceKeyActive())
        send(0)
        assertEquals(1, factoryPresses)
        val replacement = mock(AirPlaySession::class.java)
        `when`(controller.activeAirPlaySession()).thenReturn(replacement)
        CarPlayMediaSession.onSessionActive(controller, replacement)
        CarPlayMediaSession.onSessionEnded(phone)
        send(0)
        verify(controller, times(1)).requestSiri()
        CarPlayMediaSession.detach(controller)
        send(0)
        assertEquals(2, factoryPresses)
        assertFalse(CarPlayMediaSession.ecarxVoiceKeyActive())
    }

    private fun assignDoubleVoice() {
        `when`(controller.activeAirPlaySession()).thenReturn(phone)
        WheelZoomSettings.setSiriKey(app, true)
        WheelZoomSettings.assign(app, WheelZoomSettings.Role.SIRI, WheelKey(200231, 0, "?"))
        WheelZoomSettings.setSiriDoublePress(app, true)
        WheelZoomSettings.setSiriDoublePressIntervalMillis(app, 600)
        WheelZoomSettings.setSiriTriggerDelayMillis(app, 300)
        CarPlayMediaSession.onSessionActive(controller, phone)
    }

    private fun send(type: Int?, marked: Boolean = false, ordered: Boolean = true) {
        val intent = Intent(ACTION).addCategory(Intent.CATEGORY_DEFAULT)
        if (type != null) intent.putExtra("ecarx.extra.ECARX_KEY_ACTION_TYPE", type)
        if (marked) intent.putExtra("ecarx.extra.HY", 1)
        if (ordered) app.sendOrderedBroadcast(intent, null) else app.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun rawVoiceDoesNotRequestSiriBeforeConnection() {
        assertFalse(CarPlayMediaSession.onExternalSiriKey())
        verify(controller, never()).requestSiri()
    }

    @Test fun rawVoiceFallbackChecksCallStateAndDebouncesInEitherMode() {
        `when`(controller.activeAirPlaySession()).thenReturn(phone)
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_CALL
        assertFalse(CarPlayMediaSession.onExternalSiriKey())
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        assertTrue(CarPlayMediaSession.onExternalSiriKey())
        assertFalse(CarPlayMediaSession.onExternalSiriKey())
        verify(controller, times(1)).requestSiri()
    }

    companion object {
        private const val ACTION = "ecarx.intent.action.ECARX_KEY_RVOICEASSIST_EVENT"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun modes() = MediaSessionMode.entries.map { arrayOf(it) }
    }
}
