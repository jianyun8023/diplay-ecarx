package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Test

/** The same block and advertisement as `ncm.rs`'s golden tests, so the native wired path frames as this one does. */
class NcmNativeCodecGoldenTest {
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test
    fun anNtb16BlockMatchesTheNativeCodec() {
        val block = Ntb16Codec.build(ByteArray(20) { it.toByte() }, 0x1234)
        assertEquals(
            "4e434d480c00341230000c004e434d30100000001c00140000000000000102030405060708090a0b0c0d0e0f10111213",
            block.hex(),
        )
    }

    @Test
    fun aNeighborAdvertisementGetsTheSameOptionAndChecksum() {
        val advertisement = ByteArray(64).apply {
            this[0] = 0x60; this[5] = 24; this[6] = 58; this[7] = 0xff.toByte()
            this[8] = 0xfe.toByte(); this[9] = 0x80.toByte(); this[23] = 1
            this[24] = 0xff.toByte(); this[25] = 0x02; this[39] = 1
            this[40] = 136.toByte(); this[44] = 0x20
            this[48] = 0xfe.toByte(); this[49] = 0x80.toByte(); this[63] = 1
        }
        val fixed = EthernetIpv6Codec.addNeighborAdvertisementTargetMac(advertisement, byteArrayOf(2, 0, 0, 0, 0, 1))
        assertEquals(72, fixed.size)
        assertEquals("0201020000000001", fixed.copyOfRange(64, 72).hex())
        assertEquals("579b", fixed.copyOfRange(42, 44).hex())
    }
}
