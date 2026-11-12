package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.airplay.NativeMedia
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** SDK objects and family preference strings are adapted here; Rust selects the address. */
internal object HotspotAddressPolicy {
    const val FAMILY_IPV4 = "IPv4"
    const val FAMILY_IPV6 = "IPv6"

    fun parseFamily(raw: String?): String? = when (raw) {
        FAMILY_IPV4 -> FAMILY_IPV4
        FAMILY_IPV6 -> FAMILY_IPV6
        else -> null
    }

    fun flipFamily(family: String): String = if (family == FAMILY_IPV6) FAMILY_IPV4 else FAMILY_IPV6

    fun select(addresses: List<InetAddress>): InetAddress? = select(addresses, null)

    fun select(addresses: List<InetAddress>, preferredFamily: String?): InetAddress? =
        addresses.getOrNull(NativeHotspotAddress.select(facts(addresses), if (preferredFamily == FAMILY_IPV6) 1 else 0, false))

    fun alternateFamilyCandidate(addresses: List<InetAddress>, published: InetAddress): InetAddress? =
        alternate(addresses, published, false)

    internal fun alternate(addresses: List<InetAddress>, published: InetAddress, scopedOnly: Boolean): InetAddress? =
        addresses.getOrNull(NativeHotspotAddress.select(facts(addresses), if (published is Inet6Address) 3 else 2, scopedOnly))

    internal fun bonjour(addresses: List<InetAddress>): InetAddress? =
        addresses.getOrNull(NativeHotspotAddress.select(facts(addresses), 4, false))

    internal fun facts(addresses: List<InetAddress>): IntArray = addresses.map { address ->
        (if (address is Inet4Address && !address.isLoopbackAddress && !address.isAnyLocalAddress &&
            !address.isLinkLocalAddress && !address.isMulticastAddress) 1 else 0) or
            (if (address is Inet6Address && address.isLinkLocalAddress) 2 else 0) or
            (if (address is Inet6Address && address.isLinkLocalAddress && address.scopeId != 0) 4 else 0) or
            (if (address is Inet6Address) 8 else 0) or
            (if (address is Inet4Address) 16 else 0)
    }.toIntArray()
}

internal object NativeHotspotAddress {
    init { NativeMedia.requireAvailable() }
    @JvmStatic external fun select(facts: IntArray, operation: Int, scopedOnly: Boolean): Int
    @JvmStatic external fun readiness(preferIpv6: Boolean, facts: IntArray): Int
    @JvmStatic external fun filter(facts: IntArray): IntArray
    @JvmStatic external fun flipGate(phoneOnHotspot: Boolean, familyReachable: Boolean): Int
    @JvmStatic external fun deadline(start: Long, timeout: Long): LongArray
    @JvmStatic external fun time(token: LongArray, elapsed: Boolean, now: Long): Long
}
