@file:Suppress("EXPOSED_PARAMETER_TYPE")

package com.shihab.diplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.ParcelFileDescriptor
import com.shihab.diplay.airplay.NativeStreamCallbacks
import com.shihab.diplay.testcore.ShadowNativeMedia
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
 * Production startup keeps managed PFDs and lends their numbers to JNI. Each managed duplicate
 * is closed once on every exit, including pre-JNI OOM and native failure. Native separately owns
 * its own duplicates (tested in the Rust engine); Kotlin never detaches or adopts a raw FD.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE,
    shadows = [NcmStartMediaShadow::class, NcmStartConnectionShadow::class, NcmStartPfdShadow::class])
class NcmNativeStartCleanupTest {
    @After fun tearDown() { NcmStartReplay.reset() }

    @Test fun successClosesOnlyTheManagedCopiesAfterNativeBorrowsThem() {
        bridge().let { ncm ->
            try {
                assertTrue(ncm.startNative(tun(), ByteArray(6), {}, {}))
                assertEquals(listOf(NcmStartReplay.created.last()), NcmStartReplay.starts.toList())
                assertManagedCopiesClosed()
                // A second start is rejected by state, not by a stuck flag.
                assertFalse(ncm.startNative(tun(), ByteArray(6), {}, {}))
                assertEquals(1, NcmStartReplay.starts.size)
            } finally { ncm.close() }
        }
        assertEquals(1, NcmStartReplay.stops.size)
    }

    @Test fun zeroReturnClosesTheManagedCopiesAndAllowsRetry() {
        NcmStartReplay.mode = NcmStartReplay.Mode.ReturnZero
        bridge().let { ncm ->
            try {
                assertFalse(ncm.startNative(tun(), ByteArray(6), {}, {}))
                assertEquals(1, NcmStartReplay.starts.size)
                assertManagedCopiesClosed()
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
            assertManagedCopiesClosed()
            // The failure must not wedge the bridge: the next start succeeds.
            NcmStartReplay.mode = NcmStartReplay.Mode.Success
            assertTrue(ncm.startNative(tun(), ByteArray(6), {}, {}))
        } finally { ncm.close() }
    }

    @Test fun aTunnelDupFailureStillClosesTheManagedUsbCopy() {
        NcmStartReplay.mode = NcmStartReplay.Mode.ThrowAtDup
        val ncm = bridge()
        try {
            assertFalse(ncm.startNative(tun(), ByteArray(6), {}, {}))
            assertEquals(1, NcmStartReplay.created.size)
            assertManagedCopiesClosed()
            NcmStartReplay.mode = NcmStartReplay.Mode.Success
            assertTrue(ncm.startNative(tun(), ByteArray(6), {}, {}))
        } finally { ncm.close() }
    }

    @Test fun failureAfterBothCopiesExistButBeforeJniClosesBothAndClearsStarting() {
        NcmStartReplay.mode = NcmStartReplay.Mode.ThrowAtGetFd
        val ncm = bridge()
        try {
            assertThrows(OutOfMemoryError::class.java) { ncm.startNative(tun(), ByteArray(6), {}, {}) }
            assertEquals(2, NcmStartReplay.created.size)
            assertTrue("The injected failure precedes JNI", NcmStartReplay.starts.isEmpty())
            assertManagedCopiesClosed()
            NcmStartReplay.mode = NcmStartReplay.Mode.Success
            assertTrue(ncm.startNative(tun(), ByteArray(6), {}, {}))
        } finally { ncm.close() }
    }

    @Test fun oneCloseFailureDoesNotSkipTheOtherCopyOrWedgeStarting() {
        NcmStartReplay.mode = NcmStartReplay.Mode.ThrowAtClose
        val ncm = bridge()
        try {
            assertFalse(ncm.startNative(tun(), ByteArray(6), {}, {}))
            assertManagedCopiesClosed()
            NcmStartReplay.mode = NcmStartReplay.Mode.Success
            assertTrue(ncm.startNative(tun(), ByteArray(6), {}, {}))
        } finally { ncm.close() }
    }

    @Test fun closeDuringNativeStartRejectsAndStopsTheLateHandleWithoutDoubleClosing() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        NcmStartReplay.beforeReturn = {
            entered.countDown()
            check(resume.await(5, TimeUnit.SECONDS))
        }
        val ncm = bridge()
        val result = AtomicReference<Boolean>()
        val failure = AtomicReference<Throwable>()
        val starter = Thread {
            try { result.set(ncm.startNative(tun(), ByteArray(6), {}, {})) }
            catch (error: Throwable) { failure.set(error) }
        }
        try {
            starter.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse("A concurrent start cannot acquire the same bridge", ncm.startNative(tun(), ByteArray(6), {}, {}))
            assertEquals(2, NcmStartReplay.created.size)
            ncm.close()
        } finally {
            resume.countDown()
            starter.join(5_000)
            ncm.close()
        }
        assertFalse("Startup must finish after close", starter.isAlive)
        failure.get()?.let { throw AssertionError("startup failed", it) }
        assertEquals(false, result.get())
        assertEquals(listOf(770L), NcmStartReplay.stops.toList())
        assertManagedCopiesClosed()
        assertFalse(ReflectionHelpers.getField(ncm, "nativeStarting"))
        assertFalse(ncm.startNative(tun(), ByteArray(6), {}, {}))
    }

    private fun assertManagedCopiesClosed() {
        assertEquals("Each managed duplicate is closed exactly once", NcmStartReplay.created.toList(), NcmStartReplay.closed.toList())
        assertTrue("No raw ownership handoff or Pair is needed", NcmStartReplay.detached.isEmpty())
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
    enum class Mode { Success, ReturnZero, ThrowAtStart, ThrowAtDup, ThrowAtGetFd, ThrowAtClose }

    const val usbFd = 0x6E63_5553 // plausible descriptor numbers, never real ones
    const val tunFd = 0x6E63_5455

    val created = mutableListOf<Int>()
    val detached = mutableListOf<Int>()
    val closed = mutableListOf<Int>()
    val starts = mutableListOf<Int>()
    val stops = mutableListOf<Long>()
    var mode = Mode.Success
    var nextHandle = 770L
    var nextFd = 10_000
    var beforeReturn: (() -> Unit)? = null

    fun reset() {
        created.clear(); detached.clear(); closed.clear(); starts.clear(); stops.clear()
        mode = Mode.Success; nextHandle = 770L; nextFd = 10_000; beforeReturn = null
    }
}

/** The shadowed connection hands the bridge a fake descriptor number to duplicate. */
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

    @Implementation fun getFd(): Int {
        if (NcmStartReplay.mode == NcmStartReplay.Mode.ThrowAtGetFd) throw OutOfMemoryError("injected post-dup argument failure")
        return fd
    }

    @Implementation fun dup(): ParcelFileDescriptor {
        if (NcmStartReplay.mode == NcmStartReplay.Mode.ThrowAtDup) throw OutOfMemoryError("injected dup failure")
        return duplicate()
    }

    @Implementation fun close() {
        if (fd != -1) NcmStartReplay.closed += fd
        fd = -1
        if (NcmStartReplay.mode == NcmStartReplay.Mode.ThrowAtClose) throw IllegalStateException("injected close failure")
    }

    companion object {
        @JvmStatic fun of(descriptor: Int): ParcelFileDescriptor {
            val result = Shadow.newInstanceOf(ParcelFileDescriptor::class.java)
            Shadow.extract<NcmStartPfdShadow>(result).fd = descriptor
            return result
        }

        private fun duplicate(): ParcelFileDescriptor {
            val fd = NcmStartReplay.nextFd++
            return of(fd).also { NcmStartReplay.created += fd }
        }

        @JvmStatic @Implementation fun fromFd(descriptor: Int): ParcelFileDescriptor = duplicate()

        @JvmStatic @Implementation fun adoptFd(descriptor: Int): ParcelFileDescriptor =
            throw AssertionError("startup must not adopt a raw FD")
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
            assertTrue("Borrowed copies must stay open through JNI", NcmStartReplay.closed.none { it == usbFd || it == tunFd })
            NcmStartReplay.beforeReturn?.invoke()
            when (NcmStartReplay.mode) {
                NcmStartReplay.Mode.ThrowAtStart -> throw OutOfMemoryError("injected start failure")
                NcmStartReplay.Mode.ReturnZero, NcmStartReplay.Mode.ThrowAtClose -> return 0L
                NcmStartReplay.Mode.Success -> return NcmStartReplay.nextHandle++
                NcmStartReplay.Mode.ThrowAtDup, NcmStartReplay.Mode.ThrowAtGetFd -> throw IllegalStateException("unreachable")
            }
        }

        @JvmStatic @Implementation fun ncmStop(handle: Long) {
            NcmStartReplay.stops += handle
        }
    }
}
