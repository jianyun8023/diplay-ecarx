package com.shilapi.xcertplay.hud

import android.car.Car
import android.car.hardware.CarPropertyValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EcarxVehicleSignalProbeTest {
    @After fun clearCar() = Car.reset()

    private fun probe(logs: MutableList<String>) =
        EcarxVehicleSignalProbe(RuntimeEnvironment.getApplication()) { logs += it }

    @Test fun logsCurrentValuesAndListensToEverySignal() {
        Car.available = true
        Car.seeded[557843202] = 13
        Car.seeded[560993300] = byteArrayOf(8, 1, 0x20, 2)
        val logs = mutableListOf<String>()
        val probe = probe(logs)
        assertTrue(probe.start())
        val properties = Car.last!!.properties
        assertEquals(EcarxVehicleSignalProbe.SIGNALS.map { it.id }, properties.listenedProperties)
        assertTrue(logs.toString(), "Vehicle signal usageMode current raw=13" in logs)
        assertTrue(logs.toString(), "Vehicle signal doorDriver current raw=08012002" in logs)
        // An unreadable signal says why and is still listened to.
        assertTrue(logs.toString(), logs.any { it.startsWith("Vehicle signal doorPassenger current failed: IllegalStateException") })

        properties.listener!!.onChangeEvent(CarPropertyValue(557843202, 2))
        properties.listener!!.onChangeEvent(CarPropertyValue(560993300, byteArrayOf(8, 1, 0x20, 4)))
        properties.listener!!.onErrorEvent(560993302, 0)
        assertTrue(logs.toString(), "Vehicle signal usageMode changed raw=2" in logs)
        assertTrue(logs.toString(), "Vehicle signal doorDriver changed raw=08012004" in logs)
        assertTrue(logs.toString(), "Vehicle signal doorPassenger error area=0" in logs)

        probe.stop()
        assertNull(properties.listener)
        assertTrue(Car.last!!.disconnected)
    }

    @Test fun reportsAMissingCarService() {
        assertFalse(probe(mutableListOf()).start())
    }

    @Test fun rendersValuesForTheReport() {
        assertEquals("null", EcarxVehicleSignalProbe.render(null))
        assertEquals("0a00ff", EcarxVehicleSignalProbe.render(byteArrayOf(10, 0, -1)))
        assertEquals("[1,2]", EcarxVehicleSignalProbe.render(intArrayOf(1, 2)))
        assertEquals("id=7", EcarxVehicleSignalProbe.nameOf(7))
    }
}
