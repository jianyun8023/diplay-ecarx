package com.shilapi.xcertplay.orchestration

import java.lang.ref.WeakReference

/**
 * Keeps session-relevant mode transitions while discarding repetitive AirPlay command logs.
 * Command execution is intentionally independent of these decisions.
 */
internal class AirPlayCommandLogLimiter {
    // A controller can outlive its last AirPlay session; do not retain socket/media resources.
    private var lastSession: WeakReference<Any>? = null
    private var lastModes: String? = null

    @Synchronized
    fun shouldLog(session: Any, type: String, params: Map<String, Any?>): Boolean {
        if (lastSession?.get() !== session) {
            lastSession = WeakReference(session)
            lastModes = null
        }
        return when (type) {
            "modesChanged" -> {
                // Only protocol mode fields, never arbitrary payloads (which may be large/sensitive).
                val signature = "appStates=" + params["appStates"] + ";resources=" + params["resources"]
                if (signature == lastModes) false else {
                    lastModes = signature
                    true
                }
            }
            "duckAudio", "unduckAudio", "suggestUI" -> false
            else -> true
        }
    }
}
