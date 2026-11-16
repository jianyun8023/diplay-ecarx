package com.shihab.diplay.testcore

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Suppress("UNCHECKED_CAST")
@Implements(className = "com.shihab.diplay.airplay.NativeClusterContent", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeClusterContent {
    companion object {
        @JvmStatic @Implementation fun create(initialUrl: String?): LongArray =
            HostNative.call("airplay.NativeClusterContent", "create", initialUrl) as LongArray
        @JvmStatic @Implementation fun change(token: LongArray, event: Int, stream: Int, url: String?, flags: Int, expectedStream: Int): Array<Any?> =
            HostNative.call("airplay.NativeClusterContent", "change", token, event, stream, url, flags, expectedStream) as Array<Any?>
        @JvmStatic @Implementation fun route(token: LongArray, closed: Boolean, stream: Int): Array<Any?>? =
            HostNative.call("airplay.NativeClusterContent", "route", token, closed, stream) as Array<Any?>?
    }
}
