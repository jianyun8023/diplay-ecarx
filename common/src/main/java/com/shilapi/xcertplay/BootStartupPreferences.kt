package com.shilapi.xcertplay

import android.content.Context

/**
 * The small part of the startup setting that must be readable before the user unlocks the device.
 *
 * `LOCKED_BOOT_COMPLETED` runs before credential-encrypted storage is available, so the normal
 * `AirPlayPersistence` preference is not readable there. Keep a device-protected mirror of the one
 * boolean that decides whether the boot receiver should warm the process. Everything else remains
 * in credential-protected storage and is read only after the normal boot broadcast.
 */
internal object BootStartupPreferences {
    private const val PREFS = "diplay_boot_startup"
    private const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"

    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadAutoStartOnBoot(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_START_ON_BOOT, true)

    fun saveAutoStartOnBoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_START_ON_BOOT, enabled).apply()
    }
}
