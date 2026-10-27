package com.shilapi.xcertplay

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class ClusterArtworkServerTest {
    @Test fun servesExactCoverOnLoopbackAndBoundsRetainedCovers() {
        val server = ClusterArtworkServer()
        val cover = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3)
        val first = server.publish(cover)
        val url = URL(first)
        try {
            assertEquals("127.0.0.1", url.host)
            val connection = url.openConnection() as HttpURLConnection
            try {
                assertEquals("image/png", connection.contentType)
                assertArrayEquals(cover, connection.inputStream.use { it.readBytes() })
            } finally { connection.disconnect() }
            assertTrue(server.diagnosticReport().contains("published=1 requests=1 served=1 notFound=0"))
            repeat(4) { server.publish(byteArrayOf(it.toByte())) }
            val expired = url.openConnection() as HttpURLConnection
            try { assertEquals(404, expired.responseCode) } finally { expired.disconnect() }
            val newest = URL(server.publish(cover)).openConnection() as HttpURLConnection
            try {
                newest.requestMethod = "HEAD"
                assertEquals(200, newest.responseCode)
                assertEquals(cover.size, newest.contentLength)
                assertEquals(0, newest.inputStream.use { it.readBytes().size })
            } finally { newest.disconnect() }
        } finally { server.close() }
        assertTrue(runCatching { Socket(url.host, url.port).use {} }.isFailure)
        assertTrue(server.diagnosticReport().contains("listening=false"))
    }

    @Test fun servesNoFilesAndRejectsUnsupportedOrUnboundedRequests() {
        ClusterArtworkServer().use { server ->
            val url = URL(server.publish(byteArrayOf(1)))
            fun request(header: String): String = Socket(InetAddress.getByName(url.host), url.port).use { socket ->
                socket.soTimeout = 3_000
                socket.getOutputStream().write(header.toByteArray())
                socket.getInputStream().readBytes().toString(Charsets.US_ASCII)
            }
            assertTrue(request("GET /../../etc/passwd HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 404"))
            assertEquals("", request("POST ${url.path} HTTP/1.1\r\n\r\n"))
            val oversized = runCatching { request("GET /${"x".repeat(4_096)}") }
            assertEquals("", oversized.getOrDefault(""))
            oversized.exceptionOrNull()?.let { assertTrue(it is java.net.SocketException) }
            assertTrue(request("GET ${url.path} HTTP/1.0\r\n\r\n").startsWith("HTTP/1.1 200"))
        }
    }
}
