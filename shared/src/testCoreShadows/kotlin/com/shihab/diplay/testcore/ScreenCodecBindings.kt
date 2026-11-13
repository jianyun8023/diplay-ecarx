@file:Suppress("UNCHECKED_CAST")

package com.shihab.diplay.testcore

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements


@Implements(className = "com.shihab.diplay.airplay.NativeScreenCodec", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeScreenCodec {
    companion object {
        @JvmStatic @Implementation fun senderNanos(header: ByteArray): Long =
            HostNative.call("airplay.NativeScreenCodec", "senderNanos", header) as Long

        @JvmStatic @Implementation fun annexB(payload: ByteArray) {
            HostNative.call("airplay.NativeScreenCodec", "annexB", payload)
        }

        @JvmStatic @Implementation fun config(payload: ByteArray): IntArray =
            HostNative.call("airplay.NativeScreenCodec", "config", payload) as IntArray

        @JvmStatic @Implementation fun avcParameterSets(payload: ByteArray): Array<ByteArray> =
            HostNative.call("airplay.NativeScreenCodec", "avcParameterSets", payload) as Array<ByteArray>

        @JvmStatic @Implementation fun hevcCsd(payload: ByteArray): ByteArray =
            HostNative.call("airplay.NativeScreenCodec", "hevcCsd", payload) as ByteArray

        @JvmStatic @Implementation fun annexBCopy(payload: ByteArray): ByteArray =
            HostNative.call("airplay.NativeScreenCodec", "annexBCopy", payload) as ByteArray

        @JvmStatic @Implementation fun randomAccess(payload: ByteArray, hevc: Boolean): Boolean =
            HostNative.call("airplay.NativeScreenCodec", "randomAccess", payload, hevc) as Boolean

    }
}
