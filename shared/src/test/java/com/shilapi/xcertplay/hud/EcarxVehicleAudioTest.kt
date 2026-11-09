package com.shilapi.xcertplay.hud

import android.car.Car
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Duration
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EcarxVehicleAudioTest {
    class Audio {
        val usageGroups = mutableMapOf(1 to 0, 12 to 1, 2 to 4, 16 to 2)
        val levels = mutableMapOf(0 to 20, 1 to 30, 4 to 8, 2 to 12)
        val writes = mutableListOf<Pair<Int, Int>>()
        var denied = false
        var applyWrites = true
        fun getVolumeGroupIdForUsage(usage: Int) = usageGroups[usage] ?: -1
        fun getGroupMinVolume(group: Int) = if (group == 1) 10 else 0
        fun getGroupMaxVolume(group: Int) = if (group == 1) 50 else 39
        fun getGroupVolume(group: Int) = levels.getValue(group)
        fun setGroupVolume(group: Int, level: Int, flags: Int) {
            check(flags == 0)
            if (denied) throw SecurityException("volume permission denied")
            writes += group to level
            if (applyWrites) levels[group] = level
        }
    }

    @After fun reset() { Car.reset(); com.ecarx.xui.adaptapi.car.Car.instance = null }

    @Test fun convertsPercentUsingTheCorrectUsageGroupAndItsRealRange() {
        val manager = Audio()
        val source = EcarxVehicleAudio(manager) {}
        assertEquals(mapOf(2306L to 51, 2311L to 50, 2314L to 21, 2321L to 31), source.read())
        assertTrue(source.write(2311, 25L))
        assertEquals(1 to 20, manager.writes.last())
        assertEquals(25, source.read()[2311])
        assertTrue(source.write(2314, 100L))
        assertEquals(4 to 39, manager.writes.last())
        assertTrue(source.write(2321, 0L))
        assertEquals(2 to 0, manager.writes.last())
        assertTrue(source.write(2306, 50L))
        assertEquals(0 to 20, manager.writes.last())
        assertEquals(51, source.read()[2306])
    }

    @Test fun refusesInvalidUnavailableAndDeniedWrites() {
        val manager = Audio()
        val source = EcarxVehicleAudio(manager) {}
        for (value in listOf(-1, 101, 10.5, Double.NaN, true, "50")) assertFalse(source.write(2306, value))
        assertFalse(source.write(2309, true))
        assertTrue(manager.writes.isEmpty())
        manager.denied = true
        assertFalse(source.write(2306, 10L))
        assertTrue(manager.writes.isEmpty())
        manager.usageGroups.remove(12)
        assertFalse(source.read().containsKey(2311L))
        assertFalse(source.write(2311, 10L))
        manager.levels[0] = -1
        assertFalse(source.read().containsKey(2306L))
    }

    @Test fun sessionUsesReadbackAndStopsUsingTheManagerAfterDisconnect() {
        val manager = Audio().apply { applyWrites = false }
        Car.available = true
        Car.audio = manager
        com.ecarx.xui.adaptapi.car.Car.instance = EcarxVehicleStateTest.Vehicle()
        requireNotNull(EcarxVehicleState.create(RuntimeEnvironment.getApplication()) {}).use { source ->
            assertEquals(51, source.values()[2306])
            assertTrue(source.write(2306, 100L))
            assertEquals(0 to 39, manager.writes.last())
            assertEquals(51, source.values()[2306])
            manager.levels[0] = 39
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            assertEquals(100, source.values()[2306])
            Car.last!!.serviceDisconnected()
            assertFalse(source.values().containsKey(2306L))
            assertFalse(source.write(2306, 0L))
            Car.last!!.connect()
            assertEquals(100, source.values()[2306])
        }
        assertTrue(Car.last!!.disconnected)
    }
}
