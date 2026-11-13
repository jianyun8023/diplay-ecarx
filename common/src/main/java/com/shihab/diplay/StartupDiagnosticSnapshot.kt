package com.shihab.diplay

import android.content.Context
import android.bluetooth.BluetoothAdapter
import android.content.SharedPreferences
import android.os.Process
import android.os.UserManager
import android.os.SystemClock
import kotlin.math.abs

/** Only the latest boot's checkpoints and launch result; no Intent extras or device identifiers. */
internal object StartupDiagnosticSnapshot {
    private const val PREFS = "diplay_startup_diagnostics"
    private const val BOOT_ORIGIN_TOLERANCE_MILLIS = 30_000L

    /** Device-protected so the locked-boot checkpoint survives before credential storage is available. */
    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Whether credential-encrypted storage was already usable at this broadcast. A head unit without
     * a lock-screen credential may unlock long before BOOT_COMPLETED; then CarPlay could start here.
     */
    private fun userUnlocked(context: Context): String = runCatching {
        context.getSystemService(UserManager::class.java)?.isUserUnlocked?.toString()
    }.getOrNull() ?: "unknown"

    /** STATE_OFF=10 … STATE_ON=12; read-only, never enables the radio. */
    private fun bluetoothState(): Int = runCatching {
        @Suppress("DEPRECATION")
        BluetoothAdapter.getDefaultAdapter()?.state
    }.getOrNull() ?: -1

    /** A new locked-boot broadcast starts a new observation; never mix it with an earlier boot. */
    fun lockedBootReceived(context: Context, launchEnabled: Boolean) {
        runCatching {
            prefs(context).edit().clear()
                .putLong("lockedBootReceivedAt", System.currentTimeMillis())
                .putLong("lockedBootElapsedMs", SystemClock.elapsedRealtime())
                .putInt("lockedBootPid", Process.myPid())
                .putBoolean("lockedLaunchEnabled", launchEnabled)
                .putString("lockedUserUnlocked", userUnlocked(context))
                .putInt("lockedBluetoothState", bluetoothState())
                .apply()
        }
    }

    /** Both broadcasts belong to this boot only if their wall-clock/elapsed origins agree. */
    private fun sameBoot(prefs: SharedPreferences, nowAt: Long, nowElapsed: Long): Boolean {
        val lockedAt = prefs.getLong("lockedBootReceivedAt", 0)
        val lockedElapsed = prefs.getLong("lockedBootElapsedMs", -1L)
        if (lockedAt <= 0 || lockedElapsed < 0 || lockedElapsed > nowElapsed) return false
        return abs((lockedAt - lockedElapsed) - (nowAt - nowElapsed)) <=
            BOOT_ORIGIN_TOLERANCE_MILLIS
    }

    fun received(context: Context, launchEnabled: Boolean) {
        runCatching {
            val prefs = prefs(context)
            val nowAt = System.currentTimeMillis()
            val nowElapsed = SystemClock.elapsedRealtime()
            val editor = prefs.edit()
            // The OEM may deliver only BOOT_COMPLETED. Drop any locked-boot marker from a
            // previous power cycle instead of reporting a fictional two-broadcast timeline.
            if (!sameBoot(prefs, nowAt, nowElapsed)) {
                editor.remove("lockedBootReceivedAt").remove("lockedBootElapsedMs")
                    .remove("lockedBootPid").remove("lockedLaunchEnabled")
                    .remove("lockedUserUnlocked").remove("lockedBluetoothState")
            }
            editor.putLong("receivedAt", nowAt)
                .putLong("receivedElapsedMs", nowElapsed)
                .putInt("receivedPid", Process.myPid())
                .putBoolean("launchEnabled", launchEnabled)
                .putInt("receivedBluetoothState", bluetoothState())
                .putString("launchResult", if (launchEnabled) "pending" else "disabled")
                .remove("failureClass").remove("launchTarget").apply()
        }
    }

    fun launchResult(context: Context, failure: RuntimeException? = null, target: String = "home") {
        runCatching {
            prefs(context).edit()
                .putString("launchResult", if (failure == null) "startActivity-returned" else "failed")
                .putString("launchTarget", target)
                .putString("failureClass", failure?.javaClass?.simpleName?.take(80)
                    ?.replace(Regex("[^A-Za-z0-9_$]"), "?")).apply()
        }
    }

    fun report(context: Context): String = runCatching {
        val prefs = prefs(context)
        val receivedAt = prefs.getLong("receivedAt", 0)
        val lockedAt = prefs.getLong("lockedBootReceivedAt", 0)
        val lockedDetails = if (lockedAt > 0) {
            " lockedBootReceivedAtEpochMs=$lockedAt" +
                " lockedBootElapsedMs=${prefs.getLong("lockedBootElapsedMs", -1L)}" +
                " lockedBootPid=${prefs.getInt("lockedBootPid", -1)}" +
                " lockedLaunchEnabled=${prefs.getBoolean("lockedLaunchEnabled", false)}" +
                " lockedUserUnlocked=${prefs.getString("lockedUserUnlocked", "unknown")}" +
                " lockedBluetoothState=${prefs.getInt("lockedBluetoothState", -1)}"
        } else ""
        if (receivedAt == 0L) {
            if (lockedAt == 0L) {
                "Boot diagnostics received=false; no boot delivery has been recorded by this version."
            } else {
                "Boot diagnostics received=false$lockedDetails; no normal BOOT_COMPLETED has been recorded."
            }
        } else {
            val receivedPid = prefs.getInt("receivedPid", -1)
            val receivedElapsed = prefs.getLong("receivedElapsedMs", -1L)
            val reuse = if (lockedAt > 0) {
                " sameProcessAtBoot=${receivedPid == prefs.getInt("lockedBootPid", -1)}" +
                    " lockedToNormalBootMs=${(receivedElapsed - prefs.getLong("lockedBootElapsedMs", receivedElapsed)).coerceAtLeast(0L)}"
            } else ""
            "Boot diagnostics received=true receivedAtEpochMs=$receivedAt " +
                "launchEnabledAtBoot=${prefs.getBoolean("launchEnabled", false)} " +
                "launchResult=${prefs.getString("launchResult", "unknown")} " +
                "failureClass=${prefs.getString("failureClass", "none") ?: "none"}" +
                " launchTarget=${prefs.getString("launchTarget", "none")}" +
                " receivedElapsedMs=$receivedElapsed receivedPid=$receivedPid" +
                " receivedBluetoothState=${prefs.getInt("receivedBluetoothState", -1)}" +
                lockedDetails + reuse + "; " +
                "startActivity-returned does not prove the OEM displayed the app."
        }
    }.getOrDefault("Boot diagnostics unavailable.")
}
