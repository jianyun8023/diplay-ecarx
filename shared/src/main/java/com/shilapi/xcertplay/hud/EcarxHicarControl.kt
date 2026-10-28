package com.shilapi.xcertplay.hud

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/** The factory HiCar package used by the Lynk 01/05 820A head unit. */
object EcarxHicarControl {
    const val PACKAGE_NAME = "com.ecarx.hmi"
    const val PERMISSION = "android.permission.CHANGE_COMPONENT_ENABLED_STATE"

    data class State(val disabled: Boolean, val canChange: Boolean)

    @Suppress("DEPRECATION")
    fun state(context: Context): State? {
        val manager = context.packageManager
        val info = try {
            manager.getApplicationInfo(PACKAGE_NAME, PackageManager.MATCH_DISABLED_COMPONENTS)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        val disabled = when (manager.getApplicationEnabledSetting(PACKAGE_NAME)) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> !info.enabled
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> false
            else -> true
        }
        return State(disabled, context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED)
    }

    fun setDisabled(context: Context, disabled: Boolean): Boolean {
        val before = state(context) ?: return false
        if (!before.canChange) return false
        if (before.disabled == disabled) return true
        return runCatching {
            context.packageManager.setApplicationEnabledSetting(
                PACKAGE_NAME,
                if (disabled) PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                0,
            )
            state(context)?.disabled == disabled
        }.getOrElse {
            Log.w("DiPlay-HiCar", "Could not change factory HiCar state", it)
            false
        }
    }
}
