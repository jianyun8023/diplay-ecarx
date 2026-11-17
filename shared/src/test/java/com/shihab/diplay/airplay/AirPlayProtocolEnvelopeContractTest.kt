package com.shihab.diplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contracts the Rust SETUP/command envelope selection through the real native bridge. */
class AirPlayProtocolEnvelopeContractTest {
    @Test
    fun setupPlanSelectsStreamsVsPeer() {
        val streams = NativeAirPlayProtocol.setupPlan(mapOf("streams" to listOf(mapOf("type" to 102))))
        assertTrue(streams is NativeAirPlayProtocol.SetupPlan.Streams)
        assertEquals(1, (streams as NativeAirPlayProtocol.SetupPlan.Streams).entries.size)

        val peer = NativeAirPlayProtocol.setupPlan(mapOf("name" to "iPhone", "deviceID" to "abc"))
        assertSame(NativeAirPlayProtocol.SetupPlan.Peer, peer)
    }

    @Test
    fun remoteCommandPlanDetectsStreamIdAndByteData() {
        val params = mapOf("data" to byteArrayOf(1, 2, 3))
        val remote = NativeAirPlayProtocol.remoteCommandPlan("42", params)
        assertNotNull(remote)
        assertEquals(42L, remote!!.streamId)
        assertArrayEquals(byteArrayOf(1, 2, 3), remote.data)

        assertNull(NativeAirPlayProtocol.remoteCommandPlan(null, params))
        assertNull(NativeAirPlayProtocol.remoteCommandPlan("42", mapOf("x" to 1)))
    }
}
