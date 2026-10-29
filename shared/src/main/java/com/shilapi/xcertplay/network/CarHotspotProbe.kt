package com.shilapi.xcertplay.network

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit

/** Explicit API-28 diagnostics. Does not save DiPlay preferences, grant access or retry writes. */
class CarHotspotProbe internal constructor(private val platform: Platform) {
    constructor(context: Context) : this(AndroidPlatform(context.applicationContext))

    enum class Action { INSPECT, READ, WRITE, RESTORE, START_PLATFORM, STOP_PLATFORM, START_OEM, STOP_OEM }
    enum class Status { OBSERVED, VERIFIED, REJECTED, DENIED, UNSUPPORTED, FAILED, TIMED_OUT, CANCELLED, BUSY }
    data class Credentials(val name: String, val key: String)
    data class Result(val status: Status, val evidence: String, val credentials: Credentials? = null)

    internal interface Platform {
        val sdk: Int
        fun inspect(): String
        fun read(): WifiConfiguration?
        fun write(config: WifiConfiguration): Boolean
        fun softAp(enabled: Boolean): Boolean
        fun request(enabled: Boolean, cancelled: () -> Boolean): ManualHotspotControl.Result
        fun state(): ManualHotspotControl.State
        fun now(): Long = System.nanoTime()
        fun pause() = Thread.sleep(100)
    }

    private var original: WifiConfiguration? = null
    val canRestore: Boolean get() = original != null

    fun run(action: Action, credentials: Credentials? = null, cancelled: () -> Boolean = { false }): Result {
        val started = platform.now()
        var locked = false
        fun stopped() = cancelled() || Thread.currentThread().isInterrupted
        fun result(status: Status, evidence: String, value: Credentials? = null) =
            Result(status, "operation=$action elapsedMs=${(platform.now() - started) / 1_000_000} $evidence", value)
        try {
            if (stopped()) return result(Status.CANCELLED, "beforeCall=true")
            if (action == Action.INSPECT) return result(Status.OBSERVED, platform.inspect())
            if (platform.sdk != 28) return result(Status.UNSUPPORTED, "sdk=${platform.sdk} requiredApi=28")
            // Platform start/stop already own this lock. Other probes share it with normal startup.
            if (action != Action.START_PLATFORM && action != Action.STOP_PLATFORM) {
                locked = CarHotspotTethering.startupLock.tryLock(100, TimeUnit.MILLISECONDS)
                if (!locked) return result(Status.BUSY, "hotspotOperationActive=true")
            }
            if (stopped()) return result(Status.CANCELLED, "beforeCall=true")
            return when (action) {
                Action.INSPECT -> error("handled above")
                Action.READ -> {
                    val config = platform.read() ?: return result(Status.FAILED, "configRead=false")
                    if (config.SSID.isNullOrBlank()) return result(Status.FAILED, "namePresent=false")
                    if (original == null) original = WifiConfiguration(config)
                    result(Status.OBSERVED,
                        "configRead=true namePresent=true keyPresent=${!config.preSharedKey.isNullOrEmpty()} " +
                            "securityBits=${config.allowedKeyManagement} state=${platform.state()}",
                        Credentials(config.SSID, config.preSharedKey.orEmpty()))
                }
                Action.WRITE, Action.RESTORE -> {
                    val backup = original ?: return result(Status.REJECTED, "readOriginalFirst=true")
                    val desired = WifiConfiguration(if (action == Action.RESTORE) backup else
                        platform.read() ?: return result(Status.FAILED, "configRead=false"))
                    if (action == Action.WRITE) {
                        val value = credentials ?: return result(Status.REJECTED, "inputMissing=true")
                        // This panel only edits the recovered WPA2 configuration; other security modes stay intact.
                        if (!desired.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK) ||
                            desired.allowedKeyManagement.cardinality() != 1) {
                            return result(Status.UNSUPPORTED, "requiresWpa2=true")
                        }
                        if (value.name.toByteArray(Charsets.UTF_8).size !in 1..32 ||
                            value.name.any { it == '\u0000' || it == '\n' || it == '\r' } ||
                            value.key.length !in 8..63 || value.key.any { it.code !in 32..126 }) {
                            return result(Status.REJECTED, "invalidInput=true")
                        }
                        desired.SSID = value.name
                        desired.preSharedKey = value.key
                    }
                    if (stopped()) return result(Status.CANCELLED, "beforeWrite=true")
                    if (!platform.write(desired)) return result(Status.REJECTED, "setterAccepted=false")
                    val saved = platform.read()
                    val matches = saved != null && saved.SSID == desired.SSID && saved.preSharedKey == desired.preSharedKey &&
                        saved.allowedKeyManagement == desired.allowedKeyManagement && saved.hiddenSSID == desired.hiddenSSID &&
                        listOf("apBand", "apChannel").all { field ->
                            val member = WifiConfiguration::class.java.getField(field)
                            member.getInt(saved) == member.getInt(desired)
                        }
                    result(if (matches) Status.VERIFIED else Status.FAILED,
                        "setterAccepted=true readbackMatches=$matches requiresHotspotRestart=true",
                        if (matches) Credentials(desired.SSID, desired.preSharedKey.orEmpty()) else null)
                }
                Action.START_PLATFORM, Action.STOP_PLATFORM -> {
                    val enabled = action == Action.START_PLATFORM
                    val outcome = platform.request(enabled, ::stopped)
                    val status = when (outcome) {
                        ManualHotspotControl.Result.ON, ManualHotspotControl.Result.OFF -> Status.VERIFIED
                        ManualHotspotControl.Result.PERMISSION_REQUIRED -> Status.DENIED
                        ManualHotspotControl.Result.UNSUPPORTED -> Status.UNSUPPORTED
                        ManualHotspotControl.Result.TIMED_OUT -> Status.TIMED_OUT
                        ManualHotspotControl.Result.CANCELLED -> Status.CANCELLED
                        ManualHotspotControl.Result.BUSY -> Status.BUSY
                        ManualHotspotControl.Result.FAILED -> Status.FAILED
                    }
                    result(status, "path=connectivity outcome=$outcome state=${platform.state()}")
                }
                Action.START_OEM, Action.STOP_OEM -> {
                    val enabled = action == Action.START_OEM
                    val target = if (enabled) ManualHotspotControl.State.ON else ManualHotspotControl.State.OFF
                    val initial = platform.state()
                    if (initial == target) return result(Status.OBSERVED, "alreadyTarget=true state=$initial")
                    if (stopped()) return result(Status.CANCELLED, "beforeCall=true")
                    if (!platform.softAp(enabled)) return result(Status.REJECTED, "setterAccepted=false")
                    val deadline = started + 8_000_000_000L
                    while (!stopped()) {
                        val state = platform.state()
                        if (state == target) return result(Status.VERIFIED, "setterAccepted=true state=$state")
                        if (state == ManualHotspotControl.State.FAILED) return result(Status.FAILED, "setterAccepted=true state=$state")
                        if (platform.now() >= deadline) return result(Status.TIMED_OUT, "setterAccepted=true state=$state")
                        platform.pause()
                    }
                    result(Status.CANCELLED, "setterAccepted=true requestMayStillComplete=true")
                }
            }
        } catch (error: Exception) {
            val cause = (error as? InvocationTargetException)?.targetException ?: error
            if (cause is InterruptedException) Thread.currentThread().interrupt()
            val status = when (cause) {
                is SecurityException -> Status.DENIED
                is ReflectiveOperationException -> Status.UNSUPPORTED
                is InterruptedException -> Status.CANCELLED
                else -> Status.FAILED
            }
            // Exception messages may include configuration values; record the type only.
            return result(status, "error=${cause.javaClass.simpleName}")
        } finally {
            if (locked) CarHotspotTethering.startupLock.unlock()
        }
    }

    private class AndroidPlatform(private val context: Context) : Platform {
        override val sdk: Int get() = Build.VERSION.SDK_INT
        private val wifi = context.getSystemService(WifiManager::class.java)
        private val api = WifiManager::class.java
        private val control = ManualHotspotControl(context)
        override fun inspect(): String = buildString {
            append("sdk=$sdk")
            for (permission in listOf("ACCESS_WIFI_STATE", "CHANGE_WIFI_STATE", "OVERRIDE_WIFI_CONFIG", "NETWORK_SETTINGS", "TETHER_PRIVILEGED")) {
                append(" $permission=").append(context.checkSelfPermission("android.permission.$permission") == PackageManager.PERMISSION_GRANTED)
            }
            append(" WRITE_SETTINGS=").append(Settings.System.canWrite(context))
            for ((name, types) in listOf(
                "getWifiApConfiguration" to emptyArray<Class<*>>(),
                "setWifiApConfiguration" to arrayOf(WifiConfiguration::class.java),
                "getWifiApState" to emptyArray<Class<*>>(),
                "startSoftAp" to arrayOf(WifiConfiguration::class.java),
                "stopSoftAp" to emptyArray<Class<*>>(),
            )) {
                append(" $name=").append(api.methods.any { it.name == name && it.parameterTypes.contentEquals(types) })
            }
            append(" ecarxWifiApClass=").append(runCatching { Class.forName("com.ecarx.xui.adaptapi.wifiap.WifiAp") }.isSuccess)
            append(" ecarxHostClass=").append(runCatching { Class.forName("com.ecarx.xui.adaptapi.wifiap.IWifiAPHost") }.isSuccess)
            append(" state=").append(state())
        }
        override fun read(): WifiConfiguration? = api.getMethod("getWifiApConfiguration").invoke(wifi) as? WifiConfiguration
        override fun write(config: WifiConfiguration): Boolean =
            api.getMethod("setWifiApConfiguration", WifiConfiguration::class.java).invoke(wifi, config) as? Boolean == true
        override fun softAp(enabled: Boolean): Boolean = if (enabled) {
            api.getMethod("startSoftAp", WifiConfiguration::class.java).invoke(wifi, null) as? Boolean == true
        } else api.getMethod("stopSoftAp").invoke(wifi) as? Boolean == true
        override fun request(enabled: Boolean, cancelled: () -> Boolean) = control.request(enabled, cancelled)
        override fun state() = control.observe()
    }
}
