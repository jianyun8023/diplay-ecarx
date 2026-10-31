package com.shilapi.xcertplay

import android.os.Handler
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
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

    private fun storePendingSession(host: CarPlayHostActivity, stop: (() -> Unit) -> Unit) {
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
        ReflectionHelpers.setField(host, "sessionLog", SessionLogFile(folder.newFile("diplay.log")))
        return host
    }
}
