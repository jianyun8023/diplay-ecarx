package com.shilapi.xcertplay.hud

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Looper
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class EcarxFactoryNavigationRestoreTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val packageName = "ecarx.naviservice"
    private val store get() = app.getSharedPreferences("diplay_factory_navigation_control", 0)
    private val processManager get() = shadowOf(app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)

    @Before fun setup() {
        store.edit().clear().commit()
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            this.packageName = this@EcarxFactoryNavigationRestoreTest.packageName
            applicationInfo = ApplicationInfo().apply {
                this.packageName = this@EcarxFactoryNavigationRestoreTest.packageName
                enabled = true
            }
        })
        shadowOf(app).grantPermissions("android.permission.CHANGE_COMPONENT_ENABLED_STATE", "android.permission.REAL_GET_TASKS")
        processManager.setProcesses(emptyList())
        // Cancel callbacks retained by the singleton between tests without touching a running service.
        EcarxFactoryNavigationRestore.pauseForGuidance(app)
        app.packageManager.setApplicationEnabledSetting(packageName, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, 0)
        store.edit().clear().commit()
    }

    @Test fun packageWeNeverPausedIsLeftAlone() {
        assertEquals("factoryNavigation restore=not-owned", EcarxFactoryNavigationRestore.restoreIfPausedByUs(app))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, app.packageManager.getApplicationEnabledSetting(packageName))
    }

    @Test fun pausePersistsOwnershipAndUsesDisabledUser() {
        val result = EcarxFactoryNavigationRestore.pauseForGuidance(app)
        assertTrue(result, result.contains("pause=disabled"))
        assertTrue(store.getBoolean("owned", false))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, store.getInt("original", -1))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, app.packageManager.getApplicationEnabledSetting(packageName))
        assertTrue(EcarxFactoryNavigationRestore.pauseForGuidance(app).contains("already-disabled"))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, store.getInt("original", -1))
    }

    @Test fun packageDisabledByTheDriverIsNeverClaimedOrEnabled() {
        app.packageManager.setApplicationEnabledSetting(packageName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0)
        assertTrue(EcarxFactoryNavigationRestore.pauseForGuidance(app).contains("owned=false"))
        EcarxFactoryNavigationRestore.restoreIfPausedByUs(app)
        assertFalse(store.getBoolean("owned", false))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, app.packageManager.getApplicationEnabledSetting(packageName))
    }

    @Test fun missingPausePermissionDoesNotClaimOrDisablePackage() {
        shadowOf(app).denyPermissions("android.permission.CHANGE_COMPONENT_ENABLED_STATE")
        assertTrue(EcarxFactoryNavigationRestore.pauseForGuidance(app).contains("permission-required"))
        assertFalse(store.getBoolean("owned", false))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, app.packageManager.getApplicationEnabledSetting(packageName))
    }

    @Test fun recoveryWaitsTwoSecondsAndOnlyClearsOwnershipAfterProcessConfirmation() {
        EcarxFactoryNavigationRestore.pauseForGuidance(app)
        val result = EcarxFactoryNavigationRestore.restoreIfPausedByUs(app)
        assertTrue(result, result.contains("serviceStart=delayed"))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, app.packageManager.getApplicationEnabledSetting(packageName))
        assertTrue(store.getBoolean("owned", false))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_999))
        assertNull(shadowOf(app).nextStartedService)
        // Duplicate cleanup does not postpone restart.
        EcarxFactoryNavigationRestore.restoreIfPausedByUs(app)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(ComponentName(packageName, "ecarx.naviservice.service.NaviService"), shadowOf(app).nextStartedService.component)
        assertTrue(store.getBoolean("owned", false))
        processManager.setProcesses(listOf(ActivityManager.RunningAppProcessInfo(packageName, 42, arrayOf(packageName))))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertFalse(store.getBoolean("owned", false))
        assertTrue(EcarxFactoryNavigationRestore.state(app).contains("restore=running"))
        assertEquals("factoryNavigation restore=not-owned", EcarxFactoryNavigationRestore.restoreIfPausedByUs(app))
    }

    @Test fun settledCallbackFiresOnceAfterProcessConfirmation() {
        EcarxFactoryNavigationRestore.pauseForGuidance(app)
        var settled = 0
        EcarxFactoryNavigationRestore.restoreIfPausedByUs(app) { settled++ }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(0, settled)
        processManager.setProcesses(listOf(ActivityManager.RunningAppProcessInfo(packageName, 42, arrayOf(packageName))))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(1, settled)
        assertFalse(store.getBoolean("owned", false))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
        assertEquals(1, settled)
    }

    @Test fun settledCallbackFiresAtOnceWhenNothingIsOwnedAndWhenSuperseded() {
        var settled = 0
        EcarxFactoryNavigationRestore.restoreIfPausedByUs(app) { settled++ }
        assertEquals(1, settled)
        EcarxFactoryNavigationRestore.pauseForGuidance(app)
        EcarxFactoryNavigationRestore.restoreIfPausedByUs(app) { settled++ }
        assertEquals(1, settled)
        // New guidance cancels the delayed start; the waiting caller must not hang on it.
        EcarxFactoryNavigationRestore.pauseForGuidance(app)
        assertEquals(2, settled)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
        assertEquals(2, settled)
    }

    @Test fun settledCallbackFiresWhenTheProcessNeverAppears() {
        EcarxFactoryNavigationRestore.pauseForGuidance(app)
        var settled = 0
        EcarxFactoryNavigationRestore.restoreIfPausedByUs(app) { settled++ }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals(0, settled)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(1, settled)
        assertTrue(store.getBoolean("owned", false))
        assertTrue(EcarxFactoryNavigationRestore.state(app).contains("process-unconfirmed"))
    }

    @Test fun originalExplicitEnabledStateIsPreservedAcrossRepeatedGuidance() {
        app.packageManager.setApplicationEnabledSetting(packageName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0)
        EcarxFactoryNavigationRestore.pauseForGuidance(app)
        EcarxFactoryNavigationRestore.restoreIfPausedByUs(app)
        EcarxFactoryNavigationRestore.pauseForGuidance(app)
        EcarxFactoryNavigationRestore.restoreIfPausedByUs(app)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, app.packageManager.getApplicationEnabledSetting(packageName))
    }

    @Test fun newGuidanceCancelsDelayedServiceRestart() {
        EcarxFactoryNavigationRestore.pauseForGuidance(app)
        EcarxFactoryNavigationRestore.restoreIfPausedByUs(app)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        val oldStart = EcarxFactoryNavigationRestore::class.java.getDeclaredField("pending").apply { isAccessible = true }
            .get(EcarxFactoryNavigationRestore) as Runnable
        EcarxFactoryNavigationRestore.pauseForGuidance(app)
        // Simulate a callback already dequeued and waiting for the controller lock.
        oldStart.run()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
        assertNull(shadowOf(app).nextStartedService)
        assertTrue(store.getBoolean("owned", false))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, app.packageManager.getApplicationEnabledSetting(packageName))
    }

    @Test fun newGuidanceAlsoCancelsPendingProcessConfirmation() {
        EcarxFactoryNavigationRestore.pauseForGuidance(app)
        EcarxFactoryNavigationRestore.restoreIfPausedByUs(app)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertTrue(shadowOf(app).nextStartedService != null)
        val oldVerify = EcarxFactoryNavigationRestore::class.java.getDeclaredField("pending").apply { isAccessible = true }
            .get(EcarxFactoryNavigationRestore) as Runnable
        EcarxFactoryNavigationRestore.pauseForGuidance(app)
        processManager.setProcesses(listOf(ActivityManager.RunningAppProcessInfo(packageName, 42, arrayOf(packageName))))
        oldVerify.run()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
        assertTrue(store.getBoolean("owned", false))
        assertTrue(EcarxFactoryNavigationRestore.state(app).startsWith("factoryNavigation pause=disabled"))
    }

    @Test fun startupRecoversPersistedOwnershipAndPermissionFailureKeepsItForRetry() {
        store.edit().putBoolean("owned", true).putInt("original", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT).commit()
        app.packageManager.setApplicationEnabledSetting(packageName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0)
        shadowOf(app).denyPermissions("android.permission.CHANGE_COMPONENT_ENABLED_STATE")
        assertTrue(EcarxFactoryNavigationRestore.restoreIfPausedByUs(app).contains("permission-required"))
        assertTrue(store.getBoolean("owned", false))
        shadowOf(app).grantPermissions("android.permission.CHANGE_COMPONENT_ENABLED_STATE")
        assertTrue(EcarxFactoryNavigationRestore.restoreIfPausedByUs(app).contains("restore=enabled"))
    }

    @Test fun serviceStartFailureKeepsMarkerAndCanBeRetried() {
        EcarxFactoryNavigationRestore.pauseForGuidance(app)
        val failing = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(service: Intent): ComponentName? = throw IllegalStateException("fixture-start-failed")
        }
        EcarxFactoryNavigationRestore.restoreIfPausedByUs(failing)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertTrue(store.getBoolean("owned", false))
        assertTrue(EcarxFactoryNavigationRestore.state(app).contains("fixture-start-failed"))
        assertTrue(EcarxFactoryNavigationRestore.restoreIfPausedByUs(app).contains("serviceStart=delayed"))
    }

    @Test fun nullServiceStartResultKeepsMarker() {
        EcarxFactoryNavigationRestore.pauseForGuidance(app)
        val missingService = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(service: Intent): ComponentName? = null
        }
        EcarxFactoryNavigationRestore.restoreIfPausedByUs(missingService)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertTrue(store.getBoolean("owned", false))
        assertTrue(EcarxFactoryNavigationRestore.state(app).contains("service-not-found"))
    }

    @Test fun unconfirmedOrInvisibleProcessKeepsMarker() {
        EcarxFactoryNavigationRestore.pauseForGuidance(app)
        EcarxFactoryNavigationRestore.restoreIfPausedByUs(app)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7))
        assertTrue(store.getBoolean("owned", false))
        assertTrue(EcarxFactoryNavigationRestore.state(app).contains("process-unconfirmed"))
        EcarxFactoryNavigationRestore.restoreIfPausedByUs(app)
        shadowOf(app).denyPermissions("android.permission.REAL_GET_TASKS")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertTrue(store.getBoolean("owned", false))
        assertTrue(EcarxFactoryNavigationRestore.state(app).contains("process-permission-required"))
    }
}
