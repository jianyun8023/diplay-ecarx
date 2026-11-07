package com.shilapi.xcertplay.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings

/**
 * Keeps the car's Wi-Fi client off while DiPlay starts the car hotspot. Android 9 runs either the
 * client or the hotspot, and turning the client on stops the hotspot.
 *
 * The ECARX dcy11 firmware does not restore Wi-Fi at boot itself (WifiServiceImpl.checkAndStartWifi
 * skips "dcy11"). The car settings app does it each time its process starts: MyApplication.enableWifi()
 * reads Settings.System "wifi_on", which Android 9 redirects to Settings.Global "wifi_on", and calls
 * setWifiEnabled with it. Its process starts 6–10 s after LOCKED_BOOT_COMPLETED, so a hotspot
 * started at user unlock was stopped again and restarted 4–5 s later (car tests 2026-10-31, 11-06,
 * 11-07). setWifiEnabled(false) persists 0 there, and the settings app then leaves the client off.
 */
object CarWifiClientGuard {
    private const val PREFS = "diplay_car_hotspot"
    private const val KEY_KEEP_OFF = "keep_car_wifi_off"
    private const val KEY_TURNED_OFF = "car_wifi_turned_off"
    private const val GLOBAL_WIFI_ON = "wifi_on"
    private const val POLL_MILLIS = 100L

    /** Longest wait for the car settings app to turn the client on, when [keepOff] is disabled. */
    const val CLIENT_RESTORE_WAIT_MILLIS = 15_000L

    enum class Restore { NOT_NEEDED, NOW, NEXT_START, FAILED }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** On by default: it only acts where DiPlay starts the car hotspot itself. */
    fun keepOff(context: Context): Boolean = prefs(context).getBoolean(KEY_KEEP_OFF, true)

    fun setKeepOff(context: Context, keepOff: Boolean) =
        prefs(context).edit().putBoolean(KEY_KEEP_OFF, keepOff).apply()

    fun turnedOffByDiPlay(context: Context): Boolean = prefs(context).getBoolean(KEY_TURNED_OFF, false)

    /** Settings.Global "wifi_on": 0 off, 1 on, 2 on in airplane mode, 3 off in airplane mode; null unreadable. */
    fun persistedState(context: Context): Int? =
        runCatching { Settings.Global.getInt(context.contentResolver, GLOBAL_WIFI_ON) }.getOrNull()

    /** Whether a start now should turn the client off first; [clientState] is WifiManager.getWifiState(). */
    internal fun shouldTurnOff(sdk: Int, keepOff: Boolean, persisted: Int?, clientState: Int?): Boolean =
        sdk == 28 && keepOff && (persistedOn(persisted) ||
            clientState == WifiManager.WIFI_STATE_ENABLED || clientState == WifiManager.WIFI_STATE_ENABLING)

    /**
     * Whether a hotspot start should first wait for the settings app to turn the client on. Only with
     * [keepOff] disabled: the client is still to come on, and would stop a hotspot started before it.
     */
    internal fun shouldAwaitClient(sdk: Int, keepOff: Boolean, persisted: Int?, clientState: Int?): Boolean =
        sdk == 28 && !keepOff && persistedOn(persisted) && clientState != WifiManager.WIFI_STATE_ENABLED

    private fun persistedOn(persisted: Int?) = persisted == 1 || persisted == 2

    /** Called before every automatic car hotspot start; true when it turned the client off. */
    @Suppress("DEPRECATION")
    fun beforeHotspotStart(context: Context, log: (String) -> Unit): Boolean {
        val wifi = context.getSystemService(WifiManager::class.java) ?: return false
        val persisted = persistedState(context)
        val clientState = runCatching { wifi.wifiState }.getOrNull()
        if (!shouldTurnOff(Build.VERSION.SDK_INT, keepOff(context), persisted, clientState)) return false
        // NETWORK_SETTINGS lets this through while the hotspot is on; it only stores the choice then.
        val accepted = runCatching { wifi.setWifiEnabled(false) }.getOrDefault(false)
        if (accepted) prefs(context).edit().putBoolean(KEY_TURNED_OFF, true).apply()
        log("car Wi-Fi client kept off: persisted=${persisted ?: "unknown"} clientState=${stateLabel(clientState)} accepted=$accepted")
        return accepted
    }

    /**
     * With [keepOff] disabled, waits until the car settings app has turned the client on, so the
     * hotspot starts after it instead of being stopped by it. Returns how long it waited, or null
     * when no wait was needed.
     */
    @Suppress("DEPRECATION")
    fun awaitClientRestore(context: Context, timeoutMillis: Long, isCancelled: () -> Boolean): Long? {
        val wifi = context.getSystemService(WifiManager::class.java) ?: return null
        fun clientState() = runCatching { wifi.wifiState }.getOrNull()
        if (!shouldAwaitClient(Build.VERSION.SDK_INT, keepOff(context), persistedState(context), clientState())) return null
        val started = SystemClock.elapsedRealtime()
        while (clientState() != WifiManager.WIFI_STATE_ENABLED &&
            SystemClock.elapsedRealtime() - started < timeoutMillis && !isCancelled()) {
            try {
                Thread.sleep(POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return SystemClock.elapsedRealtime() - started
    }

    /**
     * Gives the client back after the driver turned [keepOff] off. While the hotspot is in use the
     * client would stop it, so only the stored choice is restored and the client comes on at the
     * next start.
     */
    @Suppress("DEPRECATION")
    fun restore(context: Context, hotspotInUse: Boolean): Restore {
        if (!turnedOffByDiPlay(context)) return Restore.NOT_NEEDED
        val restored = if (hotspotInUse) {
            runCatching { Settings.Global.putInt(context.contentResolver, GLOBAL_WIFI_ON, 1) }.getOrDefault(false)
        } else {
            runCatching { context.getSystemService(WifiManager::class.java)?.setWifiEnabled(true) == true }.getOrDefault(false)
        }
        if (!restored) return Restore.FAILED
        prefs(context).edit().putBoolean(KEY_TURNED_OFF, false).apply()
        return if (hotspotInUse) Restore.NEXT_START else Restore.NOW
    }

    /** For the diagnostic report; the vendor values come from LgeCarService's WifiPowerModeControlService. */
    @Suppress("DEPRECATION")
    fun diagnostic(context: Context): String {
        val clientState = runCatching { context.getSystemService(WifiManager::class.java)?.wifiState }.getOrNull()
        return "Car Wi-Fi client: keepOff=${keepOff(context)} turnedOffByDiPlay=${turnedOffByDiPlay(context)} " +
            "persisted=${persistedState(context) ?: "unknown"} clientState=${stateLabel(clientState)} " +
            "vendorPowerModeState=${systemProperty("persist.vendor.lge.wifimodestate")} " +
            "vendorWcpConnect=${systemProperty("persist.service.wcp.connect")}"
    }

    @SuppressLint("PrivateApi")
    private fun systemProperty(name: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
            .invoke(null, name, "") as String
    }.getOrNull()?.ifEmpty { "unset" } ?: "unreadable"

    private fun stateLabel(state: Int?): String = when (state) {
        WifiManager.WIFI_STATE_DISABLING -> "disabling"
        WifiManager.WIFI_STATE_DISABLED -> "disabled"
        WifiManager.WIFI_STATE_ENABLING -> "enabling"
        WifiManager.WIFI_STATE_ENABLED -> "enabled"
        else -> "unknown"
    }
}
