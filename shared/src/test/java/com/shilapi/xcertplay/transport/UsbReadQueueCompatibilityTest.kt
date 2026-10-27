package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import java.lang.reflect.InvocationTargetException
import com.shilapi.xcertplay.transport.UsbQueueReplay.payload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Exercises the actual two read paths; queue rejection is a compatibility hypothesis. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE,
    shadows = [CompatibilityUsbConnectionShadow::class, CompatibilityUsbRequestShadow::class])
class UsbReadQueueCompatibilityTest {
    @Before fun reset() { UsbQueueReplay.reset() }

    @Test fun cachedSixteenKRejectionRetriesEightKInBothPipes() {
        UsbQueueReplay.outcomes.addAll(listOf(false, true, false, true, true))
        val pipe = pipe()
        try { repeat(3) { assertArrayEquals(payload, pipe.read(100)) } } finally { pipe.close() }
        assertEquals(listOf(65_536, 16_384, 16_384, 8_192, 8_192), UsbQueueReplay.sizes)
        UsbQueueReplay.reset()
        UsbQueueReplay.outcomes.addAll(listOf(false, true, false, true, true))
        val ncm = ncm()
        try { repeat(3) { assertEquals(payload.size, readChunk(ncm)) } } finally { ncm.close() }
        assertEquals(listOf(32_768, 16_384, 16_384, 8_192, 8_192), UsbQueueReplay.sizes)
    }

    @Test fun androidNineLegacyQueueRecoversAnExplicitVendorRejectionInBothPipes() {
        val diagnostics = mutableListOf<String>()
        UsbQueueReplay.outcomes.addAll(listOf(false, false))
        UsbQueueReplay.legacyOutcomes.addAll(listOf(true, true))
        val pipe = pipe(diagnostics::add)
        try { repeat(2) { assertArrayEquals(payload, pipe.read(100)) } } finally { pipe.close() }
        assertEquals(listOf(65_536, 16_384), UsbQueueReplay.sizes)
        assertEquals(listOf(16_384, 16_384), UsbQueueReplay.legacySizes)
        assertTrue(diagnostics.any { it.contains("switched to legacy API=28") })

        UsbQueueReplay.reset()
        UsbQueueReplay.outcomes.addAll(listOf(false, false))
        UsbQueueReplay.legacyOutcomes.addAll(listOf(true, true))
        val ncm = ncm()
        try { repeat(2) { assertEquals(payload.size, readChunk(ncm)) } } finally { ncm.close() }
        assertEquals(listOf(32_768, 16_384), UsbQueueReplay.sizes)
        assertEquals(listOf(16_384, 16_384), UsbQueueReplay.legacySizes)
    }

    @Test fun normalUsbmuxAndNcmRequestsKeepTheirOriginalSizes()
 {
        val diagnostics = mutableListOf<String>()
        val pipe = pipe(diagnostics::add)
        try { assertArrayEquals(payload, pipe.read(100)) } finally { pipe.close() }
        assertEquals(listOf(65_536), UsbQueueReplay.sizes)
        UsbQueueReplay.reset()
        val ncm = ncm(diagnostics::add)
        try { assertEquals(payload.size, readChunk(ncm)) } finally { ncm.close() }
        assertEquals(listOf(32_768), UsbQueueReplay.sizes)
        assertTrue(diagnostics.isEmpty())
    }

    @Test fun usbmuxExplicitRejectionRetriesAndRetainsSmallerSuccessfulSize() {
        UsbQueueReplay.outcomes.addAll(listOf(false, true, true))
        val diagnostics = mutableListOf<String>()
        val pipe = pipe(diagnostics::add)
        try {
            repeat(2) { assertArrayEquals(payload, pipe.read(100)) }
        } finally { pipe.close() }
        assertEquals(listOf(65_536, 16_384, 16_384), UsbQueueReplay.sizes)
        assertTrue(UsbQueueReplay.buffers[0] === UsbQueueReplay.buffers[1])
        checkFallbackDiagnostic(diagnostics, "USBMUX", 65_536)
    }

    @Test fun ncmExplicitRejectionRetriesAndRetainsSmallerSuccessfulSize() {
        UsbQueueReplay.outcomes.addAll(listOf(false, true, true))
        val diagnostics = mutableListOf<String>()
        val ncm = ncm(diagnostics::add)
        try { repeat(2) { assertEquals(payload.size, readChunk(ncm)) } } finally { ncm.close() }
        assertEquals(listOf(32_768, 16_384, 16_384), UsbQueueReplay.sizes)
        assertTrue(UsbQueueReplay.buffers.all { it === UsbQueueReplay.buffers.first() })
        checkFallbackDiagnostic(diagnostics, "NCM", 32_768)
    }

    @Test fun ncmRecvReassemblesLargePaddedNtbAcrossFallbackReadsAndRetainsNextFrame() {
        val frame = ByteArray(32_740) { (it * 31).toByte() }
        val followingFrame = byteArrayOf(0x33, 0x33, 0, 0, 0, 1, 0x86.toByte(), 0xdd.toByte())
        val block = Ntb16Codec.build(frame, 7)
        assertEquals(32_769, block.size) // Two 16 KiB reads, then the optional short-packet pad.
        UsbQueueReplay.transfer = block + Ntb16Codec.build(followingFrame, 8)
        UsbQueueReplay.outcomes.addAll(listOf(false, true))
        val diagnostics = mutableListOf<String>()
        val ncm = ncm(diagnostics::add)
        try {
            assertArrayEquals(frame, ncm.recv(1_000))
            assertArrayEquals(followingFrame, ncm.recv(1_000))
        } finally { ncm.close() }
        assertEquals(listOf(32_768, 16_384, 16_384, 16_384), UsbQueueReplay.sizes)
        assertEquals(listOf(16_384, 16_384, 37), UsbQueueReplay.completedBytes)
        assertEquals(UsbQueueReplay.transfer!!.size, UsbQueueReplay.transferOffset)
        checkFallbackDiagnostic(diagnostics, "NCM", 32_768)
    }

    @Test fun diagnosticCallbackFailureDoesNotInterruptEitherAcceptedFallback() {
        val failingDiagnostic: (String) -> Unit = { throw IllegalStateException("optional diagnostic failed") }
        UsbQueueReplay.outcomes.addAll(listOf(false, true))
        val pipe = pipe(failingDiagnostic)
        try { assertArrayEquals(payload, pipe.read(100)) } finally { pipe.close() }
        UsbQueueReplay.reset()
        UsbQueueReplay.outcomes.addAll(listOf(false, true))
        val ncm = ncm(failingDiagnostic)
        try { assertEquals(payload.size, readChunk(ncm)) } finally { ncm.close() }
    }

    @Test fun everyLadderRejectionFailsWithEndpointApiAndAttemptedSizes() {
        val pipe = pipe()
        UsbQueueReplay.outcomes.addAll(listOf(false, false, false, false, false))
        try { checkQueueFailure { pipe.read(100) } } finally { pipe.close() }
        assertEquals(listOf(65_536, 16_384, 8_192, 4_096, 2_048), UsbQueueReplay.sizes)
        UsbQueueReplay.reset()
        val ncm = ncm()
        UsbQueueReplay.outcomes.addAll(listOf(false, false, false, false, false))
        try { checkQueueFailure { readChunk(ncm) } } finally { ncm.close() }
        assertEquals(listOf(32_768, 16_384, 8_192, 4_096, 2_048), UsbQueueReplay.sizes)
    }

    @Test fun queueExceptionDoesNotTriggerCompatibilityRetryInEitherPipe() {
        val pipe = pipe()
        UsbQueueReplay.queueException = IllegalStateException("unknown queue state")
        try { expectUnavailable { pipe.read(100) } } finally { pipe.close() }
        assertEquals(listOf(65_536), UsbQueueReplay.sizes)
        UsbQueueReplay.reset()
        val ncm = ncm()
        UsbQueueReplay.queueException = IllegalStateException("unknown queue state")
        try { expectUnavailable { readChunk(ncm) } } finally { ncm.close() }
        assertEquals(listOf(32_768), UsbQueueReplay.sizes)
    }

    @Test fun timedOutQueuedNcmRequestIsReusedWithoutAnotherQueueAttempt() {
        UsbQueueReplay.outcomes.addAll(listOf(false, true))
        UsbQueueReplay.timeout = true
        val ncm = ncm()
        try {
            assertEquals(null, readChunk(ncm))
            UsbQueueReplay.timeout = false
            assertEquals(payload.size, readChunk(ncm))
        } finally { ncm.close() }
        assertEquals(listOf(32_768, 16_384), UsbQueueReplay.sizes)
    }

    @Test fun closingUsbmuxAfterRejectedQueuePreventsTheCompatibilityRetry() {
        UsbQueueReplay.legacyOutcomes.add(true)
        val pipe = pipe()
        UsbQueueReplay.outcomes.add(false)
        UsbQueueReplay.onQueue = { request ->
            val lock = ReflectionHelpers.getField<Any>(pipe, "stateLock")
            assertTrue(Thread.holdsLock(lock))
            assertTrue(ReflectionHelpers.getField<UsbRequest>(pipe, "pendingRead") === request)
            pipe.close()
        }
        try { expectUnavailable { pipe.read(100) } } finally { pipe.close() }
        assertEquals(listOf(65_536), UsbQueueReplay.sizes)
        assertTrue(UsbQueueReplay.legacySizes.isEmpty())
    }

    @Test fun closingNcmAfterRejectedQueuePreventsTheCompatibilityRetry() {
        val ncm = ncm()
        UsbQueueReplay.outcomes.add(false)
        UsbQueueReplay.onQueue = { request ->
            val lock = ReflectionHelpers.getField<Any>(ncm, "stateLock")
            assertTrue(Thread.holdsLock(lock))
            assertTrue(ReflectionHelpers.getField<UsbRequest>(ncm, "readRequest") === request)
            ncm.close()
        }
        try { expectUnavailable { readChunk(ncm) } } finally { ncm.close() }
        assertEquals(listOf(32_768), UsbQueueReplay.sizes)
    }

    private fun checkQueueFailure(block: () -> Unit) {
        val error = expectUnavailable(block)
        assertTrue(error.message!!.contains("api=28"))
        assertTrue(error.message!!.contains("endpoint=0x85"))
        assertTrue(error.message!!.contains("firstBytes="))
        assertTrue(error.message!!.contains("fallbackBytes=2048"))
    }

    private fun checkFallbackDiagnostic(diagnostics: List<String>, pipe: String, firstBytes: Int) {
        val diagnostic = diagnostics.single()
        assertTrue(diagnostic.startsWith("$pipe read queue compatibility fallback "))
        assertTrue(diagnostic.contains("api=28"))
        assertTrue(diagnostic.contains("endpoint=0x85(direction=128,type=2,maxPacket=512)"))
        assertTrue(diagnostic.contains("firstBytes=$firstBytes fallbackBytes=16384"))
    }

    private fun expectUnavailable(block: () -> Unit): IphoneUsbException.DeviceUnavailable {
        try { block(); throw AssertionError("Expected read failure") }
        catch (error: IphoneUsbException.DeviceUnavailable) { return error }
    }

    private fun readChunk(ncm: NcmUsbBridge): Int? = try {
        NcmUsbBridge::class.java.getDeclaredMethod("readChunk", Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(ncm, 100L) as Int?
    } catch (error: InvocationTargetException) { throw error.targetException }

    private fun pipe(onDiagnostic: (String) -> Unit = {}) =
        Iap2UsbSession(connection(), endpoint(0x04), endpoint(0x85), onDiagnostic)
    private fun ncm(onDiagnostic: (String) -> Unit = {}) =
        NcmUsbBridge(connection(), endpoint(0x06), endpoint(0x85), null, emptyList(), null, onDiagnostic)
    private fun connection(): UsbDeviceConnection = ReflectionHelpers.callConstructor(
        UsbDeviceConnection::class.java, ClassParameter.from(UsbDevice::class.java, null))
    private fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(
        UsbEndpoint::class.java, ClassParameter.from(Int::class.javaPrimitiveType, address),
        ClassParameter.from(Int::class.javaPrimitiveType, 2),
        ClassParameter.from(Int::class.javaPrimitiveType, 512),
        ClassParameter.from(Int::class.javaPrimitiveType, 0))

}
