package com.shilapi.xcertplay

import android.media.AudioManager
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
            val report = AudioDiagnosticSnapshot.report(context) { _, _ -> response }
            assertTrue(report, report.contains("mode=$mode scope=global"))
            assertTrue(report.contains("device IDs do not identify seats"))
            assertTrue(report.contains("OEM sound-stage API: unavailable="))
        }
    }

    @Test fun recordsSavedConfigurationAndUnavailableReadsWithoutHidingTheRestOfTheReport() {
        AirPlayPersistence.saveNavigationAudioChannel(context, 15)
        val report = AudioDiagnosticSnapshot.report(context) { _, _ -> throw SecurityException() }
        assertTrue(report.contains("navigationChannel=15"))
        assertTrue(report.contains("unavailable=SecurityException"))
        assertTrue(report.contains("Available audio outputs:"))
    }

    @Test fun rawVendorResponseUsesExistingDiagnosticRedaction() {
        val report = AudioDiagnosticSnapshot.report(context) { _, _ -> "audask_seatopt=03\nforged=success" }
        assertFalse(report.contains("forged"))
        assertTrue(report.contains("raw=unavailable mode=unknown"))
    }

    @Test fun readsBothEffectsWithoutTreatingUnknownOrMalformedResponsesAsOff() {
        for ((value, state) in listOf("01" to "on", "00" to "off", "FF" to "unknown",
            "" to "unknown", "01\nforged=success" to "unknown")) {
            val report = AudioDiagnosticSnapshot.report(context) { _, key -> "$key=$value" }
            for ((effect, key) in listOf("Logic7" to "audask_logic7", "Clari-Fi" to "audask_clarifi")) {
                assertTrue(report, report.contains("effect=$effect query=$key"))
                assertTrue(report, report.lineSequence().single { it.contains("effect=$effect ") }
                    .contains("state=$state scope=global"))
            }
            assertFalse(report.contains("forged"))
            assertTrue(report.contains("DSP processing not verified"))
        }
        val unavailable = AudioDiagnosticSnapshot.report(context) { _, _ -> null }
        assertTrue(unavailable.contains("query=audask_logic7 raw=unavailable state=unknown"))
        assertTrue(unavailable.contains("query=audask_clarifi raw=unavailable state=unknown"))
    }

    @Test fun failedLogic7ReadDoesNotHideClariFiOrChangeTheAudioMode() {
        val manager = context.getSystemService(AudioManager::class.java)
        for ((mode, name) in listOf(AudioManager.MODE_NORMAL to "NORMAL",
            AudioManager.MODE_RINGTONE to "RINGTONE", AudioManager.MODE_IN_CALL to "IN_CALL",
            AudioManager.MODE_IN_COMMUNICATION to "IN_COMMUNICATION")) {
            manager.mode = mode
            val queries = mutableListOf<String>()
            val report = AudioDiagnosticSnapshot.report(context) { _, key ->
                queries.add(key)
                if (key == "audask_logic7") throw SecurityException()
                "$key=01"
            }
            assertTrue(report, report.contains("Audio mode: value=$mode name=$name scope=global"))
            assertTrue(report.contains("query=audask_logic7 state=unknown unavailable=SecurityException"))
            assertTrue(report.contains("query=audask_clarifi raw=audask_clarifi=01 state=on"))
            assertTrue(report.contains("OEM sound stage: query=audask_seatopt"))
            assertEquals(listOf("audask_logic7", "audask_clarifi", "audask_seatopt", "audask_balance", "audask_fader"), queries)
            assertEquals(mode, manager.mode)
        }
    }
}
