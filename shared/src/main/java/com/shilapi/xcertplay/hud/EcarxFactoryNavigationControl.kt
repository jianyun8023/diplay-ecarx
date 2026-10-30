package com.shilapi.xcertplay.hud

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** Owns only the temporary pause of the fixed factory navigation package. */
object EcarxFactoryNavigationControl {
    const val PACKAGE_NAME = "ecarx.naviservice"
    private const val PREFS = "diplay_factory_navigation_control"

    @Synchronized
    @Suppress("DEPRECATION")
    fun acquire(context: Context): String {
        return runCatching {
            val manager = context.packageManager
            val info = manager.getApplicationInfo(PACKAGE_NAME, PackageManager.MATCH_DISABLED_COMPONENTS)
            val before = manager.getApplicationEnabledSetting(PACKAGE_NAME)
            val store = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val owned = store.getBoolean("owned", false)
            val canChange = context.checkSelfPermission(EcarxHicarControl.PERMISSION) == PackageManager.PERMISSION_GRANTED
            if (!owned) {
                val enabled = before == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                    before == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && info.enabled
                if (!enabled) return "factoryNavigation acquire=already-disabled owned=false setting=$before"
                if (!canChange)
                    return "factoryNavigation acquire=permission-required owned=false"
                // Persist restoration intent before the package change can kill the factory service.
                if (!store.edit().putInt("original", before).putBoolean("owned", true).commit())
                    return "factoryNavigation acquire=persist-failed"
            }
            if (!canChange)
                return "factoryNavigation acquire=permission-required owned=true"
            if (before != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
                manager.setApplicationEnabledSetting(PACKAGE_NAME, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0)
            val actual = manager.getApplicationEnabledSetting(PACKAGE_NAME)
            "factoryNavigation acquire=${if (actual == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) "disabled" else "unconfirmed"} " +
                "owned=true setting=$actual original=${store.getInt("original", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)}"
        }.getOrElse { "factoryNavigation acquire=failed error=${it.javaClass.simpleName}" }
    }

    /** Also used on process startup; a failed restoration stays owned for a later retry. */
    @Synchronized
    fun release(context: Context): String {
        return runCatching {
            val store = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!store.getBoolean("owned", false)) return "factoryNavigation release=not-owned"
            if (context.checkSelfPermission(EcarxHicarControl.PERMISSION) != PackageManager.PERMISSION_GRANTED)
                return "factoryNavigation release=permission-required owned=true"
            val original = store.getInt("original", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            val manager = context.packageManager
            if (manager.getApplicationEnabledSetting(PACKAGE_NAME) != original)
                manager.setApplicationEnabledSetting(PACKAGE_NAME, original, 0)
            val actual = manager.getApplicationEnabledSetting(PACKAGE_NAME)
            if (actual != original) return "factoryNavigation release=unconfirmed owned=true setting=$actual original=$original"
            // Enabling the package does not restart NaviService; HY explicitly starts it on handback.
            val intent = Intent().setClassName(PACKAGE_NAME, "ecarx.naviservice.service.NaviService")
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            if (started == null) return "factoryNavigation release=service-not-found owned=true setting=$actual"
            if (!store.edit().remove("owned").remove("original").commit())
                return "factoryNavigation release=persist-failed setting=$actual"
            "factoryNavigation release=restored owned=false setting=$actual serviceStart=requested"
        }.getOrElse { "factoryNavigation release=failed error=${it.javaClass.simpleName}" }
    }

    @Synchronized
    @Suppress("DEPRECATION")
    fun status(context: Context): String = runCatching {
        val store = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val manager = context.packageManager
        val info = manager.getApplicationInfo(PACKAGE_NAME, PackageManager.MATCH_DISABLED_COMPONENTS)
        "Factory navigation: installed=true setting=${manager.getApplicationEnabledSetting(PACKAGE_NAME)} " +
            "enabled=${info.enabled} owned=${store.getBoolean("owned", false)} " +
            "original=${store.getInt("original", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)} " +
            "canChange=${context.checkSelfPermission(EcarxHicarControl.PERMISSION) == PackageManager.PERMISSION_GRANTED}"
    }.getOrElse { "Factory navigation: unavailable=${it.javaClass.simpleName}" }
}
