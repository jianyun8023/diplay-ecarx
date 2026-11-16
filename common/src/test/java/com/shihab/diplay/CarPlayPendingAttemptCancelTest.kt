package com.shihab.diplay

import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.shihab.diplay.airplay.AirPlaySession
import com.shihab.diplay.airplay.AirPlaySessionListener
import com.shihab.diplay.media.AndroidMediaSink
import com.shihab.diplay.media.CarPlayNowPlaying
import com.shihab.diplay.orchestration.CarPlayController
import com.shihab.diplay.orchestration.CarPlayStatus
import com.shihab.diplay.orchestration.UsbPairingFailure
import com.shihab.diplay.transport.TransportFailureKind
import java.io.File
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.times
import org.mockito.Mockito.never
import org.mockito.Mockito.clearInvocations
import org.mockito.ArgumentCaptor
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * Leaving the connection screen before CarPlay ever drew a frame.
 *
 * The handshake used to keep running owned by no visible screen, so the next attempt — often after
 * choosing a different iPhone — adopted that same dead handshake and appeared stuck at the stage it
 * had reached, however many times the driver retried.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1920dp-h1080dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayPendingAttemptCancelTest {
    @get:Rule val folder = TemporaryFolder()
    private val hosts = mutableListOf<CarPlayHostActivity>()

    @Before fun setUp() {
        CarPlayBackgroundSession.clear()
    }

    @After fun tearDown() {
        for (host in hosts) {
            ReflectionHelpers.getField<AtomicBoolean>(host, "shuttingDown").set(true)
            ReflectionHelpers.getField<Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
            ReflectionHelpers.getField<ExecutorService>(host, "teardownExecutor").shutdownNow()
            ReflectionHelpers.getField<ExecutorService>(host, "airPlayCommandExecutor").shutdownNow()
        }
        CarPlayBackgroundSession.clear()
    }

    @Test
    fun leavingTheScreenCancelsAnAttemptThatNeverReachedCarPlay() {
        val host = host()
        var stopped = false
        storePendingSession(host) { completion ->
            stopped = true
            completion()
        }

        leaveConnectionScreen(host)

        assertTrue("the attempt that never connected must not outlive its screen", stopped)
    }

    @Test
    fun leavingTheScreenKeepsAConnectedSessionRunning() {
        val host = host()
        var stopped = false
        storePendingSession(host) { completion ->
            stopped = true
            completion()
        }
        ReflectionHelpers.setField(host, "activeAirPlaySession", mock(AirPlaySession::class.java))

        leaveConnectionScreen(host)

        assertFalse("a live CarPlay session keeps playing outside its screen", stopped)
    }

    @Test
    fun openingSettingsKeepsTheAttemptForTheDriverToFix() {
        val host = host()
        var stopped = false
        storePendingSession(host) { completion ->
            stopped = true
            completion()
        }

        ReflectionHelpers.callInstanceMethod<Unit>(
            host, "showDiPlayHome",
            ClassParameter.from(String::class.java, "settings"),
            ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )

        assertFalse("changing a setting mid-handshake is not a cancel", stopped)
    }

    @Test fun leavingAfterStartSessionKeepsHandshakeButFailureStopsIt() {
        val host = host()
        var stopped = false
        val controller = storePendingSession(host) { completion -> stopped = true; completion() }
        `when`(controller.startSessionDispatched()).thenReturn(true)
        ReflectionHelpers.setField(host, "controller", controller)

        leaveConnectionScreen(host)
        assertFalse(stopped)
        ReflectionHelpers.callInstanceMethod<(CarPlayStatus) -> Unit>(host, "createStatusReporter",
            ClassParameter.from(Int::class.javaPrimitiveType, 0))(CarPlayStatus.Failed("connection lost"))
        assertTrue(stopped)
        assertFalse(ReflectionHelpers.getField(host, "reconnectScheduled"))
    }

    @Test fun dispatchedAndPageSelectWhetherLeavingKeepsOrCancels() {
        val host = host()
        var stops = 0
        val controller = storePendingSession(host) { completion -> stops++; completion() }
        ReflectionHelpers.setField(host, "controller", controller)
        leave(host, "home", true)
        assertEquals(1, stops) // A real controller whose StartSession has not been sent.

        `when`(controller.startSessionDispatched()).thenReturn(true)
        leave(host, "home", true)
        assertEquals(1, stops)
        leave(host, "settings", false)
        assertEquals(1, stops)
        clearInvocations(controller)
        leave(host, "wireless-recovery", true)
        verify(controller, never()).startSessionDispatched()
        assertEquals(2, stops)
        report(host, CarPlayStatus.Failed("kept remains"))
        assertEquals(3, stops) // The earlier cancel did not clear the kept marker.
        assertFalse(ReflectionHelpers.getField(host, "reconnectScheduled"))
    }

    @Test fun settingsWithoutCancelDoesNotCreateAHiddenKeptAttempt() {
        val host = host()
        var stops = 0
        val controller = storePendingSession(host) { completion -> stops++; completion() }
        ReflectionHelpers.setField(host, "controller", controller)
        `when`(controller.startSessionDispatched()).thenReturn(true)
        leave(host, "settings", false)
        verify(controller, never()).startSessionDispatched()
        report(host, CarPlayStatus.Failed("normal failure"))
        assertEquals(0, stops)
        assertTrue(ReflectionHelpers.getField(host, "reconnectScheduled"))
    }

    @Test fun retentionIgnoresOwnerAndShutdownButCancellationDoesNot() {
        val host = host()
        var stops = 0
        val controller = storePendingSession(host) { completion -> stops++; completion() }
        ReflectionHelpers.setField(host, "controller", controller)
        ReflectionHelpers.getField<AtomicBoolean>(host, "shuttingDown").set(true)
        leave(host, "home", true)
        assertEquals(0, stops)
        ReflectionHelpers.getField<AtomicBoolean>(host, "shuttingDown").set(false)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", Any())
        leave(host, "home", true)
        assertEquals(0, stops)
        `when`(controller.startSessionDispatched()).thenReturn(true)
        ReflectionHelpers.getField<AtomicBoolean>(host, "shuttingDown").set(true)
        leave(host, "home", true)
        ReflectionHelpers.getField<AtomicBoolean>(host, "shuttingDown").set(false)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", host)
        report(host, CarPlayStatus.Failed("kept despite guards"))
        assertEquals(1, stops)
        assertFalse(ReflectionHelpers.getField(host, "reconnectScheduled"))
        leave(host, "home", true)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", Any())
        report(host, CarPlayStatus.Failed("no owner"))
        assertEquals(1, stops)
        assertFalse(ReflectionHelpers.getField(host, "reconnectScheduled"))
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", host)
        report(host, CarPlayStatus.Failed("now ordinary"))
        assertTrue(ReflectionHelpers.getField(host, "reconnectScheduled"))
    }

    @Test fun failedWhileShuttingDownOrInMenuConsumesWithoutFallingThrough() {
        val host = host()
        var stops = 0
        val controller = storePendingSession(host) { completion -> stops++; completion() }
        ReflectionHelpers.setField(host, "controller", controller)
        `when`(controller.startSessionDispatched()).thenReturn(true)
        leave(host, "home", true)
        ReflectionHelpers.getField<AtomicBoolean>(host, "shuttingDown").set(true)
        report(host, CarPlayStatus.Failed("shutdown"))
        assertEquals(0, stops)
        assertFalse(ReflectionHelpers.getField(host, "reconnectScheduled"))
        ReflectionHelpers.getField<AtomicBoolean>(host, "shuttingDown").set(false)
        report(host, CarPlayStatus.Failed("now ordinary"))
        assertEquals(0, stops)
        assertTrue(ReflectionHelpers.getField(host, "reconnectScheduled"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        leave(host, "home", true)
        ReflectionHelpers.setField(host, "menuOpen", true)
        report(host, CarPlayStatus.Failed("menu"))
        assertEquals(1, stops)
        assertNull(ReflectionHelpers.getField<CarPlayStatus.Failed?>(host, "failurePendingAfterMenu"))
        assertFalse(ReflectionHelpers.getField(host, "reconnectScheduled"))
    }

    @Test fun oldStatusAndSessionActiveCannotConsumeKeptAttempt() {
        val host = host()
        var stops = 0
        val controller = storePendingSession(host) { completion -> stops++; completion() }
        ReflectionHelpers.setField(host, "controller", controller)
        `when`(controller.startSessionDispatched()).thenReturn(true)
        leave(host, "home", true)
        ReflectionHelpers.setField(host, "restartGeneration", 1)
        report(host, CarPlayStatus.Failed("stale"), 0)
        listener(host, 0).onSessionActive(mock(AirPlaySession::class.java))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, stops)
        report(host, CarPlayStatus.Failed("current"), 1)
        assertEquals(1, stops)
        assertFalse(ReflectionHelpers.getField(host, "reconnectScheduled"))
    }

    @Test fun usbStoppedGenerationRejectsFailureBeforeBackgroundConsumption() {
        val host = host()
        var stops = 0
        val controller = storePendingSession(host) { completion -> stops++; completion() }
        ReflectionHelpers.setField(host, "controller", controller)
        `when`(controller.startSessionDispatched()).thenReturn(true)
        report(host, CarPlayStatus.Failed("refused", usbPairingFailure = UsbPairingFailure.TRUST_REFUSED))
        leave(host, "home", true)
        report(host, CarPlayStatus.Failed("blocked in same generation"))
        assertEquals(0, stops)
        ReflectionHelpers.setField(host, "restartGeneration", 1)
        report(host, CarPlayStatus.Failed("new generation"), 1)
        assertEquals(1, stops)
        assertFalse(ReflectionHelpers.getField(host, "reconnectScheduled"))
    }

    @Test fun currentSessionActiveClearsKeptAndRestoresOrdinaryRetry() {
        val host = host()
        var stops = 0
        val controller = storePendingSession(host) { completion -> stops++; completion() }
        ReflectionHelpers.setField(host, "controller", controller)
        `when`(controller.startSessionDispatched()).thenReturn(true)
        val budget = ReflectionHelpers.getField<WirelessStartupRetryBudget>(host, "startupRetryBudget")
        budget.schedule(startup = true)
        leave(host, "home", true)
        listener(host, 0).onSessionActive(mock(AirPlaySession::class.java))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, budget.retries)
        assertEquals(0, budget.attempts)
        report(host, CarPlayStatus.Failed("normal"))
        assertEquals(0, stops)
        assertTrue(ReflectionHelpers.getField(host, "reconnectScheduled"))
    }

    @Test fun onStartClearsKeptAndPreservesRetryBudget() {
        val host = host()
        var stops = 0
        val controller = storePendingSession(host) { completion -> stops++; completion() }
        ReflectionHelpers.setField(host, "controller", controller)
        `when`(controller.startSessionDispatched()).thenReturn(true)
        val budget = ReflectionHelpers.getField<WirelessStartupRetryBudget>(host, "startupRetryBudget")
        budget.schedule(startup = true)
        leave(host, "home", true)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", Any())
        ReflectionHelpers.callInstanceMethod<Void>(host, "onStart")
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", host)
        report(host, CarPlayStatus.Failed("normal"))
        assertEquals(0, stops)
        assertTrue(ReflectionHelpers.getField(host, "reconnectScheduled"))
        assertEquals(1, budget.retries)
        assertEquals(2, budget.attempts)
    }

    @Test fun adoptedSessionDoesNotInheritOldHostKeptMarkerOrBudget() {
        val old = host()
        var stops = 0
        val controller = storePendingSession(old) { completion -> stops++; completion() }
        ReflectionHelpers.setField(old, "controller", controller)
        `when`(controller.startSessionDispatched()).thenReturn(true)
        ReflectionHelpers.getField<WirelessStartupRetryBudget>(old, "startupRetryBudget").schedule(startup = true)
        leave(old, "home", true)
        val replacement = host()
        assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(replacement, "adoptBackgroundSession"))
        val budget = ReflectionHelpers.getField<WirelessStartupRetryBudget>(replacement, "startupRetryBudget")
        assertEquals(0, budget.retries)
        assertEquals(0, budget.attempts)
        report(replacement, CarPlayStatus.Failed("new host"))
        assertEquals(0, stops)
        assertTrue(ReflectionHelpers.getField(replacement, "reconnectScheduled"))
    }

    @Test fun controlEndedAndTransportCallbackDoNotConsumeKeptMarker() {
        val host = host()
        var stops = 0
        val controller = storePendingSession(host) { completion -> stops++; completion() }
        ReflectionHelpers.setField(host, "controller", controller)
        `when`(controller.startSessionDispatched()).thenReturn(true)
        leave(host, "home", true)
        ReflectionHelpers.callInstanceMethod<Void>(host, "onStop")
        report(host, CarPlayStatus.ControlEnded)
        listener(host, 0).onTransportError("transport", TransportFailureKind.OTHER)
        listener(host, 0).onSessionEnded(mock(AirPlaySession::class.java))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, stops)
        assertTrue(ReflectionHelpers.getField(host, "reconnectScheduled"))
        report(host, CarPlayStatus.Failed("failed"))
        assertEquals(1, stops)
    }

    @Test fun realBackgroundStopInvalidatesRepeatFailure() {
        val host = host()
        val original = ReflectionHelpers.getField<ExecutorService>(host, "teardownExecutor")
        original.shutdownNow()
        val executor = mock(ExecutorService::class.java)
        ReflectionHelpers.setField(host, "teardownExecutor", executor)
        val controller = storePendingSession(host) { completion ->
            ReflectionHelpers.callInstanceMethod<Void>(host, "shutdown",
                ClassParameter.from(Boolean::class.javaPrimitiveType, false),
                ClassParameter.from(String::class.java, "test background stop"),
                ClassParameter.from(kotlin.jvm.functions.Function0::class.java, completion))
        }
        ReflectionHelpers.setField(host, "controller", controller)
        `when`(controller.startSessionDispatched()).thenReturn(true)
        leave(host, "home", true)
        report(host, CarPlayStatus.Failed("first"))
        assertTrue(ReflectionHelpers.getField<AtomicBoolean>(host, "shuttingDown").get())
        assertEquals(1, ReflectionHelpers.getField<Int>(host, "restartGeneration"))
        report(host, CarPlayStatus.Failed("duplicate"))
        val task = ArgumentCaptor.forClass(Runnable::class.java)
        verify(executor, times(1)).execute(task.capture())
        assertFalse(ReflectionHelpers.getField(host, "reconnectScheduled"))
        task.value.run()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun report(host: CarPlayHostActivity, status: CarPlayStatus, generation: Int = 0) =
        ReflectionHelpers.callInstanceMethod<(CarPlayStatus) -> Unit>(host, "createStatusReporter",
            ClassParameter.from(Int::class.javaPrimitiveType, generation))(status)

    private fun listener(host: CarPlayHostActivity, generation: Int): AirPlaySessionListener =
        ReflectionHelpers.callInstanceMethod(host, "createSessionListener",
            ClassParameter.from(Int::class.javaPrimitiveType, generation))

    private fun leave(host: CarPlayHostActivity, page: String, cancel: Boolean) {
        ReflectionHelpers.callInstanceMethod<Unit>(host, "showDiPlayHome",
            ClassParameter.from(String::class.java, page), ClassParameter.from(Boolean::class.javaPrimitiveType, cancel))
    }

    private fun storePendingSession(host: CarPlayHostActivity, stop: (() -> Unit) -> Unit): CarPlayController {
        val controller = mock(CarPlayController::class.java)
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        CarPlayBackgroundSession.store(
            controller,
            mock(AndroidMediaSink::class.java),
            1280,
            720,
            host,
            CarPlaySessionDisplay(1280, 720, Surface.ROTATION_0, false, false, 1280, 720),
            stop,
        )
        return controller
    }

    private fun leaveConnectionScreen(host: CarPlayHostActivity) {
        ReflectionHelpers.callInstanceMethod<Unit>(
            host, "showDiPlayHome",
            ClassParameter.from(String::class.java, "home"),
            ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
    }

    private fun host(): CarPlayHostActivity {
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        hosts += host
        ReflectionHelpers.setField(host, "sessionLog", SessionLogFile(folder.newFile()))
        return host
    }
}
