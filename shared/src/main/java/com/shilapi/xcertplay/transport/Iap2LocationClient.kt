package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/** One Android location sample converted into the fields carried by the NMEA pair. */
data class CarPlayLocationFix(
    val latitudeDegrees: Double,
    val longitudeDegrees: Double,
    val altitudeMeters: Double? = null,
    val bearingDegrees: Double? = null,
    val speedMetersPerSecond: Double? = null,
    val accuracyMeters: Double? = null,
    val timestampMillis: Long? = null,
) {
    init {
        require(latitudeDegrees.isFinite() && latitudeDegrees in -90.0..90.0) {
            "latitudeDegrees must be between -90 and 90"
        }
        require(longitudeDegrees.isFinite() && longitudeDegrees in -180.0..180.0) {
            "longitudeDegrees must be between -180 and 180"
        }
    }
}

/** Supplies location data only while the phone has subscribed to iAP2 LocationInformation. */
interface Iap2LocationProvider : AutoCloseable {
    /** The 0xFFFA parameter ids, i.e. the sentence types the iPhone asked for; called before [start]. */
    fun onRequested(components: Set<Int>) = Unit

    /** Starts location updates and returns whether at least one source was subscribed. */
    fun start(): Boolean

    fun stop()

    fun latestNmea(): String?

    override fun close() {
        stop()
    }
}

/** LIVI-compatible $GPGGA + $GPRMC encoding used by iAP2 0xFFFB. */
object NmeaLocationEncoder {
    fun encode(fix: CarPlayLocationFix): String {
        val timestamp = fix.timestampMillis
            ?.takeIf { it > 0 }
            ?.let(::ofEpochMilli)
            ?: ofEpochMilli(System.currentTimeMillis())
        val time = format("%02d%02d%02d.00", timestamp.hour, timestamp.minute, timestamp.second)
        val date = format(
            "%02d%02d%02d",
            timestamp.day,
            timestamp.month,
            timestamp.year % 100,
        )

        val presence = (if (fix.altitudeMeters != null) 1 else 0) or
            (if (fix.bearingDegrees != null) 2 else 0) or
            (if (fix.speedMetersPerSecond != null) 4 else 0) or
            (if (fix.accuracyMeters != null) 8 else 0)
        val plan = NativeNmeaLocation.prepare(fix.latitudeDegrees, fix.longitudeDegrees,
            fix.altitudeMeters ?: 0.0, fix.bearingDegrees ?: 0.0,
            fix.speedMetersPerSecond ?: 0.0, fix.accuracyMeters ?: 0.0, presence)
        val flags = plan[8].toInt()
        // Keep Android Formatter's decimal rounding, signed zero and Infinity spelling.
        return NativeNmeaLocation.frame(arrayOf(time, date,
            format("%02d", plan[0].toInt()), format("%07.4f", plan[1]),
            format("%03d", plan[2].toInt()), format("%07.4f", plan[3]),
            format("%.1f", plan[4]), format("%.1f", plan[5]),
            format("%.2f", plan[6]), if (flags and 4 != 0) format("%.2f", plan[7]) else ""), flags)
    }

    private fun ofEpochMilli(millis: Long): Timestamp = Timestamp(millis)

    private fun format(format: String, vararg arguments: Any): String =
        String.format(Locale.US, format, *arguments)

    private class Timestamp(millis: Long) {
        // java.time needs API 26; a proleptic Gregorian calendar in UTC gives the same fields.
        private val fields = GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.US).apply {
            gregorianChange = Date(Long.MIN_VALUE)
            timeInMillis = millis
        }

        val hour: Int = fields.get(Calendar.HOUR_OF_DAY)
        val minute: Int = fields.get(Calendar.MINUTE)
        val second: Int = fields.get(Calendar.SECOND)
        val day: Int = fields.get(Calendar.DAY_OF_MONTH)
        val month: Int = fields.get(Calendar.MONTH) + 1
        val year: Int = fields.get(Calendar.YEAR)
    }

}

/**
 * Accessory side of iAP2 LocationInformation on one link: starts on 0xFFFA, sends the latest fix
 * on every [tick] (about once a second), and stops on 0xFFFC. Subscription state belongs to this
 * iAP2 link: a request received on Bluetooth must never authorize output on the Wi-Fi tunnel.
 */
class Iap2LocationReporter(
    private val provider: Iap2LocationProvider?,
    private val onProgress: (String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val subscription = NativeIap2ReportSubscription.create(vehicleStatus = false)

    /** Handles 0xFFFA/0xFFFC; returns false for any other message. */
    fun handle(frame: Iap2Frame, send: (Iap2Frame) -> Unit): Boolean = when (frame.messageId) {
        Iap2LocationMessages.START_LOCATION_INFORMATION -> {
            val components = Iap2LocationMessages.requestedComponents(frame)
            onProgress("iap2 rx=0xfffa start-location-information components=$components")
            provider?.onRequested(components)
            start(send)
            true
        }
        Iap2LocationMessages.STOP_LOCATION_INFORMATION -> {
            onProgress("iap2 rx=0xfffc stop-location-information")
            NativeIap2ReportSubscription.stop(subscription)
            provider?.stop()
            true
        }
        else -> false
    }

    /** Sends the latest fix once a second while this link has an active request. */
    fun tick(send: (Iap2Frame) -> Unit) {
        if (NativeIap2ReportSubscription.active(subscription) &&
            NativeIap2ReportSubscription.due(subscription, nanoTime())) sendLatest(send)
    }

    /** Wakes the loop when the next requested fix is due. */
    fun pollTimeout(remainingMillis: Long): Long =
        if (NativeIap2ReportSubscription.active(subscription))
            NativeIap2ReportSubscription.timeout(subscription, remainingMillis, nanoTime())
        else remainingMillis

    private fun start(send: (Iap2Frame) -> Unit) {
        NativeIap2ReportSubscription.start(subscription, startProvider())
        if (NativeIap2ReportSubscription.active(subscription)) sendLatest(send)
    }

    private fun startProvider(): Boolean {
        if (provider == null) return false
        return try {
            provider.start().also { started -> if (!started) onProgress("iap2 location provider did not start") }
        } catch (error: Exception) {
            onProgress("iap2 location provider start failed: ${error.message}")
            false
        }
    }

    private fun sendLatest(send: (Iap2Frame) -> Unit) {
        NativeIap2ReportSubscription.mark(subscription, nanoTime())
        val sentence = provider?.latestNmea() ?: return
        send(Iap2LocationMessages.locationInformation(sentence))
        if (NativeIap2ReportSubscription.firstSent(subscription)) {
            onProgress("iap2 tx=0xfffb location-information")
        }
    }

}

object Iap2LocationMessages {
    const val START_LOCATION_INFORMATION = 0xfffa
    const val LOCATION_INFORMATION = 0xfffb
    const val STOP_LOCATION_INFORMATION = 0xfffc

    /** 0xFFFA selector for `$PASCD`; the matching IdentificationInformation flag is 20. */
    const val VEHICLE_SPEED_DATA = 4

    /** The parameter ids of a 0xFFFA request (the sentence types asked for), or none if unreadable. */
    fun requestedComponents(frame: Iap2Frame): Set<Int> =
        runCatching { frame.body().asList().map { it.id }.toSortedSet() }.getOrDefault(emptySet())

    fun locationInformation(nmeaSentence: String): Iap2Frame {
        require(nmeaSentence.isNotEmpty()) { "NMEA sentence must not be empty" }
        return Iap2ControlMessages.locationInformation(nmeaSentence)
    }
}
