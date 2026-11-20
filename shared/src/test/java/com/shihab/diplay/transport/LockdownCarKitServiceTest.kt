package com.shihab.diplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** CarKit service negotiation against the native state machine, with scripted platform streams. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LockdownCarKitServiceTest {

    @Test
    fun openServiceSwitchesTlsThenConnectsTheServiceAndHandsBothStreamsOff() {
        val closes = mutableListOf<String>()
        val lockdown = ScriptedStream("lockdown", closes) { "<key>EnableSessionSSL</key><true/>" }
        // The TLS line over Lockdown answers StartService after the switch.
        val tlsOverLockdown = ScriptedStream("tls-lockdown", closes, lockdown) {
            "<key>Port</key><integer>1234</integer><key>EnableServiceSSL</key><true/>"
        }
        val serviceLine = ScriptedStream("service-line", closes)
        val serviceTls = ScriptedStream("service-tls", closes, serviceLine)
        val connections = mutableListOf<Pair<Int, Long>>()
        val tlsUnderlyings = mutableListOf<BlockingDuplexByteStream>()
        val tlsTimeouts = mutableListOf<Long>()
        val client = LockdownCarKitClient(
            connect = { port, timeout ->
                connections += port to timeout
                when (port) {
                    62078 -> lockdown
                    1234 -> serviceLine
                    else -> throw AssertionError("unexpected service port $port")
                }
            },
            tlsOpen = { underlying, _, timeout ->
                tlsUnderlyings += underlying
                tlsTimeouts += timeout
                when (underlying) {
                    lockdown -> tlsOverLockdown
                    serviceLine -> serviceTls
                    else -> throw AssertionError("unexpected TLS underlying")
                }
            },
        )

        val result = client.openService(record(), "test", "com.apple.carkit.service")

        // Connection order and budgets, exactly as the native negotiation plans them.
        assertEquals(listOf(62078 to 5_000L, 1234 to 5_000L), connections)
        assertEquals(1, lockdown.requests.count { "<string>StartSession</string>" in it })
        assertEquals(1, tlsOverLockdown.requests.count { "<string>StartService</string>" in it })
        assertEquals(listOf<BlockingDuplexByteStream>(lockdown, serviceLine), tlsUnderlyings)
        assertEquals(listOf(5_000L, 5_000L), tlsTimeouts)
        assertTrue(closes.isEmpty())

        // The stream that reaches the caller keeps the Lockdown session that created it alive.
        result.close()
        // The service line closes first; its own TLS wrapper releases the raw stream beneath it.
        // The Lockdown channel then closes its TLS line, which releases the detached Lockdown stream.
        assertEquals(listOf("service-tls", "service-line", "tls-lockdown", "lockdown"), closes)
    }

    @Test
    fun aServiceWithoutItsOwnTlsFlagConnectsThePlainServiceStream() {
        val closes = mutableListOf<String>()
        val lockdown = ScriptedStream("lockdown", closes) { "<key>EnableSessionSSL</key><true/>" }
        val tlsOverLockdown = ScriptedStream("tls-lockdown", closes, lockdown) {
            "<key>Port</key><integer>2345</integer>"
        }
        val serviceLine = ScriptedStream("service-line", closes)
        val connections = mutableListOf<Pair<Int, Long>>()
        var tlsCalls = 0
        val client = LockdownCarKitClient(
            connect = { port, timeout ->
                connections += port to timeout
                when (port) {
                    62078 -> lockdown
                    2345 -> serviceLine
                    else -> throw AssertionError("unexpected service port $port")
                }
            },
            tlsOpen = { _, _, _ -> tlsCalls++; tlsOverLockdown },
        )

        client.openService(record(), "test", "com.apple.carkit.service").close()

        // One TLS handshake: only the Lockdown session line, never the plain service stream.
        assertEquals(listOf(62078 to 5_000L, 2345 to 5_000L), connections)
        assertEquals(1, tlsCalls)
        assertEquals(listOf("service-line", "tls-lockdown", "lockdown"), closes)
    }

    @Test
    fun startSessionRejectionCarriesTheStructuredReasonAndCleansUp() {
        val closes = mutableListOf<String>()
        val lockdown = ScriptedStream("lockdown", closes) { "<key>Error</key><string>InvalidPairRecord</string>" }
        val connections = mutableListOf<Pair<Int, Long>>()
        val client = LockdownCarKitClient(
            connect = { port, timeout -> connections += port to timeout; lockdown },
            tlsOpen = { _, _, _ -> throw AssertionError("no TLS handshake after a rejected StartSession") },
        )

        try {
            client.openService(record(), "test", "com.apple.carkit.service")
            fail("expected the Lockdown rejection to surface")
        } catch (rejected: IphoneUsbException.CarkitRejected) {
            assertEquals(1, rejected.reason)
            val message = rejected.message ?: ""
            assertTrue(message, message.contains("InvalidPairRecord"))
        }
        // Only the Lockdown line was opened, and the failure path closed it before handoff.
        assertEquals(listOf(62078 to 5_000L), connections)
        assertEquals(listOf("lockdown"), closes)
    }
}

/** Serves one scripted plist reply per request and records its close order by [name]. */
private class ScriptedStream(
    private val name: String,
    private val closes: MutableList<String>,
    private val underlying: ScriptedStream? = null,
    private val responseFor: (String) -> String = { "" },
) : BlockingDuplexByteStream {
    val requests = mutableListOf<String>()
    private var response = ByteArray(0)

    override fun send(data: ByteArray) {
        val request = data.copyOfRange(4, data.size).toString(Charsets.UTF_8)
        requests += request
        response = plistFrame(responseFor(request))
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        val count = minOf(maxBytes, response.size)
        return response.copyOfRange(0, count).also { response = response.copyOfRange(count, response.size) }
    }

    override fun close() {
        closes += name
        underlying?.close()
    }
}
