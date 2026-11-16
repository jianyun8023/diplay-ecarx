package com.shihab.diplay

import android.os.Handler
import android.os.Looper
import android.content.BroadcastReceiver
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.view.View
import android.widget.Button
import com.shihab.diplay.network.WirelessStartupFailure
import com.shihab.diplay.orchestration.CarPlayController
import com.shihab.diplay.orchestration.CarPlayStatus
import com.shihab.diplay.orchestration.MfiTarget
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1920dp-h1080dp-land-mdpi", manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class HostMenuRecoveryContractTest {
    private lateinit var host: CarPlayHostActivity
    private val startup = CarPlayStatus.Failed("startup timeout", startupFailure = WirelessStartupFailure.FIRST_TCP_TIMEOUT)
    private val stop = CarPlayStatus.Failed("configuration", startupFailure = WirelessStartupFailure.HOTSPOT_CONFIGURATION)
    private val manual = CarPlayStatus.Failed("permission", automaticRetryAllowed = false)

    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        RuntimeEnvironment.getApplication().getSharedPreferences("diplay_connection_decisions", 0).edit().clear().commit()
        host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        host.setTheme(android.R.style.Theme_Material_NoActionBar)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", host)
        set("startupRetryButton", Button(host).apply { visibility = View.GONE })
        set("wifiRecoveryButton", Button(host).apply { visibility = View.GONE })
        set("smoothVideo", AirPlayPersistence.loadSmoothVideo(host))
        set("directVideoOutput", AirPlayPersistence.loadDirectVideoOutput(host))
    }

    @After fun cleanup() {
        ReflectionHelpers.getField<Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
        ReflectionHelpers.getField<ExecutorService>(host, "teardownExecutor").shutdownNow()
        ReflectionHelpers.getField<ExecutorService>(host, "airPlayCommandExecutor").shutdownNow()
        CarPlayBackgroundSession.clear()
    }

    private fun set(name: String, value: Any?) = ReflectionHelpers.setField(host, name, value)
    private fun <T> get(name: String): T = ReflectionHelpers.getField(host, name)
    private fun report(status: CarPlayStatus) = ReflectionHelpers.callInstanceMethod<(CarPlayStatus) -> Unit>(host,
        "createStatusReporter", from(Int::class.javaPrimitiveType, 0))(status)
    private fun open() = set("menuOpen", true)
    private fun finish(reconnect: Boolean) = ReflectionHelpers.callInstanceMethod<Void>(host, "finishSettingsMenu",
        from(String::class.java, "closed"), from(Boolean::class.javaPrimitiveType, reconnect))
    private fun budget() = get<WirelessStartupRetryBudget>("startupRetryBudget")
    private fun withController() {
        val executor = get<ExecutorService>("teardownExecutor")
        executor.shutdownNow()
        set("teardownExecutor", mock(ExecutorService::class.java))
        set("controller", mock(CarPlayController::class.java))
        set("activeDisplaySize", CarPlayHostActivity.DisplaySize(1920, 1080))
    }

    @Test fun menuRetainsFirstManualFailureButReplacesNonManualFailures() {
        open()
        report(stop)
        assertSame(stop, get<CarPlayStatus.Failed>("failurePendingAfterMenu"))
        assertFalse(host.testFailureState().stopped)
        val ordinary = CarPlayStatus.Failed("ordinary")
        report(ordinary)
        assertSame(ordinary, get<CarPlayStatus.Failed>("failurePendingAfterMenu"))
        report(manual)
        assertSame(manual, get<CarPlayStatus.Failed>("failurePendingAfterMenu"))
        assertTrue(host.testFailureState().stopped)
        val secondManual = CarPlayStatus.Failed("other manual", automaticRetryAllowed = false)
        report(secondManual)
        report(CarPlayStatus.Failed("cleanup"))
        report(CarPlayStatus.RunningControl)
        assertSame(manual, get<CarPlayStatus.Failed>("failurePendingAfterMenu"))
        assertTrue(host.testFailureState().stopped)
        assertFalse(get("reconnectScheduled"))
        assertEquals(0, budget().retries)
        assertEquals(0, budget().attempts)
    }

    @Test fun startupStopOnCancelStopsWithoutConsumingBudget() {
        open(); report(stop); finish(false)
        assertTrue(host.testFailureState().stopped)
        assertFalse(get("reconnectScheduled"))
        assertEquals(0, budget().retries)
        assertEquals(0, budget().attempts)
    }

    @Test fun startupOnCancelSchedulesOnlyWhenNotAlreadyConsumedInGeneration() {
        open(); report(startup); finish(false)
        assertTrue(get("reconnectScheduled"))
        assertEquals(1, budget().retries)
        assertEquals(1, budget().attempts)
        open(); report(startup)
        val pending = get<Runnable>("pendingReconnect")
        finish(false)
        assertSame(pending, get<Runnable>("pendingReconnect"))
        assertEquals(1, budget().retries)
        assertEquals(1, budget().attempts)
        assertEquals(0, get<Int>("restartGeneration"))
    }

    @Test fun replayOfAlreadyConsumedStartupDoesNotFallThroughToControllerRestart() {
        withController()
        report(startup)
        open(); report(startup)
        finish(false)
        assertTrue(get("reconnectScheduled"))
        assertEquals(0, get<Int>("restartGeneration"))
        assertEquals(1, budget().retries)
        assertEquals(1, budget().attempts)
    }

    @Test fun saveManualAndStartupStopResetOnlyStartupAndRestartController() {
        budget().schedule(startup = true)
        report(CarPlayStatus.Failed("ordinary"))
        open(); report(manual)
        withController()
        finish(true)
        assertEquals(0, budget().retries)
        assertEquals(2, budget().attempts)
        assertFalse(host.testFailureState().stopped)
        assertEquals(1, get<Int>("restartGeneration"))
    }

    @Test fun saveStartupStopAlsoResetsStartupAndRestarts() {
        budget().schedule(startup = true)
        open(); report(stop)
        withController(); finish(true)
        assertEquals(0, budget().retries)
        assertEquals(1, budget().attempts)
        assertEquals(1, get<Int>("restartGeneration"))
    }

    @Test fun wifiResetReplayDependsOnReconnectAndTransportEvenWhenManual() {
        val wifi = CarPlayStatus.Failed("reset wifi", wifiResetRequired = true, automaticRetryAllowed = false)
        withController(); set("wirelessEnabled", false)
        set("handshakeResetInProgress", true)
        open(); report(wifi); finish(false)
        assertEquals(View.VISIBLE, get<View>("wifiRecoveryButton").visibility)
        assertFalse(get("startAfterHandshakeReset"))
        assertEquals(0, get<Int>("restartGeneration"))

        set("handshakeResetInProgress", false)
        host.testFailureState().resume()
        set("wirelessEnabled", true)
        get<View>("wifiRecoveryButton").visibility = View.GONE
        open(); report(wifi); finish(true)
        assertEquals(View.VISIBLE, get<View>("wifiRecoveryButton").visibility)
        assertEquals(0, get<Int>("restartGeneration"))

        get<View>("wifiRecoveryButton").visibility = View.GONE
        CarPlayBackgroundSession.usbTakeover = true
        open(); report(wifi); finish(true)
        assertEquals(View.GONE, get<View>("wifiRecoveryButton").visibility)
        assertEquals(1, get<Int>("restartGeneration"))
    }

    @Test fun replayPrecedesHandshakeAndHandshakePrecedesRestart() {
        withController(); set("handshakeResetInProgress", true)
        open(); report(stop); finish(false)
        assertFalse(host.testFailureState().stopped)
        assertTrue(ConnectionDiagnostics.report(host).contains("event=HANDSHAKE_RESET"))
        assertFalse(get("startAfterHandshakeReset"))
        assertEquals(0, get<Int>("restartGeneration"))
        host.testFailureState().resume()
        open(); report(CarPlayStatus.Failed("ordinary")); finish(false)
        assertTrue(get("startAfterHandshakeReset"))
        assertEquals(0, get<Int>("restartGeneration"))
    }

    @Test fun ordinaryPendingRestartsOnlyWithController() {
        withController()
        open(); report(CarPlayStatus.Failed("ordinary")); finish(false)
        assertEquals(1, get<Int>("restartGeneration"))
        assertEquals(0, budget().attempts)
    }

    @Test fun noControllerOrNoPendingDoesNotRestartAndExistingTimerSurvivesCancel() {
        open(); report(CarPlayStatus.Failed("ordinary")); finish(false)
        assertEquals(0, get<Int>("restartGeneration"))
        assertFalse(get("reconnectScheduled"))
        withController()
        report(CarPlayStatus.Failed("outside"))
        val pending = get<Runnable>("pendingReconnect")
        open(); finish(false)
        assertSame(pending, get<Runnable>("pendingReconnect"))
        assertTrue(get("reconnectScheduled"))
        assertEquals(0, get<Int>("restartGeneration"))
        assertTrue(get<Handler>("mainHandler").hasCallbacks(pending))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertNull(get<Runnable?>("pendingReconnect"))
    }

    @Test fun invalidSettingsKeepMenuAndFailurePending() {
        open(); report(manual)
        set("mfiTarget", MfiTarget.I2C)
        set("mfiI2cPath", "")
        ReflectionHelpers.callInstanceMethod<Void>(host, "saveSettingsAndReconnect")
        assertTrue(get("menuOpen"))
        assertSame(manual, get<CarPlayStatus.Failed>("failurePendingAfterMenu"))
        assertTrue(host.testFailureState().stopped)
    }

    @Test fun usbDetachDuringMenuClearsFailureAndDefersRecovery() {
        CarPlayBackgroundSession.usbTakeover = true
        open(); report(manual)
        val usb = mock(UsbDevice::class.java)
        `when`(usb.vendorId).thenReturn(1452)
        get<BroadcastReceiver>("usbConnectionReceiver").onReceive(host,
            Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).putExtra(UsbManager.EXTRA_DEVICE, usb))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertNull(get<CarPlayStatus.Failed?>("failurePendingAfterMenu"))
        assertFalse(host.testFailureState().stopped)
        assertTrue(get("recoveryPendingAfterMenu"))
        assertTrue(get("menuOpen"))
    }
}
