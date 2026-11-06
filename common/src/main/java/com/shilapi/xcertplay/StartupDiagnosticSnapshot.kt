package com.shilapi.xcertplay

import android.content.Context

/** Only the latest received boot event and launch result; no Intent extras or device identifiers. */
internal object StartupDiagnosticSnapshot {
    private const val PREFS = "diplay_startup_diagnostics"

    /** Device-protected so the locked-boot checkpoint survives before credential storage is available. */
    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lockedBootReceived(context: Context, launchEnabled: Boolean) {
        runCatching {
            prefs(context).edit()
                .putLong("lockedBootReceivedAt", System.currentTimeMillis())
                .putBoolean("lockedLaunchEnabled", launchEnabled)
                .apply()
        }
    }

    fun received(context: Context, launchEnabled: Boolean) {
        runCatching {
            prefs(context).edit()
                .putLong("receivedAt", System.currentTimeMillis())
                .putBoolean("launchEnabled", launchEnabled)
                .putString("launchResult", if (launchEnabled) "pending" else "disabled")
                .remove("failureClass").apply()
        }
    }

    fun launchResult(context: Context, failure: RuntimeException? = null) {
        runCatching {
            prefs(context).edit()
                .putString("launchResult", if (failure == null) "startActivity-returned" else "failed")
                .putString("failureClass", failure?.javaClass?.simpleName?.take(80)
                    ?.replace(Regex("[^A-Za-z0-9_$]"), "?")).apply()
        }
    }

    fun report(context: Context): String = runCatching {
        val prefs = prefs(context)
        val receivedAt = prefs.getLong("receivedAt", 0)
        val lockedAt = prefs.getLong("lockedBootReceivedAt", 0)
        if (receivedAt == 0L) {
            if (lockedAt == 0L) {
                "Boot diagnostics received=false; no boot delivery has been recorded by this version."
            } else {
                "Boot diagnostics received=false lockedBootReceivedAtEpochMs=$lockedAt " +
                    "lockedLaunchEnabled=${prefs.getBoolean("lockedLaunchEnabled", false)}; " +
                    "no normal BOOT_COMPLETED has been recorded."
            }
        } else {
            "Boot diagnostics received=true receivedAtEpochMs=$receivedAt " +
                "launchEnabledAtBoot=${prefs.getBoolean("launchEnabled", false)} " +
                "launchResult=${prefs.getString("launchResult", "unknown")} " +
                "failureClass=${prefs.getString("failureClass", "none") ?: "none"}" +
                (if (lockedAt > 0) " lockedBootReceivedAtEpochMs=$lockedAt" else "") + "; " +
                "startActivity-returned does not prove the OEM displayed the app."
        }
    }.getOrDefault("Boot diagnostics unavailable.")
}
