package com.shilapi.xcertplay.hud

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Owns only the factory navigation package paused by DiPlay; widget bindings are not restored. */
object EcarxFactoryNavigationRestore {
    private const val PACKAGE_NAME = "ecarx.naviservice"
    private const val SERVICE_CLASS = "ecarx.naviservice.service.NaviService"
    private const val PREFS = "diplay_factory_navigation_control"
    private const val KEY_OWNED = "owned"
    private const val KEY_ORIGINAL = "original"
    private const val PERMISSION = "android.permission.CHANGE_COMPONENT_ENABLED_STATE"
    private const val PROCESS_PERMISSION = "android.permission.REAL_GET_TASKS"
    private const val TAG = "DiPlay-ECARX"
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var pending: Runnable? = null
    /** Fires once when the chain behind [pending] ends, is superseded, or never started. */
    private var onSettled: (() -> Unit)? = null
    private var generation = 0L
    private var lastResult = "factoryNavigation idle"
    private var lastAsyncResult = "not-attempted"

    @Synchronized
    fun pauseForGuidance(context: Context): String {
        generation++
        pending?.let { handler.removeCallbacks(it) }
        pending = null
        settle()
        return runCatching {
            val store = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val manager = context.packageManager
            val before = manager.getApplicationEnabledSetting(PACKAGE_NAME)
            val owned = store.getBoolean(KEY_OWNED, false)
            if (isDisabled(before)) {
                return record("factoryNavigation pause=already-disabled owned=$owned setting=$before")
            }
            check(context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED) { "permission-required" }
            // Persist before changing the package so a killed process can recover on the next open.
            if (!owned) {
                check(store.edit().putBoolean(KEY_OWNED, true).putInt(KEY_ORIGINAL, before).commit()) { "marker-write-failed" }
            }
            manager.setApplicationEnabledSetting(PACKAGE_NAME, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0)
            val after = manager.getApplicationEnabledSetting(PACKAGE_NAME)
            check(after == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) { "unconfirmed setting=$after" }
            record("factoryNavigation pause=disabled owned=true before=$before setting=$after")
        }.getOrElse { record("factoryNavigation pause=failed error=${it.message}", true) }
    }

    /**
     * Call only without active guidance; repeated calls keep the original two-second deadline.
     *
     * Enabling the package is synchronous. Starting and confirming the service takes up to about
     * seven more seconds on the main looper, so a caller whose process may not live that long (a
     * boot broadcast with nothing else running) passes [onSettled] and keeps itself alive until it
     * fires. It fires exactly once: at once when nothing asynchronous was started, otherwise when
     * the chain ends with a result or is cancelled by new guidance.
     */
    @Synchronized
    fun restoreIfPausedByUs(context: Context, onSettled: (() -> Unit)? = null): String {
        val store = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!store.getBoolean(KEY_OWNED, false)) {
            onSettled?.invoke()
            return record("factoryNavigation restore=not-owned")
        }
        if (pending != null) {
            // Another caller's chain is already running; it does not hold this caller's process.
            onSettled?.invoke()
            return lastResult
        }
        return runCatching {
            check(context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED) { "permission-required owned=true" }
            val manager = context.packageManager
            val before = manager.getApplicationEnabledSetting(PACKAGE_NAME)
            val original = store.getInt(KEY_ORIGINAL, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            check(original == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT || original == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) { "invalid-original setting=$original" }
            if (before != original) manager.setApplicationEnabledSetting(PACKAGE_NAME, original, 0)
            val after = manager.getApplicationEnabledSetting(PACKAGE_NAME)
            check(after == original) { "unconfirmed owned=true setting=$after" }
            val app = context.applicationContext
            val token = ++generation
            settle()
            this.onSettled = onSettled
            pending = Runnable { startRestoredService(app, token) }.also { handler.postDelayed(it, 2_000L) }
            record("factoryNavigation restore=enabled before=$before setting=$after serviceStart=delayed owned=true")
        }.getOrElse {
            onSettled?.invoke()
            record("factoryNavigation restore=failed error=${it.message}", true)
        }
    }

    @Synchronized
    fun state(context: Context): String {
        val owned = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_OWNED, false)
        val setting = runCatching { context.packageManager.getApplicationEnabledSetting(PACKAGE_NAME) }.getOrNull()
        return "$lastResult; owned=$owned setting=$setting pending=${pending != null} async=$lastAsyncResult"
    }

    @Synchronized
    private fun startRestoredService(context: Context, token: Long) {
        if (token != generation) return
        pending = null
        runCatching {
            check(!isDisabled(context.packageManager.getApplicationEnabledSetting(PACKAGE_NAME))) { "package-disabled" }
            val intent = Intent().setClassName(PACKAGE_NAME, SERVICE_CLASS)
            val component = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            check(component != null) { "service-not-found" }
            lastAsyncResult = record("factoryNavigation restore=enabled serviceStart=requested owned=true")
            pending = Runnable { verifyRestart(context, token, 5) }.also { handler.postDelayed(it, 1_000L) }
        }.onFailure {
            lastAsyncResult = record("factoryNavigation restore=failed owned=true error=${it.message}", true)
            settle()
        }
    }

    @Synchronized
    private fun verifyRestart(context: Context, token: Long, attempts: Int) {
        if (token != generation) return
        pending = null
        runCatching {
            check(context.checkSelfPermission(PROCESS_PERMISSION) == PackageManager.PERMISSION_GRANTED) { "process-permission-required" }
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val running = manager.runningAppProcesses.orEmpty().any { process ->
                process.pid > 0 && (process.processName == PACKAGE_NAME || process.pkgList?.contains(PACKAGE_NAME) == true)
            }
            if (!running && attempts > 1) {
                pending = Runnable { verifyRestart(context, token, attempts - 1) }.also { handler.postDelayed(it, 1_000L) }
                return
            }
            check(running) { "process-unconfirmed" }
            check(!isDisabled(context.packageManager.getApplicationEnabledSetting(PACKAGE_NAME))) { "package-disabled" }
            check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_OWNED).remove(KEY_ORIGINAL).commit()) { "marker-clear-failed" }
            lastAsyncResult = record("factoryNavigation restore=running serviceStart=confirmed owned=false")
        }.onFailure { lastAsyncResult = record("factoryNavigation restore=failed owned=true error=${it.message}", true) }
        if (pending == null) settle()
    }

    /** Hands the settled signal to the waiting caller once; later calls are no-ops. */
    private fun settle() {
        val callback = onSettled ?: return
        onSettled = null
        runCatching(callback).onFailure { Log.w(TAG, "factoryNavigation settle callback failed", it) }
    }

    private fun record(message: String, failed: Boolean = false): String {
        lastResult = message
        if (failed) Log.w(TAG, message) else Log.i(TAG, message)
        return message
    }

    private fun isDisabled(state: Int): Boolean =
        state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
}
