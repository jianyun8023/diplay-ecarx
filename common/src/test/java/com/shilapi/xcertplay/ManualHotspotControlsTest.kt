package com.shilapi.xcertplay

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.ManualHotspotControl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-w1920dp-h1080dp-land-mdpi", manifest = Config.NONE)
class ManualHotspotControlsTest {
    private class Backend : ManualHotspotControls.Backend {
        var state = ManualHotspotControl.State.ON
        var wifiState = ManualHotspotControl.State.OFF
        var wifiAvailable = ManualHotspotControl.Capability.AVAILABLE
        var wifiResult = ManualHotspotControl.Result.ON
        val wifiWrites = mutableListOf<Boolean>()
        var available = ManualHotspotControl.Capability.AVAILABLE
        var result = ManualHotspotControl.Result.OFF
        val writes = mutableListOf<Boolean>()
        var work: (() -> Unit)? = null
        var queuedCount = 0
        var rejectExecution = false
        override fun observe() = state
        override fun capability() = available
        override fun observeWifi() = wifiState
        override fun wifiCapability() = wifiAvailable
        override fun requestWifi(enabled: Boolean, cancelled: () -> Boolean): ManualHotspotControl.Result {
            if (cancelled()) return ManualHotspotControl.Result.CANCELLED
            wifiWrites += enabled
            return wifiResult
        }
        override fun request(enabled: Boolean, cancelled: () -> Boolean): ManualHotspotControl.Result {
            if (cancelled()) return ManualHotspotControl.Result.CANCELLED
            writes += enabled
            return result
        }
        override fun execute(block: () -> Unit) {
            if (rejectExecution) throw java.util.concurrent.RejectedExecutionException()
            queuedCount++; work = block
        }
    }
    private class Fixture {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get().apply {
            setTheme(android.R.style.Theme_Material_NoActionBar)
        }
        val backend = Backend()
        var session = false
        var settings = 0
        var permissions = 0
        val controls = ManualHotspotControls(activity, { session }, { settings++ }, { permissions++ }, backend)
        var root = controls.build()
        fun button(id: Int) = views(root).filterIsInstance<Button>().single { it.text == activity.getString(id) }
        fun click(id: Int) { button(id).performClick() }
        fun confirm() {
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            // AlertDialog dispatches its button listener through the main Handler. Deliver that
            // real callback before asserting pending work or simulating a lifecycle transition.
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun finishWork() {
            assertNotNull("confirmed action must have queued a worker", backend.work)
            backend.work!!.invoke()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
    @Test fun renderRefreshAndSettingsNeverWrite() {
        val f = Fixture()
        f.root = f.controls.build()
        f.click(R.string.settings_manual_hotspot_refresh)
        f.click(R.string.open_car_hotspot_settings)
        assertEquals(1, f.settings)
        assertEquals(0, f.backend.queuedCount)
        assertTrue(f.backend.writes.isEmpty())
        f.controls.close()
    }
    @Test fun offRequiresConfirmationAndChecksNewOwnerAgain() {
        val f = Fixture()
        f.click(R.string.settings_manual_hotspot_off)
        assertEquals(0, f.backend.queuedCount)
        f.session = true
        f.confirm()
        assertEquals(0, f.backend.queuedCount)
        assertTrue(f.backend.writes.isEmpty())
        f.controls.close()
    }
    @Test fun cancelConfirmationDoesNotWrite() {
        val f = Fixture()
        f.click(R.string.settings_manual_hotspot_off)
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, f.backend.queuedCount)
        f.controls.close()
    }
    @Test fun queuedActionChecksOwnerBeforeWrite() {
        val f = Fixture()
        f.click(R.string.settings_manual_hotspot_off); f.confirm()
        f.session = true
        f.finishWork()
        assertTrue(f.backend.writes.isEmpty())
        assertFalse(f.controls.isBusy)
        f.controls.close()
    }
    @Test fun lifecycleCancellationKeepsBusyUntilWorkerExitsAndNeverReplays() {
        val f = Fixture()
        f.click(R.string.settings_manual_hotspot_off); f.confirm()
        f.controls.cancelPending()
        f.root = f.controls.build()
        assertTrue(f.controls.isBusy)
        assertFalse(f.button(R.string.settings_manual_hotspot_off).isEnabled)
        f.click(R.string.settings_manual_hotspot_off)
        assertEquals(1, f.backend.queuedCount)
        f.finishWork()
        assertTrue(f.backend.writes.isEmpty())
        assertFalse(f.controls.isBusy)
        f.controls.close()
    }
    @Test fun closeBeforeWorkerPreventsWrite() {
        val f = Fixture()
        f.click(R.string.settings_manual_hotspot_off); f.confirm()
        f.controls.close(); f.finishWork()
        assertTrue(f.backend.writes.isEmpty())
    }
    @Test fun requestIsOnceAndTimeoutDoesNotShowOff() {
        val f = Fixture()
        f.backend.result = ManualHotspotControl.Result.TIMED_OUT
        f.click(R.string.settings_manual_hotspot_off); f.confirm()
        f.click(R.string.settings_manual_hotspot_off)
        f.finishWork()
        assertEquals(listOf(false), f.backend.writes)
        assertEquals(1, f.backend.queuedCount)
        assertTrue(views(f.root).filterIsInstance<android.widget.TextView>().any { it.text == f.activity.getString(R.string.settings_manual_hotspot_timeout) })
        assertTrue(views(f.root).filterIsInstance<android.widget.TextView>().any { it.text == f.activity.getString(R.string.settings_manual_hotspot_state_on) })
        val report = SettingsActionDiagnostics.report(f.activity)
        assertTrue(report.contains("phase=begin enabled=false before=ON"))
        assertTrue(report.contains("phase=finished enabled=false result=TIMED_OUT after=ON"))
        f.controls.close()
    }
    @Test fun onIsExplicitAndActiveOwnerBlocksIt() {
        val f = Fixture()
        f.backend.state = ManualHotspotControl.State.OFF
        f.root = f.controls.build()
        f.session = true; f.click(R.string.settings_manual_hotspot_on)
        assertEquals(0, f.backend.queuedCount)
        f.session = false; f.click(R.string.settings_manual_hotspot_on); f.finishWork()
        assertEquals(listOf(true), f.backend.writes)
        f.controls.close()
    }
    @Test fun unknownAndTransitionNeverOfferAnEnabledWrite() {
        val f = Fixture()
        for (state in listOf(ManualHotspotControl.State.UNKNOWN, ManualHotspotControl.State.STARTING,
            ManualHotspotControl.State.STOPPING, ManualHotspotControl.State.FAILED)) {
            f.backend.state = state; f.root = f.controls.build()
            assertFalse(f.button(R.string.settings_manual_hotspot_on).isEnabled)
            assertFalse(f.button(R.string.settings_manual_hotspot_off).isEnabled)
        }
        assertEquals(0, f.backend.queuedCount)
        f.controls.close()
    }
    @Test fun rejectedWorkerSubmissionReleasesBusyAndAllowsAnotherExplicitAttempt() {
        val f = Fixture()
        f.backend.rejectExecution = true
        f.click(R.string.settings_manual_hotspot_off); f.confirm()
        assertFalse(f.controls.isBusy)
        assertTrue(f.backend.writes.isEmpty())
        f.backend.rejectExecution = false
        f.click(R.string.settings_manual_hotspot_off); f.confirm(); f.finishWork()
        assertEquals(listOf(false), f.backend.writes)
        f.controls.close()
    }

    @Test fun activityProjectionEntryDoesNotLaunchWhileManualRequestIsPending() {
        val f = Fixture()
        f.click(R.string.settings_manual_hotspot_off); f.confirm()
        org.robolectric.util.ReflectionHelpers.setField(f.activity, "manualHotspotControls", f.controls)
        f.activity.javaClass.getDeclaredMethod("openProjection").apply { isAccessible = true }.invoke(f.activity)
        assertNull(shadowOf(f.activity).nextStartedActivity)
        assertTrue(f.controls.isBusy)
        f.controls.close(); f.finishWork()
    }

    @Test fun missingPermissionOnlyOpensUserPermissionScreen() {
        val f = Fixture()
        f.backend.available = ManualHotspotControl.Capability.PERMISSION_REQUIRED
        f.root = f.controls.build()
        f.click(R.string.settings_write_settings_allow)
        assertEquals(1, f.permissions)
        assertTrue(f.backend.writes.isEmpty())
        assertFalse(views(f.root).filterIsInstance<Button>().any { it.text == f.activity.getString(R.string.settings_manual_hotspot_off) })
        f.controls.close()
    }
    @Test fun unsupportedStillOffersNativeSettings() {
        val f = Fixture()
        f.backend.available = ManualHotspotControl.Capability.UNSUPPORTED
        f.root = f.controls.build()
        f.click(R.string.open_car_hotspot_settings)
        assertEquals(1, f.settings)
        assertEquals(0, f.backend.queuedCount)
        f.controls.close()
    }

    @Test fun failedHotspotStillOffersExplicitWifiSwitchesAndRefreshesActualState() {
        val f = Fixture()
        f.backend.state = ManualHotspotControl.State.FAILED
        f.backend.wifiResult = ManualHotspotControl.Result.TIMED_OUT
        f.root = f.controls.build()
        assertTrue(f.button(R.string.settings_manual_wifi_on).isEnabled)
        f.click(R.string.settings_manual_wifi_on)
        assertFalse(f.button(R.string.settings_manual_wifi_on).isEnabled)
        f.finishWork()
        assertEquals(listOf(true), f.backend.wifiWrites)
        assertTrue(f.backend.writes.isEmpty())
        assertTrue(views(f.root).filterIsInstance<android.widget.TextView>().any {
            it.text == f.activity.getString(R.string.settings_manual_wifi_state_off)
        })
        val report = SettingsActionDiagnostics.report(f.activity)
        assertTrue(report.contains("action=MANUAL_WIFI phase=begin enabled=true before=OFF"))
        assertTrue(report.contains("action=MANUAL_WIFI phase=finished enabled=true result=TIMED_OUT after=OFF"))
        f.backend.wifiState = ManualHotspotControl.State.ON
        f.click(R.string.settings_manual_hotspot_refresh)
        assertTrue(f.button(R.string.settings_manual_wifi_off).isEnabled)
        assertFalse(f.button(R.string.settings_manual_wifi_on).isEnabled)
        f.controls.close()
    }

    @Test fun wifiUsesTheExistingOwnerAndLifecycleCancellationGate() {
        val f = Fixture()
        f.session = true
        f.click(R.string.settings_manual_wifi_on)
        assertEquals(0, f.backend.queuedCount)
        f.session = false
        f.click(R.string.settings_manual_wifi_on)
        f.controls.cancelPending()
        f.finishWork()
        assertTrue(f.backend.wifiWrites.isEmpty())
        assertFalse(f.controls.isBusy)
        f.click(R.string.settings_manual_wifi_on)
        f.session = true
        f.finishWork()
        assertTrue(f.backend.wifiWrites.isEmpty())
        f.controls.close()
    }

    @Test fun wifiOffIsExplicitAndWifiDoesNotNeedHotspotWriteSettingsPermission() {
        val f = Fixture()
        f.backend.available = ManualHotspotControl.Capability.PERMISSION_REQUIRED
        f.backend.wifiState = ManualHotspotControl.State.ON
        f.backend.wifiResult = ManualHotspotControl.Result.OFF
        f.root = f.controls.build()
        f.click(R.string.settings_manual_wifi_off)
        f.finishWork()
        assertEquals(listOf(false), f.backend.wifiWrites)
        assertTrue(f.backend.writes.isEmpty())
        assertEquals(0, f.permissions)
        f.controls.close()
    }
    companion object {
        private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    }
}
