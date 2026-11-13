package com.shihab.diplay.transport

import com.shihab.diplay.airplay.NativeMedia

/** Ethernet/IPv6 framing and neighbor-advertisement correction in the shared Rust NCM codec. */
object EthernetIpv6Codec {
    const val ETHERTYPE_IPV6 = 0x86dd
    const val MAC_BYTES = 6
    data class Ipv6Frame(val sourceMac: ByteArray, val destinationMac: ByteArray, val ipv6: ByteArray)
    data class Ipv6FrameView(val sourceMac: ByteArray, val payloadOffset: Int, val payloadLength: Int)

    fun parseIpv6(frame: ByteArray): Ipv6Frame? {
        NativeMedia.requireAvailable()
        return NativeNcm.ethernetParse(frame)?.let { Ipv6Frame(it[0], it[1], it[2]) }
    }
    fun parseIpv6View(frame: ByteArray): Ipv6FrameView? {
        NativeMedia.requireAvailable()
        return NativeNcm.ethernetView(frame)?.let(::view)
    }
    internal fun view(values: IntArray) = Ipv6FrameView(ByteArray(6) { values[it].toByte() }, values[6], values[7])
    fun build(sourceMac: ByteArray, destinationMac: ByteArray, ipv6: ByteArray): ByteArray {
        NativeMedia.requireAvailable()
        return NativeNcm.ethernetBuild(sourceMac, destinationMac, ipv6)
    }
    fun multicastDestinationMac(ipv6: ByteArray): ByteArray? {
        NativeMedia.requireAvailable()
        return NativeNcm.ethernetMulticast(ipv6)
    }
    fun addNeighborAdvertisementTargetMac(ipv6: ByteArray, hostMac: ByteArray): ByteArray {
        NativeMedia.requireAvailable()
        return NativeNcm.ethernetFixNa(ipv6, hostMac) ?: ipv6
    }
}
