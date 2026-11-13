package com.shihab.diplay.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager

/**
 * One-time migration of user settings from the historical `xcertplay_airplay` SharedPreferences file
 * to the current [AIRPLAY] file.
 *
 * The default SharedPreferences file lives in credential-encrypted (CE) storage, which is not
 * readable before the user unlocks. This app is [android:directBootAware] for
 * `LOCKED_BOOT_COMPLETED`, so its process (and `Application.onCreate`) can run while still locked:
 * touching CE storage there throws. [ensureMigrated] therefore never reads CE while locked; it waits
 * for `ACTION_USER_UNLOCKED` on the warmed process. [migrateAirPlay] itself is also guarded so no
 * caller can accidentally access CE before unlock. The legacy file is left in place (read-only) so an
 * interrupted or downgraded install still has the original data.
 */
object LegacyPreferences {
    const val AIRPLAY = "diplay_airplay"
    private const val LEGACY_AIRPLAY = "xcertplay_airplay"
    private const val MIGRATED = "migrated_from_legacy_airplay"

    /** The one-shot receiver registered while this process is waiting for user unlock. */
    @Volatile private var unlockReceiver: BroadcastReceiver? = null

    /**
     * Runs the migration when it is safe. If the user is already unlocked it migrates now; otherwise
     * it registers a one-shot `ACTION_USER_UNLOCKED` receiver and migrates when the user unlocks.
     * Safe to call more than once (Application start and the boot receiver both do).
     */
    fun ensureMigrated(context: Context): Boolean = ensureMigrated(context, ::isUserUnlocked)

    internal fun ensureMigrated(context: Context, isUnlocked: (Context) -> Boolean): Boolean {
        val app = context.applicationContext ?: context
        if (isUnlocked(app)) {
            // A direct read may beat the queued USER_UNLOCKED callback. Retire its receiver before
            // migrating synchronously so the pending callback cannot keep a stale process reference.
            val pending = synchronized(LegacyPreferences::class.java) {
                unlockReceiver.also { unlockReceiver = null }
            }
            pending?.let { runCatching { app.unregisterReceiver(it) } }
            return migrateAirPlay(app, isUnlocked)
        }

        var receiverToUnregister: BroadcastReceiver? = null
        synchronized(LegacyPreferences::class.java) {
            if (unlockReceiver != null) return false
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    val shouldMigrate = synchronized(LegacyPreferences::class.java) {
                        if (unlockReceiver !== this) false else {
                            unlockReceiver = null
                            true
                        }
                    }
                    runCatching { app.unregisterReceiver(this) }
                    if (shouldMigrate) migrateAirPlay(app, isUnlocked)
                }
            }
            unlockReceiver = receiver
            val registered = runCatching {
                app.registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
            }.isSuccess
            if (!registered) {
                if (unlockReceiver === receiver) unlockReceiver = null
                return false
            }

            // Close the window between the initial check and registration: an unlock in that window
            // is not delivered to a receiver that was not registered yet. Re-check immediately after
            // registration and migrate synchronously if the user has already unlocked.
            if (isUnlocked(app)) {
                if (unlockReceiver === receiver) unlockReceiver = null
                receiverToUnregister = receiver
            }
        }

        val receiver = receiverToUnregister ?: return false
        runCatching { app.unregisterReceiver(receiver) }
        return migrateAirPlay(app, isUnlocked)
    }

    /**
     * Copies the legacy file into [AIRPLAY] once. Must only run while the user is unlocked (CE storage
     * readable); it returns without touching storage otherwise.
     */
    fun migrateAirPlay(context: Context): Boolean = migrateAirPlay(context, ::isUserUnlocked)

    internal fun migrateAirPlay(context: Context, isUnlocked: (Context) -> Boolean): Boolean {
        if (!isUnlocked(context)) return false
        val target = context.getSharedPreferences(AIRPLAY, Context.MODE_PRIVATE)
        if (target.getBoolean(MIGRATED, false)) return true
        return synchronized(LegacyPreferences::class.java) {
            if (target.getBoolean(MIGRATED, false)) {
                true
            } else {
                val legacy = context.getSharedPreferences(LEGACY_AIRPLAY, Context.MODE_PRIVATE)
                val editor = target.edit()
                for ((key, value) in legacy.all) {
                    // Never overwrite a value the new file already holds; a partial migration wins.
                    if (target.contains(key)) continue
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                        else -> {}
                    }
                }
                editor.putBoolean(MIGRATED, true).commit()
            }
        }
    }

    private fun isUserUnlocked(context: Context): Boolean = runCatching {
        context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
    }.getOrDefault(false)
}
