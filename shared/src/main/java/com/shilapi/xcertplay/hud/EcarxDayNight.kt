package com.shilapi.xcertplay.hud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import java.lang.reflect.Proxy

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
 * Neither backend is packaged in this APK; everything is resolved reflectively and every failure
 * keeps CarPlay on the configured fallback (Android system) instead of throwing.
 */
class EcarxVehicleDayNight(context: Context) : VehicleNightSource {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val adaptapiPresent by lazy { EcarxCapabilities.classPresent(EcarxCapabilities.DEVICE) }
    private val amapPresent by lazy { AMAP_PACKAGES.any { packageInstalled(it) } }

    private var onNight: ((Boolean) -> Unit)? = null
    private var modeHandle: Any? = null
    private var callbackProxy: Any? = null
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
        if (!started) this.onNight = null
        return started
    }

    override fun stop() {
        onNight = null
        callbackProxy?.let { proxy ->
            modeHandle?.let { mode ->
                runCatching {
                    val callbackClass = Class.forName(ADAPTAPI_CALLBACK, true, mode.javaClass.classLoader)
                    mode.javaClass
                        .getMethod("unregisterDayNigntModeCallBack", callbackClass)
                        .invoke(mode, proxy)
                }
            }
        }
        callbackProxy = null
        modeHandle = null
        receiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        receiver = null
    }

    private fun startAdaptapi(onNight: (Boolean) -> Unit): Boolean = runCatching {
        val deviceClass = Class.forName(EcarxCapabilities.DEVICE, true, javaClass.classLoader)
        val device = deviceClass.getMethod("create", Context::class.java).invoke(null, appContext)
        val mode = deviceClass.getMethod("getDayNightMode").invoke(device)
            ?: return@runCatching false
        val callbackClass = Class.forName(ADAPTAPI_CALLBACK, true, mode.javaClass.classLoader)
        val proxy = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { _, method, args ->
            // Only the resolved appearance matters; onDayNightModeChange (the user's mode choice)
            // is deliberately ignored so CarPlay never flips on a mere settings edit.
            if (method.name == "onDayNightChanged" && args != null && args.size == 1 && args[0] is Int) {
                EcarxDayNightMapping.fromAdaptapiDayNight(args[0] as Int)?.let { night ->
                    main.post { onNight(night) }
                }
            }
            null
        }
        val ok = mode.javaClass
            .getMethod("registerDayNightModeCallBack", callbackClass)
            .invoke(mode, proxy) as Boolean
        if (!ok) return@runCatching false
        modeHandle = mode
        callbackProxy = proxy
        // The callback only fires on changes; read the resolved state once so CarPlay matches
        // the head unit without waiting for the next transition.
        val current = runCatching { mode.javaClass.getMethod("getDayNight").invoke(mode) as Int }
            .getOrDefault(0)
        EcarxDayNightMapping.fromAdaptapiDayNight(current)?.let { night ->
            main.post { onNight(night) }
        }
        true
    }.getOrDefault(false)

    private fun startAmapBroadcast(onNight: (Boolean) -> Unit): Boolean = runCatching {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val extras = intent.extras ?: return
                if (extras.getInt(AMAP_KEY_TYPE, -1) != AMAP_STATUS_PUSH) return
                val night = EcarxDayNightMapping.fromAmapState(extras.getInt(AMAP_EXTRA_STATE, -1))
                    ?: return
                onNight(night)
            }
        }
        appContext.registerReceiver(receiver, IntentFilter(AMAP_ACTION))
        this.receiver = receiver
        true
    }.getOrDefault(false)

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
