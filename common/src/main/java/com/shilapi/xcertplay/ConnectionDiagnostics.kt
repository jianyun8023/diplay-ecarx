package com.shilapi.xcertplay

import android.content.Context
import android.os.SystemClock
import com.shilapi.xcertplay.bluetooth.CarBluetoothProfileGuard

/** Bounded decision trace, not a claim about the iPhone's radio or audio output. */
internal object ConnectionDiagnostics {
    private const val PREFS = "diplay_connection_decisions"
    private const val KEY = "events"
    private const val LIMIT = 32

    enum class Source { ACL_BROADCAST, PHONE_ACL, APP_LAUNCH, UI_OR_CONTINUATION, RETRY }
    enum class Event {
        OPT_OUT, OWNER_PRESENT, NO_SELECTION, PERMISSION_DENIED, NO_DEVICE, ADDRESS_UNAVAILABLE,
        OTHER_DEVICE, THROTTLED, LAUNCH_REQUESTED, LAUNCH_RETURNED, LAUNCH_FAILED,
        INTENT_ACCEPTED, SETUP_BLOCKED, CONNECT_ENTERED, HOTSPOT_SETUP_REQUIRED, HOTSPOT_OFF,
        PHONE_SELECTION_REQUIRED, NOTIFICATION_PERMISSION, PROJECTION_REQUESTED,
        NOT_OWNER, SHUTTING_DOWN, MENU_OPEN, HANDSHAKE_RESET, RETRY_STOPPED, ALREADY_SCHEDULED,
        RETRY_BUDGET_STOPPED, RETRY_NOT_RETRYABLE, RETRY_SCHEDULED, STALE_GENERATION, ALREADY_RECOVERED, RETRY_DISPATCHED,
    }

    /** Only enums, clocks and numeric counters cross the persistence boundary; never exception messages. */
    @Synchronized
    fun record(context: Context, source: Source, event: Event, generation: Int? = null, delayMillis: Long? = null) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val line = "at=${System.currentTimeMillis()} elapsed=${SystemClock.elapsedRealtime()} " +
                "source=$source event=$event ownerPresent=${CarPlayBackgroundSession.hasSession()} " +
                "active=${CarPlayBackgroundSession.active}" +
                (generation?.let { " generation=$it" } ?: "") +
                (delayMillis?.let { " delayMs=$it" } ?: "")
            val previous = prefs.getString(KEY, "").orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
            prefs.edit().putString(KEY, (previous.takeLast(LIMIT - 1) + line).joinToString("\n")).apply()
        }
    }

    fun report(context: Context): String = runCatching {
        val events = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        "--- Connection decisions (last $LIMIT; requests are not successful connections) ---\n" +
            "Saved settings: phoneAclWake=${DiPlayPreferences.connectOnPhoneBluetooth(context)} " +
            "connectWhenOpened=${DiPlayPreferences.autoConnect(context)} " +
            "selectedPhonePresent=${DiPlayPreferences.phoneAddress(context) != null} " +
            "carBluetoothAudio=${AirPlayPersistence.loadCarBluetoothAudio(context)} " +
            "headUnitAdapterYield=${CarBluetoothProfileGuard.adapterEnabled(context)}\n" +
            events.ifBlank { "No decisions recorded; this does not prove an ACL event was absent." }
    }.getOrDefault("Connection decisions unavailable")
}
