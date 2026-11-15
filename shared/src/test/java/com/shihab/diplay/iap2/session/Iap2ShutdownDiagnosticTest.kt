package com.shihab.diplay.iap2.session

import com.shihab.diplay.iap2.trace.Iap2FrameFormatter
import com.shihab.diplay.iap2.trace.Iap2TraceDirection
import com.shihab.diplay.transport.BlockingDuplexByteStream
import com.shihab.diplay.transport.Iap2CsmChannel
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class Iap2ShutdownDiagnosticTest {
    @Test fun localShutdownChangesOnlyTheTraceAndPreservesTheOriginalTransportFailure() {
        for (locallyClosing in listOf(false, true)) {
            val failure = IOException("iAP2 link ended")
            val release = CountDownLatch(1)
            val closing = AtomicBoolean(false)
            val traces = CopyOnWriteArrayList<String>()
            val stream = object : BlockingDuplexByteStream {
                override fun send(data: ByteArray) = Unit
                override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
                    check(release.await(2, TimeUnit.SECONDS))
                    throw failure
                }
                override fun close() = Unit
            }
            val channel = Iap2CsmChannel.openTunnel(stream)
            val session = Iap2Session.wrap(channel, traceContext = "test", onTrace = traces::add,
                isShuttingDown = closing::get)
            try {
                closing.set(locallyClosing)
                release.countDown()
                assertSame(failure, assertThrows(IOException::class.java) { session.recv(2_000) })
                assertTrue(channel.isClosed)
                assertFalse("a terminal I/O failure must not become evidence of a local close", channel.locallyClosed)
                assertEquals(1, traces.size)
                assertEquals(if (locallyClosing) "IAP2 RX [test] CLOSED: local shutdown"
                    else "IAP2 RX [test] FAILED: iAP2 link ended", traces.single())
            } finally {
                release.countDown()
                session.close()
            }
        }
    }

    @Test fun programmerAndVmFailuresStayFailuresEvenWhenShutdownIsInProgress() {
        for (failure in listOf(IllegalStateException("invalid frame"), AssertionError("broken invariant"))) {
            val line = Iap2FrameFormatter.formatFailure(Iap2TraceDirection.RX, "test", null,
                failure, locallyClosing = true)
            assertTrue(line, line.contains("FAILED:"))
            assertFalse(line, line.contains("CLOSED:"))
        }
    }

    @Test fun explicitlyClosedSessionIsKnownLocalEvenWithoutAnOwnerCallback() {
        val closed = CountDownLatch(1)
        val traces = CopyOnWriteArrayList<String>()
        val stream = object : BlockingDuplexByteStream {
            override fun send(data: ByteArray) = Unit
            override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? =
                if (closed.await(timeoutMillis, TimeUnit.MILLISECONDS)) byteArrayOf() else null
            override fun close() { closed.countDown() }
        }
        val channel = Iap2CsmChannel.openTunnel(stream)
        val session = Iap2Session.wrap(channel, traceContext = "test", onTrace = traces::add)
        assertFalse(channel.locallyClosed)
        session.close()
        assertTrue(channel.locallyClosed)
        assertEquals("CSM channel is closed", assertThrows(IOException::class.java) { session.recv(10) }.message)
        assertTrue("reading after explicit close does not record a terminal transport failure", channel.locallyClosed)
        assertEquals("IAP2 RX [test] CLOSED: local shutdown", traces.last())
        assertFalse(traces.any { it.contains("FAILED:") })
    }
}
