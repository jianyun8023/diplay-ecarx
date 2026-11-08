package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayUiSyncTest {
    private val configuration = "asset:1:19.0\nCenter_Display\nCluster_Display\nlayout\nblue\nzone:component"
    private fun frame(vararg pairs: Pair<String, Any?>) = CarPlayOpack.encode(mapOf(
        "displayUUID" to "Cluster_Display", "data" to BplistCodec.encode(mapOf(*pairs))))
    private fun header(bytes: ByteArray) = BplistCodec.decode(CarPlayOpack.decode(bytes)["data"] as ByteArray) as Map<*, *>

    @Test fun readsEnvelopeReferencesAndRejectsTruncatedData() {
        val vector = byteArrayOf(0xe2.toByte(), 0x41, 0x61, 0x41, 0x62, 0xa1.toByte(), 0x72, 1, 2)
        val fields = CarPlayOpack.decode(vector)
        assertEquals("b", fields["a"])
        assertArrayEquals(byteArrayOf(1, 2), fields["b"] as ByteArray)
        assertArrayEquals(byteArrayOf(1, 2), CarPlayOpack.decode(CarPlayOpack.encode(fields))["b"] as ByteArray)
        assertThrows(IllegalArgumentException::class.java) { CarPlayOpack.decode(vector.copyOf(vector.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { CarPlayOpack.decode(vector + 0) }
    }

    @Test fun usesShortestBlobLengthForIphoneResetCompatibility() {
        for ((length, prefix) in listOf(32 to byteArrayOf(0x90.toByte()),
            33 to byteArrayOf(0x91.toByte(), 33), 256 to byteArrayOf(0x92.toByte(), 0, 1))) {
            val bytes = CarPlayOpack.encode(mapOf("a" to ByteArray(length)))
            assertArrayEquals(prefix, bytes.copyOfRange(3, 3 + prefix.size))
            assertEquals(length, (CarPlayOpack.decode(bytes)["a"] as ByteArray).size)
        }
    }

    @Test fun negotiatesLayoutAndAcknowledgesPhoneWithoutAnsweringAcks() {
        val config = requireNotNull(CarPlayUltraExperiment.parse(configuration))
        val sync = CarPlayUiSync(config.clusterPanel, requireNotNull(config.layout)) {}
        val reset = frame("type" to 0, "ssn" to 1, "psn" to 1, "version" to 3, "id" to "phone")
        val answer = header(sync.handle(reset).single())
        assertEquals(0L, answer["type"])
        assertEquals(2L, answer["version"])
        assertEquals("phone", answer["id"])
        assertTrue(sync.handle(reset).isEmpty())
        val complete = sync.handle(frame("type" to 1, "ssn" to 1, "psn" to 1, "version" to 3))
        assertEquals(4, complete.size)
        val request = BplistCodec.decode(header(complete[2])["data"] as ByteArray) as Map<*, *>
        assertEquals(5L, request["type"])
        val data = request["data"] as Map<*, *>
        assertEquals("layout", data["layoutID"])
        assertEquals(mapOf("zone" to "component"), data["activeComponents"])
        assertEquals(3L, header(sync.handle(frame("type" to 2, "ssn" to 1, "psn" to 2)).single())["type"])
        assertTrue(sync.handle(frame("type" to 3, "ssn" to 1, "psn" to 3)).isEmpty())
        assertTrue(sync.handle(frame("type" to 2, "ssn" to 99, "psn" to 4)).isEmpty())
    }

    @Test fun onlyConfiguredLayoutOffersUiSyncAndHidNamesMatch() {
        assertNull(CarPlayUltraExperiment.parse(configuration + "\nzone:duplicate"))
        assertNull(CarPlayUltraExperiment.parse(configuration.substringBefore("\nblue")))
        val experiment = requireNotNull(CarPlayUltraExperiment.parse(configuration))
        val config = AirPlayConfig("Test", "test", "02:00:00:00:00:02", "950.7.1",
            AirPlayDisplayConfig(1280, 720), AirPlayDisplayConfig(1920, 720), ultraExperiment = experiment)
        assertTrue("uiSync" in setupEnabledFeatures(config, listOf("uiSync")))
        assertFalse("uiSync" in setupEnabledFeatures(config.copy(ultraExperiment = null), listOf("uiSync")))
        val info = AirPlayInfoPlist.build(config)
        assertEquals(emptyMap<String, Any?>(), info["uiSyncInfo"])
        for (hid in info["hidDevices"] as List<*>) assertEquals("Center_Display", (hid as Map<*, *>)["displayUUID"])
    }
}
