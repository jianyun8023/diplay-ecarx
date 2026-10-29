package com.shilapi.xcertplay.network

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ResultReceiver
import android.provider.Settings
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One explicit hotspot or Wi-Fi request, preserving the saved hotspot configuration. Never uses ADB,
 * changes credentials, grants permissions, retries a write, or exempts hidden API restrictions. */
class ManualHotspotControl @JvmOverloads internal constructor(
    private val platform: Platform,
    private val onDiagnostic: (String) -> Unit = {},
) {
    @JvmOverloads constructor(context: Context, onDiagnostic: (String) -> Unit = {}) :
        this(AndroidPlatform(context.applicationContext), onDiagnostic)

    enum class State { OFF, ON, STARTING, STOPPING, FAILED, UNKNOWN }
    enum class Capability { AVAILABLE, PERMISSION_REQUIRED, UNSUPPORTED }
    enum class Result { ON, OFF, PERMISSION_REQUIRED, UNSUPPORTED, FAILED, TIMED_OUT, CANCELLED, BUSY }

    internal interface Platform {
        val sdk: Int
        fun permitted(): Boolean
        fun state(): State
        fun request(enabled: Boolean, response: (Int) -> Unit)
        fun wifiPermitted(): Boolean
        fun wifiState(): State
        fun requestWifi(enabled: Boolean, response: (Int) -> Unit)
        fun now(): Long = System.nanoTime() / 1_000_000L
        fun pause() = Thread.sleep(100L)
    }

    fun observe(): State = runCatching { platform.state() }.getOrDefault(State.UNKNOWN)
    fun observeWifi(): State = runCatching { platform.wifiState() }.getOrDefault(State.UNKNOWN)
    fun wifiCapability(): Capability = when {
        platform.sdk != 28 -> Capability.UNSUPPORTED
        !runCatching { platform.wifiPermitted() }.getOrDefault(false) -> Capability.PERMISSION_REQUIRED
        else -> Capability.AVAILABLE
    }
    fun capability(): Capability = when {
        platform.sdk != 28 -> Capability.UNSUPPORTED
        !runCatching { platform.permitted() }.getOrDefault(false) -> Capability.PERMISSION_REQUIRED
        else -> Capability.AVAILABLE // Preflight only: the service may still reject this caller.
    }

    /** Blocking: call off the main thread. Cancellation never performs a compensating system write. */
    fun request(targetEnabled: Boolean, isCancelled: () -> Boolean): Result =
        request(targetEnabled, isCancelled, 8_000L)

    fun ensureHotspot(isCancelled: () -> Boolean, timeoutMillis: Long = 8_000L): Result {
        if (timeoutMillis <= 0) return Result.TIMED_OUT
        val deadline = platform.now() + timeoutMillis
        val result = request(true, isCancelled, timeoutMillis, deadline = deadline)
        return if (result == Result.FAILED || (result == Result.TIMED_OUT && observe() == State.FAILED))
            request(true, isCancelled, timeoutMillis, recover = true,
                deadline = minOf(deadline, platform.now() + 8_000L)) else result
    }

    internal fun request(targetEnabled: Boolean, isCancelled: () -> Boolean, timeoutMillis: Long,
        wifi: Boolean = false, recover: Boolean = false, deadline: Long = platform.now() + timeoutMillis): Result {
        if (!synchronized(connectionGate) { busy.compareAndSet(false, true) }) return Result.BUSY
        var locked = false
        var radio = if (wifi) "radio=wifi " else ""
        var requested = targetEnabled
        fun cancelled() = Thread.currentThread().isInterrupted || isCancelled()
        try {
            if (cancelled()) return Result.CANCELLED
            if (platform.now() >= deadline) return Result.TIMED_OUT
            locked = CarHotspotTethering.startupLock.tryLock()
            if (!locked) return Result.BUSY
            val capability = if (wifi) wifiCapability() else capability()
            report("${radio}phase=preflight enabled=$targetEnabled sdk=${platform.sdk} capability=$capability")
            when (capability) {
                Capability.UNSUPPORTED -> return Result.UNSUPPORTED
                Capability.PERMISSION_REQUIRED -> return Result.PERMISSION_REQUIRED
                Capability.AVAILABLE -> Unit
            }
            if (recover) {
                when (wifiCapability()) {
                    Capability.UNSUPPORTED -> return Result.UNSUPPORTED
                    Capability.PERMISSION_REQUIRED -> return Result.PERMISSION_REQUIRED
                    Capability.AVAILABLE -> Unit
                }
                var state = observeWifi()
                while (state == State.STARTING || state == State.STOPPING) {
                    if (cancelled()) return Result.CANCELLED
                    if (platform.now() >= deadline) return Result.TIMED_OUT
                    platform.pause()
                    state = observeWifi()
                }
                report("recovery phase=wifi-initial state=$state")
                if (state == State.UNKNOWN) return Result.UNSUPPORTED
                if (state == State.FAILED) return Result.FAILED
            }
            val steps = if (recover) listOf(false to false, true to false, true to true, true to false, false to true)
                else listOf(wifi to targetEnabled)
            for ((stepWifi, enabled) in steps) {
                if (cancelled()) return Result.CANCELLED
                if (platform.now() >= deadline) return Result.TIMED_OUT
                radio = if (stepWifi) "radio=wifi " else ""
                requested = enabled
                val target = if (enabled) State.ON else State.OFF
                var initial = if (stepWifi) observeWifi() else observe()
                while (initial == State.STARTING || initial == State.STOPPING) {
                    if (cancelled()) return Result.CANCELLED
                    if (platform.now() >= deadline) return Result.TIMED_OUT
                    platform.pause()
                    initial = if (stepWifi) observeWifi() else observe()
                }
                report("${radio}phase=observed enabled=$enabled before=$initial")
                if (initial == target) continue
                if (initial == State.UNKNOWN) return Result.UNSUPPORTED
                if (initial == State.STARTING || initial == State.STOPPING) return Result.BUSY
                val stoppingFailedHotspot = recover && !stepWifi && !enabled && initial == State.FAILED
                if (initial == State.FAILED && !stoppingFailedHotspot && !(recover && !stepWifi && enabled)) return Result.FAILED
                if (cancelled()) return Result.CANCELLED
                if (platform.now() >= deadline) return Result.TIMED_OUT
                val response = AtomicInteger(-1)
                val callback: (Int) -> Unit = {
                    response.compareAndSet(-1, it)
                    report("${radio}phase=callback enabled=$enabled code=$it")
                }
                if (stepWifi) platform.requestWifi(enabled, callback) else platform.request(enabled, callback)
                var failedStateWasCleared = initial != State.FAILED
                while (true) {
                    if (cancelled()) return Result.CANCELLED
                    val state = if (stepWifi) observeWifi() else observe()
                    if (cancelled()) return Result.CANCELLED
                    if (state == target) break
                    if (response.get() > 0) return Result.FAILED
                    if (stoppingFailedHotspot && state == State.FAILED) {
                        // FAILED can stay latched until the Wi-Fi cycle; do not claim it was observed OFF.
                        report("recovery phase=stop-requested state=FAILED; cycling Wi-Fi")
                        break
                    }
                    if (state != State.FAILED) failedStateWasCleared = true
                    if (state == State.FAILED && failedStateWasCleared) return Result.FAILED
                    if (platform.now() >= deadline) return Result.TIMED_OUT
                    platform.pause()
                }
                if (recover) report("recovery phase=step-finished ${radio}enabled=$enabled")
            }
            return if (targetEnabled) Result.ON else Result.OFF
        } catch (error: Exception) {
            if (error is InterruptedException) { Thread.currentThread().interrupt(); return Result.CANCELLED }
            val cause = (error as? InvocationTargetException)?.targetException ?: error
            report("${radio}phase=failure enabled=$requested error=${cause.javaClass.simpleName}")
            return when (cause) {
                is SecurityException -> Result.PERMISSION_REQUIRED
                is ReflectiveOperationException -> Result.UNSUPPORTED
                else -> Result.FAILED
            }
        } catch (_: LinkageError) {
            report("${radio}phase=failure enabled=$requested error=LinkageError")
            return Result.UNSUPPORTED
        } finally {
            if (locked) CarHotspotTethering.startupLock.unlock()
            busy.set(false)
        }
    }

    private fun report(message: String) { runCatching { onDiagnostic(message) } }

    companion object {
        private val busy = AtomicBoolean(false)
        private val connectionGate = Any()
        /** Register a connection owner atomically against a manual request. No queued restart. */
        fun runIfIdle(action: () -> Unit): Boolean = synchronized(connectionGate) {
            if (busy.get()) false else { action(); true }
        }
        fun isBusy(): Boolean = busy.get()
        /** Exact API 28 platform signatures; exposed internally for fake-service contract tests. */
        internal fun invokePlatform(service: Any, api: Class<*>, packageName: String,
            enabled: Boolean, response: (Int) -> Unit) {
            val intType = Int::class.javaPrimitiveType!!
            if (enabled) {
                val receiver = object : ResultReceiver(null) {
                    override fun onReceiveResult(resultCode: Int, resultData: android.os.Bundle?) = response(resultCode)
                }
                api.getMethod("startTethering", intType, ResultReceiver::class.java,
                    Boolean::class.javaPrimitiveType!!, String::class.java)
                    .invoke(service, 0, receiver, false, packageName)
            } else {
                api.getMethod("stopTethering", intType, String::class.java)
                    .invoke(service, 0, packageName)
            }
        }

        internal fun decode(state: Int?): State = when (state) {
            10 -> State.STOPPING
            11 -> State.OFF
            12 -> State.STARTING
            13 -> State.ON
            14 -> State.FAILED
            else -> State.UNKNOWN
        }
    }

    private class AndroidPlatform(private val context: Context) : Platform {
        override val sdk get() = Build.VERSION.SDK_INT
        override fun permitted() = Settings.System.canWrite(context)
        override fun wifiPermitted() = context.checkSelfPermission(android.Manifest.permission.CHANGE_WIFI_STATE) ==
            PackageManager.PERMISSION_GRANTED
        override fun wifiState(): State = when (context.getSystemService(WifiManager::class.java)?.wifiState) {
            WifiManager.WIFI_STATE_DISABLING -> State.STOPPING
            WifiManager.WIFI_STATE_DISABLED -> State.OFF
            WifiManager.WIFI_STATE_ENABLING -> State.STARTING
            WifiManager.WIFI_STATE_ENABLED -> State.ON
            else -> State.UNKNOWN
        }
        @Suppress("DEPRECATION")
        override fun requestWifi(enabled: Boolean, response: (Int) -> Unit) {
            val wifi = context.getSystemService(WifiManager::class.java)
                ?: throw NoSuchMethodException("Wi-Fi service unavailable")
            response(if (wifi.setWifiEnabled(enabled)) 0 else 1)
        }
        override fun state(): State {
            val wifi = context.getSystemService(WifiManager::class.java) ?: return State.UNKNOWN
            return decode(WifiManager::class.java.getMethod("getWifiApState").invoke(wifi) as? Int)
        }

        override fun request(enabled: Boolean, response: (Int) -> Unit) {
            // API 28 AOSP IConnectivityManager signatures. No vendor setter, shell, or exemption.
            val manager = context.getSystemService(ConnectivityManager::class.java)
                ?: throw NoSuchMethodException("Connectivity service unavailable")
            val service = ConnectivityManager::class.java.getDeclaredField("mService")
                .apply { isAccessible = true }.get(manager)
                ?: throw NoSuchMethodException("Connectivity service unavailable")
            invokePlatform(service, Class.forName("android.net.IConnectivityManager"), context.packageName, enabled, response)
        }
    }
}
