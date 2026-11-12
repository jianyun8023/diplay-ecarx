package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.airplay.NativeMedia
import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import org.junit.Test

/** Native group 20/21 TLV bytes must equal the Kotlin fallback for names and every connector set. */
class NativeVehicleIdentificationDeviceTest {
    private fun components(native: Boolean, name: String, connectors: EvChargingConnectors): ByteArray =
        Iap2BodyBuilder.opaque(0x1234).apply {
            with(Iap2VehicleStatus) { electricVehicleComponents(name, connectors, native) }
        }.build().payload

    @Test fun nativeMatchesKotlinFallbackForVehicleComponents() {
        assertTrue(NativeMedia.available)
        for (name in listOf("Tesla", "Bülli😀", "测试车", "", "\uD800X", "\uDC00", "\uD800\uD800\uDC00")) {
            for (connectors in EvChargingConnectors.entries) {
                assertArrayEquals(
                    "$name/$connectors",
                    components(false, name, connectors),
                    components(true, name, connectors),
                )
            }
        }
    }

    @Test fun invalidNamesAndOversizedGroupsKeepTheOriginalRejection() {
        assertTrue(NativeMedia.available)
        for (name in listOf("a\u0000b", "a".repeat(65532), "a".repeat(22000))) {
            val expected = assertThrows(IllegalArgumentException::class.java) {
                components(false, name, EvChargingConnectors.GB_T)
            }
            for (native in listOf(false, true)) {
                val error = assertThrows(IllegalArgumentException::class.java) {
                    components(native, name, EvChargingConnectors.GB_T)
                }
                assertEquals(expected.message, error.message)
            }
        }
    }

    @Test fun actualIdentificationKeepsVehicleCapabilityGatesAndComponentBytes() {
        assertTrue(NativeMedia.available)
        val config = Iap2IdentificationConfig("xcertplay", "model", "测试车", "serial", "1", "1", 0,
            vehicleStatusEnabled = true)
        val frame = Iap2IdentificationClient.identificationInformation(config)
        val expected = Iap2ParameterList.parse(components(false, config.manufacturer, config.chargingConnectors))
        val body = frame.body()
        for (id in listOf(20, 21)) assertArrayEquals(expected.require(id).payload, body.require(id).payload)
        val disabled = Iap2IdentificationClient.identificationInformation(config.withVehicleStatusFrom(null)).body()
        assertTrue(disabled.first(20) == null && disabled.first(21) == null)
    }
}
