package com.shihab.diplay.airplay

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AirPlayEventPeerTest {
    @org.junit.Before fun needsIpv6Loopback() {
        org.junit.Assume.assumeTrue("no IPv6 loopback", runCatching {
            java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("::1")).use { true }
        }.getOrDefault(false))
    }

    private val logs = CopyOnWriteArrayList<String>()
    private val strangerRejected = CountDownLatch(1)
    private val peerAccepted = CountDownLatch(1)

    private fun openEvent(session: AirPlaySession): Int {
        val method = AirPlaySession::class.java.getDeclaredMethod("openEvent")
        method.isAccessible = true
        return method.invoke(session) as Int
    }

    /** A session whose control peer is 127.0.0.1. */
    private fun withSession(block: (AirPlaySession) -> Unit) {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { control ->
            val client = Socket(InetAddress.getByName("127.0.0.1"), control.localPort)
            val accepted = control.accept()
            val session = AirPlaySession(
                socket = client,
                config = AirPlayConfig(
                    deviceName = "test",
                    deviceId = "02:00:00:00:00:02",
                    btMac = "02:00:00:00:00:01",
                    sourceVersion = "1.0",
                    main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
                ),
                identity = AirPlayIdentity.generate(),
                pairings = PairingStore(),
                mfi = null,
                listener = object : AirPlaySessionListener {
                    override fun onDebugLog(message: String) {
                        logs += message
                        if (message.contains("event rejected sender outside the control session")) strangerRejected.countDown()
                        if (message.contains("accepted from /127.0.0.1")) peerAccepted.countDown()
                    }
                },
                media = object : AirPlayMediaHandler {},
            )
            try { block(session) } finally { session.close(); accepted.close() }
        }
    }

    @Test
    fun aStrangerCannotTakeTheEventSlot() = withSession { session ->
        val port = openEvent(session)
        Socket(InetAddress.getByName("::1"), port).use {
            assertTrue("stranger was not rejected: $logs", strangerRejected.await(5, TimeUnit.SECONDS))
        }
        try {
            Socket(InetAddress.getByName("127.0.0.1"), port).use {
                assertTrue("control peer was not accepted: $logs", peerAccepted.await(5, TimeUnit.SECONDS))
            }
        } catch (error: java.net.ConnectException) {
            fail("control peer could not connect after a stranger took the slot: $error")
        }
        assertTrue("stranger was accepted: $logs", logs.none { it.contains("accepted from /0:0:0:0:0:0:0:1") })
        assertTrue("control peer was not accepted: $logs", logs.any { it.contains("accepted from /127.0.0.1") })
    }

    @Test
    fun theControlPeerIsAccepted() = withSession { session ->
        val port = openEvent(session)
        Socket(InetAddress.getByName("127.0.0.1"), port).use {
            assertTrue("control peer was not accepted: $logs", peerAccepted.await(5, TimeUnit.SECONDS))
        }
        assertTrue("control peer was not accepted: $logs", logs.any { it.contains("accepted from /127.0.0.1") })
    }
}
