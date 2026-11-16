package com.shihab.diplay.airplay

/** Called under the engine's existing ownership lock; no platform callbacks or native heap handles. */
internal class BufferedEngineCore {
    enum class Barrier { UNCHANGED, CLEAR, RETIREMENT_ONLY }
    data class Admission(val accepted: Boolean, val releaseBarrier: Boolean)
    data class BeginPlan(val accepted: Boolean, val releaseBarrier: Boolean, val previousEligibility: Long?)
    data class RetirePlan(val closeCandidate: Boolean, val createBarrier: Boolean)
    data class SetupPlan(val install: Boolean, val barrier: Barrier)

    private var state: Long
    val initialEligibility: Long
    init {
        NativeMedia.requireAvailable()
        val values = NativeMedia.bufferedEngineCreate()
        state = values[0]
        initialEligibility = values[1]
    }

    private fun step(operation: Int, eligibility: Long = initialEligibility, facts: Int): LongArray =
        NativeMedia.bufferedEngineStep(state, operation, eligibility, facts).also { state = it[0] }

    fun check(closed: Boolean, eligibility: Long, hasRetiring: Boolean, cleanupComplete: Boolean): Admission {
        val result = step(0, eligibility, (if (closed) 1 else 0) or (if (hasRetiring) 2 else 0) or (if (cleanupComplete) 4 else 0))
        return Admission(result[1] != 0L, result[2] == Barrier.CLEAR.ordinal.toLong())
    }

    fun begin(closed: Boolean, eligibility: Long, hasRetiring: Boolean, cleanupComplete: Boolean,
        hasPreviousOwner: Boolean, sameOwnerSession: Boolean): BeginPlan {
        val result = step(1, eligibility, (if (closed) 1 else 0) or (if (hasRetiring) 2 else 0) or
            (if (cleanupComplete) 4 else 0) or (if (hasPreviousOwner) 8 else 0) or (if (sameOwnerSession) 16 else 0))
        return BeginPlan(result[1] != 0L, result[2] == Barrier.CLEAR.ordinal.toLong(), result[3].takeIf { it != -1L })
    }

    fun retire(matchesTransitionSession: Boolean, hasCandidate: Boolean, hasRegisteredStream: Boolean): RetirePlan {
        val result = step(2, facts = (if (matchesTransitionSession) 1 else 0) or (if (hasCandidate) 2 else 0) or
            (if (hasRegisteredStream) 4 else 0))
        return RetirePlan(result[1] != 0L, result[2] == Barrier.RETIREMENT_ONLY.ordinal.toLong())
    }

    fun finishSetup(closeReturnedNormally: Boolean, hasRetiring: Boolean, cleanupComplete: Boolean,
        sameTransition: Boolean, closed: Boolean, eligibility: Long): SetupPlan {
        val result = step(3, eligibility, (if (closeReturnedNormally) 1 else 0) or (if (hasRetiring) 2 else 0) or
            (if (cleanupComplete) 4 else 0) or (if (sameTransition) 8 else 0) or (if (closed) 16 else 0))
        return SetupPlan(result[1] != 0L, Barrier.entries[result[2].toInt()])
    }

    fun finishRetire(sameTransition: Boolean, hasRetiring: Boolean, cleanupComplete: Boolean): Boolean {
        val result = step(4, facts = (if (sameTransition) 1 else 0) or (if (hasRetiring) 2 else 0) or (if (cleanupComplete) 4 else 0))
        return result[2] == Barrier.CLEAR.ordinal.toLong()
    }
}
