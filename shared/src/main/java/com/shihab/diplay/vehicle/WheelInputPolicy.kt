package com.shihab.diplay.vehicle

import com.shihab.diplay.airplay.NativeMedia

/** Key identity remains with the caller; a value describes one captured press. */
object WheelInputPolicy {
    enum class PressDecision { PASS, CONSUME, DECIDE }
    init { NativeMedia.requireAvailable() }
    fun consumed(token: Long?): Boolean = NativeWheelInput.pressConsumed(token ?: 0L, token != null)
    fun press(token: Long?, down: Boolean, first: Boolean): PressDecision =
        PressDecision.entries[NativeWheelInput.pressDecision(token ?: 0L, token != null, down, first)]
    fun commit(consumed: Boolean): Long = NativeWheelInput.pressCommit(consumed)
    val repeatGapMillis: Long get() = NativeWheelInput.siriConstant(0)
    val minDoublePressGapMillis: Long get() = NativeWheelInput.siriConstant(1)
}

/** Each consumer owns its own GC value token; time samples remain supplied by that consumer. */
class WheelSiriState {
    private var token = run { NativeMedia.requireAvailable(); NativeWheelInput.siriCreate() }
    fun opens(now: Long): Boolean {
        token = NativeWheelInput.siriStep(token, 0, now, 0)
        return NativeWheelInput.siriResult(token) != 0
    }
    fun doublePress(now: Long, maximumGap: Long): Int {
        require(maximumGap >= WheelInputPolicy.minDoublePressGapMillis)
        token = NativeWheelInput.siriStep(token, 1, now, maximumGap)
        return NativeWheelInput.siriResult(token)
    }
    fun reset() { token = NativeWheelInput.siriStep(token, 2, 0, 0) }
}

internal object NativeWheelInput {
    @JvmStatic external fun pressConsumed(token: Long, present: Boolean): Boolean
    @JvmStatic external fun pressDecision(token: Long, present: Boolean, down: Boolean, first: Boolean): Int
    @JvmStatic external fun pressCommit(consumed: Boolean): Long
    @JvmStatic external fun siriCreate(): LongArray
    @JvmStatic external fun siriStep(token: LongArray, event: Int, now: Long, maximumGap: Long): LongArray
    @JvmStatic external fun siriResult(token: LongArray): Int
    @JvmStatic external fun siriConstant(field: Int): Long
}
