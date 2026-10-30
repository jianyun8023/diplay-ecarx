package com.shilapi.xcertplay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.session.MediaSessionManager

internal object SystemMediaSessionDiagnostics {
    fun report(context: Context): String {
        if (context.checkSelfPermission(Manifest.permission.MEDIA_CONTENT_CONTROL) != PackageManager.PERMISSION_GRANTED) {
            return "System media sessions: permission-denied"
        }
        return try {
            val manager = context.getSystemService(MediaSessionManager::class.java)
                ?: return "System media sessions: manager-unavailable"
            val sessions = manager.getActiveSessions(null)
            val ownPackage = context.packageName
            val first = sessions.firstOrNull()
            buildString {
                appendLine("System media sessions: count=${sessions.size} " +
                    "first=${first?.packageName ?: "none"} " +
                    "firstIsDiPlay=${first != null && first.packageName == ownPackage}")
                sessions.forEachIndexed { index, controller ->
                    val playback = controller.playbackState
                    appendLine("  [$index] package=${controller.packageName} " +
                        "state=${playback?.state ?: "none"} " +
                        "actions=${playback?.actions?.let { "0x${it.toString(16)}" } ?: "none"} " +
                        "ownPackage=${controller.packageName == ownPackage}")
                }
            }.trimEnd()
        } catch (error: SecurityException) {
            "System media sessions: query-denied=${error.javaClass.simpleName}"
        } catch (error: RuntimeException) {
            "System media sessions: query-failed=${error.javaClass.simpleName}"
        }
    }
}
