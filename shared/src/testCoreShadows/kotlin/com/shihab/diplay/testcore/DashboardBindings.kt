package com.shihab.diplay.testcore

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(className = "com.shihab.diplay.orchestration.NativeControllerDashboard", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeControllerDashboard {
    companion object {
        @JvmStatic @Implementation fun create(url: String?): LongArray =
            HostNative.call("orchestration.NativeControllerDashboard", "create", url) as LongArray
        @JvmStatic @Implementation fun transition(token: LongArray, operation: Int, url: String?, first: Boolean, second: Boolean, stream: Int): LongArray =
            HostNative.call("orchestration.NativeControllerDashboard", "transition", token, operation, url, first, second, stream) as LongArray
        @JvmStatic @Implementation fun query(token: LongArray, operation: Int, first: Boolean, second: Boolean): Long =
            HostNative.call("orchestration.NativeControllerDashboard", "query", token, operation, first, second) as Long
        @JvmStatic @Implementation fun acceptedUrl(token: LongArray): String? =
            HostNative.call("orchestration.NativeControllerDashboard", "acceptedUrl", token) as String?
        @JvmStatic @Implementation fun route(token: LongArray, sameStream: Boolean, stream: Int, url: String?, closed: Boolean): Long =
            HostNative.call("orchestration.NativeControllerDashboard", "route", token, sameStream, stream, url, closed) as Long
        @JvmStatic @Implementation fun permits(url: String?, physical: Boolean, stream: Int, shown: Boolean, closed: Boolean): Boolean =
            HostNative.call("orchestration.NativeControllerDashboard", "permits", url, physical, stream, shown, closed) as Boolean
    }
}
