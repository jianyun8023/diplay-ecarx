package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LockdownPlistWaitTest {
    private val request = LockdownPlistValue.Dictionary(emptyMap())

    @Test fun idleGapsInsideHeaderAndBodyPreserveBytesAndNextFrame() {
        val first = plistFrame("<key>Result</key><string>Success</string>")
        val second = plistFrame("<key>Result</key><string>Next</string>")
        val stream = ScriptedPlistStream(mutableListOf(
            first.copyOfRange(0, 2), null, first.copyOfRange(2, 4),
            first.copyOfRange(4, 19), null, first.copyOfRange(19, first.size), second,
        ))
        LockdownPlistChannel(stream).use { channel ->
            val result = channel.requestAwaitingResponse(request, 1_000) {}
            assertEquals(LockdownPlistValue.Text("Success"), result.entries["Result"])
            assertEquals(LockdownPlistValue.Text("Next"), channel.receive().entries["Result"])
        }
        assertEquals(1, stream.sends)
        assertEquals(1, stream.closes)
    }

    @Test fun cancellationAfterPartialHeaderTerminatesWithoutResending() {
        val frame = plistFrame("")
        val stream = ScriptedPlistStream(mutableListOf(frame.copyOfRange(0, 2), null))
        try {
            LockdownPlistChannel(stream).use { channel ->
                channel.requestAwaitingResponse(request, 1_000) {
                    if (stream.reads >= 2) throw LockdownPairingException.Cancelled
                }
            }
            fail("Expected cancellation")
        } catch (error: LockdownPairingException) { assertSame(LockdownPairingException.Cancelled, error) }
        assertEquals(1, stream.sends)
        assertEquals(1, stream.closes)
    }

    @Test fun eofDuringHumanWaitRemainsProtocolFailure() {
        val stream = ScriptedPlistStream(mutableListOf(byteArrayOf()))
        try {
            LockdownPlistChannel(stream).use { it.requestAwaitingResponse(request, 1_000) {} }
            fail("Expected EOF failure")
        } catch (_: IphoneUsbException.Protocol) { /* not pending or a trust timeout */ }
        assertEquals(1, stream.closes)
    }

    @Test fun ordinaryRequestsStillTimeOutOnIdleWithoutPolling() {
        val stream = ScriptedPlistStream(mutableListOf(null))
        try {
            LockdownPlistChannel(stream).use { it.request(request, 1_000) }
            fail("Expected ordinary read timeout")
        } catch (_: IphoneUsbException.TimedOut) { /* unchanged ordinary request contract */ }
        assertEquals(1, stream.reads)
        assertEquals(1, stream.closes)
    }
}

private class ScriptedPlistStream(private val chunks: MutableList<ByteArray?>) : BlockingDuplexByteStream {
    var reads = 0
    var sends = 0
    var closes = 0
    override fun send(data: ByteArray) { sends++ }
    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        reads++
        check(chunks.isNotEmpty()) { "Unexpected additional read" }
        val bytes = chunks.removeAt(0) ?: return null
        if (bytes.size <= maxBytes) return bytes
        chunks.add(0, bytes.copyOfRange(maxBytes, bytes.size))
        return bytes.copyOf(maxBytes)
    }
    override fun close() { closes++ }
}
