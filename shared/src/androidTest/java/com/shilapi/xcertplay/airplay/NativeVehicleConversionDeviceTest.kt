package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.hud.NativeClusterArrow
import com.shilapi.xcertplay.hud.NativeDayNight
import com.shilapi.xcertplay.hud.NativeLaneIcon
import com.shilapi.xcertplay.media.NativeAudioScheduler
import com.shilapi.xcertplay.transport.NativeVehicleSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the new input/vehicle/output-accounting JNI owners against their Kotlin contracts. */
class NativeVehicleConversionDeviceTest {
    @Test fun touchSlotsKeepOneFingerPerSlotAndReleaseWhereItWas() {
        assertTrue(NativeMedia.available)
        val handle = NativeTouchSlots.create(2)
        assertTrue(handle != 0L)
        try {
            // Slot 0 down at (0.5, 0.25) of 1920x1080 → x=960=0x03c0, y=270=0x010e.
            val pressed = NativeTouchSlots.assignReport(
                handle, intArrayOf(7), doubleArrayOf(0.5), doubleArrayOf(0.25), booleanArrayOf(true), 1920, 1080,
            )
            assertEquals(listOf(0, 1, 0xc0, 0x03, 0x0e, 0x01, 1, 0, 0, 0, 0, 0), pressed.map { it.toInt() and 0xff })
            val released = NativeTouchSlots.assignReport(
                handle, intArrayOf(), doubleArrayOf(), doubleArrayOf(), booleanArrayOf(), 1920, 1080,
            )
            assertEquals(0, released[1].toInt() and 0xff)
            assertEquals(0xc0, released[2].toInt() and 0xff)
        } finally {
            NativeTouchSlots.destroy(handle)
        }
    }

    @Test fun pascdMatchesThePinnedHeadUnitSentence() {
        assertTrue(NativeMedia.available)
        val sentence = NativeVehicleSpeed.pascdEncode(
            0, longArrayOf(17_877_092, 17_877_252), doubleArrayOf(0.0, 0.0),
        )
        assertEquals("\$PASCD,17877.092,C,P,0,2,0.00,0.000,0.16,0.000*54\r\n", sentence)
        assertEquals(null, NativeVehicleSpeed.pascdEncode(0, longArrayOf(), doubleArrayOf()))
    }

    @Test fun audioSchedulerBufferAccountingAndRebufferMatchKotlin() {
        assertTrue(NativeMedia.available)
        val handle = NativeAudioScheduler.create(4)
        assertTrue(handle != 0L)
        try {
            NativeAudioScheduler.written(handle, 4000)
            assertEquals(3600L, NativeAudioScheduler.queuedBytes(handle, 100))
            assertFalse(NativeAudioScheduler.shouldRebuffer(handle, true, false, true, true, 100, 4000))
            assertTrue(NativeAudioScheduler.shouldRebuffer(handle, true, true, true, true, 200, 4000))
            assertEquals(4096, NativeAudioScheduler.startBytesFor(8192, 8192, 2048))
        } finally {
            NativeAudioScheduler.destroy(handle)
        }
    }

    @Test fun hudAndClusterConversionTablesMatchTheirKotlinContracts() {
        assertTrue(NativeMedia.available)
        assertEquals(109, NativeClusterArrow.arrowId(0, 0))
        assertEquals(112, NativeClusterArrow.amapToEcarx(12, 0))
        assertEquals(123, NativeClusterArrow.amapToEcarx(11, 3))
        assertEquals(12, NativeClusterArrow.ecarxToAmap(112))
        assertEquals(104, NativeClusterArrow.appleToAmap(8, 1))
        assertEquals(1, NativeLaneIcon.forLane(0, intArrayOf(-90), 0, false))
        assertEquals(33, NativeLaneIcon.forLane(2, intArrayOf(0), 90, true))
        assertEquals(-1, NativeLaneIcon.forLane(0, intArrayOf(), 0, false))
        assertEquals(0, NativeDayNight.fromAdaptapi(1))
        assertEquals(1, NativeDayNight.fromAdaptapi(2))
        assertEquals(-1, NativeDayNight.fromAmap(31))
    }
}
