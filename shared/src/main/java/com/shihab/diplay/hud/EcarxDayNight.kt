package com.shihab.diplay.hud

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/**
 * Push source for the head unit's resolved day/night state (`night = true`).
 *
 * Deliveries must reach [start]'s callback on the main thread, matching
 * `CarPlayNightModeController`. Sources without a current reading simply do not call back until
 * the head unit decides, so the controller keeps its initial state meanwhile.
 */
interface VehicleNightSource {
    val available: Boolean

    /** Starts push delivery. Returns false when no backend could be armed. */
    fun start(onNight: (Boolean) -> Unit): Boolean

    fun stop()
}

/** Pure code tables shared by the vehicle day/night source and its tests. */
object EcarxDayNightMapping {
    init { com.shihab.diplay.airplay.NativeMedia.requireAvailable() }
    /**
     * ECARX XUI AdaptAPI `IDayNightMode.getDayNight()` / `onDayNightChanged(int)`:
     * 1 = day, 2 = night, 0/unknown invalid. Contract recovered from the bytecode of the
     * `com.ecarx.xcvehicle` app shipped on this head unit.
     */
    fun fromAdaptapiDayNight(value: Int): Boolean? =
        native(NativeDayNight.fromAdaptapi(value))

    /**
     * Car AMap standard broadcast `AUTONAVI_STANDARD_BROADCAST_SEND` with `KEY_TYPE` 10019:
     * `EXTRA_STATE` 30 = day, 32 = night. The same feed the OEM projection widget normalizes.
     */
    fun fromAmapState(state: Int): Boolean? =
        native(NativeDayNight.fromAmap(state))

    /**
     * Vehicle property `0x21400305` (`TwliBriSts`, body twilight sensor): 1 = day, 0 = night.
     * Observed on the target head unit: each change is followed within milliseconds by the
     * AdaptAPI `onDayNightChanged` with the same decision.
     */
    fun fromTwilightProperty(value: Int): Boolean? =
        native(NativeDayNight.fromTwilight(value))

    private fun native(code: Int): Boolean? = when (code) {
        0 -> false
        1 -> true
        else -> null
    }
}

/** The native day/night tables; the AdaptAPI/broadcast adapters stay Kotlin. */
internal object NativeDayNight {
    @JvmStatic external fun fromAdaptapi(value: Int): Int
    @JvmStatic external fun fromAmap(state: Int): Int
    @JvmStatic external fun fromTwilight(value: Int): Int
}

/**
 * Head-unit day/night as a push source, no polling.
 *
 * Primary backend: the AdaptAPI `Device.create(context)` handle and its
 * `IDayNightMode.registerDayNightModeCallBack` push (note the interface package's official
 * `daynigntmode` spelling). The callback only fires on changes, so the resolved state is also
 * read once via `getDayNight()` at start.
 *
 * Early backend: the vehicle twilight property through `android.car`. At a cold start AdaptAPI
 * answers 0 until its own service connects, which took about 8 s on the target head unit, so this
 * reading stands in until AdaptAPI delivers a valid value and is then released. AdaptAPI stays
 * authoritative because it also applies the driver's forced day or night preference.
 *
 * Fallback backend: the car AMap standard broadcast. It has no current-state query, so the first
 * delivery waits for the map app to announce a change.
 *
 * Vendor interfaces are resolved at runtime. Every failure keeps CarPlay on the configured fallback.
 */
class EcarxVehicleDayNight(
    context: Context,
    private val diagnostic: (String) -> Unit = {},
) : VehicleNightSource {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val adaptapiPresent by lazy { EcarxCapabilities.classPresent(EcarxCapabilities.DEVICE) }
    private val amapPresent by lazy { AMAP_PACKAGES.any { packageInstalled(it) } }
    private val twilight = EcarxTwilightSensor(context, "Vehicle day/night property", diagnostic)

    private var releaseAdaptapi: (() -> Unit)? = null
    private var releaseProperty: (() -> Unit)? = null
    private var receiver: BroadcastReceiver? = null

    // Main-thread state of the current start; [generation] drops deliveries from an older one.
    private var generation = 0
    private var startedAt = 0L
    private var adaptapiReady = false
    private var firstSource: String? = null

    override val available: Boolean
        get() = adaptapiPresent || twilight.available || amapPresent

    override fun start(onNight: (Boolean) -> Unit): Boolean {
        stop()
        val session = generation
        startedAt = SystemClock.elapsedRealtime()
        adaptapiReady = false
        firstSource = null
        val deliver: (String, Boolean) -> Unit = { source, night ->
            main.post { if (session == generation) accept(source, night, onNight) }
        }
        val adaptapi = adaptapiPresent && startAdaptapi(deliver)
        // Stands in until AdaptAPI is ready; see the class comment.
        val property = twilight.available && twilight.start { night -> deliver(SOURCE_PROPERTY, night) }
        if (property) releaseProperty = twilight::stop
        val started = adaptapi || property || (amapPresent && startAmapBroadcast(deliver))
        if (!started) {
            log("Vehicle day/night backend=none adaptapi=$adaptapiPresent car=${twilight.available} amap=$amapPresent")
        }
        return started
    }

    override fun stop() {
        generation++
        releaseAdaptapi?.let { runCatching { it() } }
        releaseAdaptapi = null
        releaseProperty?.let { runCatching { it() } }
        releaseProperty = null
        receiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        receiver = null
    }

    private fun accept(source: String, night: Boolean, onNight: (Boolean) -> Unit) {
        if (source == SOURCE_PROPERTY && adaptapiReady) return
        if (firstSource == null) {
            firstSource = source
            log("Vehicle day/night first value source=$source night=$night +${elapsed()}ms")
        }
        if (source == SOURCE_ADAPTAPI && !adaptapiReady) {
            adaptapiReady = true
            releaseProperty?.let {
                runCatching { it() }
                log("Vehicle day/night adaptapi ready, property released +${elapsed()}ms")
            }
            releaseProperty = null
        }
        onNight(night)
    }

    private fun elapsed(): Long = SystemClock.elapsedRealtime() - startedAt

    private fun startAdaptapi(deliver: (String, Boolean) -> Unit): Boolean = runCatching {
        val deviceType = Class.forName(EcarxCapabilities.DEVICE)
        val device = deviceType.getMethod("create", Context::class.java).invoke(null, appContext)
            ?: return@runCatching false
        val getMode = deviceType.getMethod("getDayNightMode")
        val mode = getMode.invoke(device) ?: return@runCatching false
        val modeType = getMode.returnType
        val callbackType = Class.forName(ADAPTAPI_CALLBACK, true, modeType.classLoader)
        // Resolve release first: a callback that cannot be unregistered must not be registered.
        val unregister = modeType.getMethod("unregisterDayNigntModeCallBack", callbackType)
        val getDayNight = runCatching { modeType.getMethod("getDayNight") }.getOrNull()
        fun readCurrent(): Int = getDayNight?.let { runCatching { it.invoke(mode) as Int }.getOrNull() } ?: 0
        fun deliverRaw(raw: Int) {
            EcarxDayNightMapping.fromAdaptapiDayNight(raw)?.let { night -> deliver(SOURCE_ADAPTAPI, night) }
        }
        val callback = EcarxDimApi.proxy(callbackType, "EcarxDayNightCallback") { method, args ->
            val value = args?.firstOrNull() as? Int
            when (method) {
                "onDayNightChanged" -> value?.let {
                    log("Vehicle day/night changed raw=$it +${elapsed()}ms")
                    deliverRaw(it)
                }
                // A preference change (auto/day/night) may not be followed by onDayNightChanged on
                // this firmware, so read the resolved state again instead of trusting the push.
                "onDayNightModeChange" -> {
                    val current = readCurrent()
                    log("Vehicle day/night preference raw=$value current=$current +${elapsed()}ms")
                    deliverRaw(current)
                }
            }
            null
        }
        val ok = modeType.getMethod("registerDayNightModeCallBack", callbackType).invoke(mode, callback) as Boolean
        if (!ok) {
            log("Vehicle day/night backend=adaptapi register=refused")
            return@runCatching false
        }
        releaseAdaptapi = { unregister.invoke(mode, callback) }
        // The callback only fires on changes; read the resolved state once so CarPlay matches
        // the head unit without waiting for the next transition.
        val current = readCurrent()
        val preference = runCatching { modeType.getMethod("getDayNightMode").invoke(mode) }.getOrNull()
        log("Vehicle day/night backend=adaptapi current=$current preference=$preference +${elapsed()}ms")
        deliverRaw(current)
        true
    }.getOrElse {
        log("Vehicle day/night backend=adaptapi failed: ${describe(it)}")
        false
    }

    private fun describe(error: Throwable): String {
        val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }

    private fun startAmapBroadcast(deliver: (String, Boolean) -> Unit): Boolean = runCatching {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val extras = intent.extras ?: return
                if (extras.getInt(AMAP_KEY_TYPE, -1) != AMAP_STATUS_PUSH) return
                val state = extras.getInt(AMAP_EXTRA_STATE, -1)
                log("Vehicle day/night amap state=$state")
                val night = EcarxDayNightMapping.fromAmapState(state) ?: return
                deliver(SOURCE_AMAP, night)
            }
        }
        // Sent by the map app: Android 13+ needs the receiver marked exported.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) appContext.registerReceiver(receiver, IntentFilter(AMAP_ACTION), Context.RECEIVER_EXPORTED)
        else appContext.registerReceiver(receiver, IntentFilter(AMAP_ACTION))
        this.receiver = receiver
        // This feed has no current-state query, so nothing changes until the map app announces.
        log("Vehicle day/night backend=amap waiting for the map app")
        true
    }.getOrDefault(false)

    /** Vendor callbacks arrive on Binder threads; the diagnostic sink is main-thread only. */
    private fun log(line: String) {
        main.post { diagnostic(line) }
    }

    private fun packageInstalled(pkg: String): Boolean =
        runCatching { appContext.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private companion object {
        const val SOURCE_ADAPTAPI = "adaptapi"
        const val SOURCE_PROPERTY = "property"
        const val SOURCE_AMAP = "amap"
        const val ADAPTAPI_CALLBACK =
            "com.ecarx.xui.adaptapi.device.daynigntmode.IDayNightMode\$IDayNightChangeCallBack"
        const val AMAP_ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND"
        const val AMAP_KEY_TYPE = "KEY_TYPE"
        const val AMAP_EXTRA_STATE = "EXTRA_STATE"
        const val AMAP_STATUS_PUSH = 10019
        val AMAP_PACKAGES = listOf("com.autonavi.amapauto", "com.autonavi.amapautp.yh")
    }
}

/**
 * The body twilight sensor as a push source: vehicle property `0x21400305` (`TwliBriSts`) through
 * `android.car`, read once on connection and then followed. It reports day or night only, never a
 * lux value, and ignores the driver's forced day or night preference in the head-unit settings.
 */
class EcarxTwilightSensor(
    context: Context,
    private val label: String,
    private val diagnostic: (String) -> Unit = {},
) : VehicleNightSource {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val carPresent by lazy { supported(appContext) }

    private var release: (() -> Unit)? = null
    private var generation = 0
    private var startedAt = 0L

    override val available: Boolean
        get() = carPresent

    override fun start(onNight: (Boolean) -> Unit): Boolean {
        stop()
        val session = generation
        startedAt = SystemClock.elapsedRealtime()
        return runCatching { connect(session, onNight) }.getOrElse {
            log("$label failed: ${describe(it)}")
            false
        }
    }

    override fun stop() {
        generation++
        release?.let { runCatching { it() } }
        release = null
    }

    private fun connect(session: Int, onNight: (Boolean) -> Unit): Boolean {
        val carType = Class.forName(CAR_CLASS)
        val listenerType = Class.forName(PROPERTY_LISTENER)
        val intType = Int::class.javaPrimitiveType!!
        var car: Any? = null
        var manager: Any? = null
        fun report(raw: Int?, what: String) {
            log("$label $what raw=$raw +${SystemClock.elapsedRealtime() - startedAt}ms")
            val night = raw?.let(EcarxDayNightMapping::fromTwilightProperty) ?: return
            main.post { if (session == generation) onNight(night) }
        }
        val listener = EcarxDimApi.proxy(listenerType, "EcarxTwilightListener") { method, args ->
            if (method == "onChangeEvent") {
                val value = args?.firstOrNull()?.let { event ->
                    runCatching { event.javaClass.getMethod("getValue").invoke(event) as? Int }.getOrNull()
                }
                report(value, "changed")
            }
            null
        }
        // Car service callbacks arrive on [main], like every other state change here.
        fun attach() {
            if (session != generation || manager != null) return
            val connected = car ?: return
            val properties = runCatching {
                connected.javaClass.getMethod("getCarManager", String::class.java).invoke(connected, "property")
            }.getOrNull() ?: return
            manager = properties
            val current = runCatching {
                properties.javaClass.getMethod("getIntProperty", intType, intType)
                    .invoke(properties, TWILIGHT_PROPERTY, 0) as Int
            }.getOrNull()
            report(current, "current")
            runCatching {
                properties.javaClass.getMethod("registerListener", listenerType, intType, Float::class.javaPrimitiveType)
                    .invoke(properties, listener, TWILIGHT_PROPERTY, 0f)
            }.onFailure { log("$label listen failed: ${describe(it)}") }
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) = attach()
            override fun onServiceDisconnected(name: ComponentName?) { manager = null }
        }
        val created = carType.getMethod("createCar", Context::class.java, ServiceConnection::class.java, Handler::class.java)
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

    private fun log(line: String) {
        main.post { diagnostic(line) }
    }

    private fun describe(error: Throwable): String {
        val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }

    companion object {
        /**
         * An ECARX head unit with the platform car service. The property is ECARX-specific, so
         * other car platforms keep their own light sensor. It may still be unreadable at runtime.
         */
        fun supported(context: Context): Boolean =
            EcarxCapabilities.classPresent(CAR_CLASS) && EcarxCapabilities.detect(context).identified

        private const val CAR_CLASS = "android.car.Car"
        private const val PROPERTY_LISTENER =
            "android.car.hardware.property.CarPropertyManager\$CarPropertyEventListener"
        private const val TWILIGHT_PROPERTY = 0x21400305
    }
}
