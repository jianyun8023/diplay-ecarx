package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StartupDiagnosticSnapshotTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        app.getSharedPreferences("diplay_startup_diagnostics", Context.MODE_PRIVATE).edit().clear().commit()
        app.createDeviceProtectedStorageContext()
            .getSharedPreferences("diplay_startup_diagnostics", Context.MODE_PRIVATE).edit().clear().commit()
        app.createDeviceProtectedStorageContext()
            .getSharedPreferences("diplay_boot_startup", Context.MODE_PRIVATE).edit().clear().commit()
        AirPlayPersistence.saveAutoStartOnBoot(app, false)
    }

    @Test fun disabledBootIsRecordedWithoutLaunchingOrChangingTheSetting() {
        var launched = false
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { launched = true }
        }
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertFalse(launched)
        assertFalse(AirPlayPersistence.loadAutoStartOnBoot(app))
        assertTrue(StartupDiagnosticSnapshot.report(app).contains("launchEnabledAtBoot=false launchResult=disabled"))
    }

    @Test fun successfulLaunchMeansAnActivityRequestAndRetainsTheExistingFlags() {
        AirPlayPersistence.saveAutoStartOnBoot(app, true)
        var launch: Intent? = null
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { launch = intent }
        }
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(DiPlayActivity::class.java.name, launch!!.component!!.className)
        assertTrue(launch!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(StartupDiagnosticSnapshot.report(app).contains("launchResult=startActivity-returned"))
    }

    @Test fun launchFailureRecordsOnlyItsClassAndASecondBootReplacesOldEvidence() {
        AirPlayPersistence.saveAutoStartOnBoot(app, true)
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { throw SecurityException("Jane's phone token=private-data") }
        }
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        val failed = StartupDiagnosticSnapshot.report(app)
        assertTrue(failed.contains("launchResult=failed failureClass=SecurityException"))
        assertFalse(failed.contains("Jane")); assertFalse(failed.contains("private-data"))
        assertNotNull(DiagnosticRedactor.redact(failed))
        AirPlayPersistence.saveAutoStartOnBoot(app, false)
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(StartupDiagnosticSnapshot.report(app).contains("launchResult=disabled failureClass=none"))
    }

    @Test fun lockedBootWarmupRecordsTheEarlierCheckpointWithoutLaunchingOrTouchingTheNormalBootState() {
        AirPlayPersistence.saveAutoStartOnBoot(app, true)
        var launched = false
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { launched = true }
        }
        BootReceiver().onReceive(context, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertFalse(launched)
        val report = StartupDiagnosticSnapshot.report(app)
        assertTrue(report.contains("lockedBootReceivedAtEpochMs="))
        assertTrue(report.contains("lockedLaunchEnabled=true"))
        // Decides whether CarPlay can later start at this broadcast instead of BOOT_COMPLETED.
        assertTrue(report.contains("lockedUserUnlocked="))
        assertTrue(report.contains("lockedBluetoothState="))
        assertFalse(report.contains("launchResult="))
    }

    @Test fun aNewLockedBootClearsThePreviousBootResultAndRecordsProcessReuse() {
        AirPlayPersistence.saveAutoStartOnBoot(app, true)
        StartupDiagnosticSnapshot.received(app, true)
        StartupDiagnosticSnapshot.launchResult(app, SecurityException("old failure"))
        assertTrue(StartupDiagnosticSnapshot.report(app).contains("launchResult=failed"))

        StartupDiagnosticSnapshot.lockedBootReceived(app, true)
        val beforeNormal = StartupDiagnosticSnapshot.report(app)
        assertTrue(beforeNormal.contains("received=false"))
        assertTrue(beforeNormal.contains("lockedBootPid=${Process.myPid()}"))
        assertFalse(beforeNormal.contains("launchResult="))
        assertFalse(beforeNormal.contains("failureClass="))

        StartupDiagnosticSnapshot.received(app, true)
        val afterNormal = StartupDiagnosticSnapshot.report(app)
        assertTrue(afterNormal.contains("receivedPid=${Process.myPid()}"))
        assertTrue(afterNormal.contains("sameProcessAtBoot=true"))
        assertTrue(afterNormal.contains("lockedToNormalBootMs="))
        assertTrue(afterNormal.contains("launchResult=pending"))
    }

    @Test fun normalBootWithoutLockedBroadcastDropsAStaleCheckpoint() {
        val prefs = app.createDeviceProtectedStorageContext()
            .getSharedPreferences("diplay_startup_diagnostics", Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("lockedBootReceivedAt", System.currentTimeMillis() - 3_600_000L)
            .putLong("lockedBootElapsedMs", SystemClock.elapsedRealtime())
            .putInt("lockedBootPid", 123)
            .commit()
        StartupDiagnosticSnapshot.received(app, false)
        val report = StartupDiagnosticSnapshot.report(app)
        assertFalse(report.contains("lockedBootReceivedAtEpochMs"))
        assertFalse(report.contains("lockedBootPid="))
        assertTrue(report.contains("launchResult=disabled"))
    }

    @Test fun unrelatedBroadcastsDoNotCreateOrOverwriteBootEvidence() {
        BootReceiver().onReceive(app, Intent("custom.intent.token=secret"))
        assertTrue(StartupDiagnosticSnapshot.report(app).contains("received=false"))
    }
}
