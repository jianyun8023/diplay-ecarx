package com.shilapi.xcertplay.network

import java.net.Inet6Address
import java.net.InetAddress
import java.util.Random
import org.junit.Assert.assertEquals

object HotspotAddressCoreContract {
    private fun addresses(): List<InetAddress> {
        val linkLocal = InetAddress.getByName("fe80::1234") as Inet6Address
        return listOf(InetAddress.getByName("192.168.49.1"), InetAddress.getByName("10.0.0.1"),
            InetAddress.getByName("127.0.0.1"), InetAddress.getByName("0.0.0.0"),
            InetAddress.getByName("169.254.1.2"), InetAddress.getByName("224.0.0.1"),
            InetAddress.getByName("2001:db8::1"), linkLocal,
            Inet6Address.getByAddress(null, linkLocal.address, 7))
    }
    fun verifySelection() {
        val all = addresses()
        val random = Random(20261115)
        repeat(300) {
            val candidates = all.shuffled(random).take(random.nextInt(all.size + 1))
            for (family in listOf(null, "IPv4", "IPv6", "ipv6")) {
                assertEquals(KotlinHotspotAddressPolicy.select(candidates, family), HotspotAddressPolicy.select(candidates, family))
            }
            for (published in all) {
                assertEquals(KotlinHotspotAddressPolicy.alternateFamilyCandidate(candidates, published),
                    HotspotAddressPolicy.alternateFamilyCandidate(candidates, published))
            }
        }
    }
    fun verifyRecovery() {
        val all = addresses()
        for (readyAfter in listOf(0, 1, 4, 20)) for (cancelAt in listOf(-1, 0, 1, 4)) {
            fun run(reference: Boolean): List<Any?> {
                var now = 0L
                var polls = 0
                val events = mutableListOf<Any?>()
                val initial = all.take(1)
                val sample = { polls++; events.add("sample:$polls"); if (polls >= readyAfter) all else initial }
                val pause: (Long) -> Unit = { now += it; events.add("pause:$it") }
                val cancelled = { cancelAt >= 0 && polls >= cancelAt }
                val clock = { events.add("clock:$now"); now }
                val log: (String) -> Unit = { events.add(it) }
                val result = if (reference) {
                    val owner = KotlinHotspotAddressRecovery(sample, cancelled, pause, clock, log)
                    runCatching { owner.awaitCandidates(initial, "IPv6", 1100) }.map { it.map(InetAddress::getHostAddress) }
                } else {
                    val owner = HotspotAddressRecovery(sample, cancelled, pause, clock, log)
                    runCatching { owner.awaitCandidates(initial, "IPv6", 1100) }.map { it.map(InetAddress::getHostAddress) }
                }
                events.add(result.getOrNull())
                events.add(result.exceptionOrNull()?.javaClass?.name)
                return events
            }
            assertEquals("ready=$readyAfter cancel=$cancelAt", run(true), run(false))
        }
        for (published in all) {
            val reference = KotlinHotspotAddressRecovery({ all }, { false }, {})
            val actual = HotspotAddressRecovery({ all }, { false }, {})
            assertEquals(reference.nextFamily(published), actual.nextFamily(published))
        }
    }
}
