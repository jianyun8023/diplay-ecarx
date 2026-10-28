package com.shilapi.xcertplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ResultReceiver
import android.provider.Settings
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One explicit user request, using the saved Android hotspot configuration. Never uses ADB,
 * changes credentials, grants permissions, retries a write, or exempts hidden API restrictions. */
class ManualHotspotControl internal constructor(private val platform: Platform) {
    constructor(context: Context) : this(AndroidPlatform(context.applicationContext))

    enum class State { OFF, ON, STARTING, STOPPING, FAILED, UNKNOWN }
    enum class Capability { AVAILABLE, PERMISSION_REQUIRED, UNSUPPORTED }
    enum class Result { ON, OFF, PERMISSION_REQUIRED, UNSUPPORTED, FAILED, TIMED_OUT, CANCELLED, BUSY }

    internal interface Platform {
        val sdk: Int
        fun permitted(): Boolean
        fun state(): State
        fun request(enabled: Boolean, response: (Int) -> Unit)
        fun now(): Long = System.nanoTime() / 1_000_000L
        fun pause() = Thread.sleep(100L)
    }

    fun observe(): State = runCatching { platform.state() }.getOrDefault(State.UNKNOWN)
    fun capability(): Capability = when {
        platform.sdk != 28 -> Capability.UNSUPPORTED
        !runCatching { platform.permitted() }.getOrDefault(false) -> Capability.PERMISSION_REQUIRED
        else -> Capability.AVAILABLE // Preflight only: the service may still reject this caller.
    }

    /** Blocking: call off the main thread. Cancellation never performs a compensating system write. */
    fun request(targetEnabled: Boolean, isCancelled: () -> Boolean): Result =
        request(targetEnabled, isCancelled, 8_000L)

    internal fun request(targetEnabled: Boolean, isCancelled: () -> Boolean, timeoutMillis: Long): Result {
        if (!synchronized(connectionGate) { busy.compareAndSet(false, true) }) return Result.BUSY
        var locked = false
        fun cancelled() = Thread.currentThread().isInterrupted || isCancelled()
        try {
            if (cancelled()) return Result.CANCELLED
            locked = CarHotspotTethering.startupLock.tryLock()
            if (!locked) return Result.BUSY
            when (capability()) {
                Capability.UNSUPPORTED -> return Result.UNSUPPORTED
                Capability.PERMISSION_REQUIRED -> return Result.PERMISSION_REQUIRED
                Capability.AVAILABLE -> Unit
            }
            val target = if (targetEnabled) State.ON else State.OFF
            val success = if (targetEnabled) Result.ON else Result.OFF
            val initial = observe()
            if (initial == target) return success
            // A boolean getter cannot distinguish OFF from a transition or a failure.
            if (initial == State.UNKNOWN) return Result.UNSUPPORTED
            if (initial == State.STARTING || initial == State.STOPPING) return Result.BUSY
            if (initial == State.FAILED) return Result.FAILED
            if (cancelled()) return Result.CANCELLED
            val deadline = platform.now() + timeoutMillis
            val response = AtomicInteger(-1)
            platform.request(targetEnabled) { response.compareAndSet(-1, it) }
            while (true) {
                if (cancelled()) return Result.CANCELLED
                val state = observe()
                if (cancelled()) return Result.CANCELLED
                if (state == target) return success
                if (state == State.FAILED || response.get() > 0) return Result.FAILED
                if (platform.now() >= deadline) return Result.TIMED_OUT
                platform.pause()
            }
        } catch (error: Exception) {
            if (error is InterruptedException) { Thread.currentThread().interrupt(); return Result.CANCELLED }
            val cause = (error as? InvocationTargetException)?.targetException ?: error
            return when (cause) {
                is SecurityException -> Result.PERMISSION_REQUIRED
                is ReflectiveOperationException -> Result.UNSUPPORTED
                else -> Result.FAILED
            }
        } catch (_: LinkageError) {
            return Result.UNSUPPORTED
        } finally {
            if (locked) CarHotspotTethering.startupLock.unlock()
            busy.set(false)
        }
    }

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
