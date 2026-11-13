package com.shihab.diplay.transport

import com.shihab.diplay.airplay.NativeMedia

/** NTB16 framing in the shared Rust NCM codec; Android retains bulk-transfer ownership. */
object Ntb16Codec {
    const val NTH16_SIG = 0x484d434e
    const val NDP16_SIG = 0x304d434e
    fun build(frame: ByteArray, sequence: Int): ByteArray {
        NativeMedia.requireAvailable()
        return NativeNcm.ntbBuild(frame, sequence)
    }
    fun parse(block: ByteArray): List<ByteArray> = parse(block, 0, block.size)
    fun parse(block: ByteArray, offset: Int, length: Int): List<ByteArray> {
        NativeMedia.requireAvailable()
        return NativeNcm.ntbParse(block, offset, length).asList()
    }
}
