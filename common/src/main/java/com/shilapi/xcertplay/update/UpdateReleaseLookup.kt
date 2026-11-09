package com.shilapi.xcertplay.update

import java.net.URL
import java.net.URLConnection

/** What a check found, and the route that answered, so downloads try it first. */
internal data class UpdateLookup(val release: UpdateRelease?, val route: String)

internal object UpdateReleaseLookup {
    fun latest(
        installedBuild: Long,
        userAgent: String,
        routes: List<String>,
        openConnection: (URL) -> URLConnection = { it.openConnection() },
    ): UpdateLookup {
        val (json, route) = UpdateMirror.firstWorking(routes) { route ->
            UpdateClient.fetchText(UpdateMirror.url(route, UpdateCatalog.MANIFEST_URL), "application/json", userAgent, openConnection)
        }
        val release = UpdateCatalog.parse(json)?.takeIf { UpdateVersion.isNewer(it.tagName, installedBuild) }
        return UpdateLookup(release, route)
    }
}
