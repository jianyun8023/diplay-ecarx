// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.update.UpdateApkExport
import com.shilapi.xcertplay.update.UpdateAvailability
import com.shilapi.xcertplay.update.UpdateCatalog
import com.shilapi.xcertplay.update.UpdateChecksums
import com.shilapi.xcertplay.update.UpdateApkIdentity
import com.shilapi.xcertplay.update.UpdateClient
import com.shilapi.xcertplay.update.UpdateMirror
import com.shilapi.xcertplay.update.UpdateRelease
import com.shilapi.xcertplay.update.UpdateReleaseLookup
import java.io.File
import java.io.IOException

/** Update downloads and verified installation for one launcher lifetime. */
internal class DiPlayUpdates(
    private val activity: android.app.Activity,
    private val render: (aboutOnly: Boolean) -> Unit,
) : android.content.ContextWrapper(activity) {
    enum class Stage { IDLE, CHECKING, AVAILABLE, DOWNLOADING, VERIFYING, READY, FAILED }

    var availableUpdate: UpdateRelease? = null
    var updateSavedPath: String? = null
    var updateSavedUri: Uri? = null
    var updateInstallWorker: (Runnable) -> Unit = { Thread(it, "diplay-update-install").start() }
    @Volatile var updateStage = Stage.IDLE
    @Volatile private var updateGeneration = 0
    @Volatile var updateProgress: Int? = null
    var updateRelease: UpdateRelease? = null
    var updateFile: File? = null
    var updateRoute: String? = null
    var updateMessage: String? = null
    var installedNotesShown = false
    @Volatile var installedNotesLoading = false

    fun showInstalledNotes() {
        installedNotesShown = true
        val build = buildNumber()
        if (UpdateAvailability.installedNotes(this, build) != null || installedNotesLoading) return render(false)
        installedNotesLoading = true
        render(false)
        Thread({
            val outcome = runCatching { UpdateReleaseLookup.installed(build, userAgent(), UpdateAvailability.routes(this)) }
            activity.runOnUiThread {
                installedNotesLoading = false
                outcome.onSuccess { lookup ->
                    UpdateAvailability.recordWorkingRoute(this, lookup.route)
                    lookup.release?.let { UpdateAvailability.saveInstalledNotes(this, it) }
                }.onFailure { Log.w("DiPlay-Update", "installed release notes failed", it) }
                if (!activity.isFinishing && !activity.isDestroyed) render(false)
            }
        }, "diplay-installed-notes").start()
    }

    fun checkForUpdates() {
        updateStage = Stage.CHECKING
        updateMessage = null
        val generation = ++updateGeneration
        render(false)
        Thread({
            val outcome = runCatching { latestRelease() }
            activity.runOnUiThread {
                if (generation != updateGeneration || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                outcome.fold(
                    { release ->
                        UpdateAvailability.recordAttempt(this, System.currentTimeMillis())
                        availableUpdate = release
                        if (release == null) {
                            UpdateAvailability.clear(this)
                            updateMessage = getString(R.string.update_up_to_date)
                            updateStage = Stage.IDLE
                        } else {
                            UpdateAvailability.save(this, release)
                            updateRelease = release
                            updateStage = Stage.AVAILABLE
                        }
                        render(false)
                    },
                    { failure ->
                        Log.w("DiPlay-Update", "update check failed", failure)
                        // The URLs and status codes stay in the log; the driver needs the next step.
                        updateMessage = getString(R.string.settings_update_check_failed)
                        updateStage = Stage.FAILED
                        render(false)
                    },
                )
            }
        }, "diplay-update-check").start()
    }

    private fun latestRelease(): UpdateRelease? {
        val lookup = UpdateReleaseLookup.latest(buildNumber(), userAgent(), UpdateAvailability.routes(this))
        updateRoute = lookup.route
        UpdateAvailability.recordWorkingRoute(this, lookup.route)
        return lookup.release
    }

    /** The answering route first, then the rest of the driver's choice. */
    private fun downloadRoutes(): List<String> =
        (listOfNotNull(updateRoute) + UpdateAvailability.routes(this)).distinct()

    fun downloadUpdate() {
        if (updateRelease == null) return
        lateinit var release: UpdateRelease
        updateStage = Stage.DOWNLOADING
        updateSavedPath = null
        updateSavedUri = null
        updateProgress = null
        updateMessage = null
        val generation = ++updateGeneration
        render(false)
        Thread({
            // Each attempt owns its files, including across activity recreation.
            val directory = File(cacheDir, "update/${java.util.UUID.randomUUID()}")
            val outcome = runCatching {
                // The cached release can be a day old. Download the newest one.
                release = latestRelease() ?: throw IOException(getString(R.string.update_up_to_date))
                val checksumsFile = File(directory, UpdateCatalog.CHECKSUMS_FILE)
                val (_, route) = UpdateMirror.firstWorking(downloadRoutes()) { route ->
                    UpdateClient.download(UpdateMirror.url(route, release.checksumsUrl), checksumsFile) { _, _ -> }
                }
                val apkFile = File(directory, release.apkName)
                UpdateMirror.firstWorking(listOf(route) + downloadRoutes()) { apkRoute ->
                    UpdateClient.download(UpdateMirror.url(apkRoute, release.apkUrl), apkFile) { written, total ->
                        val percent = total?.takeIf { it > 0 }?.let { (written * 100 / it).toInt() } ?: return@download
                        if (percent != updateProgress) {
                            updateProgress = percent
                            refreshUpdateUi(generation)
                        }
                    }
                }
                updateStage = Stage.VERIFYING
                refreshUpdateUi(generation)
                val expected = UpdateChecksums.parse(checksumsFile.readText())
                val actual = UpdateChecksums.sha256Hex(apkFile)
                if (!UpdateChecksums.matches(expected, release.apkName, actual)) {
                    throw IOException("Checksum mismatch for ${release.apkName}")
                }
                UpdateApkIdentity.verify(packageManager, apkFile, packageName, buildNumber())
                apkFile
            }.onFailure { directory.deleteRecursively() }
            val saved = outcome.getOrNull()?.let { UpdateApkExport.copy(applicationContext, it) }
            activity.runOnUiThread {
                if (generation != updateGeneration || activity.isFinishing || activity.isDestroyed) {
                    directory.deleteRecursively()
                    return@runOnUiThread
                }
                outcome.fold(
                    { file ->
                        updateRelease = release
                        updateFile = file
                        updateSavedPath = saved?.path
                        updateSavedUri = saved?.uri
                        updateStage = Stage.READY
                        render(false)
                    },
                    { failure ->
                        Log.w("DiPlay-Update", "update download failed", failure)
                        updateMessage = failure.message ?: getString(R.string.update_failed)
                        updateStage = Stage.FAILED
                        render(false)
                    },
                )
            }
        }, "diplay-update-download").start()
    }

    fun installUpdate() {
        // No unknown-sources pre-check: some head units answer its settings page with "Not supported yet".
        // The chosen installer asks for that permission itself.
        installApk(updateFile ?: return)
    }

    private fun installApk(file: File) {
        // The public copy lets file managers such as MT Manager open the APK. The private provider URI is the fallback,
        // including when the driver deleted the public copy.
        // The public copy is outside the app's control, so it must still match the verified file. Hashing two
        // APKs takes seconds on a slow head unit, so it runs off the main thread.
        val savedUri = updateSavedUri
        val generation = updateGeneration
        updateInstallWorker(Runnable {
            val publicUri = savedUri?.takeIf { sameContent(it, file) }
            activity.runOnUiThread {
                if (generation == updateGeneration && !activity.isFinishing && !activity.isDestroyed) startInstaller(file, publicUri)
            }
        })
    }

    private fun startInstaller(file: File, publicUri: Uri?) {
        val uri = publicUri ?: FileProvider.getUriForFile(this, "$packageName.update-apks", file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // Ask only when several installers exist. A lone system installer opens directly, as before.
        val installers = packageManager.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY).size
        val launch = if (installers > 1) Intent.createChooser(view, null) else view
        val outcome = "${if (installers > 1) "chooser" else "direct"} uri=${if (publicUri != null) "public" else "provider"} installers=$installers"
        try {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.i("DiPlay-Update", "install started: $outcome")
            UpdateAvailability.recordInstall(applicationContext, System.currentTimeMillis(), outcome)
        } catch (failure: RuntimeException) {
            Log.w("DiPlay-Update", "install failed to start: $outcome", failure)
            UpdateAvailability.recordInstall(applicationContext, System.currentTimeMillis(), "$outcome failed=${failure.javaClass.simpleName}")
            updateMessage = getString(R.string.update_failed)
            render(false)
        }
    }

    private fun sameContent(uri: Uri, verified: File): Boolean = runCatching {
        val copy = contentResolver.openInputStream(uri)?.use { UpdateChecksums.sha256Hex(it) }
        copy != null && copy == UpdateChecksums.sha256Hex(verified)
    }.getOrDefault(false)

    private fun refreshUpdateUi(generation: Int) {
        activity.runOnUiThread {
            if (generation != updateGeneration || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            render(true)
        }
    }

    private fun userAgent() = "DiPlay/${version()}"

    private fun version() = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0-beta.1"
    private fun buildNumber() = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0))
}
