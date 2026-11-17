package com.shihab.diplay.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Independent expectations for the Rust-owned Bluetooth-guard decisions
 * (`session::bt_profile_guard`). The expected values are written out here, not exported from Rust.
 */
class NativeBtProfileGuardDecisionTest {

    @Test fun yieldModeMatrix() {
        // skip: media-only but CarPlay audio already uses car Bluetooth
        assertEquals(0, NativeBtProfileGuard.yieldMode(mediaOnly = true, adapterEnabled = true, mediaAudioAllowed = false))
        // skip: neither media-only nor adapter-off is enabled
        assertEquals(1, NativeBtProfileGuard.yieldMode(mediaOnly = false, adapterEnabled = false, mediaAudioAllowed = true))
        // arm media-only
        assertEquals(2, NativeBtProfileGuard.yieldMode(mediaOnly = true, adapterEnabled = false, mediaAudioAllowed = true))
        // arm adapter-off
        assertEquals(3, NativeBtProfileGuard.yieldMode(mediaOnly = false, adapterEnabled = true, mediaAudioAllowed = true))
        // adapter switch ignored while media-only holds
        assertEquals(2, NativeBtProfileGuard.yieldMode(mediaOnly = true, adapterEnabled = true, mediaAudioAllowed = true))
    }

    @Test fun armDelayClampsAdapterSecondsAndIsZeroForMedia() {
        assertEquals(0L, NativeBtProfileGuard.armDelayMs(mode = 2, adapterDelaySeconds = 5))
        assertEquals(0L, NativeBtProfileGuard.armDelayMs(mode = 1, adapterDelaySeconds = 5))
        assertEquals(3_000L, NativeBtProfileGuard.armDelayMs(mode = 3, adapterDelaySeconds = 3))
        assertEquals(1_000L, NativeBtProfileGuard.armDelayMs(mode = 3, adapterDelaySeconds = 0))
        assertEquals(10_000L, NativeBtProfileGuard.armDelayMs(mode = 3, adapterDelaySeconds = 99))
    }

    @Test fun pausedByUsRequiresAppliedAdapterModeAndOffRadio() {
        assertTrue(NativeBtProfileGuard.pausedByUs(applied = true, appliedModeIsAdapter = true, adapterOff = true))
        assertFalse(NativeBtProfileGuard.pausedByUs(applied = false, appliedModeIsAdapter = true, adapterOff = true))
        assertFalse(NativeBtProfileGuard.pausedByUs(applied = true, appliedModeIsAdapter = false, adapterOff = true))
        assertFalse(NativeBtProfileGuard.pausedByUs(applied = true, appliedModeIsAdapter = true, adapterOff = false))
    }

    @Test fun restorePhasePrefersSuperseded() {
        assertEquals(2, NativeBtProfileGuard.restorePhase(ownershipMatches = false, returned = true))
        assertEquals(2, NativeBtProfileGuard.restorePhase(ownershipMatches = false, returned = false))
        assertEquals(0, NativeBtProfileGuard.restorePhase(ownershipMatches = true, returned = true))
        assertEquals(1, NativeBtProfileGuard.restorePhase(ownershipMatches = true, returned = false))
    }

    @Test fun bootRadioRecoveryAndAdapterReady() {
        assertTrue(NativeBtProfileGuard.shouldPassivelyObserveBootRadio(earlyBoot = true, initialState = 10, ownedPause = false))
        assertFalse(NativeBtProfileGuard.shouldPassivelyObserveBootRadio(earlyBoot = false, initialState = 10, ownedPause = false))
        assertFalse(NativeBtProfileGuard.shouldPassivelyObserveBootRadio(earlyBoot = true, initialState = 12, ownedPause = false))
        assertFalse(NativeBtProfileGuard.shouldPassivelyObserveBootRadio(earlyBoot = true, initialState = 10, ownedPause = true))

        assertTrue(NativeBtProfileGuard.shouldAttemptRecovery(applied = true, hasSession = false, recoveryPending = false))
        assertFalse(NativeBtProfileGuard.shouldAttemptRecovery(applied = false, hasSession = false, recoveryPending = false))
        assertFalse(NativeBtProfileGuard.shouldAttemptRecovery(applied = true, hasSession = true, recoveryPending = false))
        assertFalse(NativeBtProfileGuard.shouldAttemptRecovery(applied = true, hasSession = false, recoveryPending = true))

        assertTrue(NativeBtProfileGuard.rejectAdapterReady(ownedPause = false, initialState = 10, observeBootRadio = false))
        assertFalse(NativeBtProfileGuard.rejectAdapterReady(ownedPause = true, initialState = 10, observeBootRadio = false))
        assertFalse(NativeBtProfileGuard.rejectAdapterReady(ownedPause = false, initialState = 12, observeBootRadio = false))
        assertFalse(NativeBtProfileGuard.rejectAdapterReady(ownedPause = false, initialState = 11, observeBootRadio = false))
        assertFalse(NativeBtProfileGuard.rejectAdapterReady(ownedPause = false, initialState = 10, observeBootRadio = true))
    }
}
