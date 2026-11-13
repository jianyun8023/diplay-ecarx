package com.shihab.diplay.update

import java.net.URI

/**
 * Where github.com downloads go. A mirror is a prefix in front of the full github.com URL, the form
 * that GitHub accelerator sites accept. A mirror can alter what it serves; the installer check that
 * the APK carries this app's signature is what makes that safe.
 */
internal object UpdateMirror {
    const val AUTO = "auto"
    const val DIRECT = "direct"
    val BUILT_IN = listOf("https://ghfast.top/", "https://gh-proxy.com/")

    fun url(route: String, githubUrl: String): String = if (route == DIRECT) githubUrl else route + githubUrl

    /** A custom prefix must be plain https with no credentials, query or fragment; it always ends with `/`. */
    fun normalize(prefix: String): String? {
        val trimmed = prefix.trim().let { if (it.endsWith("/")) it else "$it/" }
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrEmpty()) return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        if (uri.host.equals("github.com", ignoreCase = true)) return null
        return trimmed
    }

    /** Routes to try, in order. Auto starts with the last route that worked, then direct, then mirrors. */
    fun routes(choice: String, lastWorking: String?, custom: String?): List<String> = when (choice) {
        AUTO -> (listOfNotNull(lastWorking, DIRECT) + BUILT_IN + listOfNotNull(custom)).distinct()
        DIRECT -> listOf(DIRECT)
        else -> listOf(choice)
    }

    /** Runs [attempt] on each route until one succeeds; the failure of the last route is thrown. */
    fun <T> firstWorking(routes: List<String>, attempt: (String) -> T): Pair<T, String> {
        var failure: Exception? = null
        for (route in routes) {
            try {
                return attempt(route) to route
            } catch (error: Exception) {
                failure?.let(error::addSuppressed)
                failure = error
            }
        }
        throw failure ?: IllegalStateException("No update download route")
    }
}
