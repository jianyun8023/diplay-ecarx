package com.shihab.diplay.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame

internal object BonjourProbeContract {
    fun attemptsAndCancellation() {
        fun reference(successAt: Int, cancelAt: Int, interruptWait: Int): List<String> {
            val events = mutableListOf<String>()
            var reads = 0
            var waits = 0
            fun closed(): Boolean { events += "closed:${reads}"; return reads++ == cancelAt }
            repeat(7) { index ->
                if (closed()) return events
                val attempt = index + 1
                events += "attempt:$attempt"
                if (attempt == successAt) { events += "success:$attempt"; return events }
                events += "failure:$attempt"
                if (closed()) return events
                if (attempt < 7) {
                    events += "wait:1500"
                    if (++waits == interruptWait) { events += "interrupted"; return events }
                }
            }
            events += "exhausted:7"
            return events
        }
        fun actual(successAt: Int, cancelAt: Int, interruptWait: Int): List<String> {
            val events = mutableListOf<String>()
            var reads = 0
            var waits = 0
            fun closed(): Boolean { events += "closed:${reads}"; return reads++ == cancelAt }
            val core = BonjourProbeRetry()
            while (true) {
                val attempt = core.next(closed())
                if (attempt == 0) return events
                events += "attempt:$attempt"
                if (attempt == successAt) { events += "success:$attempt"; return events }
                events += "failure:$attempt"
                val delay = core.failed(closed())
                if (delay < 0) return events
                if (delay == 0L) { events += "exhausted:$attempt"; return events }
                events += "wait:$delay"
                if (++waits == interruptWait) { events += "interrupted"; return events }
            }
        }
        for (success in 0..7) for (cancel in -1..14) for (interrupt in 0..6) {
            assertEquals(reference(success, cancel, interrupt), actual(success, cancel, interrupt))
        }
    }

    fun endpointSelection() {
        val addresses = listOf("192.168.43.1", "127.0.0.1", "0.0.0.0", "169.254.1.1", "224.0.0.1", "fe80::1", "2001:db8::1")
            .map(InetAddress::getByName)
        for (bits in 0 until (1 shl addresses.size)) {
            val selected = addresses.filterIndexed { index, _ -> bits and (1 shl index) != 0 }
            for (values in listOf(selected, selected.reversed())) {
                val expected = values.firstOrNull { it is Inet6Address && it.isLinkLocalAddress }
                    ?: values.firstOrNull { it is Inet4Address }
                    ?: values.firstOrNull { it is Inet6Address }
                    ?: values.firstOrNull()
                assertSame(expected, HotspotAddressPolicy.bonjour(values))
            }
        }
    }
}
