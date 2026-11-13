package com.shihab.diplay.storage

import android.content.Context

/**
 * One-time migration of user settings from the historical `xcertplay_airplay` SharedPreferences file
 * to the current [AIRPLAY] file. It runs once at application start, before any component reads
 * settings, and copies every stored value the new file does not already have. The legacy file is left
 * in place (read-only) so an interrupted or downgraded install still has the original data.
 */
object LegacyPreferences {
    const val AIRPLAY = "diplay_airplay"
    private const val LEGACY_AIRPLAY = "xcertplay_airplay"
    private const val MIGRATED = "migrated_from_legacy_airplay"

    fun migrateAirPlay(context: Context) {
        val target = context.getSharedPreferences(AIRPLAY, Context.MODE_PRIVATE)
        if (target.getBoolean(MIGRATED, false)) return
        synchronized(LegacyPreferences::class.java) {
            if (target.getBoolean(MIGRATED, false)) return
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

    /** Sentinel key recorded in the new file once the legacy data has been considered. */
    internal const val MIGRATION_MARKER = MIGRATED
}
