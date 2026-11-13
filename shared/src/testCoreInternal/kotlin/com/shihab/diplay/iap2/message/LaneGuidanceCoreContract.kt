package com.shihab.diplay.iap2.message

import java.util.Random
import org.junit.Assert.assertEquals

object LaneGuidanceCoreContract {
    fun verify() {
        val sample = ("00060000002a000600010015" +
            "001b0002000600000000000500010000060002ffa6000600020000" +
            "001500020006000000010005000100000600020000" +
            "00150002000600000002000500010200060003005a")
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val random = Random(20261116)
        val vectors = mutableListOf(byteArrayOf(), sample)
        for (size in 1 until sample.size) vectors += sample.copyOf(size)
        repeat(500) {
            val bytes = sample.copyOf()
            repeat(random.nextInt(4) + 1) { bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte() }
            vectors += bytes
        }
        for (bytes in vectors) {
            assertEquals(KotlinIap2LaneGuidance.parseUpdate(bytes), Iap2LaneGuidance.parseUpdate(bytes))
        }
    }
}
