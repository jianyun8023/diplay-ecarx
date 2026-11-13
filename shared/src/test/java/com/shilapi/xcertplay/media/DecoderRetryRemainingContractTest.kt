package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

/** Existing decoder policy executes platform callbacks in this order and preserves their failure. */
class DecoderRetryRemainingContractTest {
    @Test fun retryNotificationPrecedesWaitAndTheNextPlatformCreate() {
        val events = mutableListOf<String>()
        val first = IllegalStateException("busy")
        var creates = 0
        val result = createWithRetries(listOf(80L), sleep = { events += "sleep:$it" },
            onRetry = { attempt, error -> assertSame(first, error); events += "retry:$attempt" }) {
            events += "create:${++creates}"
            if (creates == 1) throw first
            "decoder"
        }
        assertEquals("decoder", result)
        assertEquals(listOf("create:1", "retry:1", "sleep:80", "create:2"), events)
    }

    @Test fun callbackOrWaitFailuresAbortWithoutCreatingAnotherDecoder() {
        val callbackFailure = IllegalArgumentException("report")
        var calls = 0
        val callbackError = assertThrows(IllegalArgumentException::class.java) {
            createWithRetries(listOf(80L), sleep = { fail("No wait after callback failure") },
                onRetry = { _, _ -> throw callbackFailure }) { calls++; error("busy") }
        }
        assertSame(callbackFailure, callbackError)
        assertEquals(1, calls)
        val interrupted = InterruptedException("cancelled")
        calls = 0
        val waitError = assertThrows(InterruptedException::class.java) {
            createWithRetries(listOf(80L), sleep = { throw interrupted }) { calls++; error("busy") }
        }
        assertSame(interrupted, waitError)
        assertEquals(1, calls)
    }

    @Test fun errorsFromPlatformCreationNeverEnterTheExceptionRetryLoop() {
        val failure = LinkageError("unavailable")
        var calls = 0
        assertSame(failure, assertThrows(LinkageError::class.java) {
            createWithRetries(listOf(80L), sleep = { fail("No wait after Error") },
                onRetry = { _, _ -> fail("No retry after Error") }) { calls++; throw failure }
        })
        assertEquals(1, calls)
    }
}
