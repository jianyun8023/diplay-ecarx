package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.CarPlayMediaButton

/** What can pause CarPlay music; each one is its own setting, all off by default. */
internal enum class MusicPauseTrigger(val key: String) {
    /** The driver door opens, whatever the engine is doing. */
    DOOR("music_pause_door"),
    /** The engine stops: the car leaves active or driving. */
    ENGINE_OFF("music_pause_engine_off"),
    /** Android turns the screen off or shuts down. */
    SCREEN_OFF("music_pause_screen_off"),
}

/**
 * Pauses the iPhone on the triggers HY's head-unit app offers, which the car's own media apps do
 * not: they keep playing through ignition off and only remember whether music was playing before
 * the power went, to resume it on the next drive (`BTMusicManager`, `OpenApiCarHelper`). So all
 * three stay off unless the driver turns them on, and nothing is resumed here.
 *
 * On this car the first door opening after the engine stops turns the hotspot and Bluetooth off
 * about 20 ms later (v139 report), so the door trigger also stops the music before CarPlay drops.
 * Its dark screen is drawn by the car while Android stays on, so the screen trigger only fires on
 * a real Android screen off.
 */
internal class MusicPause(
    private val enabled: (MusicPauseTrigger) -> Boolean,
    /** The iPhone's state on the live session, null without one. */
    private val iphonePlaying: () -> Boolean?,
    private val pause: () -> Boolean,
    private val log: (String) -> Unit,
) {
    private var usageMode: Int? = null
    private var driverDoorOpen: Boolean? = null

    fun onUsageMode(value: Int) {
        val previous = usageMode
        usageMode = value
        if (previous != null && previous >= USAGE_ACTIVE && value < USAGE_ACTIVE) fire(MusicPauseTrigger.ENGINE_OFF)
    }

    fun onDriverDoor(open: Boolean) {
        val wasOpen = driverDoorOpen
        driverDoorOpen = open
        // The first reading of a start is a state, not an opening.
        if (open && wasOpen == false) fire(MusicPauseTrigger.DOOR)
    }

    fun onScreenOff() = fire(MusicPauseTrigger.SCREEN_OFF)

    private fun fire(trigger: MusicPauseTrigger) {
        val name = trigger.name.lowercase()
        if (!enabled(trigger)) return log("Music pause trigger=$name off")
        val playing = iphonePlaying()
        if (playing != true) return log("Music pause trigger=$name skipped iphonePlaying=$playing")
        log("Music pause trigger=$name sent=${pause()}")
    }

    companion object {
        /** `UsgModSts`: 0 abandon, 1 inactive (engine off), 2 convenience, 11 active, 13 driving. */
        const val USAGE_ACTIVE = 11

        /** `PA_CP_DoorDrvrSts` ends in 4 while closed (seen while driving) and 2 while open. */
        fun doorOpen(raw: ByteArray?): Boolean? = when (raw?.lastOrNull()?.toInt()) {
            2 -> true
            4 -> false
            else -> null
        }
    }
}

internal object MusicPauseSettings {
    fun enabled(context: Context, trigger: MusicPauseTrigger): Boolean =
        AirPlayPersistence.preferences(context).getBoolean(trigger.key, false)

    fun setEnabled(context: Context, trigger: MusicPauseTrigger, value: Boolean) {
        AirPlayPersistence.preferences(context).edit().putBoolean(trigger.key, value).apply()
    }

    /** The process-wide policy fed by the screen broadcasts and the car's usage mode and driver door. */
    fun policy(context: Context, log: (String) -> Unit): MusicPause {
        val app = context.applicationContext
        return MusicPause(
            enabled = { enabled(app, it) },
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
