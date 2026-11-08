package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LeaveCarPauseTest {
    private var enabled = true
    private var playing: Boolean? = true
    private var pauses = 0
    private val logs = mutableListOf<String>()
    private val policy = LeaveCarPause({ enabled }, { playing }, { pauses++; true }, { logs += it })

    private fun driveThenStop() {
        policy.onUsageMode(13)
        policy.onDriverDoor(false)
        policy.onUsageMode(LeaveCarPause.USAGE_INACTIVE)
    }

    @Test fun pausesWhenTheDriverDoorOpensAfterTheEngineStops() {
        driveThenStop()
        policy.onDriverDoor(true)
        assertEquals(1, pauses)
        assertEquals(listOf("Leave-car pause sent=true"), logs)
        // Closing and opening again in the same state is not a new departure while the door event repeats.
        policy.onDriverDoor(true)
        assertEquals(1, pauses)
    }

    @Test fun doesNothingWhileDrivingOrInConvenienceMode() {
        policy.onUsageMode(13)
        policy.onDriverDoor(false)
        policy.onDriverDoor(true)
        policy.onDriverDoor(false)
        // Convenience: the link stays up and the car's media center pauses on its own.
        policy.onUsageMode(2)
        policy.onDriverDoor(true)
        assertEquals(0, pauses)
    }

    @Test fun anOpenDoorAtStartIsAStateNotAnOpening() {
        policy.onUsageMode(LeaveCarPause.USAGE_INACTIVE)
        policy.onDriverDoor(true)
        assertEquals(0, pauses)
    }

    @Test fun leavesMusicThatIsNotPlayingAndHonoursTheSetting() {
        driveThenStop()
        playing = false
        policy.onDriverDoor(true)
        policy.onDriverDoor(false)
        playing = null
        policy.onDriverDoor(true)
        policy.onDriverDoor(false)
        enabled = false
        playing = true
        policy.onDriverDoor(true)
        assertEquals(0, pauses)
        assertEquals(listOf("Leave-car pause skipped iphonePlaying=false",
            "Leave-car pause skipped iphonePlaying=null", "Leave-car pause off"), logs)
    }

    @Test fun readsTheDriverDoorBytesTheCarReports() {
        assertEquals(true, LeaveCarPause.doorOpen(byteArrayOf(8, 1, 0x20, 2)))
        assertEquals(false, LeaveCarPause.doorOpen(byteArrayOf(8, 1, 0x20, 4)))
        assertNull(LeaveCarPause.doorOpen(byteArrayOf(8, 1, 0x20, 0)))
        assertNull(LeaveCarPause.doorOpen(null))
    }
}
