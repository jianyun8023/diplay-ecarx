package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.airplay.NativeMedia
import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2WireCodec
import com.shilapi.xcertplay.iap2.wire.Iap2Parameter

/** One reading of an electric vehicle's range and charge, as CarPlay's VehicleStatusUpdate carries it. */
data class VehicleStatusSnapshot(
    val rangeKm: Int,
    val rangeWarning: Boolean,
    val batteryPercent: Double,
    val currentChargeWh: Long?,
    val maxChargeWh: Long?,
    val maxRangeKm: Int,
    val charging: Boolean = false,
)

/** Supplies the vehicle's status for CarPlay; null while no reading is available. */
fun interface VehicleStatusProvider {
    fun snapshot(): VehicleStatusSnapshot?
}

/** The charging inlets the car declares, as iAP2 SupportedChargingConnectors values. */
enum class EvChargingConnectors(internal vararg val wireValues: Int) {
    CCS2_TYPE2(1, 4), // CCS2 (DC) and Mennekes / Type 2 (AC), as on European models
    GB_T(5, 6), // GB/T DC and AC, as on Chinese-market models
    CCS1_J1772(0, 2), // CCS1 (DC) and J1772 (AC)
}

/**
 * The identification to send: an electric vehicle is declared only while [provider] has a battery
 * reading, so the iPhone is never told about an EV that DiPlay cannot report (adb off or not
 * approved, or a car without these properties). Reads the provider's cached value; never blocks.
 */
fun Iap2IdentificationConfig.withVehicleStatusFrom(provider: VehicleStatusProvider?): Iap2IdentificationConfig =
    if (vehicleStatusEnabled && provider?.snapshot() == null) copy(vehicleStatusEnabled = false) else this

/**
 * iAP2 vehicle plane for an electric car: identification declares the vehicle (engine type,
 * charging connectors) and which status fields it reports; the phone then subscribes with
 * StartVehicleStatusUpdates (0xA100) and the accessory answers with VehicleStatusUpdate (0xA101).
 * Parameter ids follow Apple's iAP2 message catalog.
 */
object Iap2VehicleStatus {
    init { NativeMedia.requireAvailable() }
    const val START_VEHICLE_STATUS_UPDATES = 0xA100
    const val VEHICLE_STATUS_UPDATE = 0xA101
    const val STOP_VEHICLE_STATUS_UPDATES = 0xA102
    const val UPDATE_INTERVAL_MILLIS = 30_000L

    /** IdentificationInformation params 20 (VehicleInformation) and 21 (VehicleStatus). */
    fun Iap2BodyBuilder.electricVehicleComponents(name: String, connectors: EvChargingConnectors) {
        val namePayload = Iap2Parameter(1, Iap2WireCodec.string(name)).payload
        groupEncoded(20, NativeVehicleIdentification.group20(namePayload, connectors.wireValues))
        groupEncoded(21, NativeVehicleIdentification.group21(namePayload))
    }

    fun update(status: VehicleStatusSnapshot): Iap2Frame {
        val energyMask = (if (status.currentChargeWh != null) 1 else 0) or (if (status.maxChargeWh != null) 2 else 0)
        val payload = requireNotNull(NativeMedia.vehicleStatusPayload(status.rangeKm, status.rangeWarning,
            status.batteryPercent, status.currentChargeWh ?: 0, status.maxChargeWh ?: 0, energyMask,
            status.maxRangeKm, status.charging)) { "Vehicle status values cannot be encoded" }
        return Iap2Frame(VEHICLE_STATUS_UPDATE, payload)
    }


}

/** The native pure TLV encoder for IdentificationInformation vehicle components. */
internal object NativeVehicleIdentification {
    @JvmStatic external fun group20(namePayload: ByteArray, connectors: IntArray): ByteArray
    @JvmStatic external fun group21(namePayload: ByteArray): ByteArray
}

/** Serves the phone's vehicle-status subscription inside an iAP2 control loop. */
internal class Iap2VehicleStatusReporter(
    private val provider: VehicleStatusProvider?,
    private val onProgress: (String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val subscription = NativeIap2ReportSubscription.create(vehicleStatus = true)

    /** Handles 0xA100/0xA102; returns false for any other message. */
    fun handle(frame: Iap2Frame, send: (Iap2Frame) -> Unit): Boolean = when (frame.messageId) {
        Iap2VehicleStatus.START_VEHICLE_STATUS_UPDATES -> {
            NativeIap2ReportSubscription.start(subscription, provider != null)
            onProgress("iap2 rx=0xa100 start-vehicle-status provider=${provider != null}")
            if (NativeIap2ReportSubscription.active(subscription)) sendNow(send)
            true
        }
        Iap2VehicleStatus.STOP_VEHICLE_STATUS_UPDATES -> {
            NativeIap2ReportSubscription.stop(subscription)
            onProgress("iap2 rx=0xa102 stop-vehicle-status")
            true
        }
        else -> false
    }

    /** Sends the periodic update when it is due. */
    fun tick(send: (Iap2Frame) -> Unit) {
        if (NativeIap2ReportSubscription.active(subscription) &&
            NativeIap2ReportSubscription.due(subscription, nanoTime())) sendNow(send)
    }

    fun pollTimeout(remainingMillis: Long): Long =
        if (NativeIap2ReportSubscription.active(subscription))
            NativeIap2ReportSubscription.timeout(subscription, remainingMillis, 0) else remainingMillis

    private fun sendNow(send: (Iap2Frame) -> Unit) {
        val status = provider?.snapshot() ?: return
        send(Iap2VehicleStatus.update(status))
        NativeIap2ReportSubscription.mark(subscription, nanoTime())
        onProgress("iap2 tx=0xa101 vehicle-status range=${status.rangeKm}km battery=${status.batteryPercent}% " +
            "warning=${status.rangeWarning}")
    }
}
