package com.shilapi.xcertplay.update

/** This fork tags each release `v<versionCode>`, so the tag alone says whether Android accepts it as an upgrade. */
internal object UpdateVersion {
    private val BUILD_TAG = Regex("v?(\\d{1,9})")

    internal fun parse(tag: String): Long? = BUILD_TAG.matchEntire(tag.trim())?.groupValues?.get(1)?.toLong()

    internal fun isNewer(remoteTag: String, installedBuild: Long): Boolean {
        val target = parse(remoteTag) ?: return false
        return target > installedBuild
    }
}
