package com.shihab.diplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowUsbManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [PlatformUsbManagerShadow::class])
class IphoneUsbGrantTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val usb get() = app.getSystemService(UsbManager::class.java)
    private fun device(name: String = "iphone", vendor: Int = IphoneUsbMatcher.APPLE_VENDOR_ID) =
        ReflectionHelpers.newInstance(UsbDevice::class.java).apply {
            ReflectionHelpers.setField(this, "mName", name)
            ReflectionHelpers.setField(this, "mVendorId", vendor)
            ReflectionHelpers.setField(this, "mProductId", 0x12a8)
        }

    @Before fun setup() {
        shadowOf(app).grantPermissions("android.permission.MANAGE_USB")
        PlatformUsbManagerShadow.uid = -1
        PlatformUsbManagerShadow.accepts = true
    }

    @Test fun grantsOnlyTheAttachedIphoneToOurUidAndVerifiesIt() {
        val device = device()
        shadowOf(usb).addOrUpdateUsbDevice(device, false)
        assertEquals(IphoneUsbGrant.Code.GRANTED, IphoneUsbGrant.grant(app, device))
        assertEquals(app.applicationInfo.uid, PlatformUsbManagerShadow.uid)
        assertTrue(usb.hasPermission(device))
    }

    @Test fun aSuccessfulCallWithoutEffectivePermissionIsNotSuccess() {
        val device = device()
        shadowOf(usb).addOrUpdateUsbDevice(device, false)
        PlatformUsbManagerShadow.accepts = false
        assertEquals(IphoneUsbGrant.Code.DENIED, IphoneUsbGrant.grant(app, device))
    }

    @Test fun missingPlatformPermissionDoesNotCallTheUsbService() {
        shadowOf(app).denyPermissions("android.permission.MANAGE_USB")
        assertEquals(IphoneUsbGrant.Code.DENIED, IphoneUsbGrant.grant(app, device()))
        assertEquals(-1, PlatformUsbManagerShadow.uid)
    }

    @Test fun detachedAndNonAppleDevicesAreNotGranted() {
        assertEquals(IphoneUsbGrant.Code.NO_DEVICE, IphoneUsbGrant.grant(app, device()))
        val other = device("other", 1234)
        shadowOf(usb).addOrUpdateUsbDevice(other, false)
        assertEquals(IphoneUsbGrant.Code.DENIED, IphoneUsbGrant.grant(app, other))
        assertEquals(-1, PlatformUsbManagerShadow.uid)
    }

    @Test fun modernDebugDevicesKeepTheirSystemConsentFlow() {
        assertTrue(IphoneUsbGrant.supported(28))
        listOf(25, 29, 30, 33, 35).forEach { assertFalse(IphoneUsbGrant.supported(it)) }
    }
}

@Implements(UsbManager::class)
class PlatformUsbManagerShadow : ShadowUsbManager() {
    @Implementation protected fun grantPermission(device: UsbDevice, targetUid: Int) {
        uid = targetUid
        if (accepts) super.grantPermission(device)
    }
    companion object {
        var uid = -1
        var accepts = true
    }
}
