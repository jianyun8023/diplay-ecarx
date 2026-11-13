package com.shihab.diplay.network

import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * A head unit can carry the running car hotspot on an interface Android never reports as a tethered
 * Wi-Fi AP, for example the `eth0.3` VLAN of a QUALCOMM CS11 unit (#573). These checks cover the
 * rules that decide when such an interface is accepted, and that ordinary head units keep the
 * behaviour they had before.
 */
class ManualHotspotEthernetCarrierTest {
    private val ipv4 = InetAddress.getByName("192.168.15.1")
    private val globals = InetAddress.getByName("198.18.34.15")
    private val ipv6 = InetAddress.getByName("fe80::ff:fe00:1215")

    private fun iface(
        name: String,
        index: Int = 5,
        up: Boolean = true,
        addresses: List<InetAddress> = listOf(ipv4, ipv6),
    ) = HotspotInterfaceSnapshot(name, index, up, addresses, wirelessInterfaceName(name))

    private fun snapshot(
        vararg interfaces: HotspotInterfaceSnapshot,
        ap: Set<String>? = null,
        wifi: Set<String>? = emptySet(),
        default: String? = null,
    ) = HotspotNetworkSnapshot(interfaces.toList(), ap, wifi, default, consistent = true, apEnabled = true)

    private fun select(value: HotspotNetworkSnapshot) = selectHotspotInterface(value) {}

    @Test fun hotspotAddressAcceptsGlobalIpv4AndScopedLinkLocalIpv6() {
        assertTrue(publishesHotspotAddress(ipv4))
        assertTrue(publishesHotspotAddress(ipv6))
        assertFalse(publishesHotspotAddress(InetAddress.getByName("127.0.0.1")))
        assertFalse(publishesHotspotAddress(InetAddress.getByName("0.0.0.0")))
        assertFalse(publishesHotspotAddress(InetAddress.getByName("169.254.1.1")))
        assertFalse(publishesHotspotAddress(InetAddress.getByName("224.0.0.251")))
    }

    @Test fun configuredInterfaceIsAcceptedAsPlatformAp() {
        // The whole point of the override: an address-bearing VLAN becomes the AP interface.
        val selected = select(snapshot(iface("eth0.3"), ap = setOf("eth0.3")))
        assertEquals("eth0.3", selected?.name)
        assertTrue(selected!!.address is Inet4Address)
    }

    @Test fun interfaceWithoutAddressIsStillRejected() {
        assertNull(select(snapshot(iface("eth0.3", addresses = emptyList()), ap = setOf("eth0.3"))))
    }

    @Test fun downstreamInterfaceStaysRejected() {
        assertNull(select(snapshot(iface("eth0.3", up = false), ap = setOf("eth0.3"))))
    }

    @Test fun twoOwnedInterfacesResolveDeterministically() {
        // Both are in the AP set, so readiness picks by name and stays stable across samples rather
        // than flapping between the override and a Wi-Fi AP.
        val wifi = iface("wlan0", index = 9)
        val selected = select(snapshot(iface("eth0.3"), wifi, ap = setOf("eth0.3", "wlan0")))
        assertEquals("eth0.3", selected?.name)
        assertEquals(selected?.name, select(snapshot(iface("eth0.3"), wifi, ap = setOf("wlan0", "eth0.3")))?.name)
    }

    @Test fun ethVlanNamesAreNotTreatedAsWireless() {
        // The candidate filter must not mistake a VLAN for a wireless interface.
        for (name in listOf("eth0", "eth0.3", "eth0.11", "br0")) {
            assertFalse(name, wirelessInterfaceName(name))
        }
        assertTrue(wirelessInterfaceName("wlan0"))
        assertTrue(wirelessInterfaceName("p2p0"))
    }

    @Test fun linkLocalOnlyInterfaceStillSelectsAnIpv6Endpoint() {
        // The platform-owned/explicitly configured path keeps IPv6 support: an interface named as
        // the hotspot that carries only fe80:: still yields an IPv6 endpoint rather than being
        // dropped. (Automatic inference rejects it; see the tests below.)
        val selected = select(snapshot(iface("eth0.3", addresses = listOf(ipv6)), ap = setOf("eth0.3")))
        assertEquals("eth0.3", selected?.name)
        assertTrue(selected!!.address is Inet6Address)
    }

    @Test fun globalAddressThatIsNotPrivateIsStillAValidHotspotAddress() {
        // 198.18.34.15 is what the reference unit published; a private-range filter would drop it.
        assertTrue(publishesHotspotAddress(globals))
        assertTrue(publishesHotspotIpv4(globals))
    }

    @Test fun inferenceRequiresAPublishableIpv4Address() {
        // A bare link-local IPv6 is not hotspot-ownership evidence for an inferred wired interface.
        assertFalse(publishesHotspotIpv4(ipv6))
        assertTrue(publishesHotspotIpv4(ipv4))
        assertFalse(publishesHotspotIpv4(InetAddress.getByName("169.254.1.1")))
        assertFalse(publishesHotspotIpv4(InetAddress.getByName("127.0.0.1")))
        assertFalse(publishesHotspotIpv4(InetAddress.getByName("0.0.0.0")))
    }

    private fun infer(vararg interfaces: HotspotInterfaceSnapshot) = inferHotspotCarrierCandidates(
        interfaces.toList(),
        upstreams = emptySet(),
        defaultInterface = null,
        excludedInterfaces = ManualHotspotInterfaces.EXCLUDED_INTERFACES,
        preferredInterface = ManualHotspotInterfaces.DEFAULT_CAR_HOTSPOT_INTERFACE,
    )

    @Test fun internalEthernetInterfaceWithOnlyLinkLocalAddressIsNotInferred() {
        // eth0/tam0 commonly hold only fe80:: on a car head unit; that must not nominate them.
        assertTrue(infer(iface("eth0", addresses = listOf(ipv6))).isEmpty())
        assertTrue(infer(iface("tam0", addresses = listOf(ipv6))).isEmpty())
    }

    @Test fun inferredCarrierWithIpv4IsStillAccepted() {
        // The vendor VLAN case keeps working: an IPv4-bearing non-wireless interface is inferred.
        assertEquals(setOf("eth0.3"), infer(iface("eth0.3", addresses = listOf(ipv4, ipv6))))
        assertEquals(setOf("br0"), infer(iface("br0", addresses = listOf(ipv4))))
    }

    @Test fun inferenceIgnoresLinkLocalAlongsideIpv4OnUnnamedInterfaces() {
        // Mixed eth0 (fe80:: only) and eth0.3 (IPv4) must infer only eth0.3, never eth0.
        val inferred = infer(
            iface("eth0", addresses = listOf(ipv6)),
            iface("eth0.3", addresses = listOf(ipv4, ipv6)),
        )
        assertEquals(setOf("eth0.3"), inferred)
    }

    @Test fun inferencePrefersTheReferenceInterfaceWhenSeveralSayIpv4() {
        val inferred = infer(
            iface("eth0.3", addresses = listOf(ipv4)),
            iface("br0", addresses = listOf(ipv4)),
        )
        assertEquals(setOf("eth0.3"), inferred)
    }
}
