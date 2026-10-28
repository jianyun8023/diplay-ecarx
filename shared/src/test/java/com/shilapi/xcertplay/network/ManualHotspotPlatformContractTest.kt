package com.shilapi.xcertplay.network

import android.os.ResultReceiver
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.InvocationTargetException

/** Invokes the production reflection adapter against a fake service, never a device or network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ManualHotspotPlatformContractTest {
    class Service {
        var starts = 0
        var stops = 0
        var type = -1
        var caller: String? = null
        var showProvisioningUi = true
        var reject = false
        fun startTethering(type: Int, receiver: ResultReceiver, showUi: Boolean, caller: String) {
            starts++; this.type = type; this.caller = caller; showProvisioningUi = showUi
            if (reject) throw SecurityException("denied")
            receiver.send(0, null)
        }
        fun stopTethering(type: Int, caller: String) {
            stops++; this.type = type; this.caller = caller
            if (reject) throw SecurityException("denied")
        }
    }
    private fun invoke(service: Service, enabled: Boolean, response: (Int) -> Unit = {}) =
        ManualHotspotControl.invokePlatform(service, Service::class.java, "test.actual.caller", enabled, response)

    @Test fun startUsesWifiTypeRealCallerAndApi28ReceiverSignature() {
        val service = Service(); var response = -1
        invoke(service, true) { response = it }
        assertEquals(1, service.starts); assertEquals(0, service.stops)
        assertEquals(0, service.type); assertEquals("test.actual.caller", service.caller)
        assertFalse(service.showProvisioningUi); assertEquals(0, response)
    }
    @Test fun stopUsesWifiOnlyAndDoesNotInventACallbackSuccess() {
        val service = Service(); var callback = false
        invoke(service, false) { callback = true }
        assertEquals(0, service.starts); assertEquals(1, service.stops)
        assertEquals(0, service.type); assertEquals("test.actual.caller", service.caller); assertFalse(callback)
    }
    @Test fun denialPropagatesWithoutAlternativeSignatureOrRepeatedWrite() {
        for (enabled in listOf(true, false)) {
            val service = Service().apply { reject = true }
            try { invoke(service, enabled); fail("must propagate service denial") }
            catch (error: InvocationTargetException) { assertTrue(error.targetException is SecurityException) }
            assertEquals(1, service.starts + service.stops)
        }
    }
    @Test fun missingSignatureDoesNotTouchAService() {
        val service = Service()
        try {
            ManualHotspotControl.invokePlatform(service, Any::class.java, "test.actual.caller", true) {}
            fail("must reject missing API")
        } catch (_: NoSuchMethodException) { }
        assertEquals(0, service.starts + service.stops)
    }
}
