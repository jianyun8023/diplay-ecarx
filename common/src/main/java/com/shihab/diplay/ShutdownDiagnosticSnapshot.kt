package com.shihab.diplay

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.shihab.diplay.bluetooth.CarBluetoothProfileGuard
import com.shihab.diplay.hud.EcarxFactoryNavigationRestore

/** Survives the Activity log closing before teardown and external restoration finish. */
internal object ShutdownDiagnosticSnapshot {
    private const val PREFS = "diplay_shutdown_diagnostic"

    /** Called on the teardown worker, before an optional immediate process exit. */
    fun record(
        context: Context,
        reason: String,
        terminateProcess: Boolean,
        startedAtEpochMs: Long,
        startedElapsedMs: Long,
        controllerClosed: Boolean,
        mediaDiagnostic: String,
    ): String {
        val summary = "Session exit id=${Process.myPid()}/$startedElapsedMs " +
            "startedAtEpochMs=$startedAtEpochMs elapsedMs=${SystemClock.elapsedRealtime() - startedElapsedMs} " +
            "reason=$reason terminateProcess=$terminateProcess controllerClosed=$controllerClosed " +
            "externalRestoration=independent"
        val safeSummary = DiagnosticRedactor.redact(summary) ?: "Session exit details=redacted"
        val media = mediaDiagnostic.lineSequence().mapNotNull(DiagnosticRedactor::redact).joinToString("\n")
        val bluetooth = DiagnosticRedactor.redact(CarBluetoothProfileGuard.restorationDiagnostic(context)) ?: "redacted"
        val navigation = DiagnosticRedactor.redact(EcarxFactoryNavigationRestore.restorationDiagnostic(context)) ?: "redacted"
        val store = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("summary", safeSummary)
            .putString("media", media)
            .putString("bluetooth", bluetooth)
            .putString("navigation", navigation)
        return "$safeSummary media=[${media.lineSequence().first()}] bluetooth=[$bluetooth] " +
            "factoryNavigation=[$navigation] snapshotPersisted=${store.commit()}"
    }

    /** Called only for an owner's final health line; it may arrive after a different owner exits. */
    fun recordMediaFinal(context: Context, message: String): Boolean {
        val safe = DiagnosticRedactor.redact(message) ?: return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("media_completion", "observedAtEpochMs=${System.currentTimeMillis()} $safe")
            .commit()
    }

    fun report(context: Context): String = buildString {
        val store = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        appendLine(store.getString("summary", "Session exit: not recorded"))
        store.getString("media", null)?.let { appendLine("Media at exit: $it") }
        store.getString("media_completion", null)?.let { appendLine("Media latest completion: $it") }
        store.getString("bluetooth", null)?.let { appendLine("Bluetooth at exit: $it") }
        store.getString("navigation", null)?.let { appendLine("Factory navigation at exit: $it") }
        // Operation IDs distinguish this exit's asynchronous completion from a later recovery.
        DiagnosticRedactor.redact(CarBluetoothProfileGuard.restorationDiagnostic(context))
            ?.let { appendLine("Bluetooth latest: $it") }
        DiagnosticRedactor.redact(EcarxFactoryNavigationRestore.restorationDiagnostic(context))
            ?.let { append("Factory navigation latest: $it") }
    }
}
