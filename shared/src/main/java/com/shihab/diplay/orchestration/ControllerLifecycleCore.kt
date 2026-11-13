package com.shihab.diplay.orchestration

import com.shihab.diplay.airplay.NativeMedia

/** One opaque value, including lossless UTF-16 USB keys; no Android object or callback lives here. */
internal class ControllerLifecycleCore(wireless: Boolean, location: Boolean) {
    private val owner = run {
        requireNative()
        arrayOf(NativeCarPlayController.create(wireless, location))
    }
    val closed: Boolean get() = query(0) != 0L
    val phase: Int get() = query(1).toInt()
    val diagnosticRun: Int get() = query(2).toInt()
    val wirelessGeneration: Int get() = query(3).toInt()
    val availabilityGeneration: Int get() = query(4).toInt()
    val permissionGeneration: Int get() = query(5).toInt()
    val reenumerationAttempts: Int get() = query(6).toInt()
    val wiredSessionActive: Boolean get() = query(7) != 0L
    @get:Synchronized val wiredStartSentAtNanos: Long?
        get() = if (NativeCarPlayController.query(owner, 8) == 0L) null else NativeCarPlayController.query(owner, 9)

    @Synchronized private fun query(field: Int): Long = NativeCarPlayController.query(owner, field)
    @Synchronized private fun event(event: Int, value: Long = 0, present: Boolean = false): Long =
        NativeCarPlayController.event(owner, event, value, present)
    @Synchronized private fun decide(decision: Int, value: Long = 0, other: Long = 0, facts: Long = 0): Long =
        NativeCarPlayController.decide(owner, decision, value, other, facts)

    fun beginMfi(): Boolean = event(1) != 0L
    fun beginIphone(): Boolean = event(2) != 0L
    fun beginWireless(): Int = event(3).toInt()
    fun beginClose(): Boolean = event(4) != 0L
    fun beginReenumeration() { event(5) }
    @Synchronized fun beginDataPaths(): Int? = if (NativeCarPlayController.event(owner, 6, 0, false) == 0L) null
        else NativeCarPlayController.query(owner, 2).toInt()
    fun beginWiredControl(run: Int): Boolean = event(7, run.toLong()) != 0L
    fun resetIphoneDiscovery() { event(8) }
    fun resetPermissionClaim() { event(9) }
    fun claimPermission(): Boolean = event(10) != 0L
    fun permissionDenied() { event(11) }
    fun invalidateAvailability(): Int = event(12).toInt()
    fun invalidatePermission(): Int = event(13).toInt()
    fun invalidateWireless(): Int = event(14).toInt()
    fun awaitRadioReturn() { event(15) }
    fun consumeRadioReturn(): Boolean = event(16) != 0L
    fun reportPrerequisiteFailure(): Boolean = event(17) != 0L
    fun sessionActive(active: Boolean) { event(18, present = active) }
    fun startSent(atNanos: Long?) { event(19, atNanos ?: 0, atNanos != null) }
    fun beginVpnBind(): Boolean = event(20) != 0L
    fun vpnBindFailed() { event(21) }
    fun beginVpnUnbind(): Boolean = event(22) != 0L
    fun iphoneNotListed(nowMillis: Long): Boolean = event(23, nowMillis) != 0L

    fun wiredCurrent(run: Int): Boolean = decide(0, run.toLong()) != 0L
    fun canStartWireless(expected: Int?, previousFailed: Boolean): Boolean = decide(1, (expected ?: 0).toLong(),
        facts = (if (expected != null) 1L else 0L) or (if (expected != null && previousFailed) 2L else 0L)) != 0L
    fun canRestartWireless(same: Boolean, running: Boolean): Boolean = decide(2, facts =
        (if (same) 1L else 0L) or (if (running) 2L else 0L)) != 0L
    fun permissionPollCurrent(generation: Int, mfi: Boolean): Boolean = decide(3, generation.toLong(), facts = if (mfi) 1 else 0) != 0L
    fun availabilityPollCurrent(phase: Int, generation: Int): Boolean = decide(4, phase.toLong(), generation.toLong()) != 0L
    fun reenumerationCurrent(attempt: Int, generation: Int): Boolean = decide(5, attempt.toLong(), generation.toLong()) != 0L
    fun platformGrantCurrent(phase: Int, generation: Int): Boolean = decide(6, phase.toLong(), generation.toLong()) != 0L
    fun failureCurrent(generation: Int?): Boolean = decide(7, (generation ?: 0).toLong(), facts = if (generation != null) 1 else 0) != 0L
    fun statusCurrent(generation: Int?, current: Boolean, failed: Boolean, liveRun: Boolean, same: Boolean): Boolean =
        decide(8, (generation ?: 0).toLong(), facts = (if (current) 1L else 0L) or (if (generation != null) 2L else 0L) or
            (if (failed) 4L else 0L) or (if (liveRun) 8L else 0L) or (if (same) 16L else 0L)) != 0L
    fun controlLoopTimeout(): Long = decide(9)

    @Synchronized fun grantPending(key: String): Boolean = NativeCarPlayController.key(owner, 0, key)
    @Synchronized fun claimPlatformGrant(key: String): Boolean = NativeCarPlayController.key(owner, 1, key)
    @Synchronized fun platformGrantStarted(key: String) { NativeCarPlayController.key(owner, 2, key) }
    @Synchronized fun platformGrantFinished(key: String) { NativeCarPlayController.key(owner, 3, key) }
    @Synchronized fun teardownPlan(): IntArray = NativeCarPlayController.teardownPlan(owner)

    companion object {
        fun requireNative() { check(NativeMedia.available) { "Native controller lifecycle core is unavailable" } }
        fun wirelessClosePlan(current: Boolean): IntArray { requireNative(); return NativeCarPlayController.wirelessClosePlan(current) }
        fun handoff(requested: Boolean, tunnel: Boolean, session: Boolean): Boolean {
            requireNative(); return NativeCarPlayController.handoff(requested, tunnel, session)
        }
        fun expectedControlClose(stage: Int, facts: Int): Boolean {
            requireNative(); return NativeCarPlayController.expectedControlClose(stage, facts)
        }
        fun configuration(found: Boolean, mode: Boolean, started: Boolean, tried: Int, maximum: Int): Int {
            requireNative(); return NativeCarPlayController.configuration(found, mode, started, tried, maximum)
        }
        fun configurationFallback(found: Boolean, mode: Boolean, started: Boolean): Boolean {
            requireNative(); return NativeCarPlayController.configurationFallback(found, mode, started)
        }
        fun timingPolicy(field: Int): Long { requireNative(); return NativeCarPlayController.timingPolicy(field) }
        fun permissionDeadline(now: Long): Long = NativeCarPlayController.permissionDeadline(now)
        fun permissionExpired(deadline: Long, now: Long): Boolean = NativeCarPlayController.permissionExpired(deadline, now)
    }
}

/** The service's attachment generation is independent of the controller's run generation. */
internal class VpnAttachmentCore {
    private val state = run { ControllerLifecycleCore.requireNative(); NativeCarPlayController.vpnCreate() }
    @get:Synchronized val active: Boolean get() = NativeCarPlayController.vpnQuery(state, 0) != 0
    @get:Synchronized val generation: Int get() = NativeCarPlayController.vpnQuery(state, 1)
    @Synchronized fun prepareAttach(current: Boolean): Int = NativeCarPlayController.vpnEvent(state, 0, 0, current)
    @Synchronized fun beginAttach(): Int = NativeCarPlayController.vpnEvent(state, 1, 0, true)
    @Synchronized fun release(): Int = NativeCarPlayController.vpnEvent(state, 2, 0, true)
    @Synchronized fun current(generation: Int, ownerCurrent: Boolean = true): Boolean = NativeCarPlayController.vpnEvent(state, 3, generation, ownerCurrent) != 0
    @Synchronized fun matches(generation: Int): Boolean = NativeCarPlayController.vpnEvent(state, 4, generation, true) != 0
}

internal object NativeCarPlayController {
    @JvmStatic external fun create(wireless: Boolean, location: Boolean): LongArray
    @JvmStatic external fun query(owner: Array<LongArray>, field: Int): Long
    @JvmStatic external fun event(owner: Array<LongArray>, event: Int, value: Long, present: Boolean): Long
    @JvmStatic external fun decide(owner: Array<LongArray>, decision: Int, value: Long, other: Long, facts: Long): Long
    @JvmStatic external fun key(owner: Array<LongArray>, event: Int, key: String): Boolean
    @JvmStatic external fun teardownPlan(owner: Array<LongArray>): IntArray
    @JvmStatic external fun wirelessClosePlan(current: Boolean): IntArray
    @JvmStatic external fun handoff(requested: Boolean, tunnel: Boolean, session: Boolean): Boolean
    @JvmStatic external fun expectedControlClose(stage: Int, facts: Int): Boolean
    @JvmStatic external fun configuration(found: Boolean, mode: Boolean, started: Boolean, tried: Int, maximum: Int): Int
    @JvmStatic external fun configurationFallback(found: Boolean, mode: Boolean, started: Boolean): Boolean
    @JvmStatic external fun vpnCreate(): LongArray
    @JvmStatic external fun vpnQuery(state: LongArray, field: Int): Int
    @JvmStatic external fun vpnEvent(state: LongArray, event: Int, generation: Int, current: Boolean): Int
    @JvmStatic external fun proofCreate(): LongArray
    @JvmStatic external fun proofEvent(state: LongArray, event: Int, generation: Int, sameSession: Boolean): Boolean
    @JvmStatic external fun wirelessDecision(state: Long, operation: Int, facts: Int): Int
    @JvmStatic external fun timingPolicy(field: Int): Long
    @JvmStatic external fun permissionDeadline(now: Long): Long
    @JvmStatic external fun permissionExpired(deadline: Long, now: Long): Boolean
}
