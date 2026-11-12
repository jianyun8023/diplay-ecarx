package com.shilapi.xcertplay.testcore

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(className = "com.shilapi.xcertplay.airplay.NativeScreenCodec", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeScreenCodec {
    companion object {
        @JvmStatic @Implementation fun senderNanos(header: ByteArray): Long =
            HostNative.call("airplay.NativeScreenCodec", "senderNanos", header) as Long

        @JvmStatic @Implementation fun annexB(payload: ByteArray) {
            HostNative.call("airplay.NativeScreenCodec", "annexB", payload)
        }

        @JvmStatic @Implementation fun config(payload: ByteArray): IntArray =
            HostNative.call("airplay.NativeScreenCodec", "config", payload) as IntArray
    }
}
