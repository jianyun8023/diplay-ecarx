package com.shihab.diplay.network

import org.junit.Assert.assertEquals
import org.junit.Test

class CarHotspotReturnTest {
    private var now = 0L
    private val logs = mutableListOf<String>()

    private fun await(states: List<Boolean?>, current: () -> Boolean = { true }, timeout: Long = 10_000L): CarHotspotReturn.Outcome {
        val queue = ArrayDeque(states)
        return CarHotspotReturn.await(
            state = { if (queue.size > 1) queue.removeFirst() else queue.first() },
            isCurrent = current, timeoutMillis = timeout,
            nowMillis = { now }, pause = { now += it }, log = { logs += it },
        )
    }

    @Test fun waitsForTheCarToTurnItsHotspotBackOn() {
        assertEquals(CarHotspotReturn.Outcome.ON, await(listOf(false, false, false, true)))
        assertEquals(listOf("car hotspot still off; waiting for the car to turn it back on budgetMs=10000",
            "car hotspot back on waitMs=1500"), logs)
    }

    @Test fun anOnOrUnreadableHotspotDoesNotWait() {
        assertEquals(CarHotspotReturn.Outcome.ON, await(listOf(true)))
        assertEquals(CarHotspotReturn.Outcome.UNKNOWN, await(listOf(null)))
        assertEquals(0L, now)
        assertEquals(emptyList<String>(), logs)
    }

    @Test fun givesUpAtTheBudgetAndStopsWhenCancelled() {
        assertEquals(CarHotspotReturn.Outcome.TIMED_OUT, await(listOf(false), timeout = 2_000L))
        assertEquals(2_000L, now)
        var checks = 0
        assertEquals(CarHotspotReturn.Outcome.CANCELLED, await(listOf(false), current = { ++checks < 3 }))
    }

    @Test fun theOutageOutlivesAControllerAndEndsWithASessionOrTheBudget() {
        CarRadioOutage.clear()
        assertEquals(false, CarRadioOutage.active(nowMillis = 1_000L))
        CarRadioOutage.mark(nowMillis = 1_000L)
        // The host's rebuilt controller 15 s later still sees it.
        assertEquals(true, CarRadioOutage.active(nowMillis = 16_000L))
        assertEquals(false, CarRadioOutage.active(nowMillis = 1_000L + CarRadioOutage.BUDGET_MILLIS))
        CarRadioOutage.clear()
        assertEquals(false, CarRadioOutage.active(nowMillis = 16_000L))
    }
}
