package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.airplay.NativeMedia
import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Native group 20/21 TLV bytes must equal the Kotlin fallback for names and every connector set. */
class NativeVehicleIdentificationDeviceTest {
    private fun components(native: Boolean, name: String, connectors: EvChargingConnectors): ByteArray =
        Iap2BodyBuilder.opaque(0x1234).apply {
            with(Iap2VehicleStatus) { electricVehicleComponents(name, connectors, native) }
        }.build().payload

    @Test fun nativeMatchesKotlinFallbackForVehicleComponents() {
        assertTrue(NativeMedia.available)
        for (name in listOf("Tesla", "Bülli😀", "测试车", "")) {
            for (connectors in EvChargingConnectors.entries) {
                assertArrayEquals(
                    "$name/$connectors",
                    components(false, name, connectors),
                    components(true, name, connectors),
                )
            }
        }
    }
}
