package com.shihab.diplay.transport

import com.shihab.diplay.airplay.NativeMedia
import org.junit.Assert.*
import org.junit.Test

class NativeVehicleStatusDeviceTest {
    @Test fun nativeVehicleConversionMatchesTheExistingWireBodyIncludingBoundaryValues() {
        assertTrue(NativeMedia.available)
        val charges = listOf(null, Long.MIN_VALUE, -1L, 0L, 25_100L, 0xffff_ffffL)
        val percentages = listOf(Double.NEGATIVE_INFINITY, -1.0, 0.0, 0.0005, 1.2345, 25.0, 51.0, 100.0, Double.POSITIVE_INFINITY)
        for ((index, range) in listOf(Int.MIN_VALUE, -1, 0, 150, 0xffff, Int.MAX_VALUE).withIndex()) {
            for (current in charges) for (max in charges) for (percent in percentages) {
                val reading = VehicleStatusSnapshot(range, index % 2 == 0, percent, current, max,
                    listOf(Int.MAX_VALUE, -1, 600)[index % 3], index % 2 == 1)
                val expected = Iap2VehicleStatus.kotlinUpdate(reading)
                val actual = Iap2VehicleStatus.update(reading)
                assertEquals(expected.messageId, actual.messageId)
                assertArrayEquals(expected.encodedFrame(), actual.encodedFrame())
            }
        }
    }

    @Test fun unknownAndInvalidValuesPreserveOmissionAndRejectionOnTheNativePath() {
        assertTrue(NativeMedia.available)
        val unknown = VehicleStatusSnapshot(36, false, 51.0, null, null, 600, true)
        val body = Iap2VehicleStatus.update(unknown).body()
        for (id in 21..23) assertNull(body.first(id))
        assertEquals(listOf(3, 6, 11, 15, 24, 25, 30), body.asList().map { it.id })
        for (known in listOf(unknown.copy(currentChargeWh = 0), unknown.copy(maxChargeWh = 0))) {
            assertNotNull(Iap2VehicleStatus.update(known).body().first(21))
        }
        for (invalid in listOf(unknown.copy(batteryPercent = Double.NaN),
            unknown.copy(currentChargeWh = 0x1_0000_0000L), unknown.copy(maxChargeWh = Long.MAX_VALUE))) {
            assertThrows(IllegalArgumentException::class.java) { Iap2VehicleStatus.kotlinUpdate(invalid) }
            assertThrows(IllegalArgumentException::class.java) { Iap2VehicleStatus.update(invalid) }
        }
        assertNull(NativeMedia.vehicleStatusPayload(0, false, 0.0, 0, 0, 4, 0, false))
    }
}
