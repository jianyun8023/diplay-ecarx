package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class IphoneCornerMaskSessionTest {
    private val sample = ByteArray(96) { it.toByte() }

    private fun session(native: Boolean, received: MutableList<Pair<ByteArray, Int>>,
        socket: Socket = Socket(), connecting: (AirPlaySession) -> Unit = {}): AirPlaySession =
        AirPlaySession(
            socket = socket,
            config = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "366.0",
                main = AirPlayDisplayConfig(1280, 720, mainDisplayCornerMasks = true,
                    useIphoneCornerMask = native)),
            identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
            listener = object : AirPlaySessionListener {
                override fun onSessionConnecting(session: AirPlaySession) = connecting(session)
                override fun onCornerMask(session: AirPlaySession, png: ByteArray, displayWidth: Int) {
                    received += png to displayWidth
                }
            },
            media = object : AirPlayMediaHandler {
                override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int? =
                    if (type == 110) 43210 else null
            },
        )

    private fun handle(session: AirPlaySession, method: String, payload: Map<String, Any?>): RtspMessage.Response =
        AirPlaySession::class.java.getDeclaredMethod("handle", RtspMessage.Request::class.java)
            .apply { isAccessible = true }.invoke(session,
                RtspMessage.Request(method, "rtsp://test", "RTSP/1.0", emptyMap(),
                    BplistCodec.encode(payload))) as RtspMessage.Response

    @Test fun receivesMaskFromMainScreenSetupOnlyWhenExplicitlyEnabled() {
        for (native in listOf(false, true)) {
            val seen = mutableListOf<Pair<ByteArray, Int>>()
            val session = session(native, seen)
            try {
                val response = handle(session, "SETUP", mapOf("streams" to listOf(
                    mapOf("type" to 110, "topLeftCornerMask" to sample))))
                assertEquals(200, response.status ?: 200)
                if (native) {
                    assertEquals(1, seen.size)
                    assertEquals(1280, seen.single().second)
                    assertArrayEquals(sample, seen.single().first)
                    assertNotSame(sample, seen.single().first)
                } else assertTrue(seen.isEmpty())
            } finally { session.close() }
        }
    }

    /**
     * Without a peer the event listener closed the session in the background, and the repeated
     * SETUP passed only when that close won the race; otherwise the second timing listener threw
     * "NtpClock is already running" (CI). A connected peer keeps the session open throughout.
     */
    @Test fun firstNonStreamSetupReportsConnectingOnce() {
        val loopback = InetAddress.getLoopbackAddress()
        ServerSocket(0, 1, loopback).use { server ->
            Socket(loopback, server.localPort).use { phone ->
                server.accept().use { accepted ->
                    val seen = mutableListOf<AirPlaySession>()
                    val session = session(false, mutableListOf(), accepted) { seen += it }
                    try {
                        handle(session, "SETUP", mapOf("streams" to emptyList<Any>()))
                        assertTrue(seen.isEmpty())
                        val first = ports(handle(session, "SETUP", emptyMap()))
                        assertEquals(listOf(session), seen)
                        // A repeated SETUP reuses the open listeners instead of failing the session.
                        assertEquals(first, ports(handle(session, "SETUP", emptyMap())))
                        handle(session, "SETUP", mapOf("streams" to emptyList<Any>()))
                        assertEquals(listOf(session), seen)
                        assertFalse(session.isClosed)
                    } finally { session.close() }
                }
            }
        }
    }

    private fun ports(response: RtspMessage.Response): Pair<Any?, Any?> {
        val body = BplistCodec.decode(response.body) as Map<*, *>
        return body["timingPort"] to body["eventPort"]
    }

    @Test fun receivesRuntimeMaskUpdateAndIgnoresMalformedOrOversizedData() {
        val seen = mutableListOf<Pair<ByteArray, Int>>()
        val session = session(true, seen)
        try {
            handle(session, "SET_PARAMETER", mapOf("update" to mapOf("topLeftCornerMask" to sample)))
            assertEquals(1, seen.size)
            assertArrayEquals(sample, seen.single().first)
            handle(session, "SET_PARAMETER", mapOf("update" to mapOf("unrelated" to true)))
            assertEquals(1, seen.size)
            handle(session, "SET_PARAMETER", mapOf("topLeftCornerMask" to ByteArray(
                IPhoneCornerMaskWireLimit.MAX_PNG_BYTES + 1)))
            assertEquals(1, seen.size)
        } finally { session.close() }
    }
}
