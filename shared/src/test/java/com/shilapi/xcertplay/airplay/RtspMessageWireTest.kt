package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class RtspMessageWireTest {
    @Test fun signedInvalidAndOverflowingLengthsKeepTheExistingWholeIntegerBehavior() {
        for (length in listOf("-2", "12abc", "2147483648", "18446744073709551615")) {
            val wire = "GET /x RTSP/1.0\r\nContent-Length: $length\r\n\r\n".toByteArray()
            assertEquals(1, RtspMessage.parseMessages(wire).messages.size)
        }
        val parsed = RtspMessage.parseMessages("POST /x RTSP/1.0\r\nContent-Length: +2\r\n\r\nab".toByteArray())
        assertArrayEquals("ab".toByteArray(), parsed.messages.single().body)
    }

    @Test fun requestSpacingHeaderDuplicatesAndAsciiReplacementStayStable() {
        val wire = "GET  /info RTSP/1.0\r\nX : old\r\nx:\u001cnew\u001f\r\n".toByteArray() +
            byteArrayOf(-1, ':'.code.toByte(), ' '.code.toByte(), -1) + "\r\n\r\n".toByteArray()
        val request = RtspMessage.parseMessages(wire).messages.single()
        assertEquals("", request.path)
        assertEquals("/info RTSP/1.0", request.protocol)
        assertEquals("new", request.headers["x"])
        assertEquals("\ufffd", request.headers["\ufffd"])
    }

    @Test fun responseGoldenBytesKeepInsertionOrderAndBinaryBodies() {
        val request = RtspMessage.Request("GET", "/info", "RTSP/1.0", mapOf("cseq" to "5"), byteArrayOf())
        val response = RtspMessage.Response(headers = linkedMapOf("Content-Type" to "application/x-apple-binary-plist", "CSeq" to "0"),
            body = "ab".toByteArray())
        assertArrayEquals("RTSP/1.0 200 OK\r\nContent-Type: application/x-apple-binary-plist\r\nCSeq: 5\r\nContent-Length: 2\r\n\r\nab".toByteArray(),
            RtspMessage.buildResponse(request, response))
        assertArrayEquals("RTSP/1.0 500 Internal Server Error\r\nCSeq: 5\r\nContent-Length: 0\r\n\r\n".toByteArray(),
            RtspMessage.buildResponse(request, RtspMessage.Response(status = 500)))
        // RTSP status text is encoded in the Rust core, which replaces each UTF-16 unit (like Android)
        // rather than the host JDK's single replace-per-code-point. So the surrogate pair becomes "??".
        assertArrayEquals("HTTP/1.1 470 ????\r\nCSeq: 5\r\nContent-Length: 1\r\n\r\n".toByteArray() + byteArrayOf(-1),
            RtspMessage.buildResponse(request, RtspMessage.Response("HTTP/1.1", 470, "测试😀", body = byteArrayOf(-1))))
    }
}
