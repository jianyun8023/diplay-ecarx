package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.shilapi.xcertplay.hud.EcarxFactoryNavigationRestore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class BootReceiverNavigationRestoreTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val navigation = "ecarx.naviservice"
    private val bridge = Class.forName("com.shilapi.xcertplay.hud.EcarxHudBridge")
    private val store get() = app.getSharedPreferences("diplay_factory_navigation_control", Context.MODE_PRIVATE)

    @Before fun setup() {
        AirPlayPersistence.saveAutoStartOnBoot(app, false)
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = navigation
            applicationInfo = ApplicationInfo().apply { packageName = navigation; enabled = true }
        })
        shadowOf(app).grantPermissions("android.permission.CHANGE_COMPONENT_ENABLED_STATE")
        EcarxFactoryNavigationRestore.pauseForGuidance(app)
        store.edit().putBoolean("owned", true)
            .putInt("original", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT).commit()
        for (name in listOf("guidanceActive", "hudActive")) {
            bridge.getDeclaredField(name).apply { isAccessible = true }.setBoolean(null, false)
        }
    }

    @Test fun bootRestoresOurPausedPackageEvenWhenAutoStartIsDisabled() {
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            app.packageManager.getApplicationEnabledSetting(navigation))
        assertTrue(EcarxFactoryNavigationRestore.state(app).contains("serviceStart=delayed"))
        assertTrue(store.getBoolean("owned", false))
        assertNull(shadowOf(app).nextStartedActivity)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun bootDoesNotEnableAPackageDisabledBySomeoneElse() {
        store.edit().clear().commit()
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            app.packageManager.getApplicationEnabledSetting(navigation))
    }

    @Test fun lockedBootLeavesNavigationRecoveryUntilCredentialStorageIsAvailable() {
        BootReceiver().onReceive(app, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            app.packageManager.getApplicationEnabledSetting(navigation))
        assertTrue(store.getBoolean("owned", false))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun lateBootDoesNotRestoreOverGuidanceOrHudStartedAtUnlock() {
        for (name in listOf("guidanceActive", "hudActive")) {
            val field = bridge.getDeclaredField(name).apply { isAccessible = true }
            field.setBoolean(null, true)
            BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
            assertEquals(name, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                app.packageManager.getApplicationEnabledSetting(navigation))
            field.setBoolean(null, false)
        }
    }

    @Test fun missingPermissionRetainsOwnershipForRecoveryAndDoesNotAbortBoot() {
        shadowOf(app).denyPermissions("android.permission.CHANGE_COMPONENT_ENABLED_STATE")
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(store.getBoolean("owned", false))
        assertTrue(EcarxFactoryNavigationRestore.state(app).contains("permission-required"))
        assertTrue(StartupDiagnosticSnapshot.report(app).contains("launchResult=disabled"))
    }
}
