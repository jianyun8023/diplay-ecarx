@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package com.shihab.diplay.transport

import com.shihab.diplay.iap2.body.Iap2BodyBuilder
import com.shihab.diplay.iap2.message.Iap2Messages
import com.shihab.diplay.iap2.wire.Iap2Frame
import kotlin.math.roundToLong

/** The original conversion, compiled only into unit/device reference sources. */
internal object KotlinVehicleStatus {
    private const val ENGINE_TYPE_ELECTRIC = 2

    fun Iap2BodyBuilder.electricVehicleComponents(name: String, connectors: EvChargingConnectors) {
        group(20) {
            u16(0, 3) // Identifier
            string(1, name) // Name
            u8(2, ENGINE_TYPE_ELECTRIC) // EngineType
            string(6, name) // DisplayName
            string(8, name) // MapsDisplayName
            for (connector in connectors.wireValues) u8(11, connector) // SupportedChargingConnectors
        }
        group(21) {
            u16(0, 4) // Identifier
            string(1, name) // Name
            void(3) // Range
            void(6) // RangeWarning
            void(11) // RangeElectric
            void(15) // RangeWarningElectric
            void(21) // ElectricChargeInfo
            void(30) // MaxRangeInfo
        }
    }
    fun update(status: VehicleStatusSnapshot): Iap2Frame = Iap2Messages.buildRaw(Iap2VehicleStatus.VEHICLE_STATUS_UPDATE) {
        u16(3, status.rangeKm.coerceIn(0, 0xffff)) // Range, km
        bool(6, status.rangeWarning) // RangeWarning
        u16(11, status.rangeKm.coerceIn(0, 0xffff)) // RangeElectric, km
        bool(15, status.rangeWarning) // RangeWarningElectric
        // Unknown energy is omitted, never represented as an empty battery or a zero capacity.
        if (status.currentChargeWh != null || status.maxChargeWh != null) u32(21, 0L) // MinBatteryCharge, Wh
        status.currentChargeWh?.let { u32(22, it.coerceAtLeast(0)) } // CurrentBatteryCharge, Wh
        status.maxChargeWh?.let { u32(23, it.coerceAtLeast(0)) } // MaxBatteryCharge, Wh
        u32(24, (status.batteryPercent.coerceIn(0.0, 100.0) * 1000).roundToLong()) // DisplayedBatteryPercentage, % x 1000
        bool(25, status.charging) // isCharging
        u16(30, status.maxRangeKm.coerceIn(0, 0xffff)) // MaxRangeElectric, km
    }
}

internal fun Iap2VehicleStatus.kotlinUpdate(status: VehicleStatusSnapshot) = KotlinVehicleStatus.update(status)
