package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat

/** Passive global broadcasts verified in the OEM/HY dumps; no commands or broadcast takeover. */
internal object EnvironmentBroadcastDiagnostics {
    private const val HY_MEDIA_ACTION = "com.hyphp.hywebview.HyMediaReceiver"
    private val actions = mapOf(
        // OEM NaviNotifyHelper.sendNaviStatus; strings/addresses are never read.
        "ECARX_NAVI_SERVICE_ACTION" to listOf("NAVI_STATUS"),
        // HY a.b publishes these map-window requests; geometry values are not collected.
        "com.autonavi.plus.showmap" to listOf("x", "y", "w", "h"),
        "com.autonavi.plus.closemap" to listOf("x", "y", "w", "h"),
        // HY media broadcasts; only known key presence.
        HY_MEDIA_ACTION to listOf("method", "getTrackName", "getArtistName", "getAlbumName", "getArtwork", "getDuration", "appName", "pkgName", "playState"),
    )
    private var context: Context? = null
    private var status = "not-started"
    private var received = 0L
    private var hyMediaEnabled = false
    private val events = ArrayDeque<String>()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = record(intent)
    }

    @Synchronized fun start(value: Context, hyCompatibility: Boolean = false) {
        if (context != null) return
        val app = value.applicationContext
        runCatching {
            ContextCompat.registerReceiver(app, receiver, IntentFilter().apply {
                actions.keys.filter { it != HY_MEDIA_ACTION || hyCompatibility }.forEach(::addAction)
            }, ContextCompat.RECEIVER_EXPORTED)
        }.onSuccess { context = app; hyMediaEnabled = hyCompatibility; status = "registered" }
            .onFailure { status = "register-failed:${it.javaClass.simpleName}" }
    }

    @Synchronized fun stop() {
        val app = context ?: return
        context = null
        runCatching { app.unregisterReceiver(receiver) }
            .onSuccess { status = "stopped" }
            .onFailure { status = "unregister-failed:${it.javaClass.simpleName}" }
    }

    @Synchronized private fun record(intent: Intent) {
        if (context == null) return
        val action = intent.action ?: return
        if (action == HY_MEDIA_ACTION && !hyMediaEnabled) return
        val keys = actions[action] ?: return
        received++
        val fields = runCatching {
            val present = keys.filter(intent::hasExtra)
            @Suppress("DEPRECATION")
            val naviStatus = if (action == "ECARX_NAVI_SERVICE_ACTION")
                (intent.extras?.get("NAVI_STATUS") as? Int)?.takeIf { it == 0 || it == 1 } else null
            "keys=${present.joinToString(",")} naviStatus=${naviStatus?.toString() ?: "unknown"}"
        }.getOrElse { "extras-unreadable:${it.javaClass.simpleName}" }
        val event = "elapsedMs=${SystemClock.elapsedRealtime()} epochMs=${System.currentTimeMillis()} action=$action sender=unknown $fields"
        while (events.size >= 64) events.removeFirst()
        events.addLast(event)
    }

    @Synchronized fun report(): String =
        "Environment broadcasts active=${context != null} status=$status received=$received retained=${events.size}\n" +
            "Scope: CarPlay session; ${if (hyMediaEnabled) 4 else 3} global actions; HY media=$hyMediaEnabled; sender unknown; no string values. " +
            "Package-targeted/local broadcasts and MQTT are not observable here; silence does not prove inactivity.\n" +
            events.joinToString("\n")
}
