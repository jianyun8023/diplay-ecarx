package com.shihab.diplay.update

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.IOException

/**
 * A mirror serves both the APK and its checksums, so the checksums alone prove nothing about the source.
 * Android would refuse an APK with another signature anyway; checking first gives a clear message
 * instead of an installer error, and never hands such a file to the installer.
 */
internal object UpdateApkIdentity {
    @Suppress("DEPRECATION")
    private const val SIGNATURES = PackageManager.GET_SIGNATURES

    fun verify(packageManager: PackageManager, apk: File, packageName: String, installedBuild: Long) {
        val archive = packageManager.getPackageArchiveInfo(apk.absolutePath, SIGNATURES)
            ?: throw IOException("Downloaded update is not a readable APK")
        @Suppress("DEPRECATION")
        val installed = packageManager.getPackageInfo(packageName, SIGNATURES)
        check(archive, installed, installedBuild)
    }

    internal fun check(archive: PackageInfo, installed: PackageInfo, installedBuild: Long) {
        if (archive.packageName != installed.packageName) throw IOException("Downloaded update is for another app")
        if (PackageInfoCompat.getLongVersionCode(archive) <= installedBuild) throw IOException("Downloaded update is not newer")
        @Suppress("DEPRECATION")
        val expected = installed.signatures?.map { it.toByteArray().toList() }?.toSet().orEmpty()
        @Suppress("DEPRECATION")
        val actual = archive.signatures?.map { it.toByteArray().toList() }?.toSet().orEmpty()
        if (expected.isEmpty() || actual != expected) throw IOException("Downloaded update is not signed like this app")
    }
}
