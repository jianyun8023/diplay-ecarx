package com.shihab.diplay.hud

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.lang.reflect.InvocationTargetException

/**
 * Read-only diagnostic watch of the ECARX power and door signals, so a report shows what the car
 * did when the screen went dark: the usage mode (abandon, inactive, convenience, active, driving)
 * and each door. Nothing here changes behavior; values are logged as the car service reports them.
 *
 * Property ids and value types come from the head unit's `GeelyVehicleProperty` and from the apps
 * that already read them on this car (HY reads the driver door, dudu reads all doors).
 */
class EcarxVehicleSignalProbe(
    context: Context,
    private val diagnostic: (String) -> Unit,
    /** Every value read or reported, by property id, on the car service handler (main). */
    private val onValue: (Int, Any?) -> Unit = { _, _ -> },
) {
    internal class Signal(val id: Int, val name: String, val type: Class<*>)

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var release: (() -> Unit)? = null

    /** Returns false when the car service cannot be reached; the reason is logged. */
    fun start(): Boolean {
        stop()
        return runCatching { connect() }.getOrElse {
            diagnostic("Vehicle signal probe failed: ${describe(it)}")
            false
        }
    }

    fun stop() {
        release?.let { runCatching { it() } }
        release = null
    }

    private fun connect(): Boolean {
        val listenerType = Class.forName(PROPERTY_LISTENER)
        val intType = Int::class.javaPrimitiveType!!
        var car: Any? = null
        var manager: Any? = null
        val listener = EcarxDimApi.proxy(listenerType, "EcarxVehicleSignalListener") { method, args ->
            when (method) {
                "onChangeEvent" -> args?.firstOrNull()?.let { event ->
                    val id = runCatching { event.javaClass.getMethod("getPropertyId").invoke(event) as? Int }.getOrNull()
                    val value = runCatching { event.javaClass.getMethod("getValue").invoke(event) }.getOrNull()
                    diagnostic("Vehicle signal ${nameOf(id)} changed raw=${render(value)}")
                    if (id != null) onValue(id, value)
                }
                "onErrorEvent" -> diagnostic("Vehicle signal ${nameOf(args?.firstOrNull() as? Int)} error " +
                    "area=${args?.getOrNull(1)}")
            }
            null
        }
        fun attach() {
            if (manager != null) return
            val connected = car ?: return
            val properties = runCatching {
                connected.javaClass.getMethod("getCarManager", String::class.java).invoke(connected, "property")
            }.getOrNull() ?: return diagnostic("Vehicle signal probe: no property manager")
            manager = properties
            val read = properties.javaClass.getMethod("getProperty", Class::class.java, intType, intType)
            val register = properties.javaClass.getMethod("registerListener", listenerType, intType,
                Float::class.javaPrimitiveType)
            for (signal in SIGNALS) {
                val current = runCatching { read.invoke(properties, signal.type, signal.id, 0) }
                    .map { value -> value?.let { runCatching { it.javaClass.getMethod("getValue").invoke(it) }.getOrNull() } }
                diagnostic("Vehicle signal ${signal.name} current " +
                    current.fold({ "raw=${render(it)}" }, { "failed: ${describe(it)}" }))
                current.getOrNull()?.let { onValue(signal.id, it) }
                runCatching { register.invoke(properties, listener, signal.id, 0f) }
                    .onFailure { diagnostic("Vehicle signal ${signal.name} listen failed: ${describe(it)}") }
            }
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) = attach()
            override fun onServiceDisconnected(name: ComponentName?) {
                manager = null
                diagnostic("Vehicle signal probe: car service disconnected")
            }
        }
        val created = Class.forName(CAR_CLASS)
            .getMethod("createCar", Context::class.java, ServiceConnection::class.java, Handler::class.java)
            .invoke(null, appContext, connection, main) ?: return false
        car = created
        release = {
            manager?.let { properties ->
                runCatching { properties.javaClass.getMethod("unregisterListener", listenerType).invoke(properties, listener) }
            }
            manager = null
            runCatching { created.javaClass.getMethod("disconnect").invoke(created) }
        }
        runCatching { created.javaClass.getMethod("connect").invoke(created) }
        if (runCatching { created.javaClass.getMethod("isConnected").invoke(created) == true }.getOrDefault(false)) attach()
        return true
    }

    private fun describe(error: Throwable): String {
        val cause = (error as? InvocationTargetException)?.targetException ?: error
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }

    companion object {
        private const val CAR_CLASS = "android.car.Car"
        private const val PROPERTY_LISTENER =
            "android.car.hardware.property.CarPropertyManager\$CarPropertyEventListener"
        private val INT = Int::class.javaObjectType
        private val BYTES = ByteArray::class.java

        const val USAGE_MODE = 557843202
        const val DOOR_DRIVER = 560993300

        internal val SIGNALS = listOf(
            // UsgModSts: 0 abandon, 1 inactive, 2 convenience, 11 active, 13 driving.
            Signal(USAGE_MODE, "usageMode", INT),
            Signal(DOOR_DRIVER, "doorDriver", BYTES),
            Signal(560993301, "doorDriverRear", BYTES),
            Signal(560993302, "doorPassenger", BYTES),
            Signal(560993303, "doorPassengerRear", BYTES),
            Signal(560993304, "doorTailgate", BYTES),
        )

        fun supported(context: Context): Boolean = EcarxTwilightSensor.supported(context)

        internal fun nameOf(id: Int?): String = SIGNALS.firstOrNull { it.id == id }?.name ?: "id=$id"

        internal fun render(value: Any?): String = when (value) {
            null -> "null"
            is ByteArray -> value.joinToString("") { "%02x".format(it) }
            is IntArray -> value.joinToString(",", "[", "]")
            is Array<*> -> value.joinToString(",", "[", "]")
            else -> value.toString()
        }
    }
}
