package com.shilapi.xcertplay.airplay

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Socket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AirPlayMediaCommandTest {
    @Test fun repeatedRecordRequestsRepeatTheActiveCallback() {
        var active = 0
        val messages = mutableListOf<String>()
        val session = AirPlaySession(Socket(),
            AirPlayConfig("test", "test", "test", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity.generate(), PairingStore(), null, object : AirPlaySessionListener {
                override fun onSessionActive(session: AirPlaySession) { active++ }
                override fun onDebugLog(message: String) { messages += message }
            }, object : AirPlayMediaHandler {})
        session.use {
            val handle = session.javaClass.getDeclaredMethod("handle", RtspMessage.Request::class.java)
                .apply { isAccessible = true }
            repeat(2) {
                handle.invoke(session, RtspMessage.Request("RECORD", "/", "RTSP/1.0", emptyMap(), byteArrayOf()))
            }
            assertEquals(2, active)
            assertEquals(2, messages.count { it.contains(" RECORD monotonicNs=") })
        }
    }

    @Test fun playWritesOnlyPressAndReleaseAndReportsIncompleteDeliveryWithoutTrace() {
        val messages = mutableListOf<String>()
        val listener = object : AirPlaySessionListener {
            override val wantsTrace = false
            override fun onDebugLog(message: String) { messages += message }
        }
        val session = AirPlaySession(
            Socket(),
            AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1.0",
                main = AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity.generate(), PairingStore(), null, listener, object : AirPlayMediaHandler {},
        )
        session.use {
            // A queued Play before the event channel is ready must not look like a successful write.
            session.sendMedia(CarPlayMediaButton.PLAY)
            assertTrue(messages.last().contains("downWritten=false upWritten=false"))
            var writes = 0
            var failAtWrite = Int.MAX_VALUE
            val wire = object : ByteArrayOutputStream() {
                override fun write(bytes: ByteArray, offset: Int, count: Int) {
                    if (++writes == failAtWrite) throw IOException("test release write failure")
                    super.write(bytes, offset, count)
                }
            }
            val event = object : Socket() { override fun getOutputStream() = wire }
            for ((name, value) in listOf("eventSocket" to event,
                "eventCipher" to ControlCipher(ByteArray(32) { 1 }, ByteArray(32) { 2 }))) {
                AirPlaySession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
            }
            session.sendMedia(CarPlayMediaButton.PLAY)
            assertTrue(messages.last().contains("downWritten=true upWritten=true"))
            val decrypted = ControlCipher(ByteArray(32) { 2 }, ByteArray(32) { 1 }).decrypt(wire.toByteArray())
            assertTrue(decrypted.rest.isEmpty())
            val requests = RtspMessage.parseMessages(decrypted.data)
            assertTrue(requests.rest.isEmpty())
            assertEquals(2, requests.messages.size)
            requests.messages.forEachIndexed { index, request ->
                assertEquals("POST", request.method)
                assertEquals("/command", request.path)
                val command = BplistCodec.decode(request.body) as Map<*, *>
                // No seek, playback position or restored elapsed time is sent by Play.
                assertEquals(setOf("type", "uuid", "hidReport"), command.keys)
                assertEquals("hidSendReport", command["type"])
                assertEquals(AirPlayHid.MEDIA_HID_UID.toString(16), command["uuid"])
                assertArrayEquals(byteArrayOf(if (index == 0) 1 else 0), command["hidReport"] as ByteArray)
            }
            failAtWrite = writes + 2
            session.sendMedia(CarPlayMediaButton.PLAY)
            assertTrue(messages.last().contains("downWritten=true upWritten=false"))
            assertTrue(messages.none { it.startsWith("TRACE ") })
        }
    }
}
