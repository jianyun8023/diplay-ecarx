package com.shihab.diplay

import android.app.AppOpsManager
import android.app.AlertDialog
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class UsbPermissionSetupTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun existingPermissionsNeedNoWrite() {
        val result = UsbPermissionSetup.Operation(app, apply = { fail("unnecessary write") }, granted = { true }).run()
        assertTrue(result.complete)
    }

    @Test fun successRequiresReadbackForEveryPermission() {
        val applied = mutableListOf<UsbPermissionSetup.Permission>()
        val result = UsbPermissionSetup.Operation(app, apply = { applied += it }, granted = { false }).run()
        assertEquals(UsbPermissionSetup.Permission.entries, applied)
        assertFalse(result.complete)
        assertTrue(result.verified.values.none { it })
    }

    @Test fun aDeniedPermissionDoesNotPreventOtherNativeGrants() {
        val granted = mutableSetOf<UsbPermissionSetup.Permission>()
        val denied = UsbPermissionSetup.Permission.ACCESSIBILITY
        val result = UsbPermissionSetup.Operation(app, apply = {
            if (it == denied) throw SecurityException("vehicle platform grant missing")
            granted += it
        }, granted = { it in granted }).run()
        assertFalse(result.complete)
        assertEquals(setOf(denied), result.failed)
        assertTrue(result.verified[UsbPermissionSetup.Permission.USAGE] == true)
        assertTrue(result.verified[UsbPermissionSetup.Permission.OVERLAY] == true)
    }

    @Test fun cancellationStopsSubsequentGrantsAndResults() {
        val applied = mutableListOf<UsbPermissionSetup.Permission>()
        lateinit var operation: UsbPermissionSetup.Operation
        operation = UsbPermissionSetup.Operation(app, apply = {
            applied += it
            operation.cancel()
        }, granted = { false })
        val result = operation.run()
        assertEquals(listOf(UsbPermissionSetup.Permission.ACCESSIBILITY), applied)
        assertTrue(result.verified.isEmpty())
        assertFalse(result.complete)
    }

    @Test fun appOpsAreGrantedOnlyToThisApplication() {
        shadowOf(app).grantPermissions("android.permission.MANAGE_APP_OPS_MODES")
        val manager = app.getSystemService(AppOpsManager::class.java)
        val otherModes = listOf(AppOpsManager.OPSTR_GET_USAGE_STATS, AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW).associateWith {
            manager.checkOpNoThrow(it, app.applicationInfo.uid + 1, "other.app")
        }
        for (permission in listOf(UsbPermissionSetup.Permission.USAGE, UsbPermissionSetup.Permission.OVERLAY))
            permission.apply(app)
        for (op in listOf(AppOpsManager.OPSTR_GET_USAGE_STATS, AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW)) {
            assertEquals(AppOpsManager.MODE_ALLOWED, manager.checkOpNoThrow(op, app.applicationInfo.uid, app.packageName))
            assertEquals(otherModes[op], manager.checkOpNoThrow(op, app.applicationInfo.uid + 1, "other.app"))
        }
    }

    @Test fun progressCancelSuppressesLateDialogs() = cancelledUiAttempt { progress, _ -> progress.cancel() }

    @Test fun activityStopSuppressesLateDialogs() = cancelledUiAttempt { _, activity ->
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onStop")
    }

    @Test fun activityDestroySuppressesLateDialogs() = cancelledUiAttempt { _, activity ->
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onDestroy")
    }

    private fun cancelledUiAttempt(cancel: (AlertDialog, DiPlayActivity) -> Unit) {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val applied = mutableListOf<UsbPermissionSetup.Permission>()
        val operation = UsbPermissionSetup.Operation(app, apply = {
            applied += it
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
        }, granted = { false })
        activity.usbPermissionOperationFactory = { operation }
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "autoApplyPermissions")
        val progress = ShadowAlertDialog.getLatestAlertDialog()
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            cancel(progress, activity)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(operation.isCancelled)
            assertFalse(progress.isShowing)
        } finally {
            release.countDown()
        }
        Thread.getAllStackTraces().keys.filter { it.name == "diplay-auto-permission" }.forEach { it.join(3_000) }
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(progress, ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(listOf(UsbPermissionSetup.Permission.ACCESSIBILITY), applied)
    }
}
