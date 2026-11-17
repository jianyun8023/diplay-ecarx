package com.shihab.diplay.testcore

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Robolectric shadow for the Rust-owned BT-guard decisions. Under Robolectric the JNI library is
 * not bound to this facade, so the same pure logic is expressed here as an independent Kotlin
 * reference (the project's standard `testCoreShadows` pattern). Plain JVM tests still exercise the
 * real Rust implementation through `NativeBtProfileGuard`.
 */
@Implements(className = "com.shihab.diplay.bluetooth.NativeBtProfileGuard", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeBtProfileGuard {
    companion object {
        private const val STATE_OFF = 10
        private const val STATE_TURNING_ON = 11
        private const val STATE_ON = 12

        @JvmStatic @Implementation
        fun yieldMode(mediaOnly: Boolean, adapterEnabled: Boolean, mediaAudioAllowed: Boolean): Int {
            if (mediaOnly && !mediaAudioAllowed) return 0
            val adapterMode = !mediaOnly && adapterEnabled
            if (!adapterMode && !mediaOnly) return 1
            return if (mediaOnly) 2 else 3
        }

        @JvmStatic @Implementation
        fun armDelayMs(mode: Int, adapterDelaySeconds: Int): Long =
            if (mode == 3) adapterDelaySeconds.coerceIn(1, 10).toLong() * 1000 else 0L

        @JvmStatic @Implementation
        fun pausedByUs(applied: Boolean, appliedModeIsAdapter: Boolean, adapterOff: Boolean): Boolean =
            applied && appliedModeIsAdapter && adapterOff

        @JvmStatic @Implementation
        fun restorePhase(ownershipMatches: Boolean, returned: Boolean): Int =
            if (!ownershipMatches) 2 else if (returned) 0 else 1

        @JvmStatic @Implementation
        fun shouldPassivelyObserveBootRadio(earlyBoot: Boolean, initialState: Int, ownedPause: Boolean): Boolean =
            earlyBoot && !ownedPause && initialState == STATE_OFF

        @JvmStatic @Implementation
        fun shouldAttemptRecovery(applied: Boolean, hasSession: Boolean, recoveryPending: Boolean): Boolean =
            applied && !hasSession && !recoveryPending

        @JvmStatic @Implementation
        fun rejectAdapterReady(ownedPause: Boolean, initialState: Int, observeBootRadio: Boolean): Boolean =
            !ownedPause && initialState != STATE_ON && initialState != STATE_TURNING_ON && !observeBootRadio
    }
}
