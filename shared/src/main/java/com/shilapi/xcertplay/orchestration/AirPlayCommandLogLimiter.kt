package com.shilapi.xcertplay.orchestration

/**
 * Keeps session-relevant mode transitions while discarding repetitive AirPlay command logs.
 * Command execution is intentionally independent of these decisions.
 */
internal class AirPlayCommandLogLimiter {
    private var lastSession: Any? = null
    private var lastModes: String? = null

    @Synchronized
    fun shouldLog(session: Any, type: String, params: Map<String, Any?>): Boolean {
        if (lastSession !== session) {
            lastSession = session
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
