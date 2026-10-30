package com.shilapi.xcertplay.hud

import android.app.Application
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EcarxFactoryNavigationControlTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val packageName = EcarxFactoryNavigationControl.PACKAGE_NAME

    @Before fun setup() {
        app.getSharedPreferences("diplay_factory_navigation_control", 0).edit().clear().commit()
        app.getSharedPreferences("diplay_ecarx_outputs_v2", 0).edit().clear().commit()
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            this.packageName = this@EcarxFactoryNavigationControlTest.packageName
            applicationInfo = ApplicationInfo().apply {
                this.packageName = this@EcarxFactoryNavigationControlTest.packageName
                enabled = true
            }
        })
        shadowOf(app).grantPermissions(EcarxHicarControl.PERMISSION)
    }

    @Test fun disabledFactoryNavigationIsNeverOwnedOrEnabled() {
        for (original in listOf(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)) {
            app.packageManager.setApplicationEnabledSetting(packageName, original, 0)
            assertTrue(EcarxFactoryNavigationControl.acquire(app).contains("already-disabled owned=false"))
            assertTrue(EcarxFactoryNavigationControl.release(app).contains("not-owned"))
            assertEquals(original, app.packageManager.getApplicationEnabledSetting(packageName))
            assertNull(shadowOf(app).nextStartedService)
        }
    }

    @Test fun originalDefaultAndExplicitEnabledAreRestoredExactly() {
        // The retired UI preference must no longer gate internal takeover.
        app.getSharedPreferences("diplay_ecarx_outputs_v2", 0).edit()
            .putBoolean("pause_factory_navigation", false).commit()
        for (original in listOf(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED)) {
            app.packageManager.setApplicationEnabledSetting(packageName, original, 0)
            assertTrue(EcarxFactoryNavigationControl.acquire(app).contains("acquire=disabled"))
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                app.packageManager.getApplicationEnabledSetting(packageName))
            // A second guidance frame must not replace the saved original setting with DISABLED_USER.
            EcarxFactoryNavigationControl.acquire(app)
            assertTrue(EcarxFactoryNavigationControl.release(app).contains("release=restored owned=false"))
            assertEquals(original, app.packageManager.getApplicationEnabledSetting(packageName))
            assertEquals(ComponentName(packageName, "ecarx.naviservice.service.NaviService"),
                shadowOf(app).nextStartedService.component)
            assertTrue(EcarxFactoryNavigationControl.release(app).contains("not-owned"))
            assertNull(shadowOf(app).nextStartedService)
        }
    }

    @Test fun serviceStartFailureRetainsOwnershipAndCanBeRetriedAfterPackageIsEnabled() {
        EcarxFactoryNavigationControl.acquire(app)
        val denied = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent): ComponentName? = throw SecurityException("test")
        }
        assertTrue(EcarxFactoryNavigationControl.release(denied).contains("error=SecurityException"))
        assertTrue(EcarxFactoryNavigationControl.status(app).contains("owned=true"))
        val missing = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent): ComponentName? = null
        }
        assertTrue(EcarxFactoryNavigationControl.release(missing).contains("service-not-found owned=true"))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            app.packageManager.getApplicationEnabledSetting(packageName))
        assertTrue(EcarxFactoryNavigationControl.release(app).contains("serviceStart=requested"))
        assertTrue(EcarxFactoryNavigationControl.status(app).contains("owned=false"))
    }

    @Test fun permissionFailureKeepsRestorationIntentForTheNextAttempt() {
        app.packageManager.setApplicationEnabledSetting(packageName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0)
        EcarxFactoryNavigationControl.acquire(app)
        shadowOf(app).denyPermissions(EcarxHicarControl.PERMISSION)
        assertTrue(EcarxFactoryNavigationControl.release(app).contains("permission-required owned=true"))
        assertTrue(EcarxFactoryNavigationControl.status(app).contains("owned=true"))
        shadowOf(app).grantPermissions(EcarxHicarControl.PERMISSION)
        assertTrue(EcarxFactoryNavigationControl.release(app).contains("release=restored owned=false"))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            app.packageManager.getApplicationEnabledSetting(packageName))
    }
}
