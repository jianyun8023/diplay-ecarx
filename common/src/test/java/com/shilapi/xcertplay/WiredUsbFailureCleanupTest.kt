package com.shilapi.xcertplay

import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 30], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WiredUsbFailureCleanupTest {
    @Test fun phoneLockedClosesControlAndMuxBeforeReturningNcmToTheKernel() {
        checkFailureCleanup(false)
    }

    @Test fun muxCloseFailureStillClosesNcmAndPreservesThePhoneLockedReason() {
        checkFailureCleanup(true)
    }

    private fun checkFailureCleanup(failMuxClose: Boolean) {
        val events = mutableListOf<String>()
        val pipe = mock(Iap2UsbSession::class.java)
        val ncm = mock(NcmUsbBridge::class.java)
        val control = mock(Iap2Session::class.java)
        val reads = AtomicInteger()
        val version = ByteBuffer.allocate(20).putInt(0).putInt(20).putInt(2).putInt(0).putInt(0).array()
        `when`(pipe.read(anyLong())).thenAnswer {
            if (reads.getAndIncrement() == 0) version else { Thread.sleep(5); null }
        }
        doAnswer { events += "control"; null }.`when`(control).close()
        doAnswer {
            events += "usbmux"
            if (failMuxClose) throw IllegalStateException("USB close failed")
            null
        }.`when`(pipe).close()
        doAnswer { events += "ncm"; null }.`when`(ncm).close()
        val statuses = mutableListOf<CarPlayStatus.Failed>()
        val controller = CarPlayController(RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRED,
                identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test",
                    manufacturer = "test", serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1",
                    carPlayUsbInterfaceNumber = 3)),
            AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(),
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {},
            { status -> if (status is CarPlayStatus.Failed) { events += "failed"; statuses += status } },
            loadPairRecord = { throw LockdownPairingException.PasswordProtected })
        // Exercise cleanup of an existing control channel as well as failure before a new one opens.
        ReflectionHelpers.setField(controller, "csm", control)
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(controller, "runStack",
                ClassParameter.from(Iap2UsbSession::class.java, pipe),
                ClassParameter.from(NcmUsbBridge::class.java, ncm))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("control", "usbmux", "ncm", "failed"), events)
            assertEquals(UsbPairingFailure.PHONE_LOCKED, statuses.single().usbPairingFailure)
            assertNull(ReflectionHelpers.getField<Any?>(controller, "mux"))
            assertNull(ReflectionHelpers.getField<Any?>(controller, "csm"))
            controller.close()
            assertTrue(controller.awaitClosed(2_000))
            verify(pipe, times(1)).close()
            verify(ncm, times(1)).close()
            verify(control, times(1)).close()
        } finally {
            controller.close()
            assertTrue(controller.awaitClosed(2_000))
            com.shilapi.xcertplay.hud.NavigationOutputs.endNow()
        }
    }
}
