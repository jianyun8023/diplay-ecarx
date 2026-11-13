package com.shilapi.xcertplay.testcore

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Pure bootstrap carriers forwarded to the real host JNI; no SDK receiver emulation. */
@Implements(className = "com.shilapi.xcertplay.transport.NativeIap2ReportSubscription", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeIap2ReportSubscription {
    companion object {
        @JvmStatic @Implementation fun create(vehicleStatus: Boolean): LongArray =
            HostNative.call("transport.NativeIap2ReportSubscription", "create", vehicleStatus) as LongArray
        @JvmStatic @Implementation fun start(state: LongArray, active: Boolean) {
            HostNative.call("transport.NativeIap2ReportSubscription", "start", state, active)
        }
        @JvmStatic @Implementation fun stop(state: LongArray) {
            HostNative.call("transport.NativeIap2ReportSubscription", "stop", state)
        }
        @JvmStatic @Implementation fun active(state: LongArray): Boolean =
            HostNative.call("transport.NativeIap2ReportSubscription", "active", state) as Boolean
        @JvmStatic @Implementation fun due(state: LongArray, nowNanos: Long): Boolean =
            HostNative.call("transport.NativeIap2ReportSubscription", "due", state, nowNanos) as Boolean
        @JvmStatic @Implementation fun timeout(state: LongArray, remainingMillis: Long, nowNanos: Long): Long =
            HostNative.call("transport.NativeIap2ReportSubscription", "timeout", state, remainingMillis, nowNanos) as Long
        @JvmStatic @Implementation fun mark(state: LongArray, nowNanos: Long) {
            HostNative.call("transport.NativeIap2ReportSubscription", "mark", state, nowNanos)
        }
        @JvmStatic @Implementation fun firstSent(state: LongArray): Boolean =
            HostNative.call("transport.NativeIap2ReportSubscription", "firstSent", state) as Boolean
    }
}

@Implements(className = "com.shilapi.xcertplay.transport.NativeNmeaLocation", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeNmeaLocation {
    companion object {
        @JvmStatic @Implementation fun prepare(latitude: Double, longitude: Double, altitude: Double,
            bearing: Double, speed: Double, accuracy: Double, presence: Int): DoubleArray =
            HostNative.call("transport.NativeNmeaLocation", "prepare", latitude, longitude, altitude,
                bearing, speed, accuracy, presence) as DoubleArray
        @JvmStatic @Implementation fun frame(formattedParts: Array<String>, flags: Int): String =
            HostNative.call("transport.NativeNmeaLocation", "frame", formattedParts, flags) as String
    }
}
