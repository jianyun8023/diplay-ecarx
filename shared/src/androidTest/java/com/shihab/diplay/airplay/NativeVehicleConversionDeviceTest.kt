package com.shihab.diplay.airplay

import com.shihab.diplay.media.AudioBufferProgress
import com.shihab.diplay.media.KotlinAudioBufferProgress
import com.shihab.diplay.transport.PascdEncoder
import com.shihab.diplay.transport.KotlinPascdEncoder
import com.shihab.diplay.transport.VehicleGear
import com.shihab.diplay.transport.VehicleSpeedReading
import com.shihab.diplay.transport.VehicleSpeedSample
import com.shihab.diplay.hud.NativeClusterArrow
import com.shihab.diplay.hud.NativeDayNight
import com.shihab.diplay.hud.NativeLaneIcon
import com.shihab.diplay.hud.KotlinLaneIcon
import com.shihab.diplay.iap2.message.LaneGuidanceLane
import com.shihab.diplay.media.NativeAudioScheduler
import com.shihab.diplay.transport.NativeVehicleSpeed
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/** Exercises the new input/vehicle/output-accounting JNI owners against their Kotlin contracts. */
class NativeVehicleConversionDeviceTest {
    @Test fun realWheelSpeedBatchContract() = com.shihab.diplay.transport.VehicleSpeedCoreContract.verify()
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
        val sentence = PascdEncoder.encode(VehicleSpeedReading(VehicleGear.PARK,
            listOf(VehicleSpeedSample(17_877_092, 0.0), VehicleSpeedSample(17_877_252, 0.0))))
        assertEquals("\$PASCD,17877.092,C,P,0,2,0.00,0.000,0.16,0.000*54\r\n", sentence)
        assertNull(NativeVehicleSpeed.pascdEncode(0, emptyArray()))
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
            assertEquals(6144, NativeAudioScheduler.startBytesFor(8192, 8192, 2048))
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
        assertEquals(4, NativeClusterArrow.appleToAmap(8, 1))
        assertEquals(104, NativeClusterArrow.arrowId(8, 1))
        assertEquals(1, NativeLaneIcon.forLane(0, intArrayOf(-90), 0, false))
        assertEquals(33, NativeLaneIcon.forLane(2, intArrayOf(0), 90, true))
        assertEquals(-1, NativeLaneIcon.forLane(0, intArrayOf(), 0, false))
        assertEquals(0, NativeDayNight.fromAdaptapi(1))
        assertEquals(1, NativeDayNight.fromAdaptapi(2))
        assertEquals(-1, NativeDayNight.fromAmap(31))
        assertEquals(1, NativeDayNight.fromTwilight(0))
    }

    @Test fun touchNativeMatchesKotlinAcrossTheFullEventSequence() {
        assertTrue(NativeMedia.available)
        val native = NativeTouchSlots.create(2)
        val kotlin = KotlinAirPlayTouchSlots(2)
        fun check(contacts: List<AirPlayContact>) {
            val expected = KotlinAirPlayHid.touchReport(
                kotlin.assign(contacts).map { it.copy(x = it.x * 1000.0, y = it.y * 500.0) },
            )
            val actual = NativeTouchSlots.assignReport(
                native,
                IntArray(contacts.size) { contacts[it].id },
                DoubleArray(contacts.size) { contacts[it].x },
                DoubleArray(contacts.size) { contacts[it].y },
                BooleanArray(contacts.size) { contacts[it].down },
                1000, 500,
            )
            assertArrayEquals(expected, actual)
        }
        try {
            check(listOf(AirPlayContact(7, 0.1, 0.2, true), AirPlayContact(9, 0.3, 0.4, true)))
            check(listOf(AirPlayContact(7, 0.15, 0.25, true), AirPlayContact(9, 0.35, 0.45, true)))
            check(listOf(AirPlayContact(7, 0.15, 0.25, true)))
            check(listOf(AirPlayContact(7, 0.2, 0.3, true), AirPlayContact(5, 0.5, 0.5, true)))
            check(emptyList())
        } finally {
            NativeTouchSlots.destroy(native)
        }
    }

    @Test fun pascdNativeMatchesKotlinFormattingAndFailureBehaviour() {
        assertTrue(NativeMedia.available)
        val cases = listOf(
            VehicleSpeedReading(VehicleGear.PARK, listOf(VehicleSpeedSample(17_877_092, 0.0), VehicleSpeedSample(17_877_252, 0.0))),
            VehicleSpeedReading(VehicleGear.DRIVE, listOf(VehicleSpeedSample(1_000, 1.2345), VehicleSpeedSample(1_250, -3.0))),
            VehicleSpeedReading(VehicleGear.REVERSE, listOf(VehicleSpeedSample(0, 0.0005))),
            VehicleSpeedReading(VehicleGear.DRIVE, listOf(VehicleSpeedSample(1000, 0.0), VehicleSpeedSample(2005, 1.25))),
            VehicleSpeedReading(VehicleGear.DRIVE, listOf(VehicleSpeedSample(1000, -0.0), VehicleSpeedSample(999, Double.NaN))),
            VehicleSpeedReading(VehicleGear.DRIVE, listOf(VehicleSpeedSample(Long.MAX_VALUE, Double.POSITIVE_INFINITY),
                VehicleSpeedSample(Long.MIN_VALUE, Double.MAX_VALUE))),
            VehicleSpeedReading(VehicleGear.NEUTRAL, emptyList()),
        )
        for (reading in cases) {
            val expected = KotlinPascdEncoder.encode(reading)
            val actual = PascdEncoder.encode(reading)
            assertEquals(expected, actual)
        }
    }

    @Test fun closedTouchSlotsRejectLateReportsAndRepeatedClose() {
        assertTrue(NativeMedia.available)
        val slots = AirPlayTouchSlots(2)
        assertTrue(slots.report(listOf(AirPlayContact(7, 0.5, 0.25, true)), 1920, 1080) != null)
        slots.close()
        assertNull(slots.report(emptyList(), 1920, 1080))
        slots.close()
    }

    @Test fun laneIconsMatchTheOldContractAcrossDirectionAndHighlightCombinations() {
        assertTrue(NativeMedia.available)
        val angles = listOf(-180, -90, -45, 0, 45, 90)
        for (mask in 0 until (1 shl angles.size)) {
            val directions = angles.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
            for (status in 0..2) for (highlight in listOf(null) + angles) {
                val lane = LaneGuidanceLane(0, status, directions, highlight)
                assertEquals("$lane", KotlinLaneIcon.forLane(lane),
                    NativeLaneIcon.forLane(status, directions.toIntArray(), highlight ?: 0, highlight != null).takeIf { it >= 0 })
            }
        }
    }

    @Test fun audioBufferAccountingNativeMatchesKotlinIncludingWrapAndReset() {
        assertTrue(NativeMedia.available)
        val native = AudioBufferProgress(4)
        val kotlin = KotlinAudioBufferProgress(4)
        try {
            native.written(4000); kotlin.written(4000)
            for (head in listOf(-100, 100, 200, -4)) {
                assertEquals(kotlin.queuedBytes(head), native.queuedBytes(head))
            }
            for (media in listOf(true, false)) for (playing in listOf(true, false)) for (underrun in listOf(true, false))
                for (empty in listOf(true, false)) for (head in listOf(-100, 100)) {
                    assertEquals(
                        kotlin.shouldRebuffer(media, playing, underrun, empty, head, 3000),
                        native.shouldRebuffer(media, playing, underrun, empty, head, 3000),
                    )
                }
            native.reset(); kotlin.reset()
            assertEquals(kotlin.queuedBytes(100), native.queuedBytes(100))
        } finally {
            native.close()
        }
    }
}
