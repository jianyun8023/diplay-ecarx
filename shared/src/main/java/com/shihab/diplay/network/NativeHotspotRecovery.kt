package com.shihab.diplay.network

import com.shihab.diplay.airplay.NativeMedia

/** Rust owns readiness samples and radio recovery times; Android executes polls and cancellation. */
internal object NativeHotspotRecovery {
    init { NativeMedia.requireAvailable() }
    @JvmStatic external fun readinessCreate(now: Long, timeout: Long): LongArray
    @JvmStatic external fun readinessSample(token: LongArray, selected: Boolean, hadPrevious: Boolean,
        same: Boolean, confirmed: Boolean): Boolean
    @JvmStatic external fun readinessTime(token: LongArray, expired: Boolean, now: Long): Long
    @JvmStatic external fun returnWait(start: Long, timeout: Long, now: Long): Long
    @JvmStatic external fun outageCreate(): LongArray
    @JvmStatic external fun outageEvent(token: LongArray, operation: Int, now: Long, budget: Long): Boolean
}
