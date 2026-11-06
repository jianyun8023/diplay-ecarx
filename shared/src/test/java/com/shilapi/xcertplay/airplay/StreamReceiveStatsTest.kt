package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class StreamReceiveStatsTest {
    @Test fun linksSequenceGapsToTheSameLongReadAndResetsOnlyWindowCounters() {
        var clock = 1L
        val output = mutableListOf<String>()
        val stats = StreamReceiveStats("audio", output::add) { clock }
        fun packet(sequence: Int, readMs: Long) {
            stats.reading()
            clock += readMs * 1_000_000L
            stats.received(100, sequence)
            stats.processed()
        }
        packet(1, 10)
        packet(4, 300) // Two missing packets follow this long socket wait.
        packet(7, 10) // Two more missing packets follow a short socket wait.
        packet(8, 400) // A long read alone does not imply packet loss.
        stats.flush(ended = true)
        assertEquals(1, output.size)
        assertTrue(output.single().contains("windowMs=720 readsOver250Ms=2 seqGapAfterReadOver250Ms=1 seqMissingAfterReadOver250Ms=2"))
        packet(9, 10)
        stats.flush(ended = true)
        assertTrue(output.last().contains("windowMs=10 readsOver250Ms=0 seqGapAfterReadOver250Ms=0 seqMissingAfterReadOver250Ms=0"))
        assertTrue(output.last().contains("seqForwardGaps=0"))
    }

    @Test fun separatesSocketWaitFromLocalProcessingAndTracksSequenceWrap() {
        var clock = 0L
        val output = mutableListOf<String>()
        val stats = StreamReceiveStats("audio", output::add) { clock }
        fun packet(sequence: Int) {
            stats.reading()
            clock += 400_000_000L
            stats.received(100, sequence)
            clock += 200_000L
            stats.processed()
        }
        packet(65535)
        packet(0)
        packet(3) // two missing, then one late packet arrives
        packet(2)
        packet(4)
        stats.flush(ended = true)
        assertEquals(1, output.size)
        assertTrue(output.single().contains("readMaxMs=400 processMaxUs=200 seqForwardGaps=2 lateOrDuplicate=1"))
        assertTrue(output.single().contains("packets=5 bytes=500"))
    }

    @Test fun reportResetsWindowButRetainsSequenceContinuity() {
        var clock = 0L
        val output = mutableListOf<String>()
        val stats = StreamReceiveStats("audio", output::add) { clock }
        stats.reading()
        clock = 5_000_000_000L
        stats.received(10, 1)
        stats.processed()
        stats.reading()
        stats.received(20, 3)
        stats.processed()
        stats.flush(true)
        assertEquals(2, output.size)
        assertTrue(output.last().contains("packets=1 bytes=20 readMaxMs=0"))
        assertTrue(output.last().contains("seqForwardGaps=1"))
    }

    @Test fun decryptTimingIsReportedOnlyWhenRecordedAndThenReset() {
        var clock = 0L
        val output = mutableListOf<String>()
        val stats = StreamReceiveStats("video", output::add) { clock }
        stats.received(1_000)
        stats.decrypted(2_000_000, 500_000)
        stats.decrypted(500_000, 250_000)
        stats.processed()
        clock = 5_000_000_000L
        stats.flush()
        assertTrue(output.last().endsWith(" decryptAvgUs=1250 decryptMaxUs=2000 decryptMBps=300"))
        clock = 10_000_000_000L
        stats.flush()
        assertTrue(!output.last().contains("decrypt"))
    }

    @Test fun heartbeatIntervalDefersHealthyWindows() {
        var clock = 0L
        val output = mutableListOf<String>()
        val stats = StreamReceiveStats(
            "video",
            output::add,
            reportIntervalNs = 60_000_000_000L,
        ) { clock }
        stats.received(100)
        stats.processed()
        clock = 5_000_000_000L
        stats.flush()
        assertEquals(0, output.size)
        clock = 60_000_000_000L
        stats.flush()
        assertEquals(1, output.size)
        assertTrue(output.single().contains("packets=1 bytes=100"))
    }

    @Test fun repeatedLongBlockingReadsDoNotCauseFalseFiveSecondReports() {
        var clock = 0L
        val output = mutableListOf<String>()
        val stats = StreamReceiveStats("audio type=102", output::add, reportIntervalNs = 60_000_000_000L) { clock }
        repeat(15) { sequence ->
            stats.reading()
            clock += 400_000_000L
            stats.received(100, sequence)
            stats.processed()
        }
        assertTrue("healthy reads must not bypass the 60s heartbeat", output.isEmpty())
        clock = 60_000_000_000L
        stats.flush()
        assertEquals(1, output.size)
        assertTrue(output.single().contains("readsOver250Ms=15"))
        assertTrue(output.single().contains("seqForwardGaps=0"))
    }

    @Test fun missingOrReorderedPacketsTriggerReportAtFiveSeconds() {
        var clock = 0L
        val output = mutableListOf<String>()
        val stats = StreamReceiveStats("audio", output::add, reportIntervalNs = 60_000_000_000L) { clock }
        stats.reading()
        clock = 1_000_000_000L
        stats.received(100, 1)
        stats.processed()
        stats.reading()
        clock = 4_000_000_000L
        stats.received(100, 3)
        stats.processed()
        assertTrue(output.isEmpty())
        clock = 5_000_000_000L
        stats.flush()
        assertEquals(1, output.size)
        assertTrue(output.single().contains("seqForwardGaps=1"))
        clock = 10_000_000_000L
        stats.flush()
        assertEquals(1, output.size)

        // An out-of-order/duplicate RTP packet is also a genuine sequence anomaly.
        stats.reading()
        stats.received(100, 3)
        stats.processed()
        clock = 15_000_000_000L
        stats.flush()
        assertEquals(2, output.size)
        assertTrue(output.last().contains("lateOrDuplicate=1"))
    }
}
