package com.shihab.diplay

import android.content.Context
import android.view.KeyEvent

/** Shared by the foreground windows and the accessibility filter; media keys stay on their backend. */
internal class HySiriKeys {
    private val presses = WheelKeyPresses()

    fun handle(context: Context, event: KeyEvent, enabled: Boolean, calling: Boolean,
        request: () -> Boolean): Boolean? {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return null
        val physicalKey = Triple(event.deviceId, event.keyCode, event.scanCode)
        val owned = presses.hasConsumedPress(physicalKey)
        if (!owned && (!enabled || HySiriKey.entries.none { event.keyCode in it.codes })) return null
        return presses.filter(physicalKey, event.action == KeyEvent.ACTION_DOWN, event.repeatCount == 0) {
            if (!enabled || calling || event.keyCode !in MediaSessionSettings.hySiriKey(context).codes)
                WheelZoomKeys.Action.PASS
            else {
                WheelKeyLog.note("HY Siri key=${event.keyCode} sent=${request()}")
                WheelZoomKeys.Action.CONSUME
            }
        } != WheelZoomKeys.Action.PASS
    }
}
