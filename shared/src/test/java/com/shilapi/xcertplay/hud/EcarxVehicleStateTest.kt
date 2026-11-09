package com.shilapi.xcertplay.hud

import com.ecarx.xui.adaptapi.FunctionStatus
import com.ecarx.xui.adaptapi.binder.IConnectable
import com.ecarx.xui.adaptapi.car.Car
import com.ecarx.xui.adaptapi.car.ICar
import com.ecarx.xui.adaptapi.car.base.ICarFunction
import com.ecarx.xui.adaptapi.car.sensor.ISensor
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import android.car.Car as AndroidCar
import android.car.hardware.CarPropertyValue
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EcarxVehicleStateTest {
    class Vehicle : ICar, IConnectable, ICarFunction, ISensor {
        val readings = mutableMapOf(1048832 to 10f, 1050112 to 32000f, 1050368 to 23861f, 1050624 to 610f)
        val temperatures = mutableMapOf(1 to 22.5f, 4 to 23f)
        val functions = mutableMapOf(0x10020100 to 268566787, 0x10030100 to 268632322)
        val writes = mutableListOf<Triple<Int, Int, Number>>()
        var accepted = true
        var denied = false
        var status = FunctionStatus.active
        var ready = true
        var watcher: ICarFunction.IFunctionValueWatcher? = null
        var listener: ISensor.ISensorListener? = null
        var connection: IConnectable.IConnectWatcher? = null
        override fun getICarFunction() = this
        override fun getSensorManager() = this
        override fun isFunctionSupported(function: Int, zone: Int) = status
        override fun getFunctionValue(function: Int, zone: Int) = functions[function] ?: 0
        override fun getCustomizeFunctionValue(function: Int, zone: Int) = temperatures.getValue(zone)
        override fun getSupportedFunctionValue(function: Int) = when (function) {
            0x10020100 -> intArrayOf(0, 268566785, 268566786, 268566787, 268566788, 268566789, 268566790, 268566791)
            0x10050200 -> intArrayOf(0, 268763649, 268763650, 268763651)
            0x10030100 -> intArrayOf(268632321, 268632322)
            else -> intArrayOf(0, 1)
        }
        override fun setFunctionValue(function: Int, value: Int) = setFunctionValue(function, 0, value)
        override fun setFunctionValue(function: Int, zone: Int, value: Int): Boolean {
            if (denied) throw SecurityException("car permission denied")
            writes += Triple(function, zone, value)
            return accepted
        }
        override fun setCustomizeFunctionValue(function: Int, zone: Int, value: Float): Boolean {
            writes += Triple(function, zone, value)
            return accepted
        }
        override fun registerFunctionValueWatcher(functions: IntArray, watcher: ICarFunction.IFunctionValueWatcher): Boolean {
            this.watcher = watcher
            return true
        }
        override fun unregisterFunctionValueWatcher(watcher: ICarFunction.IFunctionValueWatcher): Boolean { this.watcher = null; return true }
        override fun isSensorSupported(sensor: Int) = status
        override fun getSensorLatestValue(sensor: Int) = readings.getValue(sensor)
        override fun registerListener(listener: ISensor.ISensorListener, sensor: Int, rate: Int): Boolean { this.listener = listener; return true }
        override fun unregisterListener(listener: ISensor.ISensorListener) { this.listener = null }
        override fun registerConnectWatcher(watcher: IConnectable.IConnectWatcher) { connection = watcher; if (ready) watcher.onConnected() }
        override fun unregisterConnectWatcher() { connection = null }
    }

    @After fun reset() { Car.instance = null; AndroidCar.reset() }

    private fun pressure(value: Double, availability: Int = 1): ByteArray =
        ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN).put(8).put(availability.toByte()).put(41).putDouble(value).array()

    @Test fun usesLiveUnitsAndSdkEnumsWithoutOptimisticWrites() {
        val car = Vehicle().also { Car.instance = it }
        requireNotNull(EcarxVehicleState.create(RuntimeEnvironment.getApplication()) {}).use { source ->
            assertEquals("36.0 km/h", source.values()[1284])
            assertEquals("32.0 L", source.values()[1292])
            assertEquals("23861 km", source.values()[1300])
            assertEquals("610 km", source.values()[1308])
            assertEquals(22.5, source.values()[520])
            assertEquals(3, source.values()[525])
            assertFalse(source.values()[528] as Boolean)
            assertTrue(car.writes.isEmpty())
            assertTrue(source.write(525, 5L))
            assertEquals(Triple(0x10020100, 0, 268566789), car.writes.last())
            assertEquals(3, source.values()[525])
            assertTrue(source.write(534, 24.5))
            assertEquals(Triple(0x10060100, 4, 24.5f), car.writes.last())
            assertEquals(23.0, source.values()[534])
            assertTrue(source.write(1026, 2L))
            assertEquals(Triple(0x10050200, 1, 268763650), car.writes.last())
            assertTrue(source.write(528, true))
            assertEquals(Triple(0x10030100, 0, 268632321), car.writes.last())
            assertTrue(source.write(545, 1L))
            assertEquals(Triple(0x10040300, 0, 1), car.writes.last())
            car.temperatures[4] = 24.5f
            car.watcher!!.onCustomizeFunctionValueChanged(0x10060100, 4, 24.5f)
            assertEquals(24.5, source.values()[534])
            assertEquals(setOf(1280, 512, 1024, 2304, 2048, 2560, 4096, 4352), source.configuration().map { it["iid"] }.toSet())
        }
        assertNull(car.listener); assertNull(car.watcher); assertNull(car.connection)
    }

    @Test fun rejectsInvalidOrUnavailableWritesAndClearsReadingsOnDisconnect() {
        val car = Vehicle().also { Car.instance = it }
        requireNotNull(EcarxVehicleState.create(RuntimeEnvironment.getApplication()) {}).use { source ->
            for ((id, value) in listOf(525L to 8, 525L to 2.5, 520L to Double.NaN,
                520L to 24.25, 520L to 32, 514L to 1, 1026L to 4, 4364L to 90500)) assertFalse(source.write(id, value))
            assertTrue(car.writes.isEmpty())
            car.accepted = false
            assertFalse(source.write(514, true))
            car.denied = true
            assertFalse(source.write(514, true))
            car.denied = false
            car.status = FunctionStatus.notavailable
            assertFalse(source.write(514, true))
            car.listener!!.onSensorSupportChanged(1048832, car.status)
            assertEquals("车辆数据不可用", source.values()[1284])
            car.connection!!.onDisConnected()
            assertFalse(source.values().containsKey(520L))
            assertEquals("车辆数据不可用", source.values()[1292])
            assertFalse(source.write(520, 22.0))
            car.status = FunctionStatus.active
            car.connection!!.onConnected()
            assertEquals(22.5, source.values()[520])
            source.close()
            assertFalse(source.write(520, 22.0))
        }
    }

    @Test fun logsWhichSignalsTheCarFillsOnlyWhenThatChanges() {
        Vehicle().also { Car.instance = it }
        val logs = mutableListOf<String>()
        requireNotNull(EcarxVehicleState.create(RuntimeEnvironment.getApplication()) { logs += it }).use { source ->
            repeat(3) { source.values() }
            val availability = logs.filter { it.startsWith("Vehicle state ECARX availability") }
            assertEquals(1, availability.size)
            // The fake car fills the AdaptAPI sensors but no car-service RPM or tire property.
            assertTrue(availability.single(), availability.single().contains("present=[speed, fuel, odometer, range"))
            assertTrue(availability.single(), availability.single().contains("rpm") &&
                availability.single().contains("tires=0/4"))
        }
    }

    @Test fun waitsForTheSdkConnectionWithoutFillingInSimulatedValues() {
        val car = Vehicle().apply { ready = false; Car.instance = this }
        requireNotNull(EcarxVehicleState.create(RuntimeEnvironment.getApplication()) {}).use { source ->
            assertFalse(source.values().containsKey(520L))
            assertEquals("车辆数据不可用", source.values()[1284])
            assertTrue(car.writes.isEmpty())
            car.connection!!.onConnected()
            assertEquals("36.0 km/h", source.values()[1284])
        }
    }

    @Test fun readsRpmAndTiresFromPropertiesAndInvalidatesUnavailableSignals() {
        Vehicle().also { Car.instance = it }
        AndroidCar.available = true
        AndroidCar.seeded[EcarxVehicleState.RPM_PROPERTY] = 1250.5f
        for (index in 0..3) AndroidCar.seeded[EcarxVehicleState.TIRE_PROPERTY + index] = pressure(230.0 + index)
        val source = requireNotNull(EcarxVehicleState.create(RuntimeEnvironment.getApplication()) {})
        val properties = AndroidCar.last!!.properties
        val listener = properties.listener!!
        assertEquals(listOf(291504901, 560992339, 560992340, 560992341, 560992342), properties.listenedProperties)
        assertEquals(1251, source.values()[2562])
        assertEquals("1251 r/min", source.values()[1316])
        assertEquals(listOf(230, 231, 232, 233), (0..3).map { source.values()[2050L + it * 5] })
        assertEquals("230 / 231 / 232 / 233 kPa", source.values()[1324])
        listener.onChangeEvent(CarPropertyValue(560992339, pressure(240.0, 0)))
        assertFalse(source.values().containsKey(2050L))
        listener.onChangeEvent(CarPropertyValue(560992340, pressure(Double.NaN)))
        assertFalse(source.values().containsKey(2055L))
        listener.onChangeEvent(CarPropertyValue(291504901, 1500f, 1))
        assertFalse(source.values().containsKey(2562L))
        assertEquals("车辆数据不可用", source.values()[1316])
        listener.onErrorEvent(560992341, 0)
        assertFalse(source.values().containsKey(2060L))
        listener.onChangeEvent(CarPropertyValue(560992342, byteArrayOf(1, 2, 3)))
        assertEquals("车辆数据不可用", source.values()[1324])
        listener.onChangeEvent(CarPropertyValue(291504901, 0f))
        assertEquals(0, source.values()[2562])
        AndroidCar.last!!.serviceDisconnected()
        assertFalse(source.values().containsKey(2562L))
        AndroidCar.last!!.connect()
        assertEquals(1251, source.values()[2562])
        source.close()
        assertNull(properties.listener)
        assertTrue(AndroidCar.last!!.disconnected)
        listener.onChangeEvent(CarPropertyValue(291504901, 2500f))
        assertFalse(source.values().containsKey(2562L))
    }

    @Test fun syncUsesFirmwarePolarityAndAdasSettingsStayReadOnly() {
        val car = Vehicle().also { Car.instance = it }
        car.functions[0x10060500] = 1
        car.functions[537329920] = 1
        requireNotNull(EcarxVehicleState.create(RuntimeEnvironment.getApplication()) {}).use { source ->
            assertEquals(true, source.values()[538])
            assertEquals("开启", source.values()[1332])
            assertEquals("关闭", source.values()[1340])
            assertFalse(source.write(1332, false))
            assertFalse(source.write(538, 1L))
            assertTrue(car.writes.isEmpty())
            assertTrue(source.write(538, false))
            assertEquals(Triple(0x10060500, 0, 0), car.writes.last())
            assertEquals(true, source.values()[538])
            car.functions[0x10060500] = 0
            car.watcher!!.onFunctionValueChanged(0x10060500, 0, 0)
            assertEquals(false, source.values()[538])
            car.functions[537329920] = 255
            car.watcher!!.onFunctionValueChanged(537329920, 0, 255)
            assertEquals("车辆数据不可用", source.values()[1332])
            val ids = source.configuration().flatMap { accessory ->
                listOf(accessory["iid"]) + (accessory["services"] as List<*>).flatMap { value ->
                    val service = value as Map<*, *>
                    listOf(service["iid"]) + (service["characteristics"] as List<*>).map { (it as Map<*, *>)["iid"] } +
                        (service["controls"] as? List<*>).orEmpty().map { (it as Map<*, *>)["iid"] }
                }
            }
            assertEquals("all accessory, service, field and control IDs are unique", ids.size, ids.toSet().size)
        }
    }
}
