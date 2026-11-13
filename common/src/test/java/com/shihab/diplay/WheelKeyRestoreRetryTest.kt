package com.shihab.diplay

import org.junit.Assert.assertEquals
import org.junit.Test

/** Boot retries are bounded and stop once the native service binds. */
class WheelKeyRestoreRetryTest {
    @Test fun temporarilyUnboundServiceIsRetriedUntilItBinds() {
        val sleeps = mutableListOf<Long>()
        val results = mutableListOf<Pair<Int, Boolean>>()
        var bound = false
        var restores = 0
        WheelKeyService.restoreWithRetries(
            needsRestore = { !bound },
            restore = { (if (++restores < 3) false else true).also { if (restores == 3) bound = true } },
            sleep = { sleeps += it },
        ) { attempt, access -> results += attempt to access }

        assertEquals(listOf(1 to false, 2 to false, 3 to true), results)
        // The fourth wake finds the service bound and stops.
        assertEquals(listOf(4_000L, 10_000L, 10_000L, 10_000L), sleeps)
    }

    @Test fun failedNativeWritesHaveBoundedAttempts() {
        val results = mutableListOf<Boolean>()
        WheelKeyService.restoreWithRetries({ true }, { false }, {}, attempts = 6) { _, access -> results += access }
        assertEquals(6, results.size)
    }

    @Test fun aServiceThatBoundDuringTheGraceIsLeftAlone() {
        var restores = 0
        WheelKeyService.restoreWithRetries({ false }, { restores++; true }, {}) { _, _ -> }
        assertEquals(0, restores)
    }
}
