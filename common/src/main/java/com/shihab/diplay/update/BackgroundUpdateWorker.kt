package com.shihab.diplay.update

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.shihab.diplay.CarPlayBackgroundSession
import java.util.concurrent.TimeUnit

internal object BackgroundUpdatePolicy {
    /** A stored but inactive session is connecting or recovering. Active sessions are safe. */
    fun connectionBusy(hasSession: Boolean, active: Boolean): Boolean = hasSession && !active
}

internal class BackgroundUpdateWorker(context: Context, parameters: WorkerParameters) :
    Worker(context, parameters) {
    override fun doWork(): Result {
        if (!UpdateAvailability.backgroundChecksEnabled(applicationContext)) return Result.success()
        if (BackgroundUpdatePolicy.connectionBusy(
                CarPlayBackgroundSession.hasSession(),
                CarPlayBackgroundSession.active,
            )) {
            return retryOrGiveUp("session busy")
        }
        val now = System.currentTimeMillis()
        if (!UpdateAvailability.shouldCheck(applicationContext, now)) return Result.success()
        val connectivity = applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return Result.success()
        val network = runCatching { InternetNetworkSelector.select(connectivity) }
            .onFailure { Log.i(TAG, "network lookup failed", it) }
            .getOrNull() ?: return retryOrGiveUp("no validated network")
        val packageInfo = applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0)
        return try {
            val lookup = UpdateReleaseLookup.latest(
                installedBuild = PackageInfoCompat.getLongVersionCode(packageInfo),
                userAgent = "DiPlay/${packageInfo.versionName}",
                routes = UpdateAvailability.routes(applicationContext),
                abi = UpdateCatalog.platformAbi(android.os.Build.SUPPORTED_ABIS.toList()),
                openConnection = network::openConnection,
            )
            UpdateAvailability.recordWorkingRoute(applicationContext, lookup.route)
            val release = lookup.release
            if (release == null) UpdateAvailability.clear(applicationContext)
            else UpdateAvailability.save(applicationContext, release)
            UpdateAvailability.recordAttempt(applicationContext, now)
            UpdateAvailability.recordResult(applicationContext, now, if (release == null) "up to date" else "found ${release.tagName}")
            Result.success()
        } catch (failure: Exception) {
            Log.i(TAG, "background update check failed", failure)
            retryOrGiveUp("fetch failed: ${failure.javaClass.simpleName}")
        }
    }

    private fun retryOrGiveUp(reason: String): Result {
        UpdateAvailability.recordResult(applicationContext, System.currentTimeMillis(), "$reason (attempt ${runAttemptCount + 1})")
        return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    private companion object {
        const val TAG = "DiPlay-Update"
        const val MAX_ATTEMPTS = 5
    }
}

internal object BackgroundUpdateScheduler {
    // Generation suffix bumped when the worker class moved (#100 renamed
    // com.shilapi.xcertplay.update to com.shihab.diplay.update). WorkManager persists the worker's
    // fully-qualified class name with every work row, not just the unique name. A row written by a
    // pre-rename build still points at com.shilapi.xcertplay.update.BackgroundUpdateWorker, which
    // no longer exists in the APK, so Existing*Policy.KEEP would keep it forever and neither the
    // periodic nor the after-open check would ever run. Enqueuing under a new generation and
    // retiring the old names rebuilds the rows with the current class.
    internal const val PERIODIC_WORK = "diplay-periodic-update-check-v2"
    internal const val OPEN_WORK = "diplay-open-update-check-v2"
    internal val RETIRED_WORK = listOf("diplay-periodic-update-check", "diplay-open-update-check")

    /** The work names to cancel and whether the current generation should be (re-)enqueued. */
    internal data class WorkPlan(val cancel: List<String>, val enqueue: Boolean)

    internal fun plan(enabled: Boolean): WorkPlan =
        if (enabled) {
            // Retire pre-rename rows, then keep the current generation.
            WorkPlan(cancel = RETIRED_WORK, enqueue = true)
        } else {
            WorkPlan(cancel = RETIRED_WORK + PERIODIC_WORK + OPEN_WORK, enqueue = false)
        }

    fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        val plan = plan(UpdateAvailability.enabled(context))
        // Drop rows left by pre-rename builds. These cancels are no-ops once the old rows are gone.
        plan.cancel.forEach { manager.cancelUniqueWork(it) }
        if (!plan.enqueue) return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val periodic = PeriodicWorkRequestBuilder<BackgroundUpdateWorker>(24, TimeUnit.HOURS, 6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setInitialDelay(10, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
            .build()
        manager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, periodic)

        val afterOpen = OneTimeWorkRequestBuilder<BackgroundUpdateWorker>()
            .setConstraints(constraints)
            .setInitialDelay(2, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
            .build()
        manager.enqueueUniqueWork(OPEN_WORK, ExistingWorkPolicy.KEEP, afterOpen)
    }
}
