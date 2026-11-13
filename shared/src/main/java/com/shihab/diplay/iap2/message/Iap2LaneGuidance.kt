package com.shihab.diplay.iap2.message

import com.shihab.diplay.airplay.NativeMedia

/** One lane in a complete CarPlay 0x5204 update. Angles retain their wire order. */
data class LaneGuidanceLane(
    val index: Int,
    val status: Int,
    val angles: List<Int>,
    val highlightAngle: Int?,
) {
    val recommended: Boolean get() = status == 2
}

data class LaneGuidanceUpdate(val index: Int?, val lanes: List<LaneGuidanceLane>)

object Iap2LaneGuidance {
    /** Null means malformed; an empty body alone means an explicit clear. */
    fun parse(payload: ByteArray): List<LaneGuidanceLane>? = parseUpdate(payload)?.lanes

    fun parseUpdate(payload: ByteArray): LaneGuidanceUpdate? {
        val rows = NativeLaneGuidance.parse(payload) ?: return null
        return LaneGuidanceUpdate(rows[0][0].takeIf { it >= 0 }, rows.drop(1).map { row ->
            LaneGuidanceLane(row[0], row[1], row.drop(3), row[2].takeUnless { it == Int.MIN_VALUE })
        })
    }
}

internal object NativeLaneGuidance {
    init { NativeMedia.requireAvailable() }
    @JvmStatic external fun parse(payload: ByteArray): Array<IntArray>?
}
