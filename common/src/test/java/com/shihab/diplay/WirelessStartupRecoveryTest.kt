package com.shihab.diplay

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.shihab.diplay.airplay.AirPlaySession
import com.shihab.diplay.airplay.AirPlaySessionListener
import com.shihab.diplay.host.R
import com.shihab.diplay.network.WirelessStartupException
import com.shihab.diplay.network.WirelessStartupFailure
import com.shihab.diplay.orchestration.UsbPairingFailure
import com.shihab.diplay.orchestration.CarPlayStatus
import com.shihab.diplay.orchestration.ConnectionFailureCause
import com.shihab.diplay.transport.BluetoothLinkCheck
import com.shihab.diplay.transport.TransportFailureKind
import com.shihab.diplay.transport.BluetoothRadioCheck
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from
import java.time.Duration
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.mockito.Mockito.mock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1920dp-h1080dp-land-mdpi", manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WirelessStartupRecoveryTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var retry: Button
    private val failure = CarPlayStatus.Failed("timeout", startupFailure = WirelessStartupFailure.FIRST_TCP_TIMEOUT)

    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("diplay_connection_decisions", 0).edit().clear().commit()
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", activity)
        retry = Button(activity).apply { visibility = View.GONE }
        ReflectionHelpers.setField(activity, "startupRetryButton", retry)
    }

    @After fun cleanup() {
        ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
        CarPlayBackgroundSession.clear()
    }

    private fun reconnectDirectly() {
        CarPlayHostActivity::class.java.getDeclaredMethod("reconnectAfterLoss", String::class.java,
            CarPlayStatus.Failed::class.java).apply { isAccessible = true }
            .invoke(activity, "private-device-error", CarPlayStatus.Failed("private-device-error"))
    }

    @Test fun retryGateReasonsAreRecordedWithoutRearmingOrChangingPolicy() {
        for ((field, event) in listOf("menuOpen" to "MENU_OPEN", "handshakeResetInProgress" to "HANDSHAKE_RESET",
            "startupRetryStopped" to "RETRY_STOPPED", "reconnectScheduled" to "ALREADY_SCHEDULED")) {
            if (field == "startupRetryStopped") activity.testFailureState().stop()
            else ReflectionHelpers.setField(activity, field, true)
            reconnectDirectly()
            assertTrue(ConnectionDiagnostics.report(activity).contains("event=$event"))
            if (field == "startupRetryStopped") activity.testFailureState().resume()
            else ReflectionHelpers.setField(activity, field, false)
        }
        assertEquals(0, budget().retries)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertFalse(ConnectionDiagnostics.report(activity).contains("private-device-error"))
    }

    @Test fun queuedRetryReportsStaleGenerationInsteadOfClaimingItExecuted() {
        report()
        ReflectionHelpers.setField(activity, "restartGeneration", 1)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        val trace = ConnectionDiagnostics.report(activity)
        assertTrue(trace.contains("event=RETRY_SCHEDULED"))
        assertTrue(trace.contains("event=STALE_GENERATION"))
        assertFalse(trace.contains("event=RETRY_DISPATCHED"))
    }

    @Test fun noOwnerIsRecordedAndNeverSchedulesRetry() {
        CarPlayBackgroundSession.clear()
        reconnectDirectly()
        assertTrue(ConnectionDiagnostics.report(activity).contains("event=NOT_OWNER"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
    }

    private fun report(generation: Int = 0, status: CarPlayStatus = failure) {
        ReflectionHelpers.callInstanceMethod<(CarPlayStatus) -> Unit>(activity, "createStatusReporter",
            from(Int::class.javaPrimitiveType, generation))(status)
    }
    @Test fun phoneTrustOutcomesStopRetriesAndPreserveActionableCopyAfterCleanup() {
        val stage = TextView(activity)
        ReflectionHelpers.setField(activity, "stageStatusView", stage)
        for ((generation, reason) in UsbPairingFailure.entries.withIndex()) {
            ReflectionHelpers.setField(activity, "restartGeneration", generation)
            activity.testFailureState().resume()
            report(generation, CarPlayStatus.Failed("permission denied", usbPairingFailure = reason))
            assertEquals(activity.getString(ConnectionFailureGuidance.pairingMessage(reason)), stage.text.toString())
            assertTrue(activity.testFailureState().stopped)
            assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
            assertEquals(View.VISIBLE, retry.visibility)
            report(generation, CarPlayStatus.Failed("USB socket closed"))
            assertEquals(activity.getString(ConnectionFailureGuidance.pairingMessage(reason)), stage.text.toString())
            assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        }
    }

    @Test fun terminalTrustFailureCancelsAnAlreadyQueuedTransportRetry() {
        report(status = CarPlayStatus.Failed("USB read failed"))
        assertTrue(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        report(status = CarPlayStatus.Failed("refused", usbPairingFailure = UsbPairingFailure.TRUST_REFUSED))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(0, ReflectionHelpers.getField<Int>(activity, "restartGeneration"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertTrue(activity.testFailureState().stopped)
        assertEquals(View.VISIBLE, retry.visibility)
    }

    @Test fun pendingTrustIsShownOnlyForExplicitPendingStatus() {
        val stage = TextView(activity)
        ReflectionHelpers.setField(activity, "stageStatusView", stage)
        report(status = CarPlayStatus.Pairing)
        assertNotEquals(activity.getString(R.string.usb_trust_waiting), stage.text.toString())
        report(status = CarPlayStatus.WaitingForIphoneTrust)
        assertEquals(activity.getString(R.string.usb_trust_waiting), stage.text.toString())
        assertFalse(activity.testFailureState().stopped)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
    }

    @Test fun menuKeepsTypedTrustFailureInsteadOfCleanupAndOldReportersAreIgnored() {
        ReflectionHelpers.setField(activity, "menuOpen", true)
        val refused = CarPlayStatus.Failed("refused", usbPairingFailure = UsbPairingFailure.TRUST_REFUSED)
        report(status = refused)
        report(status = CarPlayStatus.Failed("USB closed"))
        assertEquals(refused, ReflectionHelpers.getField<CarPlayStatus.Failed>(activity, "failurePendingAfterMenu"))
        assertTrue(activity.testFailureState().stopped)
        ReflectionHelpers.setField(activity, "menuOpen", false)
        report(status = refused)
        assertEquals(View.VISIBLE, retry.visibility)
        ReflectionHelpers.setField(activity, "restartGeneration", 1)
        activity.testFailureState().resume()
        retry.visibility = View.GONE
        report(0, refused)
        assertFalse(activity.testFailureState().stopped)
        report(1, CarPlayStatus.Failed("USB device disconnected"))
        assertTrue(ReflectionHelpers.getField(activity, "reconnectScheduled"))
    }

    private fun budget() = ReflectionHelpers.getField<WirelessStartupRetryBudget>(activity, "startupRetryBudget")

    private fun listener() = ReflectionHelpers.callInstanceMethod<AirPlaySessionListener>(activity, "createSessionListener",
        from(Int::class.javaPrimitiveType, 0))

    private fun retryTrace() = ConnectionDiagnostics.report(activity).lineSequence().last()
    private fun retryTimer() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))

    @Test fun bothRunningStatusesCancelPendingRebuildAtTheTimer() {
        for (running in listOf(CarPlayStatus.RunningControl, CarPlayStatus.RunningWireless)) {
            report(status = CarPlayStatus.Failed("lost"))
            report(status = running)
            retryTimer()
            assertTrue(retryTrace(), retryTrace().contains("event=ALREADY_RECOVERED"))
            assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        }
    }

    @Test fun oldGenerationStatusCannotChangeCurrentRecoveryDecision() {
        ReflectionHelpers.setField(activity, "restartGeneration", 1)
        report(1, CarPlayStatus.Failed("lost"))
        report(0, CarPlayStatus.RunningControl)
        retryTimer()
        assertTrue(retryTrace(), retryTrace().contains("event=RETRY_DISPATCHED"))

        report(1, CarPlayStatus.Failed("lost again"))
        report(1, CarPlayStatus.RunningWireless)
        report(0, CarPlayStatus.Failed("old loss"))
        retryTimer()
        assertTrue(retryTrace(), retryTrace().contains("event=ALREADY_RECOVERED"))
    }

    @Test fun failureOrControlEndAfterRunningKeepsPendingRebuild() {
        for (loss in listOf(CarPlayStatus.Failed("lost again"), CarPlayStatus.ControlEnded)) {
            report(status = CarPlayStatus.Failed("lost"))
            report(status = CarPlayStatus.RunningControl)
            report(status = loss)
            retryTimer()
            assertTrue(retryTrace(), retryTrace().contains("event=RETRY_DISPATCHED"))
        }
    }

    @Test fun sessionActiveDoesNotCountAsRunningControl() {
        report(status = CarPlayStatus.Failed("lost"))
        listener().onSessionActive(mock(AirPlaySession::class.java))
        shadowOf(Looper.getMainLooper()).idle()
        retryTimer()
        assertTrue(retryTrace(), retryTrace().contains("event=RETRY_DISPATCHED"))
    }

    @Test fun sessionEndAndTransportErrorRespectCurrentSessionAndPendingLoss() {
        val listener = listener()
        val active = mock(AirPlaySession::class.java)
        listener.onSessionActive(active)
        shadowOf(Looper.getMainLooper()).idle()
        report(status = CarPlayStatus.Failed("lost"))
        report(status = CarPlayStatus.RunningWireless)
        listener.onSessionEnded(mock(AirPlaySession::class.java))
        shadowOf(Looper.getMainLooper()).idle()
        retryTimer()
        assertTrue(retryTrace(), retryTrace().contains("event=ALREADY_RECOVERED"))

        report(status = CarPlayStatus.Failed("lost"))
        report(status = CarPlayStatus.RunningWireless)
        listener.onSessionEnded(active)
        shadowOf(Looper.getMainLooper()).idle()
        retryTimer()
        assertTrue(retryTrace(), retryTrace().contains("event=RETRY_DISPATCHED"))

        report(status = CarPlayStatus.Failed("lost"))
        report(status = CarPlayStatus.RunningWireless)
        listener.onTransportError("lost again", TransportFailureKind.OTHER)
        shadowOf(Looper.getMainLooper()).idle()
        retryTimer()
        assertTrue(retryTrace(), retryTrace().contains("event=RETRY_DISPATCHED"))
    }

    @Test fun staleTimerClearsTheNewerPendingFlagsBeforeCheckingGeneration() {
        report(status = CarPlayStatus.Failed("old"))
        report(status = CarPlayStatus.Failed("stop", automaticRetryAllowed = false))
        ReflectionHelpers.callInstanceMethod<View>(activity, "buildContentView")
        ReflectionHelpers.getField<View>(activity, "startupRetryButton").performClick()
        ReflectionHelpers.setField(activity, "restartGeneration", 1)
        report(1, CarPlayStatus.Failed("new", transportFailure = TransportFailureKind.IAP_TUNNEL))
        val newRetry = ReflectionHelpers.getField<Runnable>(activity, "pendingReconnect")
        val handler = ReflectionHelpers.getField<Handler>(activity, "mainHandler")
        assertTrue(handler.hasCallbacks(newRetry))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertTrue(retryTrace(), retryTrace().contains("event=STALE_GENERATION"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertNull(ReflectionHelpers.getField<Runnable?>(activity, "pendingReconnect"))
        assertTrue(handler.hasCallbacks(newRetry))
    }

    @Test fun menuTimerDefersButClosingBeforeTimerKeepsItsRecoveryDecision() {
        report(status = CarPlayStatus.Failed("lost"))
        report(status = CarPlayStatus.RunningControl)
        ReflectionHelpers.setField(activity, "menuOpen", true)
        retryTimer()
        assertTrue(retryTrace(), retryTrace().contains("event=MENU_OPEN"))
        assertTrue(ReflectionHelpers.getField(activity, "recoveryPendingAfterMenu"))

        ReflectionHelpers.setField(activity, "handshakeResetInProgress", true)
        ReflectionHelpers.callInstanceMethod<Void>(activity, "finishSettingsMenu",
            from(String::class.java, "closed"), from(Boolean::class.javaPrimitiveType, false))
        ReflectionHelpers.setField(activity, "handshakeResetInProgress", false)
        report(status = CarPlayStatus.Failed("lost"))
        ReflectionHelpers.setField(activity, "menuOpen", true)
        report(status = CarPlayStatus.RunningControl)
        ReflectionHelpers.setField(activity, "handshakeResetInProgress", true)
        ReflectionHelpers.callInstanceMethod<Void>(activity, "finishSettingsMenu",
            from(String::class.java, "closed"), from(Boolean::class.javaPrimitiveType, false))
        ReflectionHelpers.setField(activity, "handshakeResetInProgress", false)
        retryTimer()
        assertTrue(retryTrace(), retryTrace().contains("event=ALREADY_RECOVERED"))
    }

    @Test fun activeSettingsAndManualRetryResetDifferentBudgets() {
        report()
        val session = mock(AirPlaySession::class.java)
        listener().onSessionActive(session)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, budget().attempts)
        assertEquals(1, budget().retries)

        repeat(3) { budget().schedule(startup = false) }
        ReflectionHelpers.setField(activity, "menuOpen", true)
        ReflectionHelpers.callInstanceMethod<Void>(activity, "finishSettingsMenu",
            from(String::class.java, "saved"), from(Boolean::class.javaPrimitiveType, true))
        assertEquals(3, budget().attempts)
        assertEquals(0, budget().retries)

        budget().schedule(startup = true)
        ReflectionHelpers.callInstanceMethod<View>(activity, "buildContentView")
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", activity)
        ReflectionHelpers.getField<View>(activity, "startupRetryButton").performClick()
        assertEquals(0, budget().attempts)
        assertEquals(0, budget().retries)
    }

    @Test fun firstFrameTimerRequiresCurrentSessionAndGeneration() {
        budget().schedule(startup = true)
        repeat(2) { budget().schedule(startup = false) }
        val listener = listener()
        val first = mock(AirPlaySession::class.java)
        val second = mock(AirPlaySession::class.java)
        ReflectionHelpers.setField(activity, "activeAirPlaySession", first)
        listener.onVideoFrameRendered(first)
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        ReflectionHelpers.setField(activity, "activeAirPlaySession", second)
        listener.onVideoFrameRendered(second)
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertEquals(1, budget().retries)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(29))
        assertEquals(1, budget().retries)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(0, budget().retries)
        assertEquals(3, budget().attempts)

        budget().schedule(startup = true)
        val third = mock(AirPlaySession::class.java)
        ReflectionHelpers.setField(activity, "activeAirPlaySession", third)
        listener.onVideoFrameRendered(third)
        shadowOf(Looper.getMainLooper()).idle()
        ReflectionHelpers.setField(activity, "restartGeneration", 1)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
        assertEquals(1, budget().retries)
    }

    @Test fun mixedRetriesCountEveryScheduledAttemptEvenWhenCancelled() {
        report()
        assertTrue(ConnectionDiagnostics.report(activity).contains("delayMs=2000"))
        report() // An existing pending retry consumes nothing.
        assertEquals(1, budget().retries)
        assertEquals(1, budget().attempts)
        ReflectionHelpers.setField(activity, "restartGeneration", 1)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        report(1, CarPlayStatus.Failed("tunnel", transportFailure = TransportFailureKind.IAP_TUNNEL))
        assertTrue(ConnectionDiagnostics.report(activity).contains("delayMs=15000"))
        ReflectionHelpers.setField(activity, "restartGeneration", 2)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))
        report(2, CarPlayStatus.Failed("ordinary"))
        assertTrue(ConnectionDiagnostics.report(activity).contains("delayMs=8000"))
        assertEquals(1, budget().retries)
        assertEquals(3, budget().attempts)
    }

    @Test fun duplicateFailureAndCleanupErrorScheduleOnlyOneRetry() {
        report()
        report()
        report(status = CarPlayStatus.Failed("Bluetooth socket closed"))
        assertEquals(1, budget().retries)
        assertEquals(1, budget().attempts)
    }

    @Test fun typedTunnelFailureKeepsItsDelayWithoutEnglishText() {
        report(status = CarPlayStatus.Failed("隧道已关闭", transportFailure = TransportFailureKind.IAP_TUNNEL))
        assertTrue(ConnectionDiagnostics.report(activity).contains("delayMs=15000"))
        assertEquals(0, budget().retries)
        assertEquals(1, budget().attempts)
    }

    @Test fun diagnosticWordsCannotSelectTunnelOrRadioRecovery() {
        report(status = CarPlayStatus.Failed("AirPlay iAP tunnel: Turn on Bluetooth"))
        assertTrue(ConnectionDiagnostics.report(activity).contains("delayMs=2000"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectWaitsForBluetooth"))
        assertEquals(0, budget().retries)
    }

    @Test fun exhaustedBudgetStopsAutomaticRecoveryAndExposesRetry() {
        repeat(6) { generation ->
            ReflectionHelpers.setField(activity, "restartGeneration", generation)
            ReflectionHelpers.setField(activity, "reconnectScheduled", false)
            report(generation)
        }
        assertEquals(5, budget().retries)
        assertTrue(activity.testFailureState().stopped)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(View.VISIBLE, retry.visibility)
        report(5, CarPlayStatus.Failed("Bluetooth socket closed"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
    }

    @Test fun unrecoverableConfigurationDoesNotAutomaticallyRetry() {
        report(status = CarPlayStatus.Failed("Permission missing", startupFailure = WirelessStartupFailure.HOTSPOT_CONFIGURATION))
        assertEquals(0, budget().retries)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(View.VISIBLE, retry.visibility)
    }

    // No further attempt can make a head unit's Bluetooth reach the phone.
    @Test fun bluetoothThatReachesNoPhoneDoesNotAutomaticallyRetry() {
        report(status = CarPlayStatus.Failed("Android Bluetooth on this head unit reports connections that are not real",
            startupFailure = WirelessStartupFailure.BLUETOOTH_UNUSABLE))
        assertEquals(0, budget().retries)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertTrue(activity.testFailureState().stopped)
        assertEquals(View.VISIBLE, retry.visibility)
    }

    // Issue 709: every connect fails inside Android. The iPhone is not what is missing, and no retry helps.
    @Test fun bluetoothThatCannotOpenConnectionsSaysSoAndDoesNotRetry() {
        val stage = TextView(activity)
        ReflectionHelpers.setField(activity, "stageStatusView", stage)
        val failure = BluetoothLinkCheck.connectFailure(NullPointerException("Attempt to invoke interface method " +
            "'android.os.ParcelFileDescriptor android.bluetooth.IBluetoothSocketManager.connectSocket(" +
            "android.bluetooth.BluetoothDevice, int, android.os.ParcelUuid, int, int)' on a null object reference"),
            "phone-address") as WirelessStartupException
        report(status = CarPlayStatus.Failed(failure.message!!, startupFailure = failure.reason))
        assertEquals(activity.getString(R.string.bluetooth_cannot_open_connections), stage.text.toString())
        assertEquals(0, budget().retries)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertTrue(activity.testFailureState().stopped)
        assertEquals(View.VISIBLE, retry.visibility)
    }

    // A head unit whose Android has no Bluetooth adapter: nothing to turn on and nothing to wait for.
    @Test fun aHeadUnitWithoutABluetoothAdapterSaysSoAndDoesNotRetry() {
        val stage = TextView(activity)
        ReflectionHelpers.setField(activity, "stageStatusView", stage)
        val failure = BluetoothRadioCheck.failure(BluetoothRadioCheck.NO_ADAPTER) as WirelessStartupException
        report(status = CarPlayStatus.Failed(failure.message!!, startupFailure = failure.reason))
        assertEquals(activity.getString(R.string.bluetooth_cannot_open_connections), stage.text.toString())
        assertEquals(0, budget().retries)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertTrue(activity.testFailureState().stopped)
        assertEquals(View.VISIBLE, retry.visibility)
    }

    // The driver refused Android's prompt to turn the car hotspot off: a retry would only show it again.
    @Test fun wifiDirectRefusedBesideTheCarHotspotSaysSoAndDoesNotRetry() {
        val stage = TextView(activity)
        ReflectionHelpers.setField(activity, "stageStatusView", stage)
        report(status = CarPlayStatus.Failed("Could not establish WIFI_P2P hotspot: " +
            "Android was not allowed to turn the car hotspot off for Wi-Fi Direct",
            startupFailure = WirelessStartupFailure.WIFI_DIRECT_BESIDE_HOTSPOT))
        assertEquals(activity.getString(R.string.wifi_direct_refused_beside_hotspot), stage.text.toString())
        assertEquals(0, budget().retries)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertTrue(activity.testFailureState().stopped)
        assertEquals(View.VISIBLE, retry.visibility)
    }

    // The real wireless classifier requires manual retry after the driver enables Bluetooth.
    @Test fun wirelessRadioOffKeepsTheProductionManualRetryDecision() {
        val stage = TextView(activity)
        ReflectionHelpers.setField(activity, "stageStatusView", stage)
        report(status = CarPlayStatus.Failed(BluetoothRadioCheck.RADIO_OFF, automaticRetryAllowed = false,
            transportFailure = TransportFailureKind.BLUETOOTH_OFF))
        assertEquals(activity.getString(R.string.turn_on_bluetooth_in_the_head_unit_s_settings_to_connect), stage.text.toString())
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertTrue(activity.testFailureState().stopped)
        assertEquals(View.VISIBLE, retry.visibility)
        bluetooth(BluetoothAdapter.STATE_ON)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(0, budget().retries)
    }

    // Explicitly retryable callers retain the existing wake-on-radio behavior; wireless prerequisites
    // use the manual decision above. This synthetic input must not claim to be that production path.
    @Test fun bluetoothTurningOnRunsAnExplicitlyRetryableRadioFailure() {
        repeat(4) { budget().schedule(startup = false) }
        report(status = CarPlayStatus.Failed("无线电暂不可用", transportFailure = TransportFailureKind.BLUETOOTH_OFF))
        val pending = ReflectionHelpers.getField<Runnable>(activity, "pendingReconnect")
        val handler = ReflectionHelpers.getField<Handler>(activity, "mainHandler")
        assertTrue(handler.hasCallbacks(pending))
        bluetooth(BluetoothAdapter.STATE_TURNING_ON)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        bluetooth(BluetoothAdapter.STATE_ON)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        // It ran once, now; the delayed copy is gone.
        assertFalse(handler.hasCallbacks(pending))
    }

    @Test fun bluetoothTurningOnLeavesAnyOtherRetryOnItsDelay() {
        report()
        bluetooth(BluetoothAdapter.STATE_ON)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ReflectionHelpers.getField(activity, "reconnectScheduled"))
    }

    private fun bluetooth(state: Int) {
        ReflectionHelpers.getField<BroadcastReceiver>(activity, "bluetoothOnReceiver").onReceive(activity,
            Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, state))
    }

    /** Fails every attempt with [status] until the retries stop, checking the screen names [cause] meanwhile. */
    private fun assertNamedUntilRetriesStop(status: CarPlayStatus.Failed, cause: Int) {
        val stage = TextView(activity)
        ReflectionHelpers.setField(activity, "stageStatusView", stage)
        repeat(6) { generation ->
            ReflectionHelpers.setField(activity, "restartGeneration", generation)
            ReflectionHelpers.setField(activity, "reconnectScheduled", false)
            report(generation, status)
            if (generation < 5) assertEquals(activity.getString(cause), stage.text.toString())
        }
        assertTrue(activity.testFailureState().stopped)
        assertEquals(View.VISIBLE, retry.visibility)
        assertEquals(activity.getString(cause) + "\n" + activity.getString(R.string.wireless_startup_retries_exhausted),
            stage.text.toString())
    }

    private val hotspotNotReady = CarPlayStatus.Failed("Could not establish MANUAL hotspot: Hotspot network is not ready",
        startupFailure = WirelessStartupFailure.HOTSPOT_NOT_READY)
    private val carHotspotOff = CarPlayStatus.Failed("The car hotspot is off. Turn it on in the car settings and connect again.",
        startupFailure = WirelessStartupFailure.HOTSPOT_CONFIGURATION)

    // #809: the screen said "Getting CarPlay ready…" while the car hotspot never came up, and after the retries stopped.
    @Test fun aHotspotThatIsNotReadyIsNamedAndTheStopIsSaid() =
        assertNamedUntilRetriesStop(hotspotNotReady, R.string.hotspot_network_not_ready)

    @Test @Config(qualifiers = "zh-rCN-w1920dp-h1080dp-land-mdpi") fun aHotspotThatIsNotReadyIsNamedInChinese() {
        assertNotEquals("Hotspot network is not ready", activity.getString(R.string.hotspot_network_not_ready))
        assertNamedUntilRetriesStop(hotspotNotReady, R.string.hotspot_network_not_ready)
    }

    @Test fun anIphoneThatDoesNotConnectInTimeIsNamedAndTheStopIsSaid() =
        assertNamedUntilRetriesStop(failure, R.string.first_tcp_timeout)

    // Nothing is retried for a car hotspot DiPlay cannot use, so the screen does not say "Retrying…".
    @Test fun aHotspotDiPlayCannotUseSaysTheRetriesStopped() {
        val stage = TextView(activity)
        ReflectionHelpers.setField(activity, "stageStatusView", stage)
        listOf("The car hotspot is off. Turn it on in the car settings and connect again.",
            "Hotspot control permission is missing. Open the car hotspot settings and connect again.",
            "Manual hotspot is not running on 5 GHz").forEachIndexed { generation, cause ->
            ReflectionHelpers.setField(activity, "restartGeneration", generation)
            activity.testFailureState().resume()
            report(generation, CarPlayStatus.Failed(cause, startupFailure = WirelessStartupFailure.HOTSPOT_CONFIGURATION))
            assertEquals(cause, activity.getString(R.string.hotspot_network_not_ready) + "\n" +
                activity.getString(R.string.wireless_startup_retries_exhausted), stage.text.toString())
            assertEquals(0, budget().retries)
            assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        }
    }

    // The stopped attempt can still report its own failure again, or its cleanup's.
    @Test fun aLaterFailureOfTheStoppedAttemptKeepsTheLineSayingTheRetriesStopped() {
        val stage = TextView(activity)
        ReflectionHelpers.setField(activity, "stageStatusView", stage)
        val offline = CarPlayStatus.Failed("The car hotspot is off. Turn it on in the car settings and connect again.",
            startupFailure = WirelessStartupFailure.HOTSPOT_CONFIGURATION)
        report(status = offline)
        val stopped = activity.getString(R.string.hotspot_network_not_ready) + "\n" +
            activity.getString(R.string.wireless_startup_retries_exhausted)
        assertEquals(stopped, stage.text.toString())
        report(status = offline)
        report(status = CarPlayStatus.Failed("Bluetooth socket closed"))
        assertEquals(stopped, stage.text.toString())
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(View.VISIBLE, retry.visibility)
    }

    @Test fun aKnownCauseIsNamedAndRetriedAsBefore() {
        val stage = TextView(activity)
        ReflectionHelpers.setField(activity, "stageStatusView", stage)
        val log = File(activity.filesDir, "known-cause.log")
        ReflectionHelpers.setField(activity, "sessionLog", SessionLogFile(log))
        val notPaired = "The selected iPhone is no longer paired. Choose it again in DiPlay."
        report(status = CarPlayStatus.Failed(notPaired, knownCause = ConnectionFailureCause.CHOSEN_IPHONE_NOT_PAIRED))
        assertEquals(activity.getString(R.string.chosen_iphone_not_paired), stage.text.toString())
        assertEquals(0, budget().retries)
        assertTrue(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertFalse(activity.testFailureState().stopped)
        assertEquals(View.GONE, retry.visibility)
        assertTrue(ReflectionHelpers.getField<SessionLogFile>(activity, "sessionLog").awaitIdle(2_000))
        assertTrue(log.readText(), log.readText().contains("Failed: $notPaired; retrying in 2000ms generation=0 startupFailure=null"))
    }

    @Test fun aHotspotCauseIsNamedUntilTheRetriesStop() = assertNamedUntilRetriesStop(
        hotspotNotReady.copy(knownCause = ConnectionFailureCause.HOTSPOT_INTERFACE_NOT_FOUND), R.string.hotspot_interface_not_found)

    @Test fun aCarHotspotThatIsOffIsNamedWhereTheRetriesStop() {
        val stage = TextView(activity)
        ReflectionHelpers.setField(activity, "stageStatusView", stage)
        report(status = carHotspotOff.copy(knownCause = ConnectionFailureCause.CAR_HOTSPOT_OFF))
        assertEquals(activity.getString(R.string.car_hotspot_is_off) + "\n" +
            activity.getString(R.string.wireless_startup_retries_exhausted), stage.text.toString())
        assertEquals(0, budget().retries)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(View.VISIBLE, retry.visibility)
    }

    private data class Retries(val logWithoutTimes: List<String>, val used: Int)

    private fun retries(status: CarPlayStatus.Failed): Retries {
        cleanup()
        setup()
        val log = File.createTempFile("retries", ".log", activity.filesDir)
        ReflectionHelpers.setField(activity, "sessionLog", SessionLogFile(log))
        repeat(6) { generation ->
            ReflectionHelpers.setField(activity, "restartGeneration", generation)
            ReflectionHelpers.setField(activity, "reconnectScheduled", false)
            report(generation, status)
        }
        assertTrue(ReflectionHelpers.getField<SessionLogFile>(activity, "sessionLog").awaitIdle(2_000))
        return Retries(log.readLines().map { it.substringAfter("  ") }, budget().retries)
    }

    @Test fun aHotspotCauseLeavesTheRetriesAndTheLogAsTheyWere() {
        val notReady = retries(hotspotNotReady)
        assertEquals(5, notReady.used)
        assertTrue(notReady.toString(), notReady.logWithoutTimes.any { "retrying in" in it })
        assertEquals(notReady, retries(hotspotNotReady.copy(knownCause = ConnectionFailureCause.HOTSPOT_INTERFACE_NOT_FOUND)))
        val off = retries(carHotspotOff)
        assertTrue(off.toString(), off.logWithoutTimes.any { "wireless startup recovery stopped" in it })
        assertEquals(off, retries(carHotspotOff.copy(knownCause = ConnectionFailureCause.CAR_HOTSPOT_OFF)))
    }

    @Test fun staleGenerationUserDisconnectMenuAndDifferentOwnerCannotSchedule() {
        report(1)
        assertEquals(0, budget().retries)
        ReflectionHelpers.setField(activity, "menuOpen", true)
        report()
        assertEquals(0, budget().retries)
        ReflectionHelpers.setField(activity, "menuOpen", false)
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(true)
        report()
        assertEquals(0, budget().retries)
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(false)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", Any())
        report()
        assertEquals(0, budget().retries)
    }
}
