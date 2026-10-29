package com.shilapi.xcertplay

import android.content.pm.PackageInfo
import com.shilapi.xcertplay.hud.EcarxCapabilities
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** This module has no DIM fixture classes: vendor identity must not imply a usable DIM API. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EcarxCapabilitiesTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun ordinaryAndroidHasNeitherEcarxIdentityNorDimInterfaces() {
        assertEquals(EcarxCapabilities.Detection.NONE, EcarxCapabilities.detect(context))
        assertFalse(EcarxCapabilities.detect(context).identified)
        assertFalse(EcarxCapabilities.dimPresent())
        assertFalse(EcarxCapabilities.dimMediaPresent())
    }

    @Test fun vendorPackagesKeepTheExistingGateWithoutClaimingDimSupport() {
        val packages = shadowOf(context.packageManager)
        for (name in listOf("com.ecarx.xui.service", "com.ecarx.eas.service", "com.ecarx.assistant",
            "com.ecarx.xiaoka", "ecarx.naviservice")) {
            packages.installPackage(PackageInfo().apply { packageName = name })
            assertEquals(EcarxCapabilities.Detection.VENDOR_PACKAGE, EcarxCapabilities.detect(context))
            assertTrue(name, EcarxCapabilities.detect(context).identified)
            assertFalse(EcarxCapabilities.dimPresent())
            assertFalse(EcarxCapabilities.dimMediaPresent())
            packages.removePackage(name)
            assertFalse(EcarxCapabilities.detect(context).identified)
        }
    }

    @Test fun vendorBuildMarkerStillIdentifiesAnImageWithoutVendorPackages() {
        // Build fields are captured once per process; Robolectric reuses that snapshot across tests.
        val markers = ReflectionHelpers.getStaticField<MutableList<String>>(EcarxCapabilities::class.java, "ecarxBuildMarkers")
        val original = markers.toList()
        try {
            for (index in markers.indices) {
                markers[index] = "ECARX"
                assertEquals(EcarxCapabilities.Detection.BUILD_MARKER, EcarxCapabilities.detect(context))
                assertTrue(EcarxCapabilities.detect(context).identified)
                markers[index] = original[index]
            }
            assertFalse(EcarxCapabilities.dimPresent())
            assertFalse(EcarxCapabilities.dimMediaPresent())
        } finally {
            original.forEachIndexed { index, value -> markers[index] = value }
        }
    }
}
