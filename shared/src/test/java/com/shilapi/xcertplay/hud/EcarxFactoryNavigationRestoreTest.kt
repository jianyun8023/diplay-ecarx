package com.shilapi.xcertplay.hud

import android.app.Application
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EcarxFactoryNavigationRestoreTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val packageName = "ecarx.naviservice"

    @Before fun setup() {
        app.getSharedPreferences("diplay_factory_navigation_control", 0).edit().clear().commit()
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            this.packageName = this@EcarxFactoryNavigationRestoreTest.packageName
            applicationInfo = ApplicationInfo().apply {
                this.packageName = this@EcarxFactoryNavigationRestoreTest.packageName
                enabled = true
            }
        })
        shadowOf(app).grantPermissions("android.permission.CHANGE_COMPONENT_ENABLED_STATE")
    }

    @Test fun packageWeNeverPausedIsLeftAlone() {
        assertEquals("factoryNavigation restore=not-owned",
            EcarxFactoryNavigationRestore.restoreIfPausedByUs(app))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            app.packageManager.getApplicationEnabledSetting(packageName))
    }

    @Test fun pausedPackageIsReEnabledAndTheFlagCleared() {
        app.getSharedPreferences("diplay_factory_navigation_control", 0).edit()
            .putBoolean("owned", true)
            .putInt("original", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            .commit()
        app.packageManager.setApplicationEnabledSetting(packageName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0)

        val result = EcarxFactoryNavigationRestore.restoreIfPausedByUs(app)

        assertTrue(result, result.contains("restore=enabled"))
        assertNotEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            app.packageManager.getApplicationEnabledSetting(packageName))
        assertEquals(ComponentName(packageName, "ecarx.naviservice.service.NaviService"),
            shadowOf(app).nextStartedService.component)
        assertFalse(app.getSharedPreferences("diplay_factory_navigation_control", 0).getBoolean("owned", false))
        // A second open is a no-op once the flag is cleared.
        assertEquals("factoryNavigation restore=not-owned",
            EcarxFactoryNavigationRestore.restoreIfPausedByUs(app))
    }

    @Test fun missingPermissionKeepsTheFlagForTheNextAttempt() {
        app.getSharedPreferences("diplay_factory_navigation_control", 0).edit()
            .putBoolean("owned", true)
            .putInt("original", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            .commit()
        app.packageManager.setApplicationEnabledSetting(packageName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0)
        shadowOf(app).denyPermissions("android.permission.CHANGE_COMPONENT_ENABLED_STATE")

        assertEquals("factoryNavigation restore=permission-required owned=true",
            EcarxFactoryNavigationRestore.restoreIfPausedByUs(app))
        assertTrue(app.getSharedPreferences("diplay_factory_navigation_control", 0).getBoolean("owned", false))
    }
}
