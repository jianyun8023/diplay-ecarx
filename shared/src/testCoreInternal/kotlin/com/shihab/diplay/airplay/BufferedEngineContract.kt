package com.shihab.diplay.airplay

import java.util.Random
import org.junit.Assert.*

/** The old engine's resource-transition fields, without any native phase/token encoding. */
private class KotlinBufferedEngine {
    class Transition(val hasCandidate: Boolean, var cancelled: Boolean = false)
    var transition: Transition? = null
    fun check(closed: Boolean, retired: Boolean, hasRetiring: Boolean, cleanup: Boolean): BufferedEngineCore.Admission {
        val release = transition?.hasCandidate == false && hasRetiring && cleanup
        if (release) transition = null
        return BufferedEngineCore.Admission(!closed && !retired && transition == null, release)
    }
    fun begin(closed: Boolean, retired: Boolean, hasRetiring: Boolean, cleanup: Boolean,
        previousOwner: Boolean, sameOwner: Boolean): BufferedEngineCore.BeginPlan {
        val admission = check(closed, retired, hasRetiring, cleanup)
        if (admission.accepted) transition = Transition(true)
        return BufferedEngineCore.BeginPlan(admission.accepted, admission.releaseBarrier,
            if (admission.accepted && previousOwner && !sameOwner) 1L else null)
    }
    fun retire(matchesSession: Boolean, hasCandidate: Boolean, registered: Boolean): BufferedEngineCore.RetirePlan {
        var closeCandidate = false
        transition?.takeIf { matchesSession }?.let { it.cancelled = true; closeCandidate = hasCandidate }
        val create = registered && transition == null
        if (create) transition = Transition(false)
        return BufferedEngineCore.RetirePlan(closeCandidate, create)
    }
    fun finishSetup(normal: Boolean, hasRetiring: Boolean, cleanup: Boolean, same: Boolean,
        closed: Boolean, retired: Boolean): BufferedEngineCore.SetupPlan {
        val released = normal && (!hasRetiring || cleanup)
        val install = released && same && transition?.cancelled == false && !closed && !retired
        val barrier = if (!same) BufferedEngineCore.Barrier.UNCHANGED else if (released) {
            transition = null
            BufferedEngineCore.Barrier.CLEAR
        } else {
            transition = Transition(false)
            BufferedEngineCore.Barrier.RETIREMENT_ONLY
        }
        return BufferedEngineCore.SetupPlan(install, barrier)
    }
    fun finishRetire(same: Boolean, hasRetiring: Boolean, cleanup: Boolean): Boolean {
        val release = same && hasRetiring && cleanup
        if (release) transition = null
        return release
    }
}

internal object BufferedEngineContract {
    fun verifyTransitions() {
        val random = Random(20261117)
        repeat(40) {
            val core = BufferedEngineCore()
            val old = KotlinBufferedEngine()
            // Eligibility is session-scoped and lasts for this engine; it is never restored on cancel.
            val actualEligibility = mutableMapOf<Int, Long>()
            val retiredSessions = mutableSetOf<Int>()
            repeat(250) {
                val session = random.nextInt(3)
                val previous = random.nextInt(3)
                val eligibility = actualEligibility[session] ?: core.initialEligibility
                val retired = session in retiredSessions
                val closed = random.nextBoolean()
                val hasRetiring = random.nextBoolean()
                val cleanup = random.nextBoolean()
                when (random.nextInt(5)) {
                    0 -> assertEquals(old.check(closed, retired, hasRetiring, cleanup),
                        core.check(closed, eligibility, hasRetiring, cleanup))
                    1 -> {
                        val hasOwner = random.nextBoolean()
                        val expected = old.begin(closed, retired, hasRetiring, cleanup, hasOwner, previous == session)
                        val actual = core.begin(closed, eligibility, hasRetiring, cleanup, hasOwner, previous == session)
                        assertEquals(expected.accepted, actual.accepted)
                        assertEquals(expected.releaseBarrier, actual.releaseBarrier)
                        assertEquals(expected.previousEligibility != null, actual.previousEligibility != null)
                        if (expected.previousEligibility != null) retiredSessions.add(previous)
                        actual.previousEligibility?.let { actualEligibility[previous] = it }
                    }
                    2 -> {
                        val same = old.transition != null && random.nextBoolean()
                        val candidate = old.transition?.hasCandidate == true
                        val registered = old.transition == null && random.nextBoolean()
                        assertEquals(old.retire(same, candidate, registered), core.retire(same, candidate, registered))
                    }
                    3 -> {
                        val same = old.transition?.hasCandidate == true && random.nextBoolean()
                        val normal = random.nextBoolean()
                        assertEquals(old.finishSetup(normal, hasRetiring, cleanup, same, closed, retired),
                            core.finishSetup(normal, hasRetiring, cleanup, same, closed, eligibility))
                    }
                    else -> {
                        val same = old.transition?.hasCandidate == false && random.nextBoolean()
                        assertEquals(old.finishRetire(same, hasRetiring, cleanup), core.finishRetire(same, hasRetiring, cleanup))
                    }
                }
                assertEquals(old.check(false, false, false, false), core.check(false, core.initialEligibility, false, false))
            }
        }
    }

    fun verifyCompletionBoundaries() {
        for (normal in listOf(false, true)) for (hasRetiring in listOf(false, true))
            for (cleanup in listOf(false, true)) for (cancel in listOf(false, true)) {
                val old = KotlinBufferedEngine()
                val core = BufferedEngineCore()
                old.begin(false, false, false, false, true, false)
                val oldEligibility = core.begin(false, core.initialEligibility, false, false, true, false).previousEligibility!!
                if (cancel) {
                    old.retire(true, true, false)
                    assertEquals(BufferedEngineCore.RetirePlan(true, false), core.retire(true, true, false))
                }
                assertEquals(old.finishSetup(normal, hasRetiring, cleanup, true, false, false),
                    core.finishSetup(normal, hasRetiring, cleanup, true, false, core.initialEligibility))
                // A declined retired-session request still frees a completed retirement-only barrier.
                assertEquals(old.check(false, true, hasRetiring, cleanup), core.check(false, oldEligibility, hasRetiring, cleanup))
                assertEquals(old.check(false, false, hasRetiring, cleanup), core.check(false, core.initialEligibility, hasRetiring, cleanup))
            }
    }

    fun verifyEngineIsolation() {
        val first = BufferedEngineCore()
        val second = BufferedEngineCore()
        val retiredInFirst = first.begin(false, first.initialEligibility, false, false, true, false).previousEligibility!!
        first.finishSetup(true, false, false, true, false, first.initialEligibility)
        assertFalse(first.check(false, retiredInFirst, false, false).accepted)
        assertTrue(second.check(false, second.initialEligibility, false, false).accepted)
        assertTrue(second.begin(false, second.initialEligibility, false, false, false, false).accepted)
        // A stale finish and a completed previous stream cannot clear another installing transition.
        assertEquals(BufferedEngineCore.Barrier.UNCHANGED,
            second.finishSetup(true, true, true, false, false, second.initialEligibility).barrier)
        assertFalse(second.check(false, second.initialEligibility, true, true).accepted)
    }

    fun verifyInvalidValues() {
        NativeMedia.requireAvailable()
        val initial = NativeMedia.bufferedEngineCreate()
        for (state in listOf(Long.MIN_VALUE, -1L, 4L, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { NativeMedia.bufferedEngineStep(state, 0, initial[1], 0) }
        }
        assertThrows(IllegalArgumentException::class.java) { NativeMedia.bufferedEngineStep(initial[0], 0, 2, 0) }
        assertThrows(IllegalArgumentException::class.java) { NativeMedia.bufferedEngineStep(initial[0], 5, initial[1], 0) }
        assertThrows(IllegalArgumentException::class.java) { NativeMedia.bufferedEngineStep(initial[0], 0, initial[1], 8) }
    }
}
