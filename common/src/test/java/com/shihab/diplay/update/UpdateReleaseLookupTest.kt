package com.shihab.diplay.update

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UpdateReleaseLookupTest {
    private lateinit var server: HttpServer
    // The server stands in for a mirror: it receives the full github.com URL after its prefix.
    private lateinit var mirror: String
    private val requested = CopyOnWriteArrayList<String>()
    private var tag = "v143"

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        mirror = "http://${server.address.hostString}:${server.address.port}/"
        server.createContext("/") { exchange ->
            requested += exchange.requestURI.path
            val bytes = JSONObject().put("tag", tag).put("apk", "DiPlay-release.apk").put("notes", "### Fixes\n- **Reconnect**").toString().toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun installedReadsTheManifestOfItsOwnTag() {
        val lookup = UpdateReleaseLookup.installed(143, "DiPlay/test", listOf("https://unreachable.invalid/", mirror))
        assertEquals("/https://github.com/jianyun8023/diplay-ecarx/releases/download/v143/update.json", requested.single())
        assertEquals(mirror, lookup.route)
        assertEquals("Fixes\n• Reconnect", lookup.release!!.notes)
    }

    @Test
    fun installedRefusesAManifestOfAnotherRelease() {
        tag = "v144"
        assertNull(UpdateReleaseLookup.installed(143, "DiPlay/test", listOf(mirror)).release)
    }
}
