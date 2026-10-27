package com.shilapi.xcertplay.transport

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual pairing and plist framing, replacing only the USBMUX byte stream and key generation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LockdownPairingTest {
    @Test fun pendingThenSuccessKeepsOneConnectionAndOnePairIdentity() {
        val stream = PairingStream(listOf("PairingDialogResponsePending", "PairingDialogResponsePending", null))
        var connections = 0
        var records = 0
        var pending = 0
        val client = LockdownPairingClient({ connections++; stream }) { _, _, _, _ -> records++; record() }
        val result = client.pair("test", "host", "buid", 10_000, onTrustPending = { pending++ })
        assertEquals("host", result.pairRecord.hostId)
        assertEquals(1, connections)
        assertEquals(1, records)
        assertEquals(2, pending)
        assertEquals(1, stream.requests.count { "<string>SetValue</string>" in it })
        val pairs = stream.requests.filter { "<string>Pair</string>" in it }
        assertEquals(3, pairs.size)
        assertTrue(pairs.all { it == pairs.first() })
        assertEquals(1, stream.closes)
    }

    @Test fun pairReplyMayArriveAfterOrdinaryFiveSecondBudget() {
        val stream = PairingStream(listOf(null), pairDelayMillis = 5_100)
        client(stream).pair("test", "host", "buid", 10_000)
        assertEquals(1, stream.closes)
        assertTrue(stream.pairReadTimeouts.all { it <= 100 })
        assertTrue(stream.pairReadTimeouts.size > 40)
    }

    @Test fun noPairReplyExpiresAtOverallBudgetAndCloses() {
        val stream = PairingStream(listOf(null), pairDelayMillis = 5_000)
        expectSame(LockdownPairingException.TimedOut) {
            client(stream).pair("test", "host", "buid", 150)
        }
        assertEquals(1, stream.closes)
    }

    @Test fun cancellationDuringSilentReplyDoesNotWaitForFiveSeconds() {
        val stream = PairingStream(listOf(null), pairDelayMillis = 5_000)
        expectSame(LockdownPairingException.Cancelled) {
            client(stream).pair("test", "host", "buid", 10_000,
                isCancelled = { stream.pairReadTimeouts.size >= 2 })
        }
        assertEquals(2, stream.pairReadTimeouts.size)
        assertEquals(1, stream.closes)
    }

    @Test fun cancellationDuringPendingRetryClosesWithoutAnotherPair() {
        val stream = PairingStream(listOf("PairingDialogResponsePending", null))
        var cancelled = false
        expectSame(LockdownPairingException.Cancelled) {
            client(stream).pair("test", "host", "buid", 10_000,
                isCancelled = { cancelled }, onTrustPending = { cancelled = true })
        }
        assertEquals(1, stream.requests.count { "<string>Pair</string>" in it })
        assertEquals(1, stream.closes)
    }

    @Test fun deniedAndLockedAreDistinctAndNeverRetriedInsidePairing() {
        for ((code, expected) in listOf("UserDeniedPairing" to LockdownPairingException.UserDeniedPairing,
            "PasswordProtected" to LockdownPairingException.PasswordProtected)) {
            val stream = PairingStream(listOf(code))
            expectSame(expected) { client(stream).pair("test", "host", "buid", 10_000) }
            assertEquals(1, stream.requests.count { "<string>Pair</string>" in it })
            assertEquals(1, stream.closes)
        }
    }

    @Test fun brokenUsbDuringPairWaitIsNotTrustTimeout() {
        val failure = IphoneUsbException.DeviceUnavailable("Android returned no USBMUX read request")
        val stream = PairingStream(listOf(null), pairFailure = failure)
        expectSame(failure) { client(stream).pair("test", "host", "buid", 10_000) }
        assertEquals(1, stream.closes)
    }

    @Test fun transportTimeoutDuringPairIsNotReclassifiedAsHumanTimeout() {
        val failure = IphoneUsbException.TimedOut("transport timeout")
        val stream = PairingStream(listOf(null), pairFailure = failure)
        expectSame(failure) { client(stream).pair("test", "host", "buid", 10_000) }
        assertEquals(1, stream.closes)
    }

    @Test fun explicitDenialAndLockDuringSetupKeepTheirTypedOutcomes() {
        for (step in listOf("SetValue", "DevicePublicKey", "WiFiAddress")) {
            for ((code, expected) in listOf("UserDeniedPairing" to LockdownPairingException.UserDeniedPairing,
                "PasswordProtected" to LockdownPairingException.PasswordProtected)) {
                val stream = PairingStream(emptyList(), setupError = step to code)
                expectSame(expected) { client(stream).pair("test", "host", "buid", 10_000) }
                assertEquals(0, stream.requests.count { "<string>Pair</string>" in it })
                assertEquals(1, stream.closes)
            }
        }
    }

    @Test fun otherSetupErrorsRemainRemoteErrors() {
        val stream = PairingStream(emptyList(), setupError = "SetValue" to "UnknownError")
        try {
            client(stream).pair("test", "host", "buid", 10_000)
            fail("Expected remote error")
        } catch (error: LockdownPairingException.RemoteError) { assertEquals("UnknownError", error.code) }
        assertEquals(1, stream.closes)
    }

    private fun client(stream: PairingStream) = LockdownPairingClient({ stream }) { _, _, _, _ -> record() }

    private fun record() = LockdownPairRecord.restore("host", "buid", "00:11:22:33:44:55",
        byteArrayOf(1), byteArrayOf(2), byteArrayOf(3), byteArrayOf(4), byteArrayOf(5), byteArrayOf(6))

    private fun expectSame(expected: Throwable, block: () -> Unit) {
        try { block(); fail("Expected $expected") } catch (actual: Throwable) { assertSame(expected, actual) }
    }
}

private class PairingStream(
    private val pairErrors: List<String?>,
    private val pairDelayMillis: Long = 0,
    private val pairFailure: Throwable? = null,
    private val setupError: Pair<String, String>? = null,
) : BlockingDuplexByteStream {
    val requests = mutableListOf<String>()
    val pairReadTimeouts = mutableListOf<Long>()
    var closes = 0
    private var response = ByteArray(0)
    private var pair = false
    private var pairIndex = 0
    private var readyAt = 0L

    override fun send(data: ByteArray) {
        check(closes == 0)
        val request = data.copyOfRange(4, data.size).toString(Charsets.UTF_8)
        requests += request
        pair = "<string>Pair</string>" in request
        val content = when {
            setupError != null && "<string>${setupError.first}</string>" in request ->
                "<key>Error</key><string>${setupError.second}</string>"
            pair -> pairErrors[pairIndex++]?.let { "<key>Error</key><string>$it</string>" }.orEmpty()
            "<string>DevicePublicKey</string>" in request -> "<key>Value</key><data>AQ==</data>"
            "<string>WiFiAddress</string>" in request -> "<key>Value</key><string>00:11:22:33:44:55</string>"
            else -> ""
        }
        response = plistFrame(content)
        if (pair) readyAt = System.nanoTime() + pairDelayMillis * 1_000_000
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        if (pair) {
            pairFailure?.let { throw it }
            pairReadTimeouts += timeoutMillis
            val remaining = readyAt - System.nanoTime()
            if (remaining > 0) {
                Thread.sleep(minOf(timeoutMillis, (remaining + 999_999) / 1_000_000))
                if (System.nanoTime() < readyAt) return null
            }
        }
        val count = minOf(maxBytes, response.size)
        return response.copyOfRange(0, count).also { response = response.copyOfRange(count, response.size) }
    }

    override fun close() { closes++ }
}

internal fun plistFrame(content: String): ByteArray {
    val xml = "<plist version=\"1.0\"><dict>$content</dict></plist>".toByteArray()
    return ByteBuffer.allocate(4 + xml.size).putInt(xml.size).put(xml).array()
}
