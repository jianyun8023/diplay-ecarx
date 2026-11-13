package com.shihab.diplay

import android.app.Application
import com.shihab.diplay.storage.LegacyPreferences

/**
 * Application entry point. Runs the one-time SharedPreferences migration before any activity or
 * service reads settings, so the renamed `diplay_airplay` file already holds the user's values.
 */
class DiPlayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LegacyPreferences.ensureMigrated(this)
    }
}
