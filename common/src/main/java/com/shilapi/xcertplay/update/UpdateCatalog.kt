package com.shilapi.xcertplay.update

import org.json.JSONObject

/** The published release that a newer build can be downloaded from. */
internal data class UpdateRelease(
    val tagName: String,
    val apkName: String,
    val apkUrl: String,
    val checksumsUrl: String,
    val notes: String = "",
)

/**
 * CI attaches `update.json` to each release. It is a github.com download, which accelerator mirrors
 * serve; api.github.com usually is not. Download URLs are built here from the tag, never taken from the file.
 */
internal object UpdateCatalog {
    internal const val REPOSITORY = "https://github.com/jianyun8023/diplay-ecarx"
    internal const val MANIFEST_URL = "$REPOSITORY/releases/latest/download/update.json"
    internal const val CHECKSUMS_FILE = "SHA256SUMS.txt"
    // Long notes are cut so a careless release body cannot fill the About page.
    internal const val MAXIMUM_NOTES_CHARS = 4000
    private val TAG = Regex("v\\d{1,9}")
    // The name becomes a file in the cache folder, so it can hold no path.
    private val APK_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.apk")

    /** Returns the release that the manifest describes, or null when it is incomplete or unsafe. */
    internal fun parse(json: String): UpdateRelease? {
        val manifest = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val tag = manifest.optString("tag")
        val apkName = manifest.optString("apk")
        if (!TAG.matches(tag) || !APK_NAME.matches(apkName)) return null
        val notes = manifest.optString("notes").trim().let {
            if (it.length > MAXIMUM_NOTES_CHARS) it.take(MAXIMUM_NOTES_CHARS).trimEnd() + "…" else it
        }
        val base = "$REPOSITORY/releases/download/$tag/"
        return UpdateRelease(tag, apkName, base + apkName, base + CHECKSUMS_FILE, notes)
    }
}
