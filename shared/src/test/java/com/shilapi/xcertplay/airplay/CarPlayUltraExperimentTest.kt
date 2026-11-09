package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.transport.*
import org.junit.Assert.*
import org.junit.Test

class CarPlayUltraExperimentTest {
    private val text = "THEME0001:7:19.0\nCenter_Display\nCluster_Display\n"
    private val ultra = requireNotNull(CarPlayUltraExperiment.parse(text))

    @Test fun rejectsIncompleteOrInvalidConfiguration() {
        for (bad in listOf("", "asset:1", text.replace("7", "-1"), text.replace("7", "4294967296"),
            text.replace("19.0", "soon"), text.replace("Cluster_Display", "Center_Display"),
            text.replace("Cluster_Display", "Cluster\u0000Display"))) {
            assertNull(bad, CarPlayUltraExperiment.parse(bad))
        }
        assertEquals(7L, ultra.asset.assetVersion)
    }

    @Test fun wiredAndWirelessIdentifyTheSameAssetAndDefaultsOmitIt() {
        val base = Iap2IdentificationConfig("Test", "Test", "Test", "test", "1", "1", 3)
        for (wireless in listOf(null, Iap2WirelessIdentification("02:00:00:00:00:02", "Test"))) {
            val config = base.copy(wireless = wireless)
            val group = if (wireless == null) 16 else 24
            for (experiment in listOf(null, ultra)) {
                val body = Iap2Messages.reader(Iap2IdentificationClient.identificationInformation(
                    config.copy(ultraExperiment = experiment),
                ))
                assertEquals(experiment?.asset?.assetId, body.optionalGroup(group)!!.optionalString(6))
            }
        }
    }

    @Test fun bothStartMessagesCarrySeparateAssetVersionAndSdk() {
        for (experiment in listOf(null, ultra)) {
            val wired = Iap2WiredControlClient.carPlayStartSession(Iap2WiredCarPlayEndpoint(
                listOf("fe80::2"), 7000, "aabbcc", "950.7.1", ultraExperiment = experiment,
            ))
            val wireless = Iap2WirelessControlClient.carPlayStartSession(Iap2WirelessCarPlayEndpoint(
                "Test", "password", 36, Iap2WirelessSecurity.WPA_WPA2, listOf("192.168.1.1"),
                7000, "test", "aabbcc", "950.7.1", ultraExperiment = experiment,
            ))
            for (frame in listOf(wired, wireless)) {
                val body = Iap2Messages.reader(frame)
                assertEquals(experiment?.sdkVersion, body.optionalString(6))
                assertEquals(experiment?.asset?.assetId, body.optionalGroup(7)?.optionalString(0))
                assertEquals(experiment?.asset?.assetVersion, body.optionalGroup(7)?.optionalU32(1))
            }
        }
    }

    @Test fun themelessPanelsDescribeOnlyTheRealDisplays() {
        val config = AirPlayConfig("Test", "test", "02:00:00:00:00:02", "950.7.1",
            AirPlayDisplayConfig(1920, 1080), AirPlayDisplayConfig(1920, 720), themelessPanels = true)
        val panels = (AirPlayInfoPlist.build(config)["displayPanels"] as List<*>).map { it as Map<*, *> }
        assertEquals(2, panels.size)
        val (main, cluster) = panels
        assertEquals(AirPlayInfoPlist.MAIN_UUID, main["uid"])
        assertEquals(1080, main["heightPixels"])
        assertEquals(emptyList<String>(), main["properties"])
        assertEquals(AirPlayInfoPlist.MAIN_UUID, ((main["initialVideoStreams"] as List<*>).single() as Map<*, *>)["uuid"])
        assertEquals(AirPlayInfoPlist.ALT_UUID, cluster["uid"])
        assertEquals(listOf("showsInstruments"), cluster["properties"])
        assertTrue(panels.none { it.containsKey("displayPlugins") })
        assertEquals(1, (AirPlayInfoPlist.build(config.copy(cluster = null))["displayPanels"] as List<*>).size)
        // A theme describes its own panels.
        val themed = AirPlayInfoPlist.build(config.copy(ultraExperiment = ultra))["displayPanels"] as List<*>
        assertEquals("Center_Display", (themed.first() as Map<*, *>)["uid"])
    }

    @Test fun panelsPointAtExistingStreamsAndOnlyClusterComposesInstruments() {
        val config = AirPlayConfig("Test", "test", "02:00:00:00:00:02", "950.7.1",
            AirPlayDisplayConfig(1280, 720), AirPlayDisplayConfig(1920, 720))
        assertFalse(AirPlayInfoPlist.build(config).containsKey("displayPanels"))
        val info = AirPlayInfoPlist.build(config.copy(ultraExperiment = ultra))
        val displays = info["displays"] as List<*>
        val panels = info["displayPanels"] as List<*>
        assertEquals(2, panels.size)
        for (i in panels.indices) {
            val panel = panels[i] as Map<*, *>
            val display = displays[i] as Map<*, *>
            assertEquals(listOf("Center_Display", "Cluster_Display")[i], panel["uid"])
            assertEquals(panel["uid"], display["uuid"])
            assertEquals(display["widthPixels"], panel["widthPixels"])
            val stream = (panel["initialVideoStreams"] as List<*>).single() as Map<*, *>
            assertEquals(display["uuid"], stream["uuid"])
            assertEquals(if (i == 1) listOf("showsInstruments") else emptyList<String>(), panel["properties"])
        }
        assertNull((panels[0] as Map<*, *>)["displayPlugins"])
        val plugin = ((panels[1] as Map<*, *>)["displayPlugins"] as List<*>).single() as Map<*, *>
        assertEquals(listOf("Cluster_Display"), plugin["videoStreams"])
        assertThrows(IllegalArgumentException::class.java) {
            AirPlayInfoPlist.build(config.copy(cluster = null, ultraExperiment = ultra))
        }
    }
}
