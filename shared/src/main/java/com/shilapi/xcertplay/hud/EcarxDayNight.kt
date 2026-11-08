package com.shilapi.xcertplay.hud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper

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
    /**
     * ECARX XUI AdaptAPI `IDayNightMode.getDayNight()` / `onDayNightChanged(int)`:
     * 1 = day, 2 = night, 0/unknown invalid. Contract recovered from the bytecode of the
     * `com.ecarx.xcvehicle` app shipped on this head unit.
     */
    fun fromAdaptapiDayNight(value: Int): Boolean? = when (value) {
        1 -> false
        2 -> true
        else -> null
    }

    /**
     * Car AMap standard broadcast `AUTONAVI_STANDARD_BROADCAST_SEND` with `KEY_TYPE` 10019:
     * `EXTRA_STATE` 30 = day, 32 = night. The same feed the OEM projection widget normalizes.
     */
    fun fromAmapState(state: Int): Boolean? = when (state) {
        30 -> false
        32 -> true
        else -> null
    }
}

/**
 * Head-unit day/night as a push source, no polling.
 *
 * Primary backend: the AdaptAPI `Device.create(context)` handle and its
 * `IDayNightMode.registerDayNightModeCallBack` push (note the interface package's official
 * `daynigntmode` spelling). The callback only fires on changes, so the resolved state is also
 * read once via `getDayNight()` at start.
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

    private var onNight: ((Boolean) -> Unit)? = null
    private var releaseAdaptapi: (() -> Unit)? = null
    private var receiver: BroadcastReceiver? = null

    override val available: Boolean
        get() = adaptapiPresent || amapPresent

    override fun start(onNight: (Boolean) -> Unit): Boolean {
        stop()
        this.onNight = onNight
        val started = when {
            adaptapiPresent && startAdaptapi(onNight) -> true
            amapPresent && startAmapBroadcast(onNight) -> true
            else -> false
        }
        if (!started) {
            this.onNight = null
            log("Vehicle day/night backend=none adaptapi=$adaptapiPresent amap=$amapPresent")
        }
        return started
    }

    override fun stop() {
        onNight = null
        releaseAdaptapi?.let { runCatching { it() } }
        releaseAdaptapi = null
        receiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        receiver = null
    }

    private fun startAdaptapi(onNight: (Boolean) -> Unit): Boolean = runCatching {
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
        fun deliver(raw: Int) {
            EcarxDayNightMapping.fromAdaptapiDayNight(raw)?.let { night -> main.post { onNight(night) } }
        }
        val callback = EcarxDimApi.proxy(callbackType, "EcarxDayNightCallback") { method, args ->
            val value = args?.firstOrNull() as? Int
            when (method) {
                "onDayNightChanged" -> value?.let {
                    log("Vehicle day/night changed raw=$it")
                    deliver(it)
                }
                // A preference change (auto/day/night) may not be followed by onDayNightChanged on
                // this firmware, so read the resolved state again instead of trusting the push.
                "onDayNightModeChange" -> {
                    val current = readCurrent()
                    log("Vehicle day/night preference raw=$value current=$current")
                    deliver(current)
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
        log("Vehicle day/night backend=adaptapi current=$current preference=$preference")
        deliver(current)
        true
    }.getOrElse {
        val cause = (it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it
        log("Vehicle day/night backend=adaptapi failed: ${cause.javaClass.simpleName}: ${cause.message}")
        false
    }

    private fun startAmapBroadcast(onNight: (Boolean) -> Unit): Boolean = runCatching {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val extras = intent.extras ?: return
                if (extras.getInt(AMAP_KEY_TYPE, -1) != AMAP_STATUS_PUSH) return
                val state = extras.getInt(AMAP_EXTRA_STATE, -1)
                log("Vehicle day/night amap state=$state")
                val night = EcarxDayNightMapping.fromAmapState(state) ?: return
                onNight(night)
            }
        }
        appContext.registerReceiver(receiver, IntentFilter(AMAP_ACTION))
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
        const val ADAPTAPI_CALLBACK =
            "com.ecarx.xui.adaptapi.device.daynigntmode.IDayNightMode\$IDayNightChangeCallBack"
        const val AMAP_ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND"
        const val AMAP_KEY_TYPE = "KEY_TYPE"
        const val AMAP_EXTRA_STATE = "EXTRA_STATE"
        const val AMAP_STATUS_PUSH = 10019
        val AMAP_PACKAGES = listOf("com.autonavi.amapauto", "com.autonavi.amapautp.yh")
    }
}
