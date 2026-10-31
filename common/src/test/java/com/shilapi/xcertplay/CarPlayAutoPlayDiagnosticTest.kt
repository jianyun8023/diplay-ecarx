package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.bluetooth.CarBluetoothProfileGuard
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
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
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(false)
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

    @Test fun waitsForBluetoothOffAndDoesNotKeepSendingAfterSuccess() {
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(true)
        schedule()
        loop.idleFor(3, TimeUnit.SECONDS)
        verify(controller, never()).sendMediaButton(anyInt())
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(false)
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
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(true)
        schedule()
        loop.idleFor(20, TimeUnit.SECONDS)
        schedule()
        loop.idleFor(3, TimeUnit.SECONDS)
        verify(controller, never()).sendMediaButton(anyInt())
        ReflectionHelpers.setField(host, "activeAirPlaySession", mock(AirPlaySession::class.java))
        schedule()
        loop.idleFor(3, TimeUnit.SECONDS)
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(false)
        loop.idleFor(2, TimeUnit.SECONDS)
        verify(controller).sendMediaButton(CarPlayMediaButton.PLAY)
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
