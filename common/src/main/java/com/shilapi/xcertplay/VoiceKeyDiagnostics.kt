package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** Reads takeover prerequisites without stopping apps or changing package state. */
internal object VoiceKeyDiagnostics {
    @Suppress("DEPRECATION")
    fun report(context: Context): String = buildString {
        appendLine("--- Siri / HiCar takeover prerequisites (read-only) ---")
        runCatching {
            val pm = context.packageManager
            val permission = "android.permission.CHANGE_COMPONENT_ENABLED_STATE"
            val own = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            val signatureResult = pm.checkSignatures(context.packageName, "android")
            appendLine("Siri takeover platformSignatureMatch=${signatureResult == PackageManager.SIGNATURE_MATCH} " +
                "signatureCheck=$signatureResult " +
                "componentPermissionRequested=${own.requestedPermissions?.contains(permission) == true} " +
                "componentPermissionGranted=${pm.checkPermission(permission, context.packageName) == PackageManager.PERMISSION_GRANTED}")
            // Visibility may be filtered on newer Android versions; zero is not proof of absence.
            val candidates = pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS).filter {
                it.packageName.contains("hicar", ignoreCase = true) ||
                    pm.getApplicationLabel(it).contains("hicar", ignoreCase = true)
            }.sortedBy { it.packageName }
            appendLine("HiCar visibleCandidates=${candidates.size}")
            for (app in candidates.take(8)) {
                appendLine("HiCar package=${app.packageName} " +
                    "enabled=${app.enabled} enabledSetting=${pm.getApplicationEnabledSetting(app.packageName)} " +
                    "system=${app.flags and ApplicationInfo.FLAG_SYSTEM != 0}")
            }
            append("HiCar changesPerformed=false; system protection and actual key ownership not verified")
        }.onFailure {
            append("Siri takeover query unavailable=${it.javaClass.simpleName}; changesPerformed=false")
        }
    }
}
