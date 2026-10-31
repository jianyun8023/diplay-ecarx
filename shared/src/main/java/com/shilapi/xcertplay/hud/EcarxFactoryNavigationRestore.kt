package com.shilapi.xcertplay.hud

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * One-time recovery for a head unit left with `ecarx.naviservice` paused by an older DiPlay build.
 *
 * Those builds disabled the whole package during guidance and persisted `owned=true` before doing
 * so. Enabling the package again does not restore widget instances the launcher removed, but it is
 * the step that lets the user re-add them; doing it here means an upgrade never needs a manual
 * `adb shell pm enable`. The saved original state is restored (normally `DEFAULT`), the service is
 * restarted, and the flag is cleared so this runs at most once per paused install.
 *
 * Only a package *we* paused is touched: a `DISABLED_USER` the driver chose is left alone because
 * the `owned` flag would be absent.
 */
object EcarxFactoryNavigationRestore {
    private const val PACKAGE_NAME = "ecarx.naviservice"
    private const val SERVICE_CLASS = "ecarx.naviservice.service.NaviService"
    private const val PREFS = "diplay_factory_navigation_control"
    private const val KEY_OWNED = "owned"
    private const val KEY_ORIGINAL = "original"
    private const val PERMISSION = "android.permission.CHANGE_COMPONENT_ENABLED_STATE"

    @Synchronized
    fun restoreIfPausedByUs(context: Context): String {
        val store = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!store.getBoolean(KEY_OWNED, false)) return "factoryNavigation restore=not-owned"
        val manager = context.packageManager
        val current = runCatching { manager.getApplicationEnabledSetting(PACKAGE_NAME) }.getOrNull()
        if (current == null) {
            store.edit().remove(KEY_OWNED).remove(KEY_ORIGINAL).commit()
            return "factoryNavigation restore=not-installed"
        }
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            return "factoryNavigation restore=permission-required owned=true"
        }
        val original = store.getInt(KEY_ORIGINAL, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        if (isDisabled(current)) {
            runCatching { manager.setApplicationEnabledSetting(PACKAGE_NAME, original, 0) }
        }
        val after = runCatching { manager.getApplicationEnabledSetting(PACKAGE_NAME) }.getOrNull()
        if (after == null || isDisabled(after)) {
            return "factoryNavigation restore=unconfirmed owned=true setting=$after"
        }
        runCatching {
            val intent = Intent().setClassName(PACKAGE_NAME, SERVICE_CLASS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
        store.edit().remove(KEY_OWNED).remove(KEY_ORIGINAL).commit()
        return "factoryNavigation restore=enabled setting=$after serviceStart=requested"
    }

    private fun isDisabled(state: Int): Boolean =
        state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
}
