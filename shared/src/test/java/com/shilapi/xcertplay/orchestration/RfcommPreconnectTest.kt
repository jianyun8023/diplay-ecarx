package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RfcommPreconnectTest {
    private fun preconnect(
        target: String = TARGET,
        isCurrent: () -> Boolean = { true },
        createSocket: () -> String = { "socket" },
        connectSocket: (String) -> Long = { 259L },
        closeSocket: (String) -> Unit = {},
        log: (String) -> Unit = {},
    ) = RfcommPreconnect(
        targetAddress = target,
        isCurrent = isCurrent,
        createSocket = createSocket,
        connectSocket = connectSocket,
        closeSocket = closeSocket,
        log = log,
    )

    @Test fun aConnectThatFinishesAfterTheWaitTimedOutIsClosedNotLeftOpen() {
        val release = CountDownLatch(1)
        val closed = mutableListOf<String>()
        val preconnect = preconnect(
            connectSocket = { release.await(5, TimeUnit.SECONDS); 259L },
            closeSocket = { synchronized(closed) { closed += it } },
        )
        val worker = preconnect.start()
        val timedOut = runCatching { preconnect.consume(TARGET, 10) }.exceptionOrNull()
        assertTrue(timedOut is IOException)
        release.countDown()
        worker.join(5_000)
        assertEquals(listOf("socket"), synchronized(closed) { closed.toList() })
        // The retry connects serially; nothing is handed out twice.
        assertSame(RfcommPreconnect.Outcome.Unavailable, preconnect.consume(TARGET, AWAIT))
    }

    @Test fun abandoningAnAdoptableSocketClosesIt() {
        val closed = mutableListOf<String>()
        val preconnect = preconnect(closeSocket = { closed += it })
        preconnect.run()
        preconnect.abandon()
        assertEquals(listOf("socket"), closed)
        assertSame(RfcommPreconnect.Outcome.Unavailable, preconnect.consume(TARGET, AWAIT))
    }

    @Test fun connectedSocketIsAdoptedWithItsConnectDuration() {
        val preconnect = preconnect()
        preconnect.run()

        val outcome = preconnect.consume(TARGET.lowercase(), AWAIT)

        val adopted = outcome as RfcommPreconnect.Outcome.Adopted
        assertEquals("socket", adopted.socket)
        assertEquals(259L, adopted.connectMillis)
    }

    @Test fun connectFailureIsSurfacedUnchanged() {
        val failure = IOException("RFCOMM connect refused")
        val preconnect = preconnect(connectSocket = { throw failure })
        preconnect.run()

        val outcome = preconnect.consume(TARGET, AWAIT)

        val failed = outcome as RfcommPreconnect.Outcome.Failed
        assertSame(failure, failed.error)
    }

    @Test fun socketCreationFailureIsSurfacedUnchanged() {
        val failure = SecurityException("BLUETOOTH_CONNECT revoked")
        val preconnect = preconnect(createSocket = { throw failure })
        preconnect.run()

        val outcome = preconnect.consume(TARGET, AWAIT)

        val failed = outcome as RfcommPreconnect.Outcome.Failed
        assertSame(failure, failed.error)
    }

    @Test fun outcomeIsConsumedExactlyOnce() {
        val preconnect = preconnect()
        preconnect.run()

        assertTrue(preconnect.consume(TARGET, AWAIT) is RfcommPreconnect.Outcome.Adopted)

        // Backoff retries and fallback phones must connect serially with a fresh socket.
        assertEquals(RfcommPreconnect.Outcome.Unavailable, preconnect.consume(TARGET, AWAIT))
    }

    @Test fun foreignTargetNeverAdoptsThePreconnect() {
        val preconnect = preconnect(target = "AA:00:00:00:00:01")
        preconnect.run()

        val outcome = preconnect.consume("AA:00:00:00:00:02", AWAIT)

        assertEquals(RfcommPreconnect.Outcome.Unavailable, outcome)
        assertEquals(
            RfcommPreconnect.Outcome.Unavailable,
            preconnect.consume("AA:00:00:00:00:01", AWAIT),
        )
    }

    @Test fun staleRunLeavesTheOutcomeUnavailable() {
        val preconnect = preconnect(isCurrent = { false })
        preconnect.run()

        assertEquals(RfcommPreconnect.Outcome.Unavailable, preconnect.consume(TARGET, AWAIT))
    }

    @Test fun consumeWaitsForAnInFlightConnect() {
        val connectStarted = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val preconnect = preconnect(connectSocket = {
            connectStarted.countDown()
            proceed.await()
            120L
        })
        val worker = preconnect.start()
        assertTrue(connectStarted.await(AWAIT, TimeUnit.MILLISECONDS))
        Thread { Thread.sleep(50); proceed.countDown() }.apply {
            isDaemon = true
            start()
        }

        val outcome = preconnect.consume(TARGET, TimeUnit.SECONDS.toMillis(5))

        assertTrue(outcome is RfcommPreconnect.Outcome.Adopted)
        assertEquals(120L, (outcome as RfcommPreconnect.Outcome.Adopted).connectMillis)
        worker.join(TimeUnit.SECONDS.toMillis(5))
    }

    @Test fun wedgedPreconnectTimesOutInsteadOfOpeningASecondSocket() {
        val proceed = CountDownLatch(1)
        val preconnect = preconnect(connectSocket = {
            proceed.await()
            1L
        })
        val worker = preconnect.start()

        val failure = try {
            preconnect.consume(TARGET, 100L)
            error("expected the adoption wait to time out")
        } catch (error: IOException) {
            error
        }
        assertTrue(failure.message!!.contains("Timed out"))

        // The wedged connect must not be adopted later, and it is released for teardown.
        assertEquals(RfcommPreconnect.Outcome.Unavailable, preconnect.consume(TARGET, AWAIT))
        proceed.countDown()
        worker.join(TimeUnit.SECONDS.toMillis(5))
    }

    private companion object {
        const val TARGET = "AA:BB:CC:DD:EE:FF"
        const val AWAIT = 5_000L
    }
}
