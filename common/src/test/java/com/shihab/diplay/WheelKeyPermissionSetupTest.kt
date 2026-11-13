package com.shihab.diplay

import android.content.ComponentName
import android.provider.Settings
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
class WheelKeyPermissionSetupTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val resolver get() = app.contentResolver
    private val service get() = ComponentName(app, WheelKeyService::class.java)
    private val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES

    @Before fun grant() { shadowOf(app).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS) }

    @Test fun nativeEnablePreservesOtherServicesAndEnablesTheGlobalSwitch() {
        Settings.Secure.putString(resolver, key, "other.app/.First:another.app/.Second")
        assertTrue(UsbPermissionSetup.enableAccessibility(app, service))
        assertEquals("other.app/.First:another.app/.Second:${service.flattenToString()}", Settings.Secure.getString(resolver, key))
        assertEquals(1, Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED))
    }

    @Test fun enablingAnExistingServiceDoesNotDuplicateItOrRebindByDefault() {
        val existing = "other.app/.First:${service.flattenToString()}"
        Settings.Secure.putString(resolver, key, existing)
        assertTrue(UsbPermissionSetup.enableAccessibility(app, service))
        assertEquals(existing, Settings.Secure.getString(resolver, key))
    }

    @Test fun crashRebindRemovesOnlyOurServiceBeforeRestoringIt() {
        Settings.Secure.putString(resolver, key, "other.app/.First:${service.flattenToString()}")
        var removedObserved = false
        assertTrue(UsbPermissionSetup.enableAccessibility(app, service, rebind = { true }, shouldContinue = {
            if (Settings.Secure.getString(resolver, key) == "other.app/.First") removedObserved = true
            true
        }))
        assertTrue(removedObserved)
        assertEquals("other.app/.First:${service.flattenToString()}", Settings.Secure.getString(resolver, key))
    }

    @Test fun malformedExistingSettingsAreLeftUntouched() {
        Settings.Secure.putString(resolver, key, "malformed value")
        assertFalse(UsbPermissionSetup.enableAccessibility(app, service))
        assertEquals("malformed value", Settings.Secure.getString(resolver, key))
    }

    @Test fun deniedPlatformPermissionDoesNotWriteSettings() {
        shadowOf(app).denyPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        Settings.Secure.putString(resolver, key, "other.app/.First")
        assertFalse(UsbPermissionSetup.enableAccessibility(app, service))
        assertEquals("other.app/.First", Settings.Secure.getString(resolver, key))
    }

    @Test fun cancelledOperationDoesNotWriteSettings() {
        Settings.Secure.putString(resolver, key, "other.app/.First")
        assertFalse(UsbPermissionSetup.enableAccessibility(app, service, shouldContinue = { false }))
        assertEquals("other.app/.First", Settings.Secure.getString(resolver, key))
    }
}
