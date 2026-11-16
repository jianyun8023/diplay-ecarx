package com.shihab.diplay.airplay

import org.junit.Assert.*
import org.junit.Test

class BufferedPolicyReferenceTest {
    @Test fun setupMatchesFormerHostForKeyFormatAndOptionalFields() {
        val key = ByteArray(32)
        val base = mapOf<String, Any?>("shk" to key, "audioFormat" to 0x800000L)
        for (input in listOf(base, base + ("shk" to ByteArray(31)), base + ("shk" to "wrong"),
            base + ("audioFormat" to null), base + ("audioFormat" to 0x70800000L),
            base + mapOf("ct" to 4.9, "spf" to 1024.9), base + ("ct" to 0x1_0000_0004L),
            base + ("ct" to Double.NaN), base + ("spf" to "wrong"), base + mapOf("ct" to null, "spf" to null))) {
            val old = BufferedPolicyReference.setup(input)
            val actual = NativeAirPlayProtocol.bufferedSetupPlan(input)
            assertEquals(old.reason, actual.failure.ordinal)
            assertEquals(old.bits, actual.formatBits)
            assertSame(old.key, actual.key)
        }
    }

    @Test fun controlMatchesFormerHostIncludingFullLongAndRates() {
        val timestamps = listOf<Any?>(null, Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE, "wrong")
        val rates = listOf<Any?>(null, -0.0, 0, 1.0, 0.5, Double.NaN, Double.POSITIVE_INFINITY, "wrong")
        for (method in listOf("SETRATE", "SETRATEANCHORTIME", "GETANCHOR", "FLUSHBUFFERED", "unknown")) {
            for (timestamp in timestamps) for (rate in rates) {
                val body = mapOf("rtpTime" to timestamp, "rate" to rate, "flushUntilTS" to timestamp)
                val old = BufferedPolicyReference.control(method, body)
                val actual = BufferedAudioControlPolicy.plan(method, body)
                assertEquals(old.action, actual.action.ordinal)
                assertEquals(old.timestamp, actual.timestamp)
                assertEquals(old.rate, actual.rate)
            }
        }
    }
}
