package com.shilapi.xcertplay.network

import android.net.wifi.p2p.WifiP2pManager
import com.shilapi.xcertplay.network.reference.KotlinP2pStartupRecovery
import org.junit.Assert.*
import org.junit.Test

class NativeP2pStartupDeviceTest {
    @Test fun channelPlansAndBusyRetriesUseRealJniWithoutTouchingTheRadio() {
        for (station in listOf(null, 2437, 5200, 5500)) for (channel in listOf(0, -5, -2, 149)) {
            assertEquals(KotlinP2pStartupRecovery.plan(station, preferredChannel = channel),
                P2pStartupRecovery.plan(station, preferredChannel = channel))
        }
        val requests = mutableListOf<P2pCreationRequest>()
        var guards = 0
        val selected = P2pStartupRecovery.create(5200, { guards++ }) {
            requests += it
            if (requests.size <= 2) throw P2pCreateRejected(WifiP2pManager.BUSY, "busy")
        }
        assertEquals(listOf(5200, 5200, 2437), requests.map { it.frequencyMHz })
        assertEquals(2, guards)
        assertEquals(P2pCreationRequest(P2pCreationMode.FIXED_2_GHZ, 2437), selected)
        val terminal = P2pCreateRejected(WifiP2pManager.ERROR, "default rejected")
        val failure = assertThrows(P2pCreateRejected::class.java) {
            P2pStartupRecovery.create(5200, {}) {
                if (it.mode == P2pCreationMode.SYSTEM_DEFAULT) throw terminal
                throw P2pConfigBuildCompatibilityFailure(NoSuchMethodError("vendor framework"))
            }
        }
        assertSame(terminal, failure)
        assertNull(P2pHotspotConflict.failure(33, true, P2pCreateRejected(WifiP2pManager.BUSY, "busy", 999)))
        assertNotNull(P2pHotspotConflict.failure(33, true, P2pCreateRejected(WifiP2pManager.BUSY, "busy", 1000)))
    }
}
