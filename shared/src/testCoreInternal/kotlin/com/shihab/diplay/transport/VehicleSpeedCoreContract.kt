package com.shihab.diplay.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue

object VehicleSpeedCoreContract {
    fun verify() {
        fun snapshot(state: LongArray) = NativeVehicleSpeed.speedSnapshot(state)
        for (gear in VehicleGear.entries) {
            var state = NativeVehicleSpeed.speedGear(longArrayOf(), gear.ordinal)
            state = NativeVehicleSpeed.speedSample(state, 1000, 10.0)
            state = NativeVehicleSpeed.speedSample(state, 1250, 0.0)
            assertArrayEquals(doubleArrayOf(gear.ordinal.toDouble(), 1000.0, 10.0, 1250.0, 0.0), snapshot(state), 0.0)
            assertTrue(snapshot(NativeVehicleSpeed.speedGear(state, -1)).isEmpty())
            for (other in VehicleGear.entries.filter { it != gear }) {
                assertTrue(snapshot(NativeVehicleSpeed.speedGear(state, other.ordinal)).isEmpty())
            }
            for (bad in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 125.1)) {
                assertTrue(snapshot(NativeVehicleSpeed.speedSample(state, 1300, bad)).isEmpty())
            }
            assertTrue(snapshot(NativeVehicleSpeed.speedSample(state, 999, 1.0)).isEmpty())
        }
        var bounded = NativeVehicleSpeed.speedGear(longArrayOf(), VehicleGear.DRIVE.ordinal)
        repeat(1024) { bounded = NativeVehicleSpeed.speedSample(bounded, it.toLong(), 125.0) }
        val values = snapshot(bounded)
        assertTrue(values.size == 1025)
        assertArrayEquals(doubleArrayOf(3.0, 512.0, 125.0), values.take(3).toDoubleArray(), 0.0)
    }
}
