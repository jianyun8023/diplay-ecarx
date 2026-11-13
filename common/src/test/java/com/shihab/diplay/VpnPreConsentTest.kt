package com.shihab.diplay

import android.content.Context
import android.content.Intent
import android.net.VpnService
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [PlatformVpnShadow::class])
class VpnPreConsentTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        shadowOf(app).grantPermissions("android.permission.CONTROL_VPN")
        PlatformVpnShadow.authorized = false
        PlatformVpnShadow.accepts = true
        PlatformVpnShadow.packageName = null
    }
    @Test fun nativeAuthorizationTargetsThisApplicationAndIsReadBack() {
        assertTrue(VpnPreConsent.grant(app))
        assertEquals(app.packageName, PlatformVpnShadow.packageName)
    }
    @Test fun missingPlatformPermissionDoesNotCallAuthorization() {
        shadowOf(app).denyPermissions("android.permission.CONTROL_VPN")
        assertFalse(VpnPreConsent.grant(app))
        assertNull(PlatformVpnShadow.packageName)
    }
    @Test fun anUnchangedConsentStateIsNotSuccess() {
        PlatformVpnShadow.accepts = false
        assertFalse(VpnPreConsent.grant(app))
    }
}

@Implements(VpnService::class)
class PlatformVpnShadow {
    companion object {
        var authorized = false
        var accepts = true
        var packageName: String? = null
        @JvmStatic @Implementation fun prepareAndAuthorize(context: Context) {
            packageName = context.packageName
            authorized = accepts
        }
        @JvmStatic @Implementation fun prepare(context: Context): Intent? = if (authorized) null else Intent()
    }
}
