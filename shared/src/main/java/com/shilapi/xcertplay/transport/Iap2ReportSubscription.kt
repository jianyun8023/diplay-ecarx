package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.airplay.NativeMedia

/** Opaque per-link value. Callbacks and clock reads stay at their original call sites. */
internal object NativeIap2ReportSubscription {
    init { NativeMedia.requireAvailable() }
    @JvmStatic external fun create(vehicleStatus: Boolean): LongArray
    @JvmStatic external fun start(state: LongArray, active: Boolean)
    @JvmStatic external fun stop(state: LongArray)
    @JvmStatic external fun active(state: LongArray): Boolean
    /** The caller snapshots active before reading the potentially reentrant clock. */
    @JvmStatic external fun due(state: LongArray, nowNanos: Long): Boolean
    @JvmStatic external fun timeout(state: LongArray, remainingMillis: Long, nowNanos: Long): Long
    @JvmStatic external fun mark(state: LongArray, nowNanos: Long)
    @JvmStatic external fun firstSent(state: LongArray): Boolean
}

/** Numeric decisions and NMEA wire layout; Java owns Locale.US numeric rendering. */
internal object NativeNmeaLocation {
    init { NativeMedia.requireAvailable() }
    @JvmStatic external fun prepare(latitude: Double, longitude: Double, altitude: Double,
        bearing: Double, speed: Double, accuracy: Double, presence: Int): DoubleArray
    @JvmStatic external fun frame(formattedParts: Array<String>, flags: Int): String
}
