package com.shihab.diplay.bluetooth

import com.shihab.diplay.airplay.NativeMedia

/**
 * Policy questions owned by Rust `session::bt_profile_guard`. Kotlin keeps every Android
 * adapter/profile verb, broadcast, retry thread and the persisted journal; these are the pure
 * "decisions" only.
 */
internal object NativeBtProfileGuard {
    init { NativeMedia.requireAvailable() }

    /** 0 skip (media uses car audio), 1 skip (adapter switch off), 2 arm media-only, 3 arm adapter. */
    @JvmStatic external fun yieldMode(mediaOnly: Boolean, adapterEnabled: Boolean, mediaAudioAllowed: Boolean): Int
    @JvmStatic external fun armDelayMs(mode: Int, adapterDelaySeconds: Int): Long
    @JvmStatic external fun pausedByUs(applied: Boolean, appliedModeIsAdapter: Boolean, adapterOff: Boolean): Boolean

    /** 0 settled, 1 interrupted, 2 superseded. */
    @JvmStatic external fun restorePhase(ownershipMatches: Boolean, returned: Boolean): Int
    @JvmStatic external fun shouldPassivelyObserveBootRadio(earlyBoot: Boolean, initialState: Int, ownedPause: Boolean): Boolean
    @JvmStatic external fun shouldAttemptRecovery(applied: Boolean, hasSession: Boolean, recoveryPending: Boolean): Boolean
    @JvmStatic external fun rejectAdapterReady(ownedPause: Boolean, initialState: Int, observeBootRadio: Boolean): Boolean
}
