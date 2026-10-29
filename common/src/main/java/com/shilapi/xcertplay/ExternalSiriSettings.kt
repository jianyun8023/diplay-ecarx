package com.shilapi.xcertplay

import android.content.Context

internal object ExternalSiriSettings {
    fun enabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("diplay_external_siri", Context.MODE_PRIVATE)
        if (prefs.contains("enabled")) return prefs.getBoolean("enabled", false)
        // Preserve the effective choice from the former two-switch launcher setting.
        val legacy = context.getSharedPreferences("diplay_hy_compatibility", Context.MODE_PRIVATE)
        return legacy.getBoolean("enabled", false) && legacy.getBoolean("siri_on_launch", false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("diplay_external_siri", Context.MODE_PRIVATE)
            .edit().putBoolean("enabled", enabled).apply()
    }
}
