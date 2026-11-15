package com.shihab.diplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * The native route owns the sampleTime calculation and the feedback entry layout; the host
 * library runs the same Rust core as production. Clock reads stay on the caller.
 */
class AudioRouteFeedbackEntryTest {
    private fun entry(
        clockRate: Int, firstSample: Int, originNs: Long, nowNs: Long, latencyMs: Int,
        connection: Any?, timestamp: Any?,
    ): Map<String, Any?> {
        val handle = NativeMedia.audioRouteCreate(clockRate, false)
        try {
            NativeMedia.audioRouteAnchor(handle, firstSample, originNs)
            @Suppress("UNCHECKED_CAST")
            return NativeMedia.audioRouteFeedbackEntry(handle, 100, nowNs, latencyMs, connection, timestamp)
                as Map<String, Any?>
        } finally {
            NativeMedia.audioRouteDestroy(handle)
        }
    }

    @Test fun withoutAnAnchorTheEntryCarriesOnlyTypeAndRate() {
        val handle = NativeMedia.audioRouteCreate(48_000, false)
        try {
            @Suppress("UNCHECKED_CAST")
            val entry = NativeMedia.audioRouteFeedbackEntry(handle, 100, 1_000L, 0, null, null)
                as Map<String, Any?>
            assertEquals(listOf("type", "sampleRate"), entry.keys.toList())
            assertEquals(100, entry["type"])
            assertEquals(48_000, entry["sampleRate"])
        } finally {
            NativeMedia.audioRouteDestroy(handle)
        }
    }

    @Test fun anchoredEntriesKeepTheFieldOrderTypesAndExternalObjectIdentity() {
        val connection = BigInteger("18446744073709551615")
        val timestamp = BigInteger("3400000000000000000")
        val entry = entry(48_000, -1000, 0L, 1_000_000_000L, 0, connection, timestamp)
        assertEquals(
            listOf("type", "sampleRate", "streamConnectionID", "timestamp", "timestampRawNs", "sampleTime"),
            entry.keys.toList(),
        )
        assertSame(connection, entry["streamConnectionID"])
        assertSame(timestamp, entry["timestamp"])
        assertEquals(1_000_000_000L, entry["timestampRawNs"])
        // firstSample -1000 becomes the unsigned 0xfffffc18; one elapsed second wraps to 47000.
        assertEquals(47_000L, entry["sampleTime"])
        assertTrue(entry["type"] is Int)
        assertTrue(entry["sampleRate"] is Int)
    }

    @Test fun latencyFloorsHalfUpRoundingAndTheUnsignedWrapSurvive() {
        // A playout latency longer than the elapsed time never moves the position before the start.
        assertEquals(-1000L and 0xffff_ffffL,
            entry(48_000, -1000, 0L, 500_000_000L, 2_000, 42L, null)["sampleTime"])
        // Math.round carries the half-up boundary, below it stays.
        assertEquals(1L, entry(2, 0, 0L, 250_000_000L, 0, 42L, null)["sampleTime"])
        assertEquals(0L, entry(2, 0, 0L, 249_999_999L, 0, 42L, null)["sampleTime"])
        // The unsigned 32-bit clock wraps like the wire value.
        assertEquals(47_999L, entry(48_000, -1, 0L, 1_000_000_000L, 0, 42L, null)["sampleTime"])
    }
}
