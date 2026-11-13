package com.shihab.diplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

/** The same packet as `uplink.rs`'s golden test, so the Kotlin and Rust sealers send one layout. */
class MicrophonePacketizerGoldenTest {
    @Test
    fun aSealedPcmPacketMatchesTheGoldenBytes() {
        val key = ByteArray(32) { it.toByte() }
        val counters = MicrophoneCounters(sequence = 7, timestamp = 960, nonce = 3)
        val packet = MicrophonePacketizer.sealPacket(
            key = key, payloadType = 100, counters = counters,
            body = MicrophonePacketizer.toWirePcm(byteArrayOf(1, 2, 3, 4)), samples = 960,
        )
        assertEquals("80640007000003c00000000055727079e7668ab0538bf19d08f4688b9121d2050300000000000000", packet.joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }
}
