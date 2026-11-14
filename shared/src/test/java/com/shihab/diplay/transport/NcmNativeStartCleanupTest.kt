@file:Suppress("EXPOSED_PARAMETER_TYPE")

package com.shihab.diplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.ParcelFileDescriptor
import com.shihab.diplay.airplay.NativeStreamCallbacks
import com.shihab.diplay.testcore.ShadowNativeMedia
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * The FD ownership boundaries of [NcmUsbBridge.startNative]: `ncmStart` is the single handoff,
 * so a call that never reached native closes each FD exactly once, a call native handled (0 or a
 * handle) closes nothing, and a failed attempt can start again afterwards. The descriptor numbers
 * are fake; the shadowed [ParcelFileDescriptor] only records what Kotlin closed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE,
    shadows = [NcmStartMediaShadow::class, NcmStartConnectionShadow::class, NcmStartPfdShadow::class])
class NcmNativeStartCleanupTest {
    @After fun tearDown() { NcmStartReplay.reset() }

    @Test fun nativeConsumesTheDescriptorsOnSuccessAndKotlinClosesNothing() {
        bridge().let { ncm ->
            try {
                assertTrue(ncm.startNative(tun(), ByteArray(6), {}, {}))
                assertEquals("The handoff passed the duplicated tunnel FD", listOf(NcmStartReplay.tunFd), NcmStartReplay.starts.toList())
                assertTrue("Kotlin must not close FDs native consumed", NcmStartReplay.closed.isEmpty())
                // A second start is rejected by state, not by a stuck flag.
                assertFalse(ncm.startNative(tun(), ByteArray(6), {}, {}))
                assertEquals(1, NcmStartReplay.starts.size)
            } finally { ncm.close() }
        }
        assertEquals(1, NcmStartReplay.stops.size)
    }

    @Test fun zeroReturnAlsoConsumesTheDescriptors() {
        NcmStartReplay.mode = NcmStartReplay.Mode.ReturnZero
        bridge().let { ncm ->
            try {
                assertFalse(ncm.startNative(tun(), ByteArray(6), {}, {}))
                assertEquals("Native consumed both FDs on its zero return", 1, NcmStartReplay.starts.size)
                assertTrue(NcmStartReplay.closed.isEmpty())
                // The finally still reset the flag, so the caller may try again.
                NcmStartReplay.mode = NcmStartReplay.Mode.Success
                assertTrue(ncm.startNative(tun(), ByteArray(6), {}, {}))
            } finally { ncm.close() }
        }
    }

    @Test fun aStartCallThatNeverReachedNativeClosesEachDescriptorExactlyOnce() {
        NcmStartReplay.mode = NcmStartReplay.Mode.ThrowAtStart
        val ncm = bridge()
        try {
            assertThrows(OutOfMemoryError::class.java) { ncm.startNative(tun(), ByteArray(6), {}, {}) }
            assertEquals("Both unhandoffed FDs are closed once",
                NcmStartReplay.detached.toList(), NcmStartReplay.closed.toList())
            // The failure must not wedge the bridge: the next start succeeds.
            NcmStartReplay.mode = NcmStartReplay.Mode.Success
            assertTrue(ncm.startNative(tun(), ByteArray(6), {}, {}))
        } finally { ncm.close() }
    }

    @Test fun aTunnelDupFailureBeforeTheHandoffStillClosesTheUsbDescriptor() {
        NcmStartReplay.mode = NcmStartReplay.Mode.ThrowAtDup
        val ncm = bridge()
        try {
            assertFalse(ncm.startNative(tun(), ByteArray(6), {}, {}))
            assertEquals("Only the duplicated USB FD was closed",
                listOf(NcmStartReplay.detached.first()), NcmStartReplay.closed.toList())
            NcmStartReplay.mode = NcmStartReplay.Mode.Success
            assertTrue(ncm.startNative(tun(), ByteArray(6), {}, {}))
        } finally { ncm.close() }
    }

    private fun tun(): ParcelFileDescriptor = NcmStartPfdShadow.of(NcmStartReplay.tunFd)

    private fun bridge(): NcmUsbBridge {
        val connection = ReflectionHelpers.callConstructor(UsbDeviceConnection::class.java,
            ClassParameter.from(UsbDevice::class.java, null))
        fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(UsbEndpoint::class.java,
            ClassParameter.from(Int::class.javaPrimitiveType, address),
            ClassParameter.from(Int::class.javaPrimitiveType, 2),
            ClassParameter.from(Int::class.javaPrimitiveType, 512),
            ClassParameter.from(Int::class.javaPrimitiveType, 0))
        return NcmUsbBridge(connection, endpoint(6), endpoint(0x88), null, emptyList(), null)
    }
}

object NcmStartReplay {
    enum class Mode { Success, ReturnZero, ThrowAtStart, ThrowAtDup }

    const val usbFd = 0x6E63_5553 // plausible descriptor numbers, never real ones
    const val tunFd = 0x6E63_5455

    val detached = mutableListOf<Int>()
    val closed = mutableListOf<Int>()
    val starts = mutableListOf<Int>()
    val stops = mutableListOf<Long>()
    var mode = Mode.Success
    var nextHandle = 770L

    fun reset() {
        detached.clear(); closed.clear(); starts.clear(); stops.clear()
        mode = Mode.Success; nextHandle = 770L
    }
}

/** The shadowed connection hands the bridge a fake descriptor number to detach. */
@Implements(UsbDeviceConnection::class)
class NcmStartConnectionShadow {
    @Implementation fun getFileDescriptor(): Int = NcmStartReplay.usbFd
    @Implementation fun close() = Unit
}

@Implements(ParcelFileDescriptor::class)
class NcmStartPfdShadow {
    private var fd = -1

    @Implementation fun detachFd(): Int {
        val result = fd
        fd = -1
        NcmStartReplay.detached += result
        return result
    }

    @Implementation fun dup(): ParcelFileDescriptor {
        if (NcmStartReplay.mode == NcmStartReplay.Mode.ThrowAtDup) throw OutOfMemoryError("injected dup failure")
        return of(fd)
    }

    @Implementation fun close() {
        if (fd != -1) NcmStartReplay.closed += fd
        fd = -1
    }

    companion object {
        @JvmStatic fun of(descriptor: Int): ParcelFileDescriptor {
            val result = Shadow.newInstanceOf(ParcelFileDescriptor::class.java)
            Shadow.extract<NcmStartPfdShadow>(result).fd = descriptor
            return result
        }

        @JvmStatic @Implementation fun fromFd(descriptor: Int): ParcelFileDescriptor = of(descriptor)

        @JvmStatic @Implementation fun adoptFd(descriptor: Int): ParcelFileDescriptor = of(descriptor)
    }
}

@Implements(className = "com.shihab.diplay.airplay.NativeMedia", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class NcmStartMediaShadow : ShadowNativeMedia() {
    companion object {
        @JvmStatic @Implementation fun ncmStart(
            usbFd: Int, inEndpoint: Int, outEndpoint: Int, packetSize: Int, tunFd: Int, hostMac: ByteArray,
            inbound: NativeStreamCallbacks, outbound: NativeStreamCallbacks,
        ): Long {
            NcmStartReplay.starts += tunFd
            when (NcmStartReplay.mode) {
                NcmStartReplay.Mode.ThrowAtStart -> throw OutOfMemoryError("injected start failure")
                NcmStartReplay.Mode.ReturnZero -> return 0L
                NcmStartReplay.Mode.Success -> return NcmStartReplay.nextHandle++
                NcmStartReplay.Mode.ThrowAtDup -> throw IllegalStateException("unreachable")
            }
        }

        @JvmStatic @Implementation fun ncmStop(handle: Long) {
            NcmStartReplay.stops += handle
        }
    }
}
