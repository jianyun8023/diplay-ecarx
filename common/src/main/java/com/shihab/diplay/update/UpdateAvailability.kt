package com.shihab.diplay.update

import android.content.Context

/** Durable metadata only. APK downloads always remain an explicit foreground action. */
internal object UpdateAvailability {
    /** The CI release package. The `.hudtest` debug package is another app, so a release APK cannot upgrade it. */
    internal const val RELEASE_PACKAGE = "com.shihab.diplay"
    private const val PREFERENCES = "diplay_update_availability"
    private const val TAG = "tag"
    private const val APK_NAME = "apk_name"
    private const val APK_URL = "apk_url"
    private const val CHECKSUMS_URL = "checksums_url"
    private const val NOTES = "notes"
    private const val INSTALLED_TAG = "installed_tag"
    private const val INSTALLED_NOTES = "installed_notes"
    private const val MIRROR = "mirror"
    private const val CUSTOM_MIRROR = "custom_mirror"
    private const val LAST_WORKING_ROUTE = "last_working_route"
    private const val LAST_ATTEMPT_MILLIS = "last_attempt_millis"
    private const val BACKGROUND_CHECKS = "background_checks"
    private const val LAST_RESULT = "last_result"
    private const val LAST_RESULT_MILLIS = "last_result_millis"
    private const val LAST_INSTALL = "last_install"
    private const val LAST_INSTALL_MILLIS = "last_install_millis"

    internal const val CHECK_INTERVAL_MILLIS = 24L * 60 * 60 * 1000

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    internal fun enabled(packageName: String): Boolean = packageName == RELEASE_PACKAGE

    fun enabled(context: Context): Boolean = enabled(context.packageName)

    fun backgroundChecksEnabled(context: Context): Boolean =
        enabled(context) && preferences(context).getBoolean(BACKGROUND_CHECKS, true)

    fun saveBackgroundChecksEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(BACKGROUND_CHECKS, enabled).apply()
    }

    fun available(context: Context, installedBuild: Long): UpdateRelease? =
        if (enabled(context)) cached(context, installedBuild) else null

    internal fun cached(context: Context, installedBuild: Long): UpdateRelease? {
        val values = preferences(context)
        val release = UpdateRelease(
            tagName = values.getString(TAG, null) ?: return null,
            apkName = values.getString(APK_NAME, null) ?: return null,
            apkUrl = values.getString(APK_URL, null) ?: return null,
            checksumsUrl = values.getString(CHECKSUMS_URL, null) ?: return null,
            notes = values.getString(NOTES, null).orEmpty(),
        )
        if (UpdateVersion.isNewer(release.tagName, installedBuild)) return release
        clear(context)
        return null
    }

    fun save(context: Context, release: UpdateRelease) {
        preferences(context).edit()
            .putString(TAG, release.tagName)
            .putString(APK_NAME, release.apkName)
            .putString(APK_URL, release.apkUrl)
            .putString(CHECKSUMS_URL, release.checksumsUrl)
            .putString(NOTES, release.notes)
            .apply()
    }

    fun clear(context: Context) {
        preferences(context).edit()
            .remove(TAG)
            .remove(APK_NAME)
            .remove(APK_URL)
            .remove(CHECKSUMS_URL)
            .remove(NOTES)
            .apply()
    }

    /** The notes of the installed build, once read; null until then and again after an upgrade. */
    fun installedNotes(context: Context, installedBuild: Long): String? {
        val values = preferences(context)
        return values.getString(INSTALLED_NOTES, null)?.takeIf { values.getString(INSTALLED_TAG, null) == "v$installedBuild" }
    }

    fun saveInstalledNotes(context: Context, release: UpdateRelease) {
        preferences(context).edit().putString(INSTALLED_TAG, release.tagName).putString(INSTALLED_NOTES, release.notes).apply()
    }

    /** [UpdateMirror.AUTO], [UpdateMirror.DIRECT] or a mirror prefix. */
    fun mirror(context: Context): String = preferences(context).getString(MIRROR, null) ?: UpdateMirror.AUTO

    fun customMirror(context: Context): String? = preferences(context).getString(CUSTOM_MIRROR, null)

    fun saveMirror(context: Context, choice: String) {
        val edit = preferences(context).edit().putString(MIRROR, choice)
        if (choice != UpdateMirror.AUTO && choice != UpdateMirror.DIRECT && choice !in UpdateMirror.BUILT_IN) {
            edit.putString(CUSTOM_MIRROR, choice)
        }
        edit.apply()
    }

    fun routes(context: Context): List<String> {
        val values = preferences(context)
        return UpdateMirror.routes(mirror(context), values.getString(LAST_WORKING_ROUTE, null), customMirror(context))
    }

    fun recordWorkingRoute(context: Context, route: String) {
        preferences(context).edit().putString(LAST_WORKING_ROUTE, route).apply()
    }

    fun shouldCheck(context: Context, nowMillis: Long): Boolean {
        val lastAttempt = preferences(context).getLong(LAST_ATTEMPT_MILLIS, 0L)
        return lastAttempt <= 0L || nowMillis < lastAttempt || nowMillis - lastAttempt >= CHECK_INTERVAL_MILLIS
    }

    fun recordAttempt(context: Context, nowMillis: Long) {
        preferences(context).edit().putLong(LAST_ATTEMPT_MILLIS, nowMillis).apply()
    }

    fun recordResult(context: Context, nowMillis: Long, result: String) {
        preferences(context).edit().putString(LAST_RESULT, result).putLong(LAST_RESULT_MILLIS, nowMillis).apply()
    }

    fun recordInstall(context: Context, nowMillis: Long, outcome: String) {
        preferences(context).edit().putString(LAST_INSTALL, outcome).putLong(LAST_INSTALL_MILLIS, nowMillis).apply()
    }

    /** One diagnostic line. Ages are relative so the report needs no clock or locale. */
    fun report(context: Context, nowMillis: Long): String {
        val values = preferences(context)
        fun age(key: String) = values.getLong(key, 0L).takeIf { it > 0L }
            ?.let { "${(nowMillis - it).coerceAtLeast(0L) / 60_000} min ago" } ?: "never"
        return "Update check: background=${if (backgroundChecksEnabled(context)) "on" else "off"}; " +
            "source=${mirror(context)} (last working ${values.getString(LAST_WORKING_ROUTE, null) ?: "none"}); " +
            "lastSuccess=${age(LAST_ATTEMPT_MILLIS)}; cachedRelease=${values.getString(TAG, null) ?: "none"}; " +
            "lastResult=${values.getString(LAST_RESULT, null) ?: "none"} (${age(LAST_RESULT_MILLIS)}); " +
            "lastInstall=${values.getString(LAST_INSTALL, null) ?: "none"} (${age(LAST_INSTALL_MILLIS)})"
    }

    internal fun clearAllForTest(context: Context) {
        preferences(context).edit().clear().commit()
    }
}
