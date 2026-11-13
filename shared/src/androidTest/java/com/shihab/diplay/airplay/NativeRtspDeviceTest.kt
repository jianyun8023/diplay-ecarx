package com.shihab.diplay.airplay

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ProtocolException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

class NativeRtspDeviceTest {
    @Test fun nativeFramingMatchesFallbackAtEveryChunkBoundary() {
        assertTrue(NativeMedia.available)
        val wire = "POST /pair-verify RTSP/1.0\r\nCSeq: 3\r\nContent-Length: +4\r\n\r\nbody".toByteArray() +
            "GET  /info RTSP/1.0\r\nX: old\r\nx:\u001cnew\u001f\r\n".toByteArray() +
            byteArrayOf(-1, ':'.code.toByte(), 0, -1) + "\r\n\r\nOPTIONS *\r\n\r\nPOST /in".toByteArray()
        for (split in wire.indices) {
            val first = RtspMessage.parseMessages(wire.copyOf(split))
            assertParsed(RtspMessage.parseFallback(wire.copyOf(split)), first)
            val remaining = first.rest + wire.copyOfRange(split, wire.size)
            assertParsed(RtspMessage.parseFallback(remaining), RtspMessage.parseMessages(remaining))
        }
    }

    @Test fun nativeResponsesMatchFallbackForDefaultsOverridesAndAsciiEncoding() {
        assertTrue(NativeMedia.available)
        val request = RtspMessage.Request("GET", "/info", "RTSP/1.0", mapOf("cseq" to "5"), byteArrayOf())
        for (status in listOf(null, 200, 400, 404, 500, 470, Int.MIN_VALUE)) {
            for (reason in listOf(null, "", "测试😀\u0000", "\ud800")) {
                val response = RtspMessage.Response(status = status, statusText = reason,
                    headers = linkedMapOf("Content-Type" to "application/test", "cseq" to "lower", "CSeq" to "replace",
                        "Content-Length" to "wrong", "X" to "a\u0000b"), body = byteArrayOf(0, -1, 0x7f))
                assertArrayEquals(RtspMessage.buildResponseFallback(request, response), RtspMessage.buildResponse(request, response))
            }
        }
        val response = RtspMessage.Response(protocol = "HTTP/1.1", status = 404)
        assertArrayEquals(RtspMessage.buildResponseFallback(request.copy(headers = emptyMap()), response),
            RtspMessage.buildResponse(request.copy(headers = emptyMap()), response))
    }

    @Test fun nativeLimitsAndProtocolExceptionsMatchFallbackBeforeBufferingBodies() {
        assertTrue(NativeMedia.available)
        val cases = listOf(ByteArray(0), ByteArray(16 * 1024) { 1 }, ByteArray(16 * 1024 + 1) { 1 },
            "POST / RTSP/1.0\r\nContent-Length: 1500000000\r\n\r\n".toByteArray(),
            "POST / RTSP/1.0\r\nContent-Length: 1048576\r\n\r\npartial".toByteArray(),
            "POST / RTSP/1.0\r\nContent-Length: -2\r\n\r\n".toByteArray(),
            "POST / RTSP/1.0\r\nContent-Length: 12abc\r\n\r\n".toByteArray(),
            "POST / RTSP/1.0\r\nContent-Length: 18446744073709551615\r\n\r\n".toByteArray())
        for (wire in cases) {
            val reference = runCatching { RtspMessage.parseFallback(wire) }
            val native = runCatching { RtspMessage.parseMessages(wire) }
            if (reference.isSuccess) assertParsed(reference.getOrThrow(), native.getOrThrow())
            else {
                assertTrue(native.exceptionOrNull() is ProtocolException)
                assertEquals(reference.exceptionOrNull()!!.message, native.exceptionOrNull()!!.message)
            }
        }
    }

    @Test fun manyMessagesDoNotExhaustJniLocalReferences() {
        assertTrue(NativeMedia.available)
        val request = "OPTIONS * RTSP/1.0\r\nX: value\r\n\r\n".toByteArray()
        val wire = ByteArrayOutputStream().apply { repeat(10_000) { write(request) } }.toByteArray()
        val parsed = RtspMessage.parseMessages(wire)
        assertEquals(10_000, parsed.messages.size)
        assertEquals("value", parsed.messages.last().headers["x"])
        assertTrue(parsed.rest.isEmpty())
    }

    @Test fun actualControlSocketUsesTheCodecAndRejectsOversizedInput() {
        assertTrue(NativeMedia.available)
        for (oversized in listOf(false, true)) {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                Socket(server.inetAddress, server.localPort).use { client ->
                    val session = AirPlaySession(server.accept(),
                        AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "366.0",
                            main = AirPlayDisplayConfig(1280, 720)),
                        AirPlayIdentity.generate(), PairingStore(), null,
                        object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
                    try {
                        session.start()
                        client.soTimeout = 5000
                        val wire = if (oversized) "POST /x RTSP/1.0\r\nContent-Length: 1500000000\r\n\r\n"
                            else "OPTIONS * HTTP/1.1\r\nCSeq: 31\r\n\r\n"
                        client.getOutputStream().apply {
                            write(wire.take(7).toByteArray()); flush()
                            write(wire.drop(7).toByteArray()); flush()
                        }
                        if (oversized) {
                            val closed = runCatching { client.getInputStream().read() }
                            assertTrue(closed.toString(), closed.getOrNull() == -1 || closed.exceptionOrNull() is SocketException)
                        }
                        else {
                            val expected = "HTTP/1.1 200 OK\r\nCSeq: 31\r\nContent-Length: 0\r\n\r\n".toByteArray()
                            val bytes = ByteArray(expected.size)
                            java.io.DataInputStream(client.getInputStream()).readFully(bytes)
                            assertArrayEquals(expected, bytes)
                        }
                    } finally { session.close() }
                }
            }
        }
    }

    private fun assertParsed(expected: RtspMessage.Parsed, actual: RtspMessage.Parsed) {
        assertArrayEquals(expected.rest, actual.rest)
        assertEquals(expected.messages.size, actual.messages.size)
        expected.messages.zip(actual.messages).forEach { (a, b) ->
            assertEquals(a.method, b.method); assertEquals(a.path, b.path); assertEquals(a.protocol, b.protocol)
            assertEquals(a.headers, b.headers); assertArrayEquals(a.body, b.body)
        }
    }
}
