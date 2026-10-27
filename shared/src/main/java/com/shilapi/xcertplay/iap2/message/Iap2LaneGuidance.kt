package com.shilapi.xcertplay.iap2.message

import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import com.shilapi.xcertplay.iap2.wire.Iap2WireCodec

/** One lane in a complete CarPlay 0x5204 update. Angles retain their wire order. */
data class LaneGuidanceLane(
    val index: Int,
    val status: Int,
    val angles: List<Int>,
    val highlightAngle: Int?,
) {
    val recommended: Boolean get() = status == 2
}

object Iap2LaneGuidance {
    /** Null means malformed; an empty body alone means an explicit clear. */
    fun parse(payload: ByteArray): List<LaneGuidanceLane>? {
        if (payload.isEmpty()) return emptyList()
        val outer = runCatching { Iap2ParameterList.parse(payload) }.getOrNull() ?: return null
        val component = outer.first(0)?.payload ?: return null
        if (component.size != 2 || outer.first(1)?.payload?.size != 2) return null
        if (Iap2WireCodec.readU16(component) != 42) return null
        val groups = outer.all(2)
        if (groups.isEmpty()) return null
        val lanes = ArrayList<LaneGuidanceLane>(groups.size)
        for (group in groups) {
            val fields = runCatching { Iap2ParameterList.parse(group.payload) }.getOrNull() ?: return null
            for (field in fields.asList()) {
                val expected = when (field.id) { 0, 2, 3 -> 2; 1 -> 1; else -> continue }
                if (field.payload.size != expected) return null
            }
            val index = fields.first(0)?.payload ?: return null
            val status = fields.first(1)?.payload ?: return null
            val angles = ArrayList<Int>()
            for (field in fields.all(2)) {
                val bytes = field.payload
                val angle = Iap2WireCodec.readI16(bytes)
                if (angle != 1000) angles += angle
            }
            val highlight = fields.first(3)?.payload?.let {
                Iap2WireCodec.readI16(it).takeUnless { angle -> angle == 1000 }
            }
            lanes += LaneGuidanceLane(Iap2WireCodec.readU16(index), Iap2WireCodec.readU8(status), angles, highlight)
        }
        return lanes.sortedBy { it.index }
    }
}
