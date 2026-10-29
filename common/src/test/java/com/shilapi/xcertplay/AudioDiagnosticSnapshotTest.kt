package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AudioDiagnosticSnapshotTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun readsOemSeatEvidenceWithoutTurningInvalidResponsesIntoAllSeats() {
        for ((response, mode) in listOf("audask_seatopt=00" to "off", "audask_seatopt=01" to "front",
            "audask_seatopt=02" to "rear", "audask_seatopt=03" to "all",
            "audask_seatopt=FF" to "unknown", "malformed" to "unknown", "" to "unknown", null to "unknown")) {
            val report = AudioDiagnosticSnapshot.report(context) { response }
            assertTrue(report, report.contains("mode=$mode scope=global"))
            assertTrue(report.contains("device IDs do not identify seats"))
            assertTrue(report.contains("OEM sound-stage API: unavailable="))
        }
    }

    @Test fun recordsSavedConfigurationAndUnavailableReadsWithoutHidingTheRestOfTheReport() {
        AirPlayPersistence.saveNavigationAudioChannel(context, 15)
        val report = AudioDiagnosticSnapshot.report(context) { throw SecurityException() }
        assertTrue(report.contains("navigationChannel=15"))
        assertTrue(report.contains("unavailable=SecurityException"))
        assertTrue(report.contains("Available audio outputs:"))
    }

    @Test fun rawVendorResponseUsesExistingDiagnosticRedaction() {
        val report = AudioDiagnosticSnapshot.report(context) { "audask_seatopt=03\nforged=success" }
        assertFalse(report.contains("forged"))
        assertTrue(report.contains("raw=unavailable mode=unknown"))
    }
}
