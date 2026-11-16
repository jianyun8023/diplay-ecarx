package com.shihab.diplay

import android.content.BroadcastReceiver
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import com.shihab.diplay.airplay.AirPlaySession
import com.shihab.diplay.airplay.AirPlaySessionListener
import com.shihab.diplay.network.WirelessStartupFailure
import com.shihab.diplay.orchestration.CarPlayStatus
import com.shihab.diplay.orchestration.UsbPairingFailure
import java.time.Duration
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1920dp-h1080dp-land-mdpi", manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class HostFailureLatchContractTest {
    private lateinit var host: CarPlayHostActivity
    private val startup = CarPlayStatus.Failed("startup", startupFailure = WirelessStartupFailure.FIRST_TCP_TIMEOUT)
    private val usbFailure = CarPlayStatus.Failed("trust refused", usbPairingFailure = UsbPairingFailure.TRUST_REFUSED)

    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        RuntimeEnvironment.getApplication().getSharedPreferences("diplay_connection_decisions", 0).edit().clear().commit()
        host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        host.setTheme(android.R.style.Theme_Material_NoActionBar)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", host)
        set("startupRetryButton", Button(host).apply { visibility = View.GONE })
        set("smoothVideo", AirPlayPersistence.loadSmoothVideo(host))
        set("directVideoOutput", AirPlayPersistence.loadDirectVideoOutput(host))
    }

    @After fun cleanup() {
        get<Handler>("mainHandler").removeCallbacksAndMessages(null)
        get<ExecutorService>("teardownExecutor").shutdownNow()
        get<ExecutorService>("airPlayCommandExecutor").shutdownNow()
        CarPlayBackgroundSession.clear()
    }

    private fun set(name: String, value: Any?) = ReflectionHelpers.setField(host, name, value)
    private fun <T> get(name: String): T = ReflectionHelpers.getField(host, name)
    private fun report(status: CarPlayStatus, generation: Int = 0) =
        ReflectionHelpers.callInstanceMethod<(CarPlayStatus) -> Unit>(host, "createStatusReporter",
            from(Int::class.javaPrimitiveType, generation))(status)
    private fun listener(generation: Int = 0) = ReflectionHelpers.callInstanceMethod<AirPlaySessionListener>(host,
        "createSessionListener", from(Int::class.javaPrimitiveType, generation))
    private fun budget() = get<WirelessStartupRetryBudget>("startupRetryBudget")
    private fun finish(reconnect: Boolean) = ReflectionHelpers.callInstanceMethod<Void>(host, "finishSettingsMenu",
        from(String::class.java, "closed"), from(Boolean::class.javaPrimitiveType, reconnect))
    private fun usbDetach() {
        CarPlayBackgroundSession.usbTakeover = true
        val device = mock(UsbDevice::class.java)
        `when`(device.vendorId).thenReturn(1452)
        get<BroadcastReceiver>("usbConnectionReceiver").onReceive(host,
            Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).putExtra(UsbManager.EXTRA_DEVICE, device))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
    }

    @Test fun startupFailureIsConsumedOncePerGenerationIncludingInitialMinusOne() {
        set("restartGeneration", -1)
        report(startup, -1)
        assertEquals(0, budget().retries)
        assertFalse(get("reconnectScheduled"))
        set("restartGeneration", 0)
        report(startup)
        assertEquals(1, budget().retries)
        val pending = get<Runnable>("pendingReconnect")
        report(startup)
        assertSame(pending, get<Runnable>("pendingReconnect"))
        assertEquals(1, budget().retries)
        set("restartGeneration", 1)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        report(startup, 1)
        assertTrue(get("reconnectScheduled"))
        assertEquals(2, budget().retries)
    }

    @Test fun startupFailureIsConsumedBeforeHandshakeOwnerAndPendingGates() {
        set("handshakeResetInProgress", true)
        report(startup)
        set("handshakeResetInProgress", false)
        report(startup)
        assertFalse(get("reconnectScheduled"))
        assertEquals(0, budget().retries)

        set("restartGeneration", 1)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", Any())
        report(startup, 1)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", host)
        report(startup, 1)
        assertFalse(get("reconnectScheduled"))

        set("restartGeneration", 2)
        report(CarPlayStatus.Failed("ordinary"), 2)
        report(startup, 2)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertFalse(get("reconnectScheduled"))
        report(startup, 2)
        assertFalse(get("reconnectScheduled"))
        assertEquals(0, budget().retries)
    }

    @Test fun usbFallbackClearsStopsButDoesNotClearStartupGeneration() {
        report(startup)
        report(usbFailure)
        assertTrue(host.testFailureState().stopped)
        set("menuOpen", true)
        usbDetach()
        assertFalse(host.testFailureState().stopped)
        set("menuOpen", false)
        report(startup)
        assertFalse(get("reconnectScheduled"))
        assertEquals(1, budget().retries)
        report(CarPlayStatus.Failed("ordinary"))
        assertTrue(get("reconnectScheduled"))
        assertEquals(2, budget().attempts)
    }

    @Test fun retryButtonClearsGlobalStopButNotUsbGeneration() {
        report(usbFailure)
        ReflectionHelpers.callInstanceMethod<View>(host, "buildContentView")
        get<View>("startupRetryButton").performClick()
        assertFalse(host.testFailureState().stopped)
        assertEquals(0, get<Int>("restartGeneration"))
        val stage = get<String>("latestStage")
        report(CarPlayStatus.RunningControl)
        report(CarPlayStatus.Failed("ordinary"))
        report(CarPlayStatus.WaitingForReenumeration)
        assertEquals(stage, get<String>("latestStage"))
        assertFalse(get("reconnectScheduled"))
        assertFalse(get("usbReenumerating"))
        assertEquals(0, budget().attempts)
    }

    @Test fun retryAndMenuSaveDoNotClearStartupDeduplication() {
        report(startup)
        report(CarPlayStatus.Failed("manual", automaticRetryAllowed = false))
        ReflectionHelpers.callInstanceMethod<View>(host, "buildContentView")
        get<View>("startupRetryButton").performClick()
        assertEquals(0, get<Int>("restartGeneration"))
        report(startup)
        assertFalse(get("reconnectScheduled"))
        assertEquals(0, budget().retries)
        set("menuOpen", true)
        finish(true)
        assertEquals(0, get<Int>("restartGeneration"))
        report(startup)
        assertFalse(get("reconnectScheduled"))
        assertEquals(0, budget().retries)
    }

    @Test fun usbStopAcceptsMinusOneAndOnlyBlocksItsOwnGeneration() {
        set("restartGeneration", -1)
        report(usbFailure, -1)
        assertTrue(host.testFailureState().stopped)
        val stage = get<String>("latestStage")
        report(CarPlayStatus.RunningControl, -1)
        assertEquals(stage, get<String>("latestStage"))
        set("restartGeneration", 0)
        report(CarPlayStatus.RunningControl)
        assertNotEquals(stage, get<String>("latestStage"))
        report(CarPlayStatus.Failed("ordinary"))
        assertTrue(host.testFailureState().stopped)
        assertFalse(get("reconnectScheduled"))
    }

    @Test fun menuSaveClearsGlobalStopButNotUsbGeneration() {
        report(usbFailure)
        set("menuOpen", true)
        finish(true)
        assertFalse(host.testFailureState().stopped)
        assertEquals(0, get<Int>("restartGeneration"))
        report(startup)
        assertEquals(0, budget().retries)
        assertFalse(get("reconnectScheduled"))
    }

    @Test fun usbFailureInMenuDoesNotSetUsbGenerationBeforeReplay() {
        set("menuOpen", true)
        report(usbFailure)
        assertTrue(host.testFailureState().stopped)
        finish(true)
        report(startup)
        assertTrue(get("reconnectScheduled"))
        assertEquals(1, budget().retries)
    }

    @Test fun sessionActiveDoesNotClearGlobalStop() {
        report(CarPlayStatus.Failed("manual", automaticRetryAllowed = false))
        listener().onSessionActive(mock(AirPlaySession::class.java))
        shadowOf(Looper.getMainLooper()).idle()
        report(CarPlayStatus.Failed("ordinary"))
        assertTrue(host.testFailureState().stopped)
        assertFalse(get("reconnectScheduled"))
        assertEquals(0, budget().attempts)
    }
}
