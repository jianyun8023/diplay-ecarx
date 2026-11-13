package com.shihab.diplay.media

import android.media.MediaCodecList
import android.os.Build

/** Optional MediaTek decoder modes exposed by old MTK ACodec implementations. */
internal enum class MtkDecoderMode(val diagnosticName: String) {
    NONE("off"),
    LOW_LATENCY("low-latency"),
    LOW_LATENCY_NO_REORDER("low-latency+no-reorder"),
    ;

    fun fallback(): MtkDecoderMode = entries[NativeVideoPolicy.mtkMode(ordinal, 0)[0]]
    fun attempts(): List<MtkDecoderMode> = NativeVideoPolicy.mtkMode(ordinal, 1).map { entries[it] }

}

/** Detection and tuning for legacy MTK OMX and dedicated Codec2 low-latency decoders. */
object MtkDecoderTuning {
    const val LOW_LATENCY_KEY = "vdec-lowlatency"

    // This is the spelling used by MTK's ACodec even though it maps to NoReorderMode.
    const val NO_REORDER_KEY = "vdec-no-record"

    fun isDecoderName(name: String): Boolean = videoCodecNameFlags(name) and 4 != 0
    fun supportsLegacyAcodecKeys(name: String): Boolean = videoCodecNameFlags(name) and 8 != 0
    fun isCodec2LowLatencyDecoder(name: String, advertisesLowLatency: Boolean): Boolean =
        videoCodecNameFlags(name, advertisesLowLatency) and 16 != 0
    internal fun selectedDecoder(candidates: List<MtkDecoderCandidate>): MtkDecoderCandidate? =
        candidates.getOrNull(selectedIndex(candidates, 0))

    private fun selectedIndex(candidates: List<MtkDecoderCandidate>, mode: Int): Int =
        NativeVideoPolicy.mtkSelect(candidates.map { videoCodecNameFlags(it.name, it.lowLatency) }.toIntArray(), mode)

    /** The MTK decoder path to expose for [mime], or null when neither safe path is available. */
    fun selectedDecoderName(mime: String): String? = candidates(mime).let(::selectedDecoder)?.name

    /** A dedicated Codec2 low-latency component for [mime], selected explicitly instead of using ACodec keys. */
    fun codec2LowLatencyDecoderName(mime: String): String? = candidates(mime).let { it.getOrNull(selectedIndex(it, 1))?.name }
    fun legacyAcodecDecoderName(mime: String): String? = candidates(mime).let { it.getOrNull(selectedIndex(it, 2))?.name }

    // The codec list does not change while DiPlay runs, and Settings asks on every render.
    private val candidatesByMime = java.util.concurrent.ConcurrentHashMap<String, List<MtkDecoderCandidate>>()

    private fun candidates(mime: String): List<MtkDecoderCandidate> =
        candidatesByMime.getOrPut(mime.lowercase()) { listCandidates(mime) }

    private fun listCandidates(mime: String): List<MtkDecoderCandidate> = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.mapNotNull { info ->
            if (info.isEncoder || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isAlias) ||
                info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) return@mapNotNull null
            val lowLatency = runCatching {
                info.getCapabilitiesForType(mime).isFeatureSupported("low-latency")
            }.getOrDefault(false)
            MtkDecoderCandidate(info.name, lowLatency)
        }
    }.getOrDefault(emptyList())
}

internal data class MtkDecoderCandidate(val name: String, val lowLatency: Boolean = false)

internal fun appliedMtkDecoderMode(requested: MtkDecoderMode, codecName: String): MtkDecoderMode =
    MtkDecoderMode.entries[NativeVideoPolicy.appliedMtkMode(requested.ordinal, MtkDecoderTuning.supportsLegacyAcodecKeys(codecName))]

internal fun MtkDecoderMode.formatKeys(): List<String> = NativeVideoPolicy.mtkMode(ordinal, 2).map {
    when (it) { 0 -> MtkDecoderTuning.LOW_LATENCY_KEY; else -> MtkDecoderTuning.NO_REORDER_KEY }
}
