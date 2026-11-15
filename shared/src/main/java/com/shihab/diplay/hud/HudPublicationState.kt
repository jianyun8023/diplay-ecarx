package com.shihab.diplay.hud

import com.shihab.diplay.airplay.NativeMedia

/** Process-owned ECARX policy. SDK calls commit facts under EcarxHudBridge's existing monitor. */
internal class HudPublicationState : java.io.Closeable {
    init { NativeMedia.requireAvailable() }
    private var handle = NativeHudPublication.create().also { check(it != 0L) }
    private inline fun <T> useOwner(action: (Long) -> T): T = synchronized(this) {
        check(handle != 0L) { "HUD publication state is closed" }
        action(handle)
    }
    private fun bool(value: Boolean): Long = if (value) 1L else 0L
    private fun step(event: Int, a: Long = 0, b: Long = 0, c: Long = 0): Long = useOwner { NativeHudPublication.step(it, event, a, b, c) }
    @Synchronized override fun close() { if (handle != 0L) { NativeHudPublication.destroy(handle); handle = 0 } }
    fun reset() { step(0) }
    var presentationWanted: Boolean
        get() = useOwner { NativeHudPublication.get(it, 0) } != 0L
        set(value) { useOwner { NativeHudPublication.set(it, 0, bool(value)) } }
    var releaseStalePresentation: Boolean
        get() = useOwner { NativeHudPublication.get(it, 1) } != 0L
        set(value) { useOwner { NativeHudPublication.set(it, 1, bool(value)) } }
    var startupReleaseAttempts: Int
        get() = useOwner { NativeHudPublication.get(it, 2) }.toInt()
        set(value) { useOwner { NativeHudPublication.set(it, 2, value.toLong()) } }
    var startupReleaseCountdown: Int
        get() = useOwner { NativeHudPublication.get(it, 3) }.toInt()
        set(value) { useOwner { NativeHudPublication.set(it, 3, value.toLong()) } }
    var presentationCountdown: Int
        get() = useOwner { NativeHudPublication.get(it, 4) }.toInt()
        set(value) { useOwner { NativeHudPublication.set(it, 4, value.toLong()) } }
    var presentationAttempts: Int
        get() = useOwner { NativeHudPublication.get(it, 5) }.toInt()
        set(value) { useOwner { NativeHudPublication.set(it, 5, value.toLong()) } }
    var ticksSinceRefresh: Int
        get() = useOwner { NativeHudPublication.get(it, 6) }.toInt()
        set(value) { useOwner { NativeHudPublication.set(it, 6, value.toLong()) } }
    var presentationOption: Int
        get() = useOwner { NativeHudPublication.get(it, 7) }.toInt()
        set(value) { useOwner { NativeHudPublication.set(it, 7, value.toLong()) } }
    var clusterVisibleWanted: Boolean
        get() = useOwner { NativeHudPublication.get(it, 8) } != 0L
        set(value) { useOwner { NativeHudPublication.set(it, 8, bool(value)) } }
    var routeAutoShowMap: Boolean
        get() = useOwner { NativeHudPublication.get(it, 9) } != 0L
        set(value) { useOwner { NativeHudPublication.set(it, 9, bool(value)) } }
    var guidanceActive: Boolean
        get() = useOwner { NativeHudPublication.get(it, 10) } != 0L
        set(value) { useOwner { NativeHudPublication.set(it, 10, bool(value)) } }
    var hudActive: Boolean
        get() = useOwner { NativeHudPublication.get(it, 11) } != 0L
        set(value) { useOwner { NativeHudPublication.set(it, 11, bool(value)) } }
    var hudOutputTouched: Boolean
        get() = useOwner { NativeHudPublication.get(it, 12) } != 0L
        set(value) { useOwner { NativeHudPublication.set(it, 12, bool(value)) } }

    fun mapIntent(cruise: Boolean, connected: Boolean): ClusterMapIntent = when (step(1, bool(cruise), bool(connected))) {
        1L -> ClusterMapIntent.SHOWN
        2L -> ClusterMapIntent.HIDDEN
        else -> ClusterMapIntent.NO_ROUTE
    }
    fun cruiseShown(enabled: Boolean, connected: Boolean): Boolean = step(2, bool(enabled), bool(connected)) != 0L
    fun platformOption(option: Int, idleWanted: Boolean): Boolean? = step(3, option.toLong(), bool(idleWanted)).let { if (it < 0) null else it != 0L }
    fun visibility(show: Boolean) { step(4, bool(show)) }
    fun acceptPresentation(show: Boolean) { step(5, bool(show)) }
    fun startGuidance(auto: Boolean, cruising: Boolean) { step(6, bool(auto), bool(cruising)) }
    fun idleVisibility(wanted: Boolean) { step(7, bool(wanted)) }
    fun presentationDue(startup: Boolean, ready: Boolean): Int = step(8, bool(startup), bool(ready)).toInt()
    fun presentationFinished(startup: Boolean, interval: Int, success: Boolean): Boolean = step(9, bool(startup), interval.toLong(), bool(success)) != 0L
    enum class Refresh { NONE, CURRENT, FORCED }
    fun refresh(missingTurn: Boolean): Refresh = Refresh.entries[step(10, bool(missingTurn)).toInt()]
    fun clearCache(resetFallback: Boolean) { step(11, bool(resetFallback)) }
    fun retireLanesBefore(cursor: Int) { step(12, cursor.toLong()) }
    fun clearLanes(force: Boolean): Boolean = step(13, bool(force)) != 0L
    data class StopPlan(val needed: Boolean, val sdk: Boolean, val card: Boolean, val arrows: Boolean)
    fun stopPlan(navi: Boolean, card: Boolean, arrows: Boolean): StopPlan = step(14, bool(navi), bool(card), bool(arrows)).let {
        StopPlan(it and 1L != 0L, it and 2L != 0L, it and 4L != 0L, it and 8L != 0L)
    }
    fun stopped() { step(15) }
    private fun laneValues(lanes: List<EcarxDimApi.Lane>?): IntArray? = lanes?.let { IntArray(it.size * 2) { index -> if (index % 2 == 0) it[index / 2].number else it[index / 2].iconId } }
    private fun lanes(values: IntArray, start: Int = 0): List<EcarxDimApi.Lane> = (start until values.size step 2).map { EcarxDimApi.Lane(values[it], values[it + 1]) }
    fun cacheLanes(index: Int, group: List<EcarxDimApi.Lane>?, turn: Int, cursor: Int?) = useOwner {
        NativeHudPublication.cache(it, index, laneValues(group), turn, cursor ?: -1)
    }
    val cachedLaneIndices: IntArray get() = useOwner { NativeHudPublication.laneSnapshot(it, 1)!! }
    var lastLanePushed: List<EcarxDimApi.Lane>?
        get() = useOwner { NativeHudPublication.laneSnapshot(it, 0)?.let { values -> lanes(values) } }
        set(value) { useOwner { NativeHudPublication.laneCommit(it, laneValues(value)) } }
    data class LanePlan(val clear: Boolean, val publish: List<EcarxDimApi.Lane>?)
    fun lanePlan(force: Boolean, cursor: Int?, showing: Boolean?, hasTurn: Boolean, turn: Int, enabled: Boolean?): LanePlan = useOwner {
        val values = NativeHudPublication.lanePlan(it, force, cursor ?: -1, showing?.let { if (it) 1 else 0 } ?: -1,
            hasTurn, turn, enabled?.let { if (it) 1 else 0 } ?: -1)
        LanePlan(values[0] == 1, if (values[0] == 2) lanes(values, 1) else null)
    }
    private fun guidanceValues(value: EcarxDimApi.Guidance): LongArray = longArrayOf(value.turnId.toLong(), value.distanceToNextMeters,
        value.distanceToDestinationMeters, value.etaRemainingSeconds.toLong(), value.drivingDirection.toLong(), value.status.toLong(),
        value.muteState.toLong(), value.dayNightMode.toLong())
    var lastPushed: EcarxDimApi.Guidance?
        get() = useOwner { NativeHudPublication.guidanceSnapshot(it)?.let { snapshot ->
            val v = snapshot[0] as LongArray
            EcarxDimApi.Guidance(v[0].toInt(), v[1], v[2], v[3].toInt(), v[4].toInt(), v[5].toInt(), v[6].toInt(), v[7].toInt(), snapshot[1] as String)
        } }
        set(value) { useOwner { NativeHudPublication.guidanceCommit(it, value?.let(::guidanceValues), value?.nextGuidancePointName) } }
    fun guidanceChanged(force: Boolean, value: EcarxDimApi.Guidance): Boolean = useOwner {
        NativeHudPublication.guidanceChanged(it, guidanceValues(value), value.nextGuidancePointName, force)
    }
    var lastDestination: String?
        get() = useOwner { NativeHudPublication.destinationSnapshot(it) }
        set(value) { useOwner { NativeHudPublication.destinationCommit(it, value) } }
    fun destinationChanged(value: String): Boolean = useOwner { NativeHudPublication.destinationChanged(it, value) }
}

internal object NativeHudPublication {
    @JvmStatic external fun create(): Long
    @JvmStatic external fun destroy(handle: Long)
    @JvmStatic external fun get(handle: Long, field: Int): Long
    @JvmStatic external fun set(handle: Long, field: Int, value: Long)
    @JvmStatic external fun step(handle: Long, event: Int, a: Long, b: Long, c: Long): Long
    @JvmStatic external fun cache(handle: Long, index: Int, lanes: IntArray?, turn: Int, cursor: Int)
    @JvmStatic external fun lanePlan(handle: Long, force: Boolean, cursor: Int, showing: Int, hasTurn: Boolean, turn: Int, enabled: Int): IntArray
    @JvmStatic external fun laneSnapshot(handle: Long, field: Int): IntArray?
    @JvmStatic external fun laneCommit(handle: Long, lanes: IntArray?)
    @JvmStatic external fun guidanceChanged(handle: Long, fields: LongArray, road: String, force: Boolean): Boolean
    @JvmStatic external fun guidanceCommit(handle: Long, fields: LongArray?, road: String?)
    @JvmStatic external fun guidanceSnapshot(handle: Long): Array<Any?>?
    @JvmStatic external fun destinationSnapshot(handle: Long): String?
    @JvmStatic external fun destinationCommit(handle: Long, value: String?)
    @JvmStatic external fun destinationChanged(handle: Long, value: String): Boolean
}
