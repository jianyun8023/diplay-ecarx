package com.shihab.diplay.airplay

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneStartTest {
    private val starts = AtomicInteger()
    private val gate = MicrophoneStart { starts.incrementAndGet() }
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    @Test fun aVoiceMessageWithoutDownlinkAudioStartsTheMicrophoneOnce() {
        var started: Boolean? = null
        gate.scheduleSilentStart(scheduler, 0) { startNow -> started = startNow() }
        drain()

        assertEquals(true, started)
        assertEquals(1, starts.get())
        gate.onDownlink()
        assertEquals(1, starts.get())
    }

    @Test fun theFirstDownlinkPacketStartsCaptureAndCancelsTheSilentStart() {
        val checks = AtomicInteger()
        gate.scheduleSilentStart(scheduler, 60_000) { startNow ->
            checks.incrementAndGet()
            startNow()
        }

        gate.onDownlink()
        gate.onDownlink()
        drain()

        assertEquals(1, starts.get())
        assertEquals(0, checks.get())
    }

    @Test fun aStreamTornDownBeforeTheDelayNeverStartsLate() {
        gate.scheduleSilentStart(scheduler, 0) { /* TEARDOWN already removed the stream's microphone. */ }
        drain()

        assertEquals(0, starts.get())
    }

    @Test fun callsWaitForTheirDownlinkAndOtherInputStreamsDoNot() {
        // The per-audioType rule travels with the Rust microphone plan (`waitDownlink`); this
        // class only keeps the platform start guard and the 500 ms scheduling parameter.
        assertFalse(waitsForDownlink("telephony"))
        assertTrue(waitsForDownlink("speechrecognition"))
        assertTrue(waitsForDownlink("default"))
    }

    private fun waitsForDownlink(audioType: String): Boolean =
        (NativeAirPlayProtocol.audioSetup(100, audioType, mapOf("dataPort" to 50_000L), null, true)
            ?.get("mic") as? Map<*, *>)?.get("waitDownlink") == true

    private fun drain() {
        // Delayed tasks still run after shutdown; cancelled ones are dropped.
        scheduler.shutdown()
        assertTrue(scheduler.awaitTermination(5, TimeUnit.SECONDS))
    }
}
