package com.shihab.diplay.media

import com.shihab.diplay.airplay.NativeMedia

/** Stateless AAC protocol conversion; Android owns codec configuration and buffer operations. */
internal object NativeAac {
    init { NativeMedia.requireAvailable() }
    @JvmStatic external fun frequencyIndex(sampleRate: Int): Int
    /** -1 when complete, otherwise the original SDK array write's first failing index. */
    @JvmStatic external fun writeAdtsHeader(target: ByteArray, accessUnitBytes: Int, sampleRate: Int, channels: Int): Int
    @JvmStatic external fun audioSpecificConfig(sampleRate: Int, channels: Int): ByteArray
    @JvmStatic external fun accessUnit(payload: ByteArray): ByteArray
}
