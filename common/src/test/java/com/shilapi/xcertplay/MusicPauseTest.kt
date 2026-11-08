package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicPauseTest {
    private val enabled = MusicPauseTrigger.entries.toMutableSet()
    private var playing: Boolean? = true
    private var pauses = 0
    private val logs = mutableListOf<String>()
    private val policy = MusicPause({ it in enabled }, { playing }, { pauses++; true }, { logs += it })

    @Test fun pausesWhenTheDriverDoorOpensWhateverTheEngineDoes() {
        policy.onDriverDoor(false)
        for (usage in listOf(13, 11, 2, 1)) {
            policy.onUsageMode(usage)
            policy.onDriverDoor(true)
            policy.onDriverDoor(false)
        }
        // 13 → 11 is still running; 11 → 2 is the engine stopping.
        assertEquals(5, pauses)
        assertEquals("Music pause trigger=door sent=true", logs.first())
        // A repeated open reading is not a new opening.
        policy.onDriverDoor(true)
        policy.onDriverDoor(true)
        assertEquals(6, pauses)
    }

    @Test fun anOpenDoorAtStartIsAStateNotAnOpening() {
        policy.onDriverDoor(true)
        assertEquals(0, pauses)
    }

    @Test fun pausesWhenTheEngineStopsButNotOnLaterPowerChanges() {
        policy.onUsageMode(13)
        policy.onUsageMode(1)
        assertEquals(listOf("Music pause trigger=engine_off sent=true"), logs)
        // Convenience and back, or the first reading of a start, is not the engine stopping.
        policy.onUsageMode(2)
        policy.onUsageMode(1)
        assertEquals(1, pauses)
        val fresh = MusicPause({ true }, { true }, { pauses++; true }, {})
        fresh.onUsageMode(1)
        assertEquals(1, pauses)
    }

    @Test fun leavesMusicThatIsNotPlayingAndHonoursEachSetting() {
        policy.onDriverDoor(false)
        playing = false
        policy.onDriverDoor(true)
        policy.onDriverDoor(false)
        playing = null
        policy.onDriverDoor(true)
        policy.onDriverDoor(false)
        playing = true
        enabled -= MusicPauseTrigger.DOOR
        policy.onDriverDoor(true)
        policy.onUsageMode(13)
        policy.onUsageMode(1)
        assertEquals(1, pauses)
        assertEquals(listOf("Music pause trigger=door skipped iphonePlaying=false",
            "Music pause trigger=door skipped iphonePlaying=null", "Music pause trigger=door off",
            "Music pause trigger=engine_off sent=true"), logs)
    }

    @Test fun readsTheDriverDoorBytesTheCarReports() {
        assertEquals(true, MusicPause.doorOpen(byteArrayOf(8, 1, 0x20, 2)))
        assertEquals(false, MusicPause.doorOpen(byteArrayOf(8, 1, 0x20, 4)))
        assertNull(MusicPause.doorOpen(byteArrayOf(8, 1, 0x20, 0)))
        assertNull(MusicPause.doorOpen(null))
    }
}
