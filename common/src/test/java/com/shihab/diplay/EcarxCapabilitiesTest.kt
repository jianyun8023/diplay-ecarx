package com.shihab.diplay

import android.content.pm.PackageInfo
import com.shihab.diplay.hud.EcarxCapabilities
import com.shihab.diplay.hud.EcarxSwKeyIntercept
import com.shihab.diplay.hud.EcarxVehicleDayNight
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

    @Test fun runtimeAdaptersDoNotBreakStartupWithoutVendorClasses() {
        val night = EcarxVehicleDayNight(context)
        assertFalse(night.available)
        assertFalse(night.start { fail("No vehicle source should deliver") })
        night.stop()
        assertNull(EcarxSwKeyIntercept.register(context, object : EcarxSwKeyIntercept.Listener {
            override fun onKeyPressed(keyCode: Int) = false
            override fun onKeyReleased(keyCode: Int) = false
        }))
        val type = Class.forName("com.shihab.diplay.hud.EcarxDimApi")
        assertNull(type.getMethod("createDimInteraction", android.content.Context::class.java)
            .invoke(type.getField("INSTANCE").get(null), context))
    }

    @Test fun microphoneArbitrationDoesNotBlockRecordingWithoutVendorClasses() {
        // The policy is internal to shared; invoke its app-owned reflection boundary.
        val type = Class.forName("com.shihab.diplay.hud.EcarxMicPolicy")
        val policy = type.getField("INSTANCE").get(null)
        val listenerType = Class.forName("com.shihab.diplay.hud.EcarxMicPolicy\$Listener")
        val listener = java.lang.reflect.Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { _, _, _ ->
            fail("Missing SDK must not invoke callbacks")
        }
        assertNull(type.getMethod("requestMic", android.content.Context::class.java, Int::class.javaPrimitiveType, listenerType)
            .invoke(policy, context, 2, listener))
        assertTrue((type.getMethod("describe").invoke(policy) as String).contains("policyApi=false"))
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
