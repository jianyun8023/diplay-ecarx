package com.shihab.diplay

import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ShutdownDiagnosticSnapshotTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val bluetooth get() = app.getSharedPreferences("diplay_bt_profile_guard", 0)
    private val navigation get() = app.getSharedPreferences("diplay_factory_navigation_control", 0)

    @Before fun reset() {
        app.getSharedPreferences("diplay_shutdown_diagnostic", 0).edit().clear().commit()
        bluetooth.edit().clear().commit()
        navigation.edit().clear().commit()
    }

    @Test fun controllerClosureDoesNotClaimPendingMediaOrExternalRestorationCompleted() {
        bluetooth.edit().putBoolean("applied", true)
            .putString("restore_diagnostic", "operationId=12/4 phase=pending").commit()
        navigation.edit().putBoolean("owned", true)
            .putString("last_diagnostic", "operationId=12/8 serviceStart=delayed").commit()
        val line = ShutdownDiagnosticSnapshot.record(app, "settings exit application", true, 1234L,
            SystemClock.elapsedRealtime(), true, "Media health pendingWorkers=2 closing=true final=false")
        assertTrue(line, line.contains("controllerClosed=true"))
        assertTrue(line, line.contains("pendingWorkers=2"))
        assertTrue(line, line.contains("serviceStart=delayed"))
        assertTrue(line, line.contains("externalRestoration=independent"))
        assertTrue(line, line.contains("snapshotPersisted=true"))
        assertFalse(line, line.contains("clean=true"))

        // External recovery completes after the Activity's session writer has gone away.
        bluetooth.edit().putBoolean("applied", false)
            .putString("restore_diagnostic", "operationId=12/4 phase=settled connectionCompletion=not-awaited").commit()
        navigation.edit().putBoolean("owned", false)
            .putString("last_diagnostic", "operationId=12/8 serviceStart=confirmed").commit()
        val report = ShutdownDiagnosticSnapshot.report(app)
        assertTrue(report, report.contains("Bluetooth at exit: journalPending=true last=operationId=12/4 phase=pending"))
        assertTrue(report, report.contains("Bluetooth latest: journalPending=false last=operationId=12/4 phase=settled"))
        assertTrue(report, report.contains("Factory navigation at exit: journalPending=true"))
        assertTrue(report, report.contains("Factory navigation latest: journalPending=false last=operationId=12/8 serviceStart=confirmed"))
    }

    @Test fun persistedSnapshotIsBoundedRedactedAndReplacedByTheNextExit() {
        ShutdownDiagnosticSnapshot.record(app, "peer=AA:BB:CC:DD:EE:FF", false, 1L,
            SystemClock.elapsedRealtime(), false, "Media health pendingWorkers=1\npassword=secret\n" + "x".repeat(1_000))
        val report = ShutdownDiagnosticSnapshot.report(app)
        assertFalse(report.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(report.contains("secret"))
        assertFalse(report.contains("x".repeat(701)))
        assertTrue(report.contains("controllerClosed=false"))
        ShutdownDiagnosticSnapshot.record(app, "activity destroy", false, 2L,
            SystemClock.elapsedRealtime(), true, "Media health pendingWorkers=0 final=true")
        val latest = ShutdownDiagnosticSnapshot.report(app)
        assertTrue(latest.contains("reason=activity destroy"))
        assertFalse(latest.contains("peer="))
        assertTrue(latest.contains("pendingWorkers=0 final=true"))
    }

    @Test fun lateMediaCompletionIsDurableAndKeepsItsOwnerSeparateFromLaterExitSnapshots() {
        val pending = "Media health owner=31/7 pendingWorkers=2 closing=true final=false"
        ShutdownDiagnosticSnapshot.record(app, "activity destroy", false, 1L,
            SystemClock.elapsedRealtime(), true, pending)
        val completed = "Media health owner=31/7 pendingWorkers=0 closing=true final=true peer=AA:BB:CC:DD:EE:FF"
        assertTrue(ShutdownDiagnosticSnapshot.recordMediaFinal(app, completed))
        val prefs = app.getSharedPreferences("diplay_shutdown_diagnostic", 0)
        val saved = prefs.getString("media_completion", null)!!
        assertTrue(saved, Regex("observedAtEpochMs=\\d+ ").containsMatchIn(saved))
        assertTrue(saved, saved.contains("owner=31/7 pendingWorkers=0 closing=true final=true"))
        assertFalse(saved.contains("AA:BB:CC:DD:EE:FF"))
        assertEquals(pending, prefs.getString("media", null))
        assertTrue(ShutdownDiagnosticSnapshot.report(app).contains("Media latest completion: $saved"))

        // A new owner's teardown must not erase or relabel the previous owner's completion.
        ShutdownDiagnosticSnapshot.record(app, "next exit", false, 2L,
            SystemClock.elapsedRealtime(), true, "Media health owner=31/9 pendingWorkers=1 final=false")
        val report = ShutdownDiagnosticSnapshot.report(app)
        assertTrue(report, report.contains("Media at exit: Media health owner=31/9 pendingWorkers=1 final=false"))
        assertTrue(report, report.contains("Media latest completion: $saved"))
        assertFalse(ShutdownDiagnosticSnapshot.recordMediaFinal(app, "Media health password=secret final=true"))
        assertEquals(saved, prefs.getString("media_completion", null))
    }
}
