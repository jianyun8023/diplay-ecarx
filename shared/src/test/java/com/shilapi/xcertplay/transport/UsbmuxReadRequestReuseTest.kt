package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * A new request per USBMUX read cost a JNI init/close pair, each logged as `UsbRequestJNI`, and a
 * 64 KiB direct buffer about 14 times a second during wired CarPlay: about 100k log lines an hour
 * on the ECARX head unit. One request and one buffer now serve the whole session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE,
    shadows = [CompatibilityUsbConnectionShadow::class, CompatibilityUsbRequestShadow::class])
class UsbmuxReadRequestReuseTest {
    @Before fun reset() { UsbQueueReplay.reset() }

    @Test fun readsShareOneRequestAndBufferUntilTheSessionCloses() {
        val pipe = pipe()
        try {
            assertArrayEquals(UsbReadQueueCompatibilityTest.payload, pipe.read(100))
            // A timed-out read is cancelled and drained, which leaves the request reusable.
            UsbQueueReplay.timeouts = 1
            UsbQueueReplay.emptyCompletion = true
            assertNull(pipe.read(100))
            assertArrayEquals(UsbReadQueueCompatibilityTest.payload, pipe.read(100))
            assertEquals(1, UsbQueueReplay.initializes)
            assertEquals(0, UsbQueueReplay.closes)
            assertEquals(3, UsbQueueReplay.buffers.size)
            UsbQueueReplay.buffers.forEach { assertSame(UsbQueueReplay.buffers.first(), it) }
        } finally { pipe.close() }
        assertEquals(1, UsbQueueReplay.closes)
    }

    @Test fun aReadAfterCloseCreatesNoRequest() {
        val pipe = pipe()
        assertArrayEquals(UsbReadQueueCompatibilityTest.payload, pipe.read(100))
        pipe.close()
        pipe.close()
        try {
            pipe.read(100)
            fail("Expected a closed session to refuse reads")
        } catch (_: IphoneUsbException.DeviceUnavailable) {
        }
        assertEquals(1, UsbQueueReplay.initializes)
        assertEquals(1, UsbQueueReplay.closes)
    }

    @Test fun aRejectedQueueKeepsTheRequestForTheNextRead() {
        // 64 KiB, then the 16, 8, 4 and 2 KiB compatibility retries.
        repeat(5) { UsbQueueReplay.outcomes.addLast(false) }
        val pipe = pipe()
        try {
            try {
                pipe.read(100)
                fail("Expected the rejected queue to fail the read")
            } catch (_: IphoneUsbException.DeviceUnavailable) {
            }
            assertArrayEquals(UsbReadQueueCompatibilityTest.payload, pipe.read(100))
            assertEquals(1, UsbQueueReplay.initializes)
        } finally { pipe.close() }
    }

    private fun pipe() = Iap2UsbSession(connection(), endpoint(0x04), endpoint(0x85))
    private fun connection(): UsbDeviceConnection = ReflectionHelpers.callConstructor(
        UsbDeviceConnection::class.java, ClassParameter.from(UsbDevice::class.java, null))
    private fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(
        UsbEndpoint::class.java, ClassParameter.from(Int::class.javaPrimitiveType, address),
        ClassParameter.from(Int::class.javaPrimitiveType, 2),
        ClassParameter.from(Int::class.javaPrimitiveType, 512),
        ClassParameter.from(Int::class.javaPrimitiveType, 0))
}
