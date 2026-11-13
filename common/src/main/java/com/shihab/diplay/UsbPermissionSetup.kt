package com.shihab.diplay

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit setup of this app's permissions using the vehicle's platform grant. */
internal object UsbPermissionSetup {
    internal val accessibilityLock = Any()

    enum class Permission {
        ACCESSIBILITY, USAGE, OVERLAY;

        fun granted(context: Context): Boolean = runCatching {
            when (this) {
                ACCESSIBILITY -> UsbAutoConfirmService.isEnabled(context) &&
                    Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
                USAGE -> HomeScreenMonitor.hasAccess(context)
                OVERLAY -> Settings.canDrawOverlays(context)
            }
        }.getOrDefault(false)

        fun apply(context: Context) {
            if (this == ACCESSIBILITY) {
                check(enableAccessibility(context, ComponentName(context, UsbAutoConfirmService::class.java)))
            } else {
                check(context.checkSelfPermission("android.permission.MANAGE_APP_OPS_MODES") == PackageManager.PERMISSION_GRANTED)
                val manager = context.getSystemService(AppOpsManager::class.java)
                AppOpsManager::class.java.getMethod("setMode", String::class.java, Int::class.javaPrimitiveType,
                    String::class.java, Int::class.javaPrimitiveType).invoke(manager,
                    if (this == USAGE) AppOpsManager.OPSTR_GET_USAGE_STATS else AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                    context.applicationInfo.uid, context.packageName, AppOpsManager.MODE_ALLOWED)
            }
        }
    }

    fun snapshot(context: Context): Map<Permission, Boolean> = Permission.entries.associateWith { it.granted(context) }

    /** Keeps other services and the existing crash-rebind delay; writes are always read back. */
    internal fun enableAccessibility(context: Context, service: ComponentName,
        rebind: () -> Boolean = { false }, shouldContinue: () -> Boolean = { true }): Boolean = synchronized(accessibilityLock) {
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED ||
            !shouldContinue()) return@synchronized false
        runCatching {
            val resolver = context.contentResolver
            val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val lists = WheelKeyService.allowedServices(current, service.flattenToString()) ?: return@synchronized false
            lists.first?.let {
                if (!shouldContinue()) return@synchronized false
                if (rebind()) {
                    if (!Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, it)) return@synchronized false
                    // The observer must see removal before Android clears a crashed binding.
                    Thread.sleep(1_000)
                }
            }
            if (!shouldContinue() || !Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, lists.second))
                return@synchronized false
            if (!shouldContinue() || !Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)) return@synchronized false
            service.flattenToString() in Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':') &&
                Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        }.getOrDefault(false)
    }

    data class Result(val verified: Map<Permission, Boolean>, val failed: Set<Permission> = emptySet()) {
        val complete: Boolean get() = failed.isEmpty() && verified.size == Permission.entries.size && verified.values.all { it }
    }

    class Operation(private val context: Context,
        private val apply: (Permission) -> Unit = { it.apply(context) },
        private val granted: (Permission) -> Boolean = { it.granted(context) }) {
        private val cancelled = AtomicBoolean(false)
        val isCancelled: Boolean get() = cancelled.get()
        fun cancel() { cancelled.set(true) }

        fun run(): Result {
            val failed = mutableSetOf<Permission>()
            for (permission in Permission.entries) {
                if (isCancelled) break
                if (granted(permission)) continue
                runCatching { apply(permission) }.onFailure { failed += permission }
            }
            val verified = if (isCancelled) emptyMap() else Permission.entries.associateWith { granted(it) }
            return Result(verified, failed)
        }
    }
}
