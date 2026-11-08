package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class SimulatedVehicleTest {
    @Test fun scenariosStayBoundedAndManualSpeedStopsAnimation() {
        var time = 0L
        val car = SimulatedVehicle { time }
        car.select(SimulatedVehicle.Scene.HIGHWAY)
        var distance = 0.0
        for (second in 0..120) {
            time = second * 1000L
            val v = car.reading()
            assertTrue(v.speed in 92.0..108.0)
            assertEquals("D", v.gear)
            assertTrue(v.rpm in 3000..4000)
            assertTrue(v.trip >= distance)
            distance = v.trip
        }
        car.speed(63.0); time += 50_000
        assertEquals(63.0, car.reading().speed, 0.0)
        car.change { it.copy(syncTemperature = true, leftTemperature = 24.0, leftTurn = true) }
        assertEquals(24.0, car.reading().rightTemperature, 0.0)
        assertTrue(car.reading().leftTurn)
        car.select(SimulatedVehicle.Scene.WARNINGS)
        assertTrue(car.reading().rightTurn)
        assertTrue(car.reading().doorOpen)
        car.select(SimulatedVehicle.Scene.PARK)
        assertFalse(car.reading().rightTurn)
        assertFalse(car.reading().doorOpen)
        assertEquals("P", car.reading().gear)
        assertThrows(IllegalArgumentException::class.java) { car.speed(Double.NaN) }
    }
}
