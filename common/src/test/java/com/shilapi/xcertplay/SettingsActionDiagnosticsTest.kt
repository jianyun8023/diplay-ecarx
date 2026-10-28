package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SettingsActionDiagnosticsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clear() {
        context.getSharedPreferences("diplay_settings_actions", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun keepsTheLast64ActionsWithoutNeedingAConnection() {
        repeat(70) {
            SettingsActionDiagnostics.record(context, SettingsActionDiagnostics.Action.FACTORY_HICAR,
                "step=$it targetDisabled=true applied=false after=false")
        }
        val report = SettingsActionDiagnostics.report(context)
        assertEquals(64, report.lineSequence().count { it.startsWith("at=") })
        assertFalse(report.contains("step=5 "))
        assertTrue(report.contains("step=6 "))
        assertTrue(report.contains("step=69 "))
        assertTrue(report.contains("elapsed="))
        assertTrue(report.contains("siriCommunicationCapture=${com.shilapi.xcertplay.shared.BuildConfig.SIRI_COMMUNICATION_CAPTURE}"))
    }

    @Test fun redactsMetadataAndRejectsCredentialsOrInjectedLinesBeforePersistence() {
        val action = SettingsActionDiagnostics.Action.MANUAL_HOTSPOT
        SettingsActionDiagnostics.record(context, action, "peer=AA:BB:CC:DD:EE:FF ip=192.168.9.1")
        SettingsActionDiagnostics.record(context, action, "password=do-not-store")
        SettingsActionDiagnostics.record(context, action, "result=ON\nforged=success")
        val report = SettingsActionDiagnostics.report(context)
        assertTrue(report.contains("peer=[address] ip=[ip]"))
        assertFalse(report.contains("AA:BB"))
        assertFalse(report.contains("192.168"))
        assertFalse(report.contains("do-not-store"))
        assertFalse(report.contains("forged"))
        assertEquals(1, report.lineSequence().count { it.startsWith("at=") })
    }
}
