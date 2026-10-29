package com.shilapi.xcertplay

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/** Local HTTP image handoff, limited to four covers on IPv4 loopback. */
internal class ClusterArtworkServer : Closeable {
    private val images = LinkedHashMap<String, ByteArray>()
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    @Volatile private var client: Socket? = null
    private var published = 0
    private var requests = 0
    private var served = 0
    private var notFound = 0
    private var failures = 0
    private var lastFailure = "none"

    init {
        Thread({
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                client = socket
                socket.use {
                    runCatching {
                        socket.soTimeout = 2_000
                        val input = socket.getInputStream()
                        val header = ByteArrayOutputStream()
                        var ending = 0
                        while (header.size() < 4_096 && ending != 4) {
                            val byte = input.read()
                            if (byte < 0) return@runCatching
                            header.write(byte)
                            ending = if (byte == if (ending % 2 == 0) 13 else 10) ending + 1
                                else if (byte == 13) 1 else 0
                        }
                        if (ending != 4) return@runCatching
                        val request = header.toString("US-ASCII").substringBefore("\r\n").split(' ')
                        if (request.size != 3 || request[0] !in listOf("GET", "HEAD") ||
                            request[2] !in listOf("HTTP/1.0", "HTTP/1.1")) return@runCatching
                        val body = synchronized(images) {
                            requests++
                            images[request[1]].also { if (it == null) notFound++ }
                        }
                        val response = "HTTP/1.1 ${if (body == null) "404 Not Found" else "200 OK"}\r\n" +
                            "Content-Type: image/png\r\nContent-Length: ${body?.size ?: 0}\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().apply {
                            write(response.toByteArray(Charsets.US_ASCII))
                            if (body != null && request[0] == "GET") write(body)
                            flush()
                        }
                        if (body != null && request[0] == "GET") synchronized(images) { served++ }
                    }.onFailure { error ->
                        if (!server.isClosed) synchronized(images) {
                            failures++
                            lastFailure = error.javaClass.simpleName
                        }
                    }
                }
                client = null
            }
        }, "diplay-cluster-artwork").apply { isDaemon = true; start() }
    }

    fun publish(png: ByteArray): String {
        val path = synchronized(images) {
            // Repeated transfers of the same cover must keep the receiver's cache identity.
            val path = images.entries.firstOrNull { it.value.contentEquals(png) }?.key
                ?: "/${UUID.randomUUID()}.png"
            images.remove(path)
            images[path] = png
            published++
            while (images.size > 4) images.remove(images.keys.first())
            path
        }
        return "http://127.0.0.1:${server.localPort}$path"
    }

    fun diagnosticReport(): String = synchronized(images) {
        "Cluster artwork HTTP: listening=${!server.isClosed} loopback=true cached=${images.size} " +
            "published=$published requests=$requests served=$served notFound=$notFound " +
            "failures=$failures lastFailure=$lastFailure"
    }

    override fun close() {

        server.close()
        runCatching { client?.close() }
        synchronized(images) { images.clear() }
    }
}
