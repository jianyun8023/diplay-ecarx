package com.shihab.diplay

import android.bluetooth.BluetoothManager
import android.os.Handler
import android.os.Looper
import com.shihab.diplay.airplay.AirPlaySession
import com.shihab.diplay.airplay.AirPlaySessionListener
import com.shihab.diplay.airplay.CarPlayMediaButton
import com.shihab.diplay.bluetooth.CarBluetoothProfileGuard
import com.shihab.diplay.media.CarPlayNowPlaying
import com.shihab.diplay.orchestration.CarPlayController
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
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
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayAutoPlayDiagnosticTest {
    private lateinit var host: CarPlayHostActivity
    private lateinit var controller: CarPlayController
    private val loop get() = shadowOf(Looper.getMainLooper())

    @Before fun setup() {
        host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        controller = mock(CarPlayController::class.java)
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying(playing = true, elapsedMillis = 207_000))
        `when`(controller.sendMediaButton(CarPlayMediaButton.PLAY)).thenReturn(true)
        ReflectionHelpers.setField(host, "controller", controller)
        ReflectionHelpers.setField(host, "activeAirPlaySession", mock(AirPlaySession::class.java))
        host.javaClass.getDeclaredMethod("initializeSessionLog").apply { isAccessible = true }.invoke(host)
        CarBluetoothProfileGuard.saveAdapterEnabled(host, true)
        shadowOf(host.getSystemService(BluetoothManager::class.java).adapter).setEnabled(false)
    }

    @After fun cleanup() {
        ReflectionHelpers.getField<Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        ReflectionHelpers.getField<SessionLogFile>(host, "sessionLog").close()
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            ReflectionHelpers.getField<ExecutorService>(host, name).shutdownNow()
        }
        host.getSharedPreferences("diplay_bt_profile_guard", 0).edit().clear().commit()
    }

    private fun schedule() {
        host.javaClass.getDeclaredMethod("scheduleAutoPlayAfterYield").apply { isAccessible = true }.invoke(host)
    }

    @Test fun schedulingAgainAfterFirstPlayIsSuppressedAndDiagnosed() {
        schedule()
        loop.idleFor(3, TimeUnit.SECONDS)
        schedule()
        loop.idleFor(3, TimeUnit.SECONDS)
        verify(controller).sendMediaButton(CarPlayMediaButton.PLAY)
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        val log = File(host.filesDir, "logs/diplay.log").readText()
        assertTrue(log.contains("AutoPlay enqueue schedule=1"))
        assertFalse(log.contains("AutoPlay enqueue schedule=2"))
        assertTrue(log.contains("reason=duplicate-session"))
        assertTrue(log.contains("playing=true elapsedMs=207000"))
    }

    @Test fun duplicateDoesNotDelayThePendingPlay() {
        schedule()
        loop.idleFor(2, TimeUnit.SECONDS)
        schedule()
        verify(controller, never()).sendMediaButton(anyInt())
        loop.idleFor(1, TimeUnit.SECONDS)
        verify(controller).sendMediaButton(CarPlayMediaButton.PLAY)
        loop.idleFor(10, TimeUnit.SECONDS)
        verify(controller).sendMediaButton(CarPlayMediaButton.PLAY)
    }

    @Test fun duplicateSessionActiveStillYieldsBluetoothBeforeSkippingAutoplay() {
        val session = ReflectionHelpers.getField<AirPlaySession>(host, "activeAirPlaySession")
        val listener = ReflectionHelpers.callInstanceMethod<AirPlaySessionListener>(host, "createSessionListener",
            from(Int::class.javaPrimitiveType, 0))
        listener.onSessionActive(session)
        listener.onSessionActive(session)
        loop.idle()
        verify(session, times(2)).deviceBtMac
        loop.idleFor(3, TimeUnit.SECONDS)
        verify(controller).sendMediaButton(CarPlayMediaButton.PLAY)
    }

    @Test fun waitsForBluetoothOffAndDoesNotKeepSendingAfterSuccess() {
        shadowOf(host.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        schedule()
        loop.idleFor(3, TimeUnit.SECONDS)
        verify(controller, never()).sendMediaButton(anyInt())
        shadowOf(host.getSystemService(BluetoothManager::class.java).adapter).setEnabled(false)
        loop.idleFor(20, TimeUnit.SECONDS)
        verify(controller).sendMediaButton(CarPlayMediaButton.PLAY)
    }

    @Test fun mediaOnlyModeNeverSchedulesAutomaticPlay() {
        CarBluetoothProfileGuard.saveMediaOnlyEnabled(host, true)
        schedule()
        loop.idleFor(20, TimeUnit.SECONDS)
        verify(controller, never()).sendMediaButton(anyInt())
    }

    @Test fun newSessionGetsOnePlayAndReplacesTheOldPendingTimer() {
        schedule()
        loop.idleFor(2, TimeUnit.SECONDS)
        ReflectionHelpers.setField(host, "activeAirPlaySession", mock(AirPlaySession::class.java))
        schedule()
        loop.idleFor(1, TimeUnit.SECONDS)
        verify(controller, never()).sendMediaButton(anyInt())
        loop.idleFor(2, TimeUnit.SECONDS)
        verify(controller).sendMediaButton(CarPlayMediaButton.PLAY)
        ReflectionHelpers.setField(host, "activeAirPlaySession", mock(AirPlaySession::class.java))
        schedule()
        loop.idleFor(3, TimeUnit.SECONDS)
        schedule()
        loop.idleFor(3, TimeUnit.SECONDS)
        verify(controller, times(2)).sendMediaButton(CarPlayMediaButton.PLAY)
    }

    @Test fun staleTimerCannotSendToAReplacementSession() {
        schedule()
        ReflectionHelpers.setField(host, "activeAirPlaySession", mock(AirPlaySession::class.java))
        loop.idleFor(3, TimeUnit.SECONDS)
        verify(controller, never()).sendMediaButton(anyInt())
    }

    @Test fun exhaustedBluetoothWaitDoesNotRearmButNewSessionGetsFreshChecks() {
        shadowOf(host.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        schedule()
        loop.idleFor(20, TimeUnit.SECONDS)
        shadowOf(host.getSystemService(BluetoothManager::class.java).adapter).setEnabled(false)
        schedule()
        loop.idleFor(3, TimeUnit.SECONDS)
        verify(controller, never()).sendMediaButton(anyInt())
        ReflectionHelpers.setField(host, "activeAirPlaySession", mock(AirPlaySession::class.java))
        shadowOf(host.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        schedule()
        loop.idleFor(3, TimeUnit.SECONDS)
        shadowOf(host.getSystemService(BluetoothManager::class.java).adapter).setEnabled(false)
        loop.idleFor(2, TimeUnit.SECONDS)
        verify(controller).sendMediaButton(CarPlayMediaButton.PLAY)
    }

    @Test fun fiveRelativeWaitsAllowOffAtThirteenSecondsButSixthOnGivesUp() {
        val bluetooth = shadowOf(host.getSystemService(BluetoothManager::class.java).adapter)
        bluetooth.setEnabled(true)
        schedule()
        for (i in 0 until 5) {
            loop.idleFor(if (i == 0) 3 else 2, TimeUnit.SECONDS)
            verify(controller, never()).sendMediaButton(anyInt())
        }
        loop.idleFor(1, TimeUnit.SECONDS) // 12 seconds: turn off before the sixth check.
        bluetooth.setEnabled(false)
        loop.idleFor(999, TimeUnit.MILLISECONDS)
        verify(controller, never()).sendMediaButton(anyInt())
        loop.idleFor(1, TimeUnit.MILLISECONDS)
        verify(controller).sendMediaButton(CarPlayMediaButton.PLAY)

        ReflectionHelpers.setField(host, "activeAirPlaySession", mock(AirPlaySession::class.java))
        bluetooth.setEnabled(true)
        schedule()
        loop.idleFor(13, TimeUnit.SECONDS)
        verify(controller).sendMediaButton(CarPlayMediaButton.PLAY)
        bluetooth.setEnabled(false)
        schedule()
        loop.idleFor(3, TimeUnit.SECONDS)
        verify(controller).sendMediaButton(CarPlayMediaButton.PLAY)
        ReflectionHelpers.setField(host, "activeAirPlaySession", mock(AirPlaySession::class.java))
        schedule()
        loop.idleFor(3, TimeUnit.SECONDS)
        verify(controller, times(2)).sendMediaButton(CarPlayMediaButton.PLAY)
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        val log = File(host.filesDir, "logs/diplay.log").readText()
        for (check in 1..5) assertTrue(log.contains("AutoPlay waiting schedule=1 check=$check max=5"))
        assertTrue(log.contains("AutoPlay skipped schedule=2 reason=bluetooth-still-on"))
    }

    @Test fun disabledSessionIsClaimedAndDisablingAfterScheduleDoesNotCancel() {
        CarBluetoothProfileGuard.saveAdapterEnabled(host, false)
        schedule()
        CarBluetoothProfileGuard.saveAdapterEnabled(host, true)
        schedule()
        loop.idleFor(3, TimeUnit.SECONDS)
        verify(controller, never()).sendMediaButton(anyInt())
        ReflectionHelpers.setField(host, "activeAirPlaySession", mock(AirPlaySession::class.java))
        schedule()
        CarBluetoothProfileGuard.saveAdapterEnabled(host, false)
        loop.idleFor(3, TimeUnit.SECONDS)
        verify(controller).sendMediaButton(CarPlayMediaButton.PLAY)
    }

    @Test fun endedSessionCancelsTimerButDoesNotRearmSameIdentity() {
        val session = ReflectionHelpers.getField<AirPlaySession>(host, "activeAirPlaySession")
        schedule()
        val listener = ReflectionHelpers.callInstanceMethod<AirPlaySessionListener>(host, "createSessionListener",
            from(Int::class.javaPrimitiveType, 0))
        listener.onSessionEnded(session)
        loop.idle()
        ReflectionHelpers.setField(host, "activeAirPlaySession", session)
        schedule()
        loop.idleFor(3, TimeUnit.SECONDS)
        verify(controller, never()).sendMediaButton(anyInt())
        ReflectionHelpers.setField(host, "activeAirPlaySession", mock(AirPlaySession::class.java))
        schedule()
        loop.idleFor(3, TimeUnit.SECONDS)
        verify(controller).sendMediaButton(CarPlayMediaButton.PLAY)
    }

    @Test fun returningToAnEarlierSessionIsANewSchedulingOpportunity() {
        val a = ReflectionHelpers.getField<AirPlaySession>(host, "activeAirPlaySession")
        val b = mock(AirPlaySession::class.java)
        schedule()
        ReflectionHelpers.setField(host, "activeAirPlaySession", b)
        schedule()
        ReflectionHelpers.setField(host, "activeAirPlaySession", a)
        schedule()
        loop.idleFor(3, TimeUnit.SECONDS)
        verify(controller).sendMediaButton(CarPlayMediaButton.PLAY)
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        assertTrue(File(host.filesDir, "logs/diplay.log").readText().contains("AutoPlay enqueue schedule=3"))
    }

    @Test fun onStopLeavesPendingAutoplayButShutdownMakesItsCallbackInert() {
        schedule()
        ReflectionHelpers.callInstanceMethod<Void>(host, "onStop")
        loop.idleFor(3, TimeUnit.SECONDS)
        verify(controller).sendMediaButton(CarPlayMediaButton.PLAY)
        ReflectionHelpers.setField(host, "activeAirPlaySession", mock(AirPlaySession::class.java))
        schedule()
        val executor = ReflectionHelpers.getField<ExecutorService>(host, "teardownExecutor")
        executor.shutdownNow()
        ReflectionHelpers.setField(host, "teardownExecutor", mock(ExecutorService::class.java))
        ReflectionHelpers.callInstanceMethod<Void>(host, "shutdown", from(Boolean::class.javaPrimitiveType, false),
            from(String::class.java, "test"), from(kotlin.jvm.functions.Function0::class.java, {}))
        ReflectionHelpers.setField(host, "controller", controller)
        clearInvocations(controller)
        loop.idleFor(3, TimeUnit.SECONDS)
        verify(controller, never()).nowPlayingSnapshot()
        verify(controller, never()).sendMediaButton(anyInt())
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        assertTrue(File(host.filesDir, "logs/diplay.log").readText().contains("reason=session-inactive-or-replaced"))
    }

    @Test fun queueRejectionDoesNotAllowDuplicateActivationToRetry() {
        `when`(controller.sendMediaButton(CarPlayMediaButton.PLAY)).thenReturn(false)
        schedule()
        loop.idleFor(3, TimeUnit.SECONDS)
        schedule()
        loop.idleFor(3, TimeUnit.SECONDS)
        verify(controller).sendMediaButton(CarPlayMediaButton.PLAY)
    }
}
