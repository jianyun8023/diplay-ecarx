package com.shihab.diplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The microphone request rules live in protocols::airplay::setup. The host library runs the same
 * Rust core as production, so these drive the JNI boundary and assert the same values the old
 * Kotlin rule tests did.
 */
class AppMicrophoneRequestTest {
    @Test fun typedPlanPreservesNativeKeysAndAudioFields() {
        val secret = ByteArray(32) { it.toByte() }
        val plan = NativeAirPlayProtocol.audioSetupPlan(
            100, "telephony",
            mapOf("streamConnectionID" to -1L, "audioFormat" to 0x40000000L,
                "dataPort" to 50_000L, "audioLatencyMs" to 175L),
            secret, true,
        )!!
        assertEquals(0x40000000L, plan.formatBits)
        assertEquals(50_000, plan.micPort)
        assertEquals(175, plan.latencyMs)
        assertEquals(50_000, plan.microphone!!.port)
        assertArrayEquals(
            AirPlayCrypto.hkdfSha512(secret, "DataStream-Salt18446744073709551615".toByteArray(), "DataStream-Output-Encryption-Key".toByteArray(), 32),
            plan.outputKey,
        )
        assertArrayEquals(
            AirPlayCrypto.hkdfSha512(secret, "DataStream-Salt18446744073709551615".toByteArray(), "DataStream-Input-Encryption-Key".toByteArray(), 32),
            plan.microphone.inputKey,
        )
        val absent = NativeAirPlayProtocol.audioSetupPlan(100, "default", emptyMap(), null, true)!!
        assertNull(absent.outputKey)
        assertEquals(0L, absent.formatBits)
        assertEquals(0, absent.micPort)
        assertEquals(0, absent.latencyMs)
        assertNull(absent.microphone)
        assertNull(mic("telephony", "dataPort" to 50_000L)!!.inputKey)
    }

    private fun mic(audioType: String, vararg entries: Pair<String, Any?>) =
        NativeAirPlayProtocol.audioSetupPlan(100, audioType, mapOf(*entries), null, true)!!.microphone

    private fun wants(audioType: String, vararg entries: Pair<String, Any?>) = mic(audioType, *entries) != null

    @Test fun callsAndSiriAlwaysRecord() {
        assertTrue(wants("telephony", "dataPort" to 50_000L))
        assertTrue(wants("speechrecognition", "dataPort" to 50_000L))
    }

    @Test fun appAudioRecordsOnlyWhenTheIphoneAsksForInput() {
        // A WhatsApp voice note arrives as app audio with an uplink port.
        assertTrue(wants("default", "dataPort" to 50_000L))
        assertTrue(wants("compatibility", "dataPort" to 50_000L))
        assertTrue(wants("default", "input" to true, "dataPort" to 50_000L))
        assertTrue(wants("default", "input" to 1L, "dataPort" to 50_000L))
        // Guidance prompts use the same audio type without input.
        assertFalse(wants("default"))
        assertFalse(wants("default", "dataPort" to 0L))
        assertFalse(wants("default", "input" to false, "dataPort" to 50_000L))
        assertFalse(wants("default", "input" to 0, "dataPort" to 50_000L))
    }

    @Test fun mediaAndAlertsNeverRecord() {
        assertFalse(wants("media", "dataPort" to 50_000L))
        assertFalse(wants("alert", "input" to true, "dataPort" to 50_000L))
    }

    @Test fun anUplinkPortOutsideTheValidRangeRefusesThePlanEvenForCalls() {
        // An explicit input makes the audio type want the microphone, but the port still has to
        // be one the platform can send a datagram to.
        assertFalse(wants("telephony", "dataPort" to 0L))
        assertFalse(wants("telephony", "dataPort" to 65_536L))
        assertFalse(wants("default", "input" to true, "dataPort" to 65_536L))
        assertTrue(wants("default", "input" to true, "dataPort" to 1L))
        assertTrue(wants("default", "input" to true, "dataPort" to 65_535L))
    }

    @Test fun opusBitrateClockAndFrameLengthTravelWithThePlan() {
        val call = mic("telephony", "audioFormat" to 0x40000000L, "dataPort" to 50_000L)!!
        assertEquals(20, call.frameMillis)
        assertEquals(96_000, call.bitrate)
        assertEquals(48_000, call.opusClockRate)
        assertEquals(true, call.waitDownlink)
        val siri = mic("default", "audioFormat" to 0x20000000L, "input" to true, "dataPort" to 50_000L)!!
        assertEquals(64_000, siri.bitrate)
        assertEquals(24_000, siri.opusClockRate)
        assertEquals(false, siri.waitDownlink)
        val quiet = mic("default", "audioFormat" to 0x10000000L, "input" to true, "dataPort" to 50_000L)!!
        assertEquals(48_000, quiet.bitrate)
        assertEquals(16_000, quiet.opusClockRate)
    }

    @Test fun aNamedFramesPerPacketRoundsIntoTheClampAndPcmFallsBackToMtu() {
        // 160 frames of 16 kHz mono are 10 ms; non-Opus streams carry no bitrate.
        val pcm = mic("telephony", "audioFormat" to 0x10L, "framesPerPacket" to 160L, "dataPort" to 50_000L)!!
        assertEquals(10, pcm.frameMillis)
        assertNull(pcm.bitrate)
        // Math.round carries the half-up boundary; the clamp maps any count into 5..60 ms.
        assertEquals(
            5,
            mic("telephony", "audioFormat" to 0x10L, "framesPerPacket" to 1L, "dataPort" to 50_000L)!!.frameMillis,
        )
        assertEquals(
            60,
            mic("telephony", "audioFormat" to 0x10L, "framesPerPacket" to 2_000_000L, "dataPort" to 50_000L)!!.frameMillis,
        )
        // Without framesPerPacket the longest PCM packet that fits one link MTU wins: 10 ms at
        // 48 kHz mono, 20 ms at 16 kHz mono.
        assertEquals(10, mic("telephony", "audioFormat" to 0x4000L, "dataPort" to 50_000L)!!.frameMillis)
        assertEquals(20, mic("telephony", "audioFormat" to 0x10L, "dataPort" to 50_000L)!!.frameMillis)
    }
}
