package com.shilapi.xcertplay.mediacenter

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.pm.ServiceInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MediaCenterProbeTest {
    interface ArtworkUri { fun getArtwork(): android.net.Uri? }
    interface ArtworkString { fun getArtwork(): String }
    @Test fun artworkIsAdvertisedOnlyForTheObservedUriDescriptor() {
        assertTrue(MediaCenterProbe.supportsArtwork(ArtworkUri::class.java))
        assertFalse(MediaCenterProbe.supportsArtwork(ArtworkString::class.java))
        assertFalse(MediaCenterProbe.supportsArtwork(Any::class.java))
    }
    private class Fixture {
        val context = mock(Context::class.java)
        val pm = mock(PackageManager::class.java)
        val service = ServiceInfo().apply { exported = true; enabled = true; applicationInfo = ApplicationInfo().apply { enabled = true } }
        init {
            `when`(context.packageManager).thenReturn(pm)
            `when`(context.packageName).thenReturn("actual.diplay")
            `when`(pm.getPackageInfo("actual.diplay", PackageManager.GET_PERMISSIONS)).thenReturn(PackageInfo().apply {
                requestedPermissions = arrayOf(MediaCenterProbe.PERMISSION)
            })
            `when`(pm.getPermissionInfo(MediaCenterProbe.PERMISSION, 0)).thenReturn(PermissionInfo().apply { protectionLevel = 18 })
            `when`(pm.checkPermission(MediaCenterProbe.PERMISSION, "actual.diplay")).thenReturn(PackageManager.PERMISSION_GRANTED)
            `when`(pm.checkSignatures("actual.diplay", "android")).thenReturn(PackageManager.SIGNATURE_MATCH)
            `when`(pm.getServiceInfo(MediaCenterProbe.component, 0)).thenReturn(service)
        }
    }
    @Test fun reportsRealProtectionLevelAndPlatformPackageMatchNotHmiIdentity() {
        val f = Fixture(); val access = MediaCenterProbe.access(f.context)
        assertTrue(access.granted); assertTrue(access.serviceUsable)
        assertTrue(access.report.contains("permissionDeclared=true"))
        assertTrue(access.report.contains("protectionLevel=18"))
        assertTrue(access.report.contains("platformSignatureMatch=true signatureCheck=0"))
    }
    @Test fun neitherSignedIsNotAPlatformSignatureMatch() {
        val f = Fixture()
        `when`(f.pm.checkSignatures("actual.diplay", "android")).thenReturn(PackageManager.SIGNATURE_NEITHER_SIGNED)
        assertTrue(MediaCenterProbe.access(f.context).report.contains("platformSignatureMatch=false"))
    }
    @Test fun actualGrantAndExportedServiceAreIndependentPrerequisites() {
        val f = Fixture(); f.service.exported = false
        assertFalse(MediaCenterProbe.access(f.context).serviceUsable)
        f.service.exported = true; f.service.permission = "actual.service.permission"
        `when`(f.pm.checkPermission("actual.service.permission", "actual.diplay")).thenReturn(PackageManager.PERMISSION_DENIED)
        assertFalse(MediaCenterProbe.access(f.context).serviceUsable)
        `when`(f.pm.checkPermission(MediaCenterProbe.PERMISSION, "actual.diplay")).thenReturn(PackageManager.PERMISSION_DENIED)
        assertFalse(MediaCenterProbe.access(f.context).granted)
    }
}
