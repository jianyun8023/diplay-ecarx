package com.shihab.diplay.network

import com.shihab.diplay.network.CarHotspotReady.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * The boot gate reuses [ManualHotspotReadiness]; these tests pin only the gate's own rules: what ends
 * the wait, what it reports, and that it never throws. The stable-sample rule itself stays covered by
 * [ManualHotspotReadinessTest].
 */
class CarHotspotReadyTest {
    private val ipv4 = InetAddress.getByName("192.168.43.1")
    private var now = 0L

    private fun iface(name: String = "wlan0", index: Int = 7, up: Boolean = true,
        addresses: List<InetAddress> = listOf(ipv4)) =
        HotspotInterfaceSnapshot(name, index, up, addresses, wirelessInterfaceName(name))

    private fun snapshot(vararg interfaces: HotspotInterfaceSnapshot, ap: Set<String>? = null,
        wifi: Set<String>? = emptySet(), default: String? = null, consistent: Boolean = true) =
        HotspotNetworkSnapshot(interfaces.toList(), ap, wifi, default, consistent)

    private fun await(
        timeout: Long = 2_000,
        current: () -> Boolean = { true },
        sample: () -> HotspotNetworkSnapshot,
    ): CarHotspotReady.Outcome = CarHotspotReady.await(
        sample = sample,
        isCurrent = current,
        timeoutMillis = timeout,
        pause = { now += it },
        now = { now },
    )

    @Test fun aConfirmedHotspotOpensWithoutFurtherDelay() {
        val outcome = await { snapshot(iface(), ap = setOf("wlan0")) }
        assertEquals(Reason.READY, outcome.reason)
        // Two samples: one poll interval, not the whole cap.
        assertEquals(250L, outcome.waitedMs)
        assertEquals(250L, now)
        assertEquals("wlan0/192.168.43.1", outcome.address)
        assertEquals(true, outcome.apEnabled)
    }

    @Test fun aHotspotThatStaysOffStopsAtTheCap() {
        // The AP state is reported (and off): the gate waits, but never past the cap.
        val outcome = await { snapshot(iface(), ap = setOf("wlan0")).copy(apEnabled = false) }
        assertEquals(Reason.TIMEOUT, outcome.reason)
        assertEquals(2_000L, outcome.waitedMs)
        assertEquals(2_000L, now)
        assertEquals(false, outcome.apEnabled)
        assertNull(outcome.address)
    }

    @Test fun aHotspotWithoutAUsableAddressKeepsWaiting() {
        val outcome = await { snapshot(iface(addresses = emptyList()), ap = setOf("wlan0")) }
        assertEquals(Reason.TIMEOUT, outcome.reason)
        assertEquals(2_000L, outcome.waitedMs)
        assertNull(outcome.address)
    }

    @Test fun aCancelledLaunchReturnsAtOnce() {
        val outcome = await(current = { false }) { snapshot(iface(), ap = setOf("wlan0")) }
        assertEquals(Reason.CANCELLED, outcome.reason)
        assertEquals(0L, outcome.waitedMs)
        assertEquals(0L, now)
        assertNull(outcome.address)
    }

    @Test fun anUnobservableApStateDoesNotDelayTheBoot() {
        // Firmware that hides the AP state cannot be waited on: there is no evidence either way, so the
        // gate ends after the first sample and lets the launch continue.
        val outcome = await { snapshot(iface()).copy(apEnabled = null) }
        assertEquals(Reason.UNOBSERVABLE, outcome.reason)
        assertEquals(0L, outcome.waitedMs)
        assertEquals(0L, now)
        assertNull(outcome.apEnabled)
        assertNull(outcome.address)
    }

    @Test fun theHotspotAppearingAfterTheWaitStillConfirmsThroughTheExistingRule() {
        val outcome = await {
            if (now < 500) snapshot(iface(), ap = emptySet()) else snapshot(iface(), ap = setOf("wlan0"))
        }
        assertEquals(Reason.READY, outcome.reason)
        assertEquals(1_000L, outcome.waitedMs)
        assertEquals("wlan0/192.168.43.1", outcome.address)
    }

    @Test fun aFailingSampleIsReportedAsAnErrorAndNeverThrows() {
        val outcome = await { throw IllegalStateException("interface listing failed") }
        assertEquals(Reason.ERROR, outcome.reason)
        assertNull(outcome.address)
    }

    @Test fun aCancellationDuringSamplingCannotReportReady() {
        var cancelled = false
        val outcome = await(current = { !cancelled }) {
            if (now >= 250) cancelled = true
            snapshot(iface(), ap = setOf("wlan0"))
        }
        assertTrue(outcome.reason == Reason.CANCELLED)
        assertNull(outcome.address)
    }
}
