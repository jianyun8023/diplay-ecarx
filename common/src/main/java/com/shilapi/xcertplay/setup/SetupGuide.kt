package com.shilapi.xcertplay.setup

import android.content.Context

/** The steps of the first-run guide. What each step offers lives in the UI, per head unit. */
object SetupGuide {
    const val STEP_CAR = 0
    const val STEP_CONNECTION = 1
    const val STEP_IPHONE = 2
    const val STEP_FEATURES = 3
    const val STEP_DONE = 4
    const val STEP_COUNT = 5

    private fun prefs(context: Context) = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)

    fun seen(context: Context): Boolean = prefs(context).getBoolean("setup_guide_seen", false)

    fun markSeen(context: Context) {
        prefs(context).edit().putBoolean("setup_guide_seen", true).apply()
    }

    /** Only a first launch opens the guide by itself; anyone with a saved iPhone has already set up. */
    fun shouldOpenOnLaunch(seen: Boolean, phoneChosen: Boolean): Boolean = !seen && !phoneChosen
}
