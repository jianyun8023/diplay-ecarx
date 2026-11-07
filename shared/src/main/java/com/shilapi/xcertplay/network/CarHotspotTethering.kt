package com.shilapi.xcertplay.network

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ResultReceiver
import android.provider.Settings
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Uses the saved hotspot configuration. Normal startup keeps an active hotspot; a failed Android 9
 *  start gets one Wi-Fi recovery cycle. An automatic start may change only the saved band to 5 GHz
 *  when the AP is off and the caller asks for it ([CarHotspotBand]); credentials and a running AP
 *  are never touched. */
object CarHotspotTethering {
    // Shared with explicit manual control: never issue simultaneous start/stop requests.
    internal val startupLock = ReentrantLock()
    enum class Result(val diagnostic: String) {
        READY("Car hotspot is on"),
        PERMISSION_REQUIRED("Hotspot control permission is missing"),
        UNSUPPORTED("This firmware does not support automatic hotspot startup"),
        FAILED("The car could not start its hotspot"),
        TIMED_OUT("Timed out waiting for the car hotspot"),
        CANCELLED("Hotspot startup was cancelled"),
    }

    fun permitted(context: Context): Boolean =
        context.checkSelfPermission("android.permission.TETHER_PRIVILEGED") == PackageManager.PERMISSION_GRANTED ||
            Settings.System.canWrite(context)

    /** Whether this Android version lets an app with [permitted] access start tethering itself, without ADB. */
    fun appStartSupported(): Boolean = PlatformTetheringStart.supported()

    /** Blocking; serialize startup and connection requests, checking cancellation after acquiring the lock. */
    fun enable(
        context: Context,
        isCancelled: () -> Boolean,
        timeoutMillis: Long = WirelessStartupPolicy.HOTSPOT_READY_MILLIS,
        log: (String) -> Unit,
        preferredBand: ManualHotspotBand? = null,
    ): Result {
        // A client the car settings app turns on later would stop this hotspot again.
        runCatching { CarWifiClientGuard.beforeHotspotStart(context, log) }
        // Android 9 manual and connection startup share state checks and one bounded recovery.
        if (android.os.Build.VERSION.SDK_INT == 28) {
            val control = ManualHotspotControl(context, log)
            val state = control.observe()
            if (state != ManualHotspotControl.State.UNKNOWN) {
                // Only a saved-config rewrite while the AP is off can move the next start to 5 GHz.
                if (state == ManualHotspotControl.State.OFF) preferFiveGhz(context, preferredBand, log)
                val result = control.ensureHotspot(isCancelled, timeoutMillis)
                val outcome = when (result) {
                    ManualHotspotControl.Result.ON -> Result.READY
                    ManualHotspotControl.Result.PERMISSION_REQUIRED -> Result.PERMISSION_REQUIRED
                    ManualHotspotControl.Result.TIMED_OUT -> Result.TIMED_OUT
                    ManualHotspotControl.Result.CANCELLED -> Result.CANCELLED
                    ManualHotspotControl.Result.UNSUPPORTED -> Result.UNSUPPORTED
                    else -> Result.FAILED
                }
                log("car hotspot auto-enable: ${outcome.diagnostic}")
                return outcome
            }
        }
        // The generic start path has no platform state read before this point; an unknown report
        // is left alone so a running 2.4 GHz AP is never rewritten behind the driver's back.
        if (CarHotspotStatus.isEnabled(context) == false) preferFiveGhz(context, preferredBand, log)
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        val startReflection: (ResultReceiver) -> Unit = { receiver ->
            try {
                PlatformTetheringStart.start(context, receiver, log)
            } catch (error: Exception) {
                // #616: the report did not say which start path failed.
                log("car hotspot: Android start request failed ${describe(error)}")
                throw error
            }
        }
        return enableUntil(
            deadline,
            isCancelled,
            { permitted(context) },
            { CarHotspotStatus.isEnabled(context) },
            start = startReflection,
        ).also {
            if (it != Result.READY && it != Result.CANCELLED)
                log("car hotspot: Android state=${CarHotspotStatus.isEnabled(context) ?: "unknown"} hotspotPermission=${permitted(context)}")
            log("car hotspot auto-enable: ${it.diagnostic}")
        }
    }

    /** Writes the saved 5 GHz preference only for automatic starts that asked for it. */
    private fun preferFiveGhz(context: Context, preferredBand: ManualHotspotBand?, log: (String) -> Unit) {
        if (preferredBand == null || preferredBand == ManualHotspotBand.GHZ_2_4) return
        if (!CarHotspotBand.supported()) return
        CarHotspotBand.preferFiveGhz(context, log = log)
    }

    internal fun describe(error: Throwable): String {
        val cause = (error as? InvocationTargetException)?.targetException ?: error
        val reason = cause.javaClass.simpleName + (cause.message?.let { ": ${it.take(100)}" } ?: "")
        return if (cause === error) reason else "${error.javaClass.simpleName} caused by $reason"
    }

    internal fun enable(
        timeoutMillis: Long,
        isCancelled: () -> Boolean,
        canWrite: () -> Boolean,
        isEnabled: () -> Boolean?,
        start: (ResultReceiver) -> Unit,
    ): Result = enableUntil(System.nanoTime() + timeoutMillis * 1_000_000L,
        isCancelled, canWrite, isEnabled, start)

    private fun enableUntil(
        deadline: Long,
        isCancelled: () -> Boolean,
        canWrite: () -> Boolean,
        isEnabled: () -> Boolean?,
        start: (ResultReceiver) -> Unit,
    ): Result {
        fun stopped(): Result? = when {
            isCancelled() || Thread.currentThread().isInterrupted -> Result.CANCELLED
            System.nanoTime() >= deadline -> Result.TIMED_OUT
            else -> null
        }
        while (true) {
            stopped()?.let { return it }
            val remaining = (deadline - System.nanoTime()) / 1_000_000L
            if (remaining <= 0) return Result.TIMED_OUT
            try {
                if (startupLock.tryLock(minOf(250L, remaining), TimeUnit.MILLISECONDS)) break
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return Result.CANCELLED
            }
        }
        try {
            stopped()?.let { return it }
            val initialState = isEnabled()
            stopped()?.let { return it }
            if (initialState == true) return Result.READY
            val permission = canWrite()
            stopped()?.let { return it }
            if (!permission) return Result.PERMISSION_REQUIRED
            if (initialState == null) return Result.UNSUPPORTED
            val response = AtomicInteger(-1)
            try {
                stopped()?.let { return it }
                start(object : ResultReceiver(null) {
                    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                        response.set(resultCode)
                    }
                })
            } catch (error: Exception) {
                stopped()?.let { return it }
                return when {
                    error is InvocationTargetException -> if (error.targetException is SecurityException) Result.PERMISSION_REQUIRED else Result.FAILED
                    error is ReflectiveOperationException -> Result.UNSUPPORTED
                    error is SecurityException -> Result.PERMISSION_REQUIRED
                    else -> Result.FAILED
                }
            }
            while (true) {
                stopped()?.let { return it }
                val enabled = isEnabled()
                stopped()?.let { return it }
                if (enabled == true) return Result.READY
                if (response.get() > 0) return Result.FAILED
                val remainingMillis = (deadline - System.nanoTime()) / 1_000_000L
                if (remainingMillis <= 0) return Result.TIMED_OUT
                try {
                    Thread.sleep(minOf(250L, remainingMillis))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return Result.CANCELLED
                }
            }
        } finally {
            startupLock.unlock()
        }
    }
}
