package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.CarPlayMediaButton

/**
 * Engine off, then the driver door opens: the head unit turns its hotspot and Bluetooth off about
 * 20 ms later, which drops CarPlay instead of pausing it (v139 car report, 2026-11-08). Pausing
 * the iPhone at the door edge stops the music the way the car stops its own sources.
 *
 * Only the first door opening after the engine stops counts: once the car is in convenience mode
 * the link stays up and the car's media center sends its own pause. What happens on the next
 * drive is left to the iPhone and the car; this sends nothing on reconnect.
 */
internal class LeaveCarPause(
    private val enabled: () -> Boolean,
    /** The iPhone's state on the live session, null without one. */
    private val iphonePlaying: () -> Boolean?,
    private val pause: () -> Boolean,
    private val log: (String) -> Unit,
) {
    private var usageMode: Int? = null
    private var driverDoorOpen: Boolean? = null

    fun onUsageMode(value: Int) {
        usageMode = value
    }

    fun onDriverDoor(open: Boolean) {
        val wasOpen = driverDoorOpen
        driverDoorOpen = open
        // The first reading of a start is a state, not an opening.
        if (!open || wasOpen != false || usageMode != USAGE_INACTIVE) return
        if (!enabled()) return log("Leave-car pause off")
        val playing = iphonePlaying()
        if (playing != true) return log("Leave-car pause skipped iphonePlaying=$playing")
        log("Leave-car pause sent=${pause()}")
    }

    companion object {
        /** `UsgModSts`: 0 abandon, 1 inactive (engine off), 2 convenience, 11 active, 13 driving. */
        const val USAGE_INACTIVE = 1

        /** `PA_CP_DoorDrvrSts` ends in 4 while closed (seen while driving) and 2 while open. */
        fun doorOpen(raw: ByteArray?): Boolean? = when (raw?.lastOrNull()?.toInt()) {
            2 -> true
            4 -> false
            else -> null
        }
    }
}

internal object LeaveCarPauseSettings {
    private const val KEY = "leave_car_pause"

    fun enabled(context: Context): Boolean = AirPlayPersistence.preferences(context).getBoolean(KEY, true)

    fun setEnabled(context: Context, value: Boolean) {
        AirPlayPersistence.preferences(context).edit().putBoolean(KEY, value).apply()
    }

    /** The process-wide policy fed by the car's usage mode and driver door signals. */
    fun policy(context: Context, log: (String) -> Unit): LeaveCarPause {
        val app = context.applicationContext
        return LeaveCarPause(
            enabled = { enabled(app) },
            iphonePlaying = {
                if (!CarPlayBackgroundSession.active) null
                else CarPlayBackgroundSession.snapshot()?.controller?.nowPlayingSnapshot()?.playing
            },
            pause = {
                CarPlayBackgroundSession.snapshot()?.controller?.sendMediaButton(CarPlayMediaButton.PAUSE) == true
            },
            log = log,
        )
    }
}
