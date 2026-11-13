package com.shilapi.xcertplay.hud

import android.car.hardware.CarPropertyValue
import android.os.Handler
import android.os.Looper
import com.ecarx.xui.adaptapi.FunctionStatus
import com.ecarx.xui.adaptapi.binder.IConnectable
import com.ecarx.xui.adaptapi.car.ICar
import com.ecarx.xui.adaptapi.car.sensor.ISensor
import com.shilapi.xcertplay.transport.VehicleGear
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Drives the read-only 753 wheel-speed/gear adapter through fake AdaptAPI + VHAL surfaces.
 *
 * The real vendor interfaces are recovered from the firmware DTOs (see
 * `docs/ECARX_753_PLATFORM_NOTES.zh-CN.md`); these fixtures only reproduce their exact shapes so a
 * Robolectric run can prove subscription, gear mapping, drain, and disconnect recovery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EcarxVehicleSpeedSourceTest {
    private lateinit var vendor: FakeVendorCar
    private var now = 1_000L

    @Before fun setUp() {
        android.car.Car.reset()
        vendor = FakeVendorCar()
        com.ecarx.xui.adaptapi.car.Car.instance = vendor
        android.car.Car.available = true
        android.car.Car.seeded[GEAR] = 8 // CarSensorEvent.GEAR_DRIVE
    }

    @After fun tearDown() {
        android.car.Car.reset()
        com.ecarx.xui.adaptapi.car.Car.instance = null
    }

    private fun source(logs: MutableList<String> = mutableListOf()) =
        EcarxVehicleSpeedSource(RuntimeEnvironment.getApplication(), { logs += it }, { now }).apply {
            newWorker = { EcarxVehicleSpeedSource.Worker(Handler(Looper.getMainLooper())) {} }
        }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun reportsDriveSpeedAndGearInMetersPerSecond() {
        vendor.sensor.value = 12.5f
        val source = source()
        source.start()
        idle()

        // The vendor connect watcher reports the signal service ready after the initial setup.
        vendor.watcher!!.onConnected()
        idle()

        val reading = source.drain()
        assertEquals(VehicleGear.DRIVE, reading!!.gear)
        assertEquals(1, reading.samples.size)
        assertEquals(1_000L, reading.samples[0].elapsedMillis)
        assertEquals(12.5, reading.samples[0].metersPerSecond, 1e-9)

        // A value pushed by the SDK listener is sampled at its own monotonic timestamp.
        now = 2_000L
        vendor.sensor.value = 13.5f
        vendor.sensor.listener!!.onSensorValueChanged(SPEED, 13.5f)
        idle()
        val pushed = source.drain()!!
        assertEquals(13.5, pushed.samples.last().metersPerSecond, 1e-9)
        assertEquals(2_000L, pushed.samples.last().elapsedMillis)

        source.stop()
        idle()
        assertNull(source.drain())
        assertTrue(vendor.sensor.unregistered)
        assertTrue(android.car.Car.last!!.disconnected)
    }

    @Test fun mapsEverySelectedGearAndDropsUnknownOrErroredSelections() {
        val source = source()
        source.start()
        idle()
        vendor.watcher!!.onConnected()
        idle()

        val properties = android.car.Car.last!!.properties
        for ((raw, expected) in listOf(4 to VehicleGear.PARK, 2 to VehicleGear.REVERSE,
            1 to VehicleGear.NEUTRAL, 8 to VehicleGear.DRIVE)) {
            properties.listener!!.onChangeEvent(CarPropertyValue(GEAR, raw))
            idle()
            vendor.sensor.value = 3.0f
            vendor.sensor.listener!!.onSensorValueChanged(SPEED, 3.0f)
            idle()
            assertEquals(expected, source.drain()!!.gear)
        }

        // An unknown raw gear is not guessed into P or D; a non-zero status is not a selection.
        properties.listener!!.onChangeEvent(CarPropertyValue(GEAR, 0))
        idle()
        assertNull(source.drain())
        properties.listener!!.onChangeEvent(CarPropertyValue(GEAR, 8, 1))
        idle()
        assertNull(source.drain())
    }

    @Test fun resumesSamplingAfterTheServiceReconnects() {
        vendor.sensor.value = 5.0f
        val source = source()
        source.start()
        idle()
        vendor.watcher!!.onConnected()
        idle()
        assertEquals(5.0, source.drain()!!.samples.last().metersPerSecond, 1e-9)

        // Disconnect clears the retained batch; a stale value must not survive the gap.
        vendor.watcher!!.onDisConnected()
        idle()
        assertNull(source.drain())

        // Reconnecting with the same gear must resume sampling (the recovery path).
        vendor.watcher!!.onConnected()
        idle()
        now = 3_000L
        vendor.sensor.value = 7.0f
        vendor.sensor.listener!!.onSensorValueChanged(SPEED, 7.0f)
        idle()
        val reading = source.drain()!!
        assertEquals(7.0, reading.samples.last().metersPerSecond, 1e-9)
        assertEquals(VehicleGear.DRIVE, reading.gear)
    }

    @Test fun fallsBackToNoSpeedWhenTheSensorIsNotActive() {
        val source = source()
        source.start()
        idle()
        vendor.watcher!!.onConnected()
        idle()

        vendor.sensor.supported = FunctionStatus.notactive
        vendor.sensor.value = 9.0f
        vendor.sensor.listener!!.onSensorValueChanged(SPEED, 9.0f)
        idle()
        assertNull(source.drain())
        assertTrue(source.status()!!.contains("gear=D"))
    }

    @Test fun doesNotAdvertiseSupportOffTheCar() {
        // No ECARX identity, no CAR_POWERTRAIN grant, so config-time support stays false here.
        assertFalse(EcarxVehicleSpeedSource.supported(RuntimeEnvironment.getApplication()))
    }

    private class FakeSensor : ISensor {
        var value = 0f
        var supported = FunctionStatus.active
        var listener: ISensor.ISensorListener? = null
        var unregistered = false

        override fun isSensorSupported(sensor: Int): FunctionStatus = supported

        override fun getSensorLatestValue(sensor: Int): Float = value

        override fun registerListener(listener: ISensor.ISensorListener, sensor: Int, rate: Int): Boolean {
            this.listener = listener
            return true
        }

        override fun unregisterListener(listener: ISensor.ISensorListener) {
            this.listener = null
            unregistered = true
        }
    }

    private class FakeVendorCar : ICar, IConnectable {
        val sensor = FakeSensor()
        var watcher: IConnectable.IConnectWatcher? = null

        override fun getSensorManager(): ISensor = sensor

        override fun registerConnectWatcher(watcher: IConnectable.IConnectWatcher) {
            this.watcher = watcher
        }

        override fun unregisterConnectWatcher() {
            watcher = null
        }
    }

    private companion object {
        const val SPEED = 1048832
        const val GEAR = 289408000
    }
}
