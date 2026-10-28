package com.shilapi.xcertplay.iap2.message

import com.shilapi.xcertplay.iap2.wire.Iap2Parameter
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import com.shilapi.xcertplay.iap2.wire.Iap2WireCodec
import org.junit.Assert.*
import org.junit.Test

class Iap2LaneGuidanceTest {
    private val sample = hex(
        "00 06 00 00 00 2a 00 06 00 01 00 00" +
            " 00 15 00 02 00 06 00 00 00 00 00 05 00 01 00 00 06 00 02 ff a6" +
            " 00 15 00 02 00 06 00 00 00 01 00 05 00 01 00 00 06 00 02 00 00" +
            " 00 1b 00 02 00 06 00 00 00 02 00 05 00 01 02 00 06 00 02 00 00" +
            " 00 06 00 03 00 5a",
    )
    private val compound = hex(
        "00 06 00 00 00 2a 00 06 00 01 00 15" +
            " 00 1b 00 02 00 06 00 00 00 00 00 05 00 01 00 00 06 00 02 ff a6" +
            " 00 06 00 02 00 00" +
            " 00 15 00 02 00 06 00 00 00 01 00 05 00 01 00 00 06 00 02 00 00" +
            " 00 15 00 02 00 06 00 00 00 02 00 05 00 01 02 00 06 00 03 00 5a",
    )

    @Test fun capturedLanesKeepAnglesAndHighlightWithoutAddingAnArrow() {
        val lanes = requireNotNull(Iap2LaneGuidance.parse(sample))
        assertEquals(listOf(0, 1, 2), lanes.map { it.index })
        assertEquals(listOf(listOf(-90), listOf(0), listOf(0)), lanes.map { it.angles })
        assertEquals(90, lanes[2].highlightAngle)

        val mixed = requireNotNull(Iap2LaneGuidance.parse(compound))
        assertEquals(listOf(-90, 0), mixed[0].angles)
        assertEquals(emptyList<Int>(), mixed[2].angles)
    }

    @Test fun malformedUpdatesCannotBecomeClearOrPartialLanes() {
        assertEquals(emptyList<LaneGuidanceLane>(), Iap2LaneGuidance.parse(byteArrayOf()))
        assertNull(Iap2LaneGuidance.parse(byteArrayOf(0, 1, 2)))
        assertNull(Iap2LaneGuidance.parse(sample.copyOf(sample.size - 1)))
        assertNull(Iap2LaneGuidance.parse(Iap2ParameterList.of(
            Iap2Parameter(0, Iap2WireCodec.u16(42)), Iap2Parameter(1, Iap2WireCodec.u16(0)),
        ).encode()))
        val badLane = Iap2ParameterList.of(
            Iap2Parameter(0, Iap2WireCodec.u16(1)), Iap2Parameter(1, byteArrayOf()),
        ).encode()
        val body = Iap2ParameterList.of(
            Iap2Parameter(0, Iap2WireCodec.u16(42)), Iap2Parameter(1, Iap2WireCodec.u16(0)),
            Iap2Parameter(2, badLane),
        ).encode()
        assertNull(Iap2LaneGuidance.parse(body))
        val shortDuplicate = Iap2ParameterList.of(
            Iap2Parameter(0, Iap2WireCodec.u16(1)), Iap2Parameter(1, Iap2WireCodec.u8(0)),
            Iap2Parameter(2, Iap2WireCodec.i16(0)), Iap2Parameter(2, byteArrayOf(0)),
        ).encode()
        assertNull(Iap2LaneGuidance.parse(Iap2ParameterList.of(
            Iap2Parameter(0, Iap2WireCodec.u16(42)), Iap2Parameter(1, Iap2WireCodec.u16(0)),
            Iap2Parameter(2, shortDuplicate),
        ).encode()))
    }

    private fun hex(value: String): ByteArray =
        value.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()
}
