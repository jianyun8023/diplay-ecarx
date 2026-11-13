package com.shihab.diplay.network

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Pauses the head unit's automatic Wi-Fi network search while a Wi-Fi Direct CarPlay session runs.
 *
 * Wi-Fi Direct needs the car's Wi-Fi client switched on. While that client is not joined to a
 * network, car firmware searches every band every 10 s. The single radio
 * leaves the CarPlay channel for each search, and the iPhone's audio packets sent meanwhile are lost,
 * so music and voice stutter on a 10 s rhythm. The built-in car hotspot runs with the client off and
 * is not affected, and Same LAN needs the search, so only Wi-Fi Direct sessions pause it.
 *
 * The Android 9 platform grant lets the app directly switch automatic joining:
 * `enableWifiConnectivityManager`. One
 * process-wide worker owns controller leases and restores the search only after the last lease
 * ends. A durable marker precedes every disable; failed restores retry and recover when the app
 * next opens, and the framework resets the setting itself when the car restarts.
 */
internal class WifiScanPause(
    context: Context,
    private val log: (String) -> Unit,
    private val control: WifiScanPauseControl = ProcessWifiScanPause,
) {
    private val app = context.applicationContext
    private val owner = Any()
    @Volatile private var closed = false

    /** Asynchronously stops the periodic scans; repeated calls are harmless. */
    @Synchronized
    fun pause() {
        if (closed) return
        control.acquire(app, owner, { !closed }, log)
    }

    /** Ends this lease; shared recovery remains alive after this controller has closed. */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        control.release(app, owner, log)
    }

    internal companion object {
        internal const val TAG = "DiPlayWifiScan"
        fun eligible(backend: WirelessHotspotBackend, sdk: Int = Build.VERSION.SDK_INT): Boolean =
            backend == WirelessHotspotBackend.WIFI_P2P && sdk == 28

        fun restoreIfNeeded(context: Context) = ProcessWifiScanPause.recover(context.applicationContext)
    }
}

internal interface WifiScanPauseControl {
    fun acquire(app: Context, owner: Any, current: () -> Boolean, log: (String) -> Unit)
    fun release(app: Context, owner: Any, log: (String) -> Unit)
}

/** The single writer is shared across full controller rebuilds, including delayed restore retries. */
private object ProcessWifiScanPause : WifiScanPauseControl {
    private const val PREFS = "diplay_wifi_scan_pause"
    private const val JOURNAL = "restore_pending"
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor {
        Thread(it, WifiScanPause.TAG).apply { isDaemon = true }
    }
    private var session: WifiScanPauseSession? = null
    private var retry: ScheduledFuture<*>? = null

    override fun acquire(app: Context, owner: Any, current: () -> Boolean, log: (String) -> Unit) {
        enqueue(app) { state ->
            val outcome = state.acquire(owner, current)
            log("Wi-Fi connectivity scans paused=${outcome.paused} reason=${outcome.reason} api=${Build.VERSION.SDK_INT}")
        }
    }

    override fun release(app: Context, owner: Any, log: (String) -> Unit) {
        enqueue(app) { state -> log("Wi-Fi scan pause lease released; cleanup ok=${state.release(owner)}") }
    }

    fun recover(app: Context) {
        enqueue(app) { state ->
            if (state.recoveryPending()) Log.i(WifiScanPause.TAG, "Wi-Fi scan recovery restored=${state.recover()}")
        }
    }

    private fun enqueue(app: Context, action: (WifiScanPauseSession) -> Unit) {
        worker.execute {
            val state = state(app)
            runCatching { action(state) }.onFailure { Log.w(WifiScanPause.TAG, "Wi-Fi scan recovery will retry", it) }
            if (state.recoveryPending()) {
                if (retry == null) retry = worker.schedule({
                    retry = null
                    recover(app)
                }, 30, TimeUnit.SECONDS)
            } else {
                retry?.cancel(false)
                retry = null
            }
        }
    }

    private fun state(app: Context): WifiScanPauseSession {
        session?.let { return it }
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val journal = WifiScanPauseJournal(prefs.contains(JOURNAL)) { pending ->
            val edit = prefs.edit()
            if (pending) edit.putBoolean(JOURNAL, true) else edit.remove(JOURNAL)
            edit.commit()
        }
        return WifiScanPauseSession(
            setEnabled = { enabled -> switch(app, enabled) },
            loadJournal = { journal.pending },
            saveJournal = journal::save,
        ).also { session = it }
    }

    private fun switch(app: Context, enabled: Boolean): WifiScanSwitchResult {
        if (Build.VERSION.SDK_INT != 28) return WifiScanSwitchResult.UNSUPPORTED
        if (app.checkSelfPermission("android.permission.CONNECTIVITY_INTERNAL") != PackageManager.PERMISSION_GRANTED)
            return WifiScanSwitchResult.DENIED
        val wifi = app.getSystemService(WifiManager::class.java) ?: return WifiScanSwitchResult.UNSUPPORTED
        return WifiAutojoinSwitch(wifi).set(enabled)
    }
}
