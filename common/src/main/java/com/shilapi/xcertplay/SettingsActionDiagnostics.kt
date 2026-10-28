package com.shilapi.xcertplay

import android.content.Context
import android.os.SystemClock

/** Settings actions also need evidence before any CarPlay session has created a session log. */
internal object SettingsActionDiagnostics {
    private const val PREFS = "diplay_settings_actions"
    private const val LIMIT = 64
    enum class Action { MANUAL_HOTSPOT, HOTSPOT_RECOVERY, FACTORY_HICAR, USB_PERMISSIONS }

    @Synchronized
    fun record(context: Context, action: Action, detail: String) {
        val safe = DiagnosticRedactor.redact(detail) ?: return
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val line = "at=${System.currentTimeMillis()} elapsed=${SystemClock.elapsedRealtime()} " +
                "siriCommunicationCapture=${com.shilapi.xcertplay.shared.BuildConfig.SIRI_COMMUNICATION_CAPTURE} " +
                "action=$action $safe"
            val previous = prefs.getString("events", "").orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
            prefs.edit().putString("events", (previous.takeLast(LIMIT - 1) + line).joinToString("\n")).apply()
        }
    }

    fun report(context: Context): String = runCatching {
        val events = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("events", "").orEmpty()
        "--- Settings actions (last $LIMIT; requests are not confirmed system changes) ---\n" +
            events.ifBlank { "No settings actions recorded by this version." }
    }.getOrDefault("Settings actions unavailable")
}
