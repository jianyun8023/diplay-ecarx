package com.shilapi.xcertplay.hud

import android.app.Application
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
        assertFalse(EcarxOutputSettings.pauseFactoryNavigation(app))
        EcarxOutputSettings.setPauseFactoryNavigation(app, true)
        for (original in listOf(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)) {
            app.packageManager.setApplicationEnabledSetting(packageName, original, 0)
            assertTrue(EcarxFactoryNavigationControl.acquire(app).contains("already-disabled owned=false"))
            assertTrue(EcarxFactoryNavigationControl.release(app).contains("not-owned"))
            assertEquals(original, app.packageManager.getApplicationEnabledSetting(packageName))
        }
    }

    @Test fun originalDefaultAndExplicitEnabledAreRestoredExactly() {
        for (original in listOf(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED)) {
            app.packageManager.setApplicationEnabledSetting(packageName, original, 0)
            assertTrue(EcarxFactoryNavigationControl.acquire(app).contains("opt-out"))
            EcarxOutputSettings.setPauseFactoryNavigation(app, true)
            assertTrue(EcarxFactoryNavigationControl.acquire(app).contains("acquire=disabled"))
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                app.packageManager.getApplicationEnabledSetting(packageName))
            // A second guidance frame must not replace the saved original setting with DISABLED_USER.
            EcarxFactoryNavigationControl.acquire(app)
            EcarxOutputSettings.setPauseFactoryNavigation(app, false)
            assertTrue(EcarxFactoryNavigationControl.release(app).contains("release=restored owned=false"))
            assertEquals(original, app.packageManager.getApplicationEnabledSetting(packageName))
        }
    }

    @Test fun permissionFailureKeepsRestorationIntentForTheNextAttempt() {
        app.packageManager.setApplicationEnabledSetting(packageName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0)
        EcarxOutputSettings.setPauseFactoryNavigation(app, true)
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
