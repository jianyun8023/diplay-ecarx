package com.shihab.diplay.hud

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import com.shihab.diplay.airplay.NativeMedia
import com.shihab.diplay.transport.NativeVehicleSpeed
import com.shihab.diplay.transport.VehicleGear
import com.shihab.diplay.transport.VehicleSpeedReading
import com.shihab.diplay.transport.VehicleSpeedSample
import com.shihab.diplay.transport.VehicleSpeedSource

/** Read-only 753 wheel-speed and selected-gear SDK adapter. No GPS or simulated substitute. */
class EcarxVehicleSpeedSource(
    context: Context,
    private val log: (String) -> Unit,
    private val elapsedMillis: () -> Long = SystemClock::elapsedRealtime,
) : VehicleSpeedSource {
    /**
     * The scheduling thread that runs every SDK callback and read. Production uses a
     * [HandlerThread]; a Robolectric test replaces it with the main looper to drive it
     * deterministically. Internal so it is not part of the platform API.
     */
    internal class Worker(val handler: Handler, val quit: () -> Unit)

    internal var newWorker: (String) -> Worker = { name ->
        val thread = HandlerThread(name).apply { start() }
        Worker(Handler(thread.looper), thread::quitSafely)
    }

    private val context = context.applicationContext
    private var run: Run? = null
    private var state = longArrayOf()
    private var diagnostic = "not started"

    private class Run(worker: Worker) {
        private val quitWorker = worker.quit
        val handler = worker.handler
        fun quit() = quitWorker()
        var car: Any? = null
        var sensors: Any? = null
        var sensorListener: Any? = null
        var connected = false
        var propertyCar: Any? = null
        var properties: Any? = null
        var propertyListener: Any? = null
        var gear: VehicleGear? = null
    }

    @Synchronized override fun start() {
        if (run != null) return
        NativeMedia.requireAvailable()
        val next = Run(newWorker("CarPlayWheelSpeed"))
        run = next
        state = longArrayOf()
        diagnostic = "starting"
        next.handler.post {
            if (!current(next)) return@post
            runCatching { open(next) }.onFailure {
                log("Vehicle wheel speed unavailable: ${it.cause ?: it}")
                synchronized(this) { if (run === next) { run = null; state = longArrayOf(); diagnostic = "SDK unavailable" } }
                release(next)
                next.quit()
            }
        }
    }

    @Synchronized override fun stop() {
        val previous = run ?: return
        run = null
        state = longArrayOf()
        diagnostic = "stopped"
        previous.handler.post { release(previous); previous.quit() }
    }

    @Synchronized override fun drain(): VehicleSpeedReading? {
        val active = run ?: return null
        val values = NativeVehicleSpeed.speedSnapshot(state)
        state = NativeVehicleSpeed.speedGear(longArrayOf(), active.gear?.ordinal ?: -1)
        if (values.isEmpty()) return null
        return VehicleSpeedReading(VehicleGear.entries[values[0].toInt()],
            (1 until values.size step 2).map { VehicleSpeedSample(values[it].toLong(), values[it + 1]) })
    }

    @Synchronized override fun status(): String = diagnostic
    @Synchronized private fun current(candidate: Run) = run === candidate

    private fun post(candidate: Run, action: () -> Unit) {
        candidate.handler.post { if (current(candidate)) action() }
    }

    private fun open(active: Run) {
        val carType = Class.forName(ADAPT_CAR)
        val vehicle = requireNotNull(carType.getMethod("create", Context::class.java).invoke(null, context))
        active.car = vehicle
        active.sensors = requireNotNull(Class.forName(ICAR).getMethod("getSensorManager").invoke(vehicle))
        val sensorListenerType = Class.forName("$SENSOR\$ISensorListener")
        val sensorListener = EcarxDimApi.proxy(sensorListenerType, "DiPlayWheelSpeed") { method, args ->
            if (args?.firstOrNull() == SPEED) post(active) {
                when (method) {
                    "onSensorValueChanged", "onSensorSupportChanged" -> readSpeed(active)
                }
            }
            null
        }
        active.sensorListener = sensorListener
        check(Class.forName(SENSOR).getMethod("registerListener", sensorListenerType, INT, INT)
            .invoke(active.sensors, sensorListener, SPEED, 3) == true) { "speed observer refused" }
        val connectType = Class.forName(CONNECT)
        val watcherType = Class.forName("$CONNECT\$IConnectWatcher")
        val watcher = EcarxDimApi.proxy(watcherType, "DiPlayWheelSpeedConnection") { method, _ ->
            post(active) {
                active.connected = method == "onConnected"
                if (active.connected) {
                    synchronized(this) {
                        if (run === active) state = NativeVehicleSpeed.speedGear(longArrayOf(), active.gear?.ordinal ?: -1)
                    }
                    readSpeed(active)
                } else synchronized(this) {
                    if (run === active) { state = longArrayOf(); diagnostic = "speed service disconnected" }
                }
            }
            null
        }
        connectType.getMethod("registerConnectWatcher", watcherType).invoke(vehicle, watcher)

        val listenerType = Class.forName(PROPERTY_LISTENER)
        active.propertyListener = EcarxDimApi.proxy(listenerType, "DiPlayWheelGear") { method, args ->
            when (method) {
                "onChangeEvent" -> args?.firstOrNull()?.let { value -> post(active) {
                    if (active.properties != null) readGear(active, value)
                } }
                "onErrorEvent" -> if (args?.firstOrNull() == GEAR) post(active) { updateGear(active, null) }
            }
            null
        }
        fun attach() {
            if (!current(active) || active.properties != null) return
            val car = active.propertyCar ?: return
            val properties = requireNotNull(car.javaClass.getMethod("getCarManager", String::class.java).invoke(car, "property"))
            active.properties = properties
            val value = runCatching { properties.javaClass.getMethod("getProperty", Class::class.java, INT, INT)
                .invoke(properties, Int::class.javaObjectType, GEAR, 0) }.getOrNull()
            if (value == null) updateGear(active, null) else readGear(active, value)
            check(properties.javaClass.getMethod("registerListener", listenerType, INT, Float::class.javaPrimitiveType)
                .invoke(properties, active.propertyListener, GEAR, 0f) == true) { "gear observer refused" }
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                post(active) { runCatching { attach() }.onFailure {
                    updateGear(active, null); log("Vehicle wheel gear unavailable: ${it.cause ?: it}")
                } }
            }
            override fun onServiceDisconnected(name: ComponentName?) = post(active) {
                active.properties = null
                updateGear(active, null)
            }
        }
        val propertyCar = requireNotNull(Class.forName(CAR).getMethod("createCar", Context::class.java,
            ServiceConnection::class.java, Handler::class.java).invoke(null, context, connection, active.handler))
        active.propertyCar = propertyCar
        propertyCar.javaClass.getMethod("connect").invoke(propertyCar)
        if (propertyCar.javaClass.getMethod("isConnected").invoke(propertyCar) == true) attach()
    }

    private fun readGear(active: Run, event: Any) {
        val type = event.javaClass
        if (type.getMethod("getPropertyId").invoke(event) != GEAR) return
        val raw = if (type.getMethod("getStatus").invoke(event) == 0) type.getMethod("getValue").invoke(event) as? Int else null
        updateGear(active, when (raw) {
            4 -> VehicleGear.PARK; 2 -> VehicleGear.REVERSE; 1 -> VehicleGear.NEUTRAL; 8 -> VehicleGear.DRIVE
            else -> null
        })
    }

    private fun updateGear(active: Run, gear: VehicleGear?) {
        synchronized(this) {
            if (run !== active) return
            active.gear = gear
            state = NativeVehicleSpeed.speedGear(state, gear?.ordinal ?: -1)
            diagnostic = "gear=${gear?.letter ?: "unavailable"}"
        }
        // Read this gear's speed now instead of relabelling a sample collected in the old gear.
        readSpeed(active)
    }

    private fun readSpeed(active: Run) {
        if (!current(active) || !active.connected) return
        val value = runCatching {
            val type = Class.forName(SENSOR)
            if (type.getMethod("isSensorSupported", INT).invoke(active.sensors, SPEED)?.toString() != "active") null
            else (type.getMethod("getSensorLatestValue", INT).invoke(active.sensors, SPEED) as Number).toDouble()
        }.onFailure { log("Vehicle wheel speed read failed: ${it.cause ?: it}") }.getOrNull()
        synchronized(this) {
            if (run !== active) return
            state = NativeVehicleSpeed.speedSample(state, elapsedMillis(), value ?: Double.NaN)
            diagnostic = "speed=${if (value == null) "unavailable" else "received"} gear=${active.gear?.letter ?: "unavailable"}"
        }
    }

    private fun release(active: Run) {
        active.propertyListener?.let { listener -> active.properties?.let { properties ->
            runCatching { properties.javaClass.getMethod("unregisterListener", Class.forName(PROPERTY_LISTENER)).invoke(properties, listener) }
        } }
        active.propertyCar?.let { car -> runCatching { car.javaClass.getMethod("disconnect").invoke(car) } }
        active.sensorListener?.let { listener -> active.sensors?.let { sensors ->
            runCatching { Class.forName(SENSOR).getMethod("unregisterListener", Class.forName("$SENSOR\$ISensorListener")).invoke(sensors, listener) }
        } }
        active.car?.let { car -> runCatching { Class.forName(CONNECT).getMethod("unregisterConnectWatcher").invoke(car) } }
    }

    companion object {
        internal const val ADAPT_CAR = "com.ecarx.xui.adaptapi.car.Car"
        internal const val ICAR = "com.ecarx.xui.adaptapi.car.ICar"
        internal const val SENSOR = "com.ecarx.xui.adaptapi.car.sensor.ISensor"
        internal const val CONNECT = "com.ecarx.xui.adaptapi.binder.IConnectable"
        internal const val CAR = "android.car.Car"
        internal const val PROPERTY_LISTENER = "android.car.hardware.property.CarPropertyManager\$CarPropertyEventListener"
        internal const val POWERTRAIN_PERMISSION = "android.car.permission.CAR_POWERTRAIN"
        internal const val SPEED = 1048832
        internal const val GEAR = 289408000
        private val INT = Int::class.javaPrimitiveType!!

        fun supported(context: Context): Boolean =
            EcarxCapabilities.detect(context).identified && context.checkSelfPermission(POWERTRAIN_PERMISSION) == PackageManager.PERMISSION_GRANTED &&
                listOf(ADAPT_CAR, ICAR, SENSOR, CONNECT, CAR, PROPERTY_LISTENER).all(EcarxCapabilities::classPresent)
    }
}
