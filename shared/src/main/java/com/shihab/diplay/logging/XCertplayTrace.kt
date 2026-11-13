package com.shihab.diplay.logging

import android.util.Log

/**
 * Gate for high-volume protocol traces.
 *
 * Frame/RTSP payload traces on the legacy `xcertplay-usb` logcat tag are DEBUG-gated through a
 * separate property tag, so they stay off by default. Enable on demand with:
 *
 *   adb shell setprop log.tag.xcertplay-trace DEBUG
 *
 * `setprop log.tag.xcertplay-trace WARN` (or a reboot) turns it back off. The logcat target stays
 * "xcertplay-usb" so existing filters keep working. Errors and lifecycle events must not go
 * through this object; they must remain visible without changing any property.
 */
object XCertplayTrace {
    const val LOGCAT_TAG = "xcertplay-usb"
    private const val TRACE_TAG = "xcertplay-trace"

    val enabled: Boolean
        get() = Log.isLoggable(TRACE_TAG, Log.DEBUG)

    fun log(message: String) {
        if (enabled) Log.d(LOGCAT_TAG, message)
    }

    fun log(message: String, error: Throwable) {
        if (enabled) Log.d(LOGCAT_TAG, message, error)
    }
}
