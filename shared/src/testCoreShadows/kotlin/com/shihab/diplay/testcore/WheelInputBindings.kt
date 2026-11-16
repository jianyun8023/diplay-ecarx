package com.shihab.diplay.testcore

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(className = "com.shihab.diplay.vehicle.NativeWheelInput", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeWheelInput {
    companion object {
        @JvmStatic @Implementation fun pressConsumed(token: Long, present: Boolean): Boolean =
            HostNative.call("vehicle.NativeWheelInput", "pressConsumed", token, present) as Boolean
        @JvmStatic @Implementation fun pressDecision(token: Long, present: Boolean, down: Boolean, first: Boolean): Int =
            HostNative.call("vehicle.NativeWheelInput", "pressDecision", token, present, down, first) as Int
        @JvmStatic @Implementation fun pressCommit(consumed: Boolean): Long =
            HostNative.call("vehicle.NativeWheelInput", "pressCommit", consumed) as Long
        @JvmStatic @Implementation fun siriCreate(): LongArray =
            HostNative.call("vehicle.NativeWheelInput", "siriCreate") as LongArray
        @JvmStatic @Implementation fun siriStep(token: LongArray, event: Int, now: Long, maximumGap: Long): LongArray =
            HostNative.call("vehicle.NativeWheelInput", "siriStep", token, event, now, maximumGap) as LongArray
        @JvmStatic @Implementation fun siriResult(token: LongArray): Int =
            HostNative.call("vehicle.NativeWheelInput", "siriResult", token) as Int
        @JvmStatic @Implementation fun siriConstant(field: Int): Long =
            HostNative.call("vehicle.NativeWheelInput", "siriConstant", field) as Long
    }
}
