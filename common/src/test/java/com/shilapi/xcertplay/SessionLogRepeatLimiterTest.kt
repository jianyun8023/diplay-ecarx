package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLogRepeatLimiterTest {
    private var clock = 0L
    private val limiter = SessionLogRepeatLimiter({ clock }, summaryIntervalMillis = 600_000L)

    private fun prompt(port: Int, connection: Long, hz: Int, ahead: Int) = listOf(
        "Audio: navigation prompt focus requested trigger=duck gain=3 usage=12 contentType=20 granted=1",
        "airplay SETUP keys=[streams]",
        "Audio UDP receive buffer type=101 original=212992 requested=524288 actual=1048576",
        "airplay audio stream type=101 accepted=true dataPort=$port controlPort=${port + 1}",
        "airplay SETUP response streams=[{type=101, dataPort=$port, controlPort=${port + 1}, streamConnectionID=$connection}]",
        "Audio: starting api=28 audioType=default codec=OPUS rate=48000 channels=1 mapping=automotive focus=true",
        "Audio: navigation prompt start iphoneDuck=true promptFocus=held usage=12 contentType=20",
        "Audio: feedback type=101 id=$connection reported=10391 received=11520 aheadMs=$ahead",
        "Audio: rtp clock type=101 audioType=default audioFormat=0x10000000 measuredHz=$hz microphoneClockHz=none",
        "Audio: navigation prompt focus released reason=unduck result=1 heldMs=4200",
        "Audio: focus change=1 activeTracks=1",
        "Receive: audio type=101 packets=335 bytes=29773 ended=true",
    )

    private fun written(lines: List<String>) = lines.flatMap(limiter::filter)

    @Test fun aRepeatedPromptKeepsOnlyItsStartAndEndLines() {
        assertEquals(12, written(prompt(56162, 146267L, 15968, -70)).size)
        val second = written(prompt(42617, 998877L, 16037, -79))
        assertEquals(listOf(
            "Audio: navigation prompt start iphoneDuck=true promptFocus=held usage=12 contentType=20",
            "Audio: navigation prompt focus released reason=unduck result=1 heldMs=4200",
            "Receive: audio type=101 packets=335 bytes=29773 ended=true",
        ), second)
    }

    @Test fun aNewAirPlaySessionWritesItsOwnSetupAndClosesThePreviousCounts() {
        written(prompt(1, 1L, 16000, -70))
        written(prompt(2, 2L, 16000, -70))
        val summary = limiter.newSession()
        assertTrue(summary, summary!!.startsWith("Log repeats not written"))
        assertEquals(12, written(prompt(3, 3L, 16000, -70)).size)
    }

    @Test fun aMeasuredValueThatComesBackIsWrittenAgain() {
        written(prompt(1, 1L, 16000, -70))
        val late = "Audio: feedback type=101 id=9 reported=1 received=2 aheadMs=-190"
        assertEquals(listOf(late), limiter.filter(late))
        val normal = "Audio: feedback type=101 id=10 reported=1 received=2 aheadMs=-72"
        assertEquals(listOf(normal), limiter.filter(normal))
        assertEquals(listOf(late), limiter.filter(late))
        // Another stream type keeps its own last value.
        val media = "Audio: feedback type=100 id=11 reported=1 received=2 aheadMs=-72"
        assertEquals(listOf(media), limiter.filter(media))
        assertEquals(emptyList<String>(), limiter.filter(media))
    }

    @Test fun aFocusGainAfterALossIsAlwaysWritten() {
        val gain = "Audio: focus change=1 activeTracks=1"
        assertEquals(listOf(gain), limiter.filter(gain))
        assertEquals(emptyList<String>(), limiter.filter(gain))
        val loss = "Audio: focus change=-2 activeTracks=1"
        assertEquals(listOf(loss), limiter.filter(loss))
        assertEquals(listOf(gain), limiter.filter(gain))
        assertEquals(emptyList<String>(), limiter.filter(gain))
    }

    @Test fun theDuckAndSoundTriggersShareOneFocusKey() {
        val duck = "Audio: navigation prompt focus requested trigger=duck gain=3 usage=12 contentType=20 granted=1"
        val audio = "Audio: navigation prompt focus requested trigger=audio gain=3 usage=12 contentType=20 granted=1"
        assertEquals(listOf(duck), limiter.filter(duck))
        assertEquals(emptyList<String>(), limiter.filter(audio))
    }

    @Test fun aChangedValueIsWrittenInFull() {
        written(prompt(56162, 1L, 15968, -70))
        val refused = "Audio: navigation prompt focus requested trigger=audio gain=3 usage=12 contentType=20 granted=0"
        assertEquals(listOf(refused), limiter.filter(refused))
        val drifted = "Audio: rtp clock type=101 audioType=default audioFormat=0x10000000 measuredHz=15200 microphoneClockHz=none"
        assertEquals(listOf(drifted), limiter.filter(drifted))
        val late = "Audio: feedback type=101 id=9 reported=1 received=2 aheadMs=-190"
        assertEquals(listOf(late), limiter.filter(late))
    }

    @Test fun suppressedCountsAreReportedPeriodicallyAndAtTheEnd() {
        written(prompt(1, 1L, 16000, -70))
        written(prompt(2, 2L, 16000, -70))
        clock = 600_000L
        val third = written(prompt(3, 3L, 16000, -70))
        // The summary goes out with the first repeat after the interval: the second prompt's lines.
        val summary = third.first()
        assertTrue(summary, summary.startsWith("Log repeats not written"))
        assertTrue(summary, summary.contains("Audio starting×1"))
        written(prompt(4, 4L, 16000, -70))
        val end = limiter.flush()!!
        assertTrue(end, end.contains("Audio starting×2"))
        assertNull(limiter.flush())
    }

    @Test fun otherLinesPassThrough() {
        val line = "wireless startup generation=1 listener=1 StartSession atNs=1"
        assertEquals(listOf(line), limiter.filter(line))
        assertEquals(listOf(line), limiter.filter(line))
    }
}
