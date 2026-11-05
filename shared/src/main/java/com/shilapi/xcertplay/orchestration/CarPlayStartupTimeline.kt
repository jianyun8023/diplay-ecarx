package com.shilapi.xcertplay.orchestration

import android.content.Context
import android.os.SystemClock
import android.util.Log

/**
 * Low-rate, privacy-safe checkpoints for car-hotspot and existing-Wi-Fi cold/warm starts.
 * elapsedRealtime includes sleep; never compare it directly with System.nanoTime timestamps.
 * The first decoded frame/callback is not proof that the physical display has presented it.
 */
object CarPlayStartupTimeline {
    enum class Stage {
        BOOT_BROADCAST,
        /** Application.onCreate: splits process fork and class loading from Activity creation. */
        APPLICATION_CREATE,
        LAUNCHER_CREATE,
        LAUNCHER_RESUME,
        AUTO_CONNECT_DISPATCH,
        HOTSPOT_PREPARE_BEGIN,
        HOTSPOT_PREPARE_END,
        PROJECTION_REQUEST,
        HOST_CREATE,
        CONTROLLER_START,
        LOCAL_MFI_READY,
        WIRELESS_RUN_START,
        NETWORK_BACKEND_READY,
        ENDPOINT_READY,
        AIRPLAY_LISTENING,
        BONJOUR_STARTED,
        RFCOMM_CONNECTED,
        IAP2_CSM_READY,
        IPHONE_MFI_ACCEPTED,
        START_SESSION_SENT,
        AIRPLAY_TCP_ACCEPTED,
        AIRPLAY_SESSION_ACTIVE,
        VIDEO_FIRST_FRAME,
        UI_FIRST_FRAME_CALLBACK,
        WIRELESS_FAILED,
    }

    private const val TAG = "DiPlay-Startup"
    private const val PREFS = "diplay_wireless_startup_timeline"
    private const val KEY = "events"
    private const val LIMIT = 80

    fun isMeasuredMode(mode: WirelessHotspotMode): Boolean =
        mode == WirelessHotspotMode.MANUAL || mode == WirelessHotspotMode.EXISTING_WIFI

    /**
     * Enum fields and numeric IDs only. Do not add SSID, BSSID, passwords, phone addresses,
     * private authentication material or exception messages to this persisted trace.
     */
    @Synchronized
    fun record(
        context: Context,
        stage: Stage,
        mode: WirelessHotspotMode,
        generation: Int? = null,
        owner: Int? = null,
        attempt: Int? = null,
    ) {
        if (!isMeasuredMode(mode)) return
        val line = format(SystemClock.elapsedRealtime(), stage, mode, generation, owner, attempt)
        Log.i(TAG, line)
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val recent = prefs.getString(KEY, "").orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
            prefs.edit().putString(KEY, (recent.takeLast(LIMIT - 1) + line).joinToString("\n")).apply()
        }
    }

    internal fun format(
        elapsedMs: Long,
        stage: Stage,
        mode: WirelessHotspotMode,
        generation: Int? = null,
        owner: Int? = null,
        attempt: Int? = null,
    ): String = buildString {
        append("elapsedSinceBootMs=").append(elapsedMs)
        append(" stage=").append(stage)
        append(" mode=").append(mode)
        generation?.let { append(" generation=").append(it) }
        owner?.let { append(" owner=").append(it) }
        attempt?.let { append(" attempt=").append(it) }
    }

    fun report(context: Context): String = runCatching {
        "--- Wireless startup timeline (last $LIMIT; elapsedSinceBootMs, not ignition time) ---\n" +
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "").orEmpty().ifBlank { "No hotspot/same-LAN startup samples recorded." }
    }.getOrDefault("Wireless startup timeline unavailable.")
}
