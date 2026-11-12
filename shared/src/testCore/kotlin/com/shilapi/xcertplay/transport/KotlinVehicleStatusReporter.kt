package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import kotlin.math.min

// Independent pre-migration implementation (73ed555); never shipped in production.
/** Serves the phone's vehicle-status subscription inside an iAP2 control loop. */
internal class KotlinIap2VehicleStatusReporter(
    private val provider: VehicleStatusProvider?,
    private val onProgress: (String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var active = false
    private var lastSentNanos = 0L

    /** Handles 0xA100/0xA102; returns false for any other message. */
    fun handle(frame: Iap2Frame, send: (Iap2Frame) -> Unit): Boolean = when (frame.messageId) {
        Iap2VehicleStatus.START_VEHICLE_STATUS_UPDATES -> {
            active = provider != null
            onProgress("iap2 rx=0xa100 start-vehicle-status provider=${provider != null}")
            if (active) sendNow(send)
            true
        }
        Iap2VehicleStatus.STOP_VEHICLE_STATUS_UPDATES -> {
            active = false
            onProgress("iap2 rx=0xa102 stop-vehicle-status")
            true
        }
        else -> false
    }

    /** Sends the periodic update when it is due. */
    fun tick(send: (Iap2Frame) -> Unit) {
        if (active && nanoTime() - lastSentNanos >= Iap2VehicleStatus.UPDATE_INTERVAL_MILLIS * 1_000_000) sendNow(send)
    }

    fun pollTimeout(remainingMillis: Long): Long =
        if (active) min(remainingMillis, Iap2VehicleStatus.UPDATE_INTERVAL_MILLIS) else remainingMillis

    private fun sendNow(send: (Iap2Frame) -> Unit) {
        val status = provider?.snapshot() ?: return
        send(Iap2VehicleStatus.update(status))
        lastSentNanos = nanoTime()
        onProgress("iap2 tx=0xa101 vehicle-status range=${status.rangeKm}km battery=${status.batteryPercent}% " +
            "warning=${status.rangeWarning}")
    }
}
