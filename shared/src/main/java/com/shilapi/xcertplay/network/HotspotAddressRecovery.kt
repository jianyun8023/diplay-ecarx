package com.shilapi.xcertplay.network

import java.io.InterruptedIOException
import java.net.Inet6Address
import java.net.InetAddress

/** Addresses can appear after the framework reports a newly created group ready. */
internal class HotspotAddressRecovery(
    private val sample: () -> List<InetAddress>,
    private val cancelled: () -> Boolean,
    private val pause: (Long) -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val log: (String) -> Unit = {},
) {
    fun awaitCandidates(
        initial: List<InetAddress>,
        preferredFamily: String?,
        timeoutMillis: Long = WirelessStartupPolicy.HOTSPOT_READY_MILLIS,
    ): List<InetAddress> {
        require(timeoutMillis >= 0)
        val deadline = NativeHotspotAddress.deadline(nowMillis(), timeoutMillis)
        var candidates = initial
        val preferIpv6 = preferredFamily == HotspotAddressPolicy.FAMILY_IPV6
        if (NativeHotspotAddress.readiness(preferIpv6, HotspotAddressPolicy.facts(candidates)) == 2) {
            log("wireless address waiting preference=IPv6 timeoutMs=$timeoutMillis")
        }
        while (true) {
            checkActive()
            val facts = HotspotAddressPolicy.facts(candidates)
            when (NativeHotspotAddress.readiness(preferIpv6, facts)) {
                0 -> return candidates
                1 -> {
                    log("wireless address preference=IPv6 ready elapsedMs=${NativeHotspotAddress.time(deadline, true, nowMillis())}")
                    return NativeHotspotAddress.filter(facts).map(candidates::get)
                }
            }
            val remaining = NativeHotspotAddress.time(deadline, false, nowMillis())
            if (remaining <= 0) {
                log("wireless address preference=IPv6 unavailable elapsedMs=${NativeHotspotAddress.time(deadline, true, nowMillis())}; using available family")
                return candidates
            }
            pause(remaining)
            checkActive()
            candidates = sample()
            checkActive()
        }
    }

    /** Re-sample at timeout; a missing alternate at group creation is not a single-family AP. */
    fun nextFamily(published: InetAddress): String? {
        if (cancelled()) return null
        val candidates = sample()
        if (cancelled()) return null
        if (HotspotAddressPolicy.alternate(candidates, published, scopedOnly = true) == null) return null
        return HotspotAddressPolicy.flipFamily(
            if (published is Inet6Address) HotspotAddressPolicy.FAMILY_IPV6 else HotspotAddressPolicy.FAMILY_IPV4,
        )
    }

    private fun checkActive() {
        if (cancelled()) throw InterruptedIOException("Hotspot address recovery cancelled")
    }

}
