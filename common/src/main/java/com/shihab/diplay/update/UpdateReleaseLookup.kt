package com.shihab.diplay.update

import java.net.URL
import java.net.URLConnection

/** What a check found, and the route that answered, so downloads try it first. */
internal data class UpdateLookup(val release: UpdateRelease?, val route: String)

internal object UpdateReleaseLookup {
    fun latest(
        installedBuild: Long,
        userAgent: String,
        routes: List<String>,
        abi: String = UpdateCatalog.DEFAULT_ABI,
        openConnection: (URL) -> URLConnection = { it.openConnection() },
    ): UpdateLookup {
        val (json, route) = UpdateMirror.firstWorking(routes) { route ->
            UpdateClient.fetchText(UpdateMirror.url(route, UpdateCatalog.MANIFEST_URL), "application/json", userAgent, openConnection)
        }
        val release = UpdateCatalog.parse(json, abi)?.takeIf { UpdateVersion.isNewer(it.tagName, installedBuild) }
        return UpdateLookup(release, route)
    }

    /** The release of the installed build, read from its own tag, so its notes stay readable after the upgrade. */
    fun installed(
        installedBuild: Long,
        userAgent: String,
        routes: List<String>,
        abi: String = UpdateCatalog.DEFAULT_ABI,
        openConnection: (URL) -> URLConnection = { it.openConnection() },
    ): UpdateLookup {
        val tag = "v$installedBuild"
        val (json, route) = UpdateMirror.firstWorking(routes) { route ->
            UpdateClient.fetchText(UpdateMirror.url(route, UpdateCatalog.manifestUrl(tag)), "application/json", userAgent, openConnection)
        }
        return UpdateLookup(UpdateCatalog.parse(json, abi)?.takeIf { it.tagName == tag }, route)
    }
}
