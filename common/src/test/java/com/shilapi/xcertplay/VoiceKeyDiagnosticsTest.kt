package com.shilapi.xcertplay

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VoiceKeyDiagnosticsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val pm get() = context.packageManager

    @Test fun aDeniedQueryIsReportedAsUnknownWithoutExportingExceptionDetails() {
        val denied = mock(android.content.Context::class.java)
        `when`(denied.packageManager).thenThrow(SecurityException("private diagnostic detail"))
        val report = VoiceKeyDiagnostics.report(denied)
        assertTrue(report.contains("query unavailable=SecurityException"))
        assertTrue(report.contains("changesPerformed=false"))
        assertFalse(report.contains("private diagnostic detail"))
        assertFalse(report.contains("visibleCandidates=0"))
    }

    @Test fun identifiesHiCarByPackageOrLabelWithoutChangingItsState() {
        for ((name, label) in listOf("com.geely.hicar" to "互联", "vendor.car.connect" to "HiCar")) {
            shadowOf(pm).installPackage(PackageInfo().apply {
                packageName = name
                applicationInfo = ApplicationInfo().apply {
                    packageName = name
                    nonLocalizedLabel = label
                    flags = ApplicationInfo.FLAG_SYSTEM
                }
            })
        }
        pm.setApplicationEnabledSetting("com.geely.hicar", PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0)
        val report = VoiceKeyDiagnostics.report(context)
        assertTrue(report.contains("HiCar visibleCandidates=2"))
        assertTrue(report.contains("package=com.geely.hicar enabled=false enabledSetting=3 system=true"))
        assertTrue(report.contains("package=vendor.car.connect"))
        assertFalse(report.contains("query unavailable"))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, pm.getApplicationEnabledSetting("com.geely.hicar"))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, pm.getApplicationEnabledSetting("vendor.car.connect"))
        assertTrue(report.lines().all { DiagnosticRedactor.redact(it) == it })
    }

    @Suppress("DEPRECATION")
    @Test fun matchingPlatformSignatureDoesNotClaimAnUnrequestedPermissionOrSuccessfulTakeover() {
        val own = pm.getPackageInfo(context.packageName, 0)
        own.signatures = arrayOf(Signature("0102"))
        shadowOf(pm).installPackage(own)
        shadowOf(pm).installPackage(PackageInfo().apply {
            packageName = "android"
            signatures = own.signatures
        })
        val report = VoiceKeyDiagnostics.report(context)
        assertTrue(report.contains("platformSignatureMatch=true"))
        assertTrue(report.contains("componentPermissionRequested=false"))
        assertTrue(report.contains("componentPermissionGranted=false"))
        assertTrue(report.contains("HiCar visibleCandidates=0"))
        assertTrue(report.contains("changesPerformed=false"))
    }
}
