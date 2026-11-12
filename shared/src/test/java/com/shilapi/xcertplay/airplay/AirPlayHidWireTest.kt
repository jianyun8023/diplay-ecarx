package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class AirPlayHidWireTest {
    @Test fun slotIdsUseTheirFirstOccurrenceAndKeepReleasedCoordinates() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 1, 0, 0x2c, 1, 0x90.toByte(), 1),
            AirPlayHid.touchReport(listOf(AirPlayContact(2, 999.0, 999.0, true),
                AirPlayContact(1, 300.0, 400.0, false), AirPlayContact(1, 10.0, 20.0, true),
                AirPlayContact(-1, 40.0, 50.0, true))))
    }

    @Test fun coordinatesUseJavaHalfRoundingThenLowSixteenBits() {
        for ((value, expected) in listOf(Double.NaN to 0, Double.POSITIVE_INFINITY to 65535,
            Double.NEGATIVE_INFINITY to 0, -0.0 to 0, 0.49999999999999994 to 0, 0.5 to 1,
            65535.5 to 0, 65536.5 to 1, 70000.0 to 4464, 4294967297.0 to 1)) {
            val report = AirPlayHid.touchReport(listOf(AirPlayContact(0, value, value, true)))
            assertEquals(expected, (report[2].toInt() and 255) or ((report[3].toInt() and 255) shl 8))
            assertEquals(report[2], report[4]); assertEquals(report[3], report[5])
        }
    }

    @Test fun knobAndIndexedButtonsKeepTheirBitWidths() {
        assertArrayEquals(byteArrayOf(7, -127, 127, -127),
            AirPlayHid.knobReport(AirPlayKnobState(true, true, true, Int.MIN_VALUE, Int.MAX_VALUE, -128)))
        for ((index, expected) in listOf(Int.MIN_VALUE to 0, -257 to -1, 255 to -1, 256 to 0, Int.MAX_VALUE to -1)) {
            assertArrayEquals(byteArrayOf(expected.toByte()), AirPlayHid.mediaReport(index))
            assertArrayEquals(byteArrayOf(expected.toByte()), AirPlayHid.telephonyReport(index))
        }
    }

    @Test fun registrationOrderUidsAndDescriptorSizesStayStable() {
        val devices = listOf(AirPlayHid.touchHidDevice(1920, 1080, "main"), AirPlayHid.knobHidDevice("main"),
            AirPlayHid.mediaHidDevice("main"), AirPlayHid.telephonyHidDevice("main"))
        for ((index, device) in devices.withIndex()) {
            assertEquals(listOf("hidProductID", "hidVendorID", "hidCountryCode", "uuid", "name", "displayUUID", "hidDescriptor"), device.keys.toList())
            assertEquals((AirPlayHid.TOUCH_HID_UID + index).toString(16), device["uuid"])
            assertEquals("main", device["displayUUID"])
            assertEquals(listOf(109, 70, 40, 57)[index], (device["hidDescriptor"] as ByteArray).size)
        }
        assertArrayEquals(AirPlayHid.touchHidDevice(65535, 0, "main")["hidDescriptor"] as ByteArray,
            AirPlayHid.touchHidDevice(-1, Int.MIN_VALUE, "main")["hidDescriptor"] as ByteArray)
    }
}
