package com.shihab.diplay.update

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
 *
 * A release carries one APK per shipped ABI under `apks`. The device downloads only the package that
 * matches its own ABI. The top-level `apk` stays as the ARM64 default (and back-compat for older
 * manifests that shipped a single universal APK).
 */
internal object UpdateCatalog {
    internal const val REPOSITORY = "https://github.com/jianyun8023/diplay-ecarx"
    internal const val MANIFEST_FILE = "update.json"
    internal const val MANIFEST_URL = "$REPOSITORY/releases/latest/download/$MANIFEST_FILE"
    internal const val CHECKSUMS_FILE = "SHA256SUMS.txt"
    // Long notes are cut so a careless release body cannot fill the About page.
    internal const val MAXIMUM_NOTES_CHARS = 4000
    private val TAG = Regex("v\\d{1,9}")
    // The name becomes a file in the cache folder, so it can hold no path.
    private val APK_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.apk")

    /** Shipped ABIs, in preference order. ARM64 is the default production package. */
    internal val ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
    internal const val DEFAULT_ABI = "arm64-v8a"

    /** The manifest attached to the release of [tag]; it carries that build's own notes. */
    internal fun manifestUrl(tag: String) = "$REPOSITORY/releases/download/$tag/$MANIFEST_FILE"

    /**
     * The ABI this device runs, chosen from [supportedAbis] (most preferred first, as Android reports
     * them). Only a shipped ABI is returned; an unrecognized device falls back to [DEFAULT_ABI] so it
     * never downloads a package it cannot run.
     */
    internal fun platformAbi(supportedAbis: List<String>): String =
        supportedAbis.firstOrNull { it in ABIS } ?: DEFAULT_ABI

    /** Returns the release that the manifest describes for [abi], or null when it is incomplete or unsafe. */
    internal fun parse(json: String, abi: String = DEFAULT_ABI): UpdateRelease? {
        val manifest = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val tag = manifest.optString("tag")
        val apkName = apkNameFor(manifest, abi)
        if (!TAG.matches(tag) || apkName == null || !APK_NAME.matches(apkName)) return null
        val notes = UpdateNotes.plain(manifest.optString("notes")).let {
            if (it.length > MAXIMUM_NOTES_CHARS) it.take(MAXIMUM_NOTES_CHARS).trimEnd() + "…" else it
        }
        val base = "$REPOSITORY/releases/download/$tag/"
        return UpdateRelease(tag, apkName, base + apkName, base + CHECKSUMS_FILE, notes)
    }

    /**
     * The APK for [abi]: its entry in `apks`, else the top-level `apk` (a universal or ARM64-default
     * package), else the ARM64 entry when a device ABI is absent from a partial manifest.
     */
    private fun apkNameFor(manifest: JSONObject, abi: String): String? {
        val apks = manifest.optJSONObject("apks")
        apks?.optString(abi)?.takeIf { it.isNotEmpty() }?.let { return it }
        return manifest.optString("apk").takeIf { it.isNotEmpty() }
            ?: apks?.optString(DEFAULT_ABI)?.takeIf { it.isNotEmpty() }
    }
}
