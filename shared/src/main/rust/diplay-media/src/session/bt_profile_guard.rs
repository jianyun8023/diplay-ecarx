//! Bluetooth profile-guard *decisions*.
//!
//! `CarBluetoothProfileGuard` is dominated by Android adapter/profile verbs, broadcasts, retry
//! threads and its persisted journal. Only the policy shell is pure: which yield to arm, the
//! adapter delay, whether a pause is ours, the restore phase, the recovery gate and the
//! boot-radio / adapter-ready predicates. That shell lives here — the same "decisions in Rust,
//! verbs in Kotlin" split used by `session::connection_recovery`. Android keeps every SDK call.
use jni_sys::{jboolean, jclass, jint, jlong, JNIEnv};

const ADAPTER_SECONDS_MIN: i32 = 1;
const ADAPTER_SECONDS_MAX: i32 = 10;
// android.bluetooth.BluetoothAdapter state values.
const STATE_OFF: i32 = 10;
const STATE_TURNING_ON: i32 = 11;
const STATE_ON: i32 = 12;

/// Yield decision, encoded as the same integers the Kotlin facade switches on.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum YieldMode {
    /// Media-only yield, but CarPlay audio is routed through car Bluetooth, so nothing to do.
    SkipMediaUsesCarAudio = 0,
    /// Neither media-only nor adapter-off is enabled, so the head unit keeps its Bluetooth.
    SkipAdapterOff = 1,
    /// Media-only yield: drop the phone's A2DP_SINK policy only.
    ArmMediaOnly = 2,
    /// Adapter-off fallback: switch the head unit radio off after the configured delay.
    ArmAdapter = 3,
}

pub fn yield_mode(media_only: bool, adapter_enabled: bool, media_audio_allowed: bool) -> YieldMode {
    if media_only && !media_audio_allowed {
        return YieldMode::SkipMediaUsesCarAudio;
    }
    let adapter_mode = !media_only && adapter_enabled;
    if !adapter_mode && !media_only {
        return YieldMode::SkipAdapterOff;
    }
    if media_only {
        YieldMode::ArmMediaOnly
    } else {
        YieldMode::ArmAdapter
    }
}

pub fn clamp_adapter_seconds(raw: i32) -> i32 {
    raw.clamp(ADAPTER_SECONDS_MIN, ADAPTER_SECONDS_MAX)
}

/// Milliseconds before an adapter-off arm fires. Media-only applies immediately.
pub fn arm_delay_ms(mode: YieldMode, adapter_delay_seconds: i32) -> i64 {
    match mode {
        YieldMode::ArmAdapter => clamp_adapter_seconds(adapter_delay_seconds) as i64 * 1_000,
        _ => 0,
    }
}

/// True while the head unit's radio is off because this app turned it off for adapter mode.
pub fn paused_by_us(applied: bool, applied_mode_is_adapter: bool, adapter_off: bool) -> bool {
    applied && applied_mode_is_adapter && adapter_off
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RestorePhase {
    Settled = 0,
    Interrupted = 1,
    Superseded = 2,
}

pub fn restore_phase(ownership_matches: bool, returned: bool) -> RestorePhase {
    if !ownership_matches {
        RestorePhase::Superseded
    } else if returned {
        RestorePhase::Settled
    } else {
        RestorePhase::Interrupted
    }
}

/// Observe an OEM-enabled radio from OFF only during the early boot window and not our own pause.
pub fn should_passively_observe_boot_radio(
    early_boot: bool,
    initial_state: i32,
    owned_pause: bool,
) -> bool {
    early_boot && !owned_pause && initial_state == STATE_OFF
}

/// Startup recovery gate: a journal exists, no live session owns it, and no attempt is in flight.
pub fn should_attempt_recovery(applied: bool, has_session: bool, recovery_pending: bool) -> bool {
    applied && !has_session && !recovery_pending
}

/// A radio that is not ON/TURNING_ON and not being passively observed must be rejected at once.
pub fn reject_adapter_ready(owned_pause: bool, initial_state: i32, observe_boot_radio: bool) -> bool {
    !owned_pause
        && initial_state != STATE_ON
        && initial_state != STATE_TURNING_ON
        && !observe_boot_radio
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_bluetooth_NativeBtProfileGuard_yieldMode(
    _env: *mut JNIEnv,
    _class: jclass,
    media_only: jboolean,
    adapter_enabled: jboolean,
    media_audio_allowed: jboolean,
) -> jint {
    yield_mode(media_only != 0, adapter_enabled != 0, media_audio_allowed != 0) as jint
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_bluetooth_NativeBtProfileGuard_armDelayMs(
    _env: *mut JNIEnv,
    _class: jclass,
    mode: jint,
    adapter_delay_seconds: jint,
) -> jlong {
    let mode = match mode {
        0 => YieldMode::SkipMediaUsesCarAudio,
        1 => YieldMode::SkipAdapterOff,
        2 => YieldMode::ArmMediaOnly,
        _ => YieldMode::ArmAdapter,
    };
    arm_delay_ms(mode, adapter_delay_seconds)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_bluetooth_NativeBtProfileGuard_pausedByUs(
    _env: *mut JNIEnv,
    _class: jclass,
    applied: jboolean,
    applied_mode_is_adapter: jboolean,
    adapter_off: jboolean,
) -> jboolean {
    paused_by_us(applied != 0, applied_mode_is_adapter != 0, adapter_off != 0) as jboolean
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_bluetooth_NativeBtProfileGuard_restorePhase(
    _env: *mut JNIEnv,
    _class: jclass,
    ownership_matches: jboolean,
    returned: jboolean,
) -> jint {
    restore_phase(ownership_matches != 0, returned != 0) as jint
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_bluetooth_NativeBtProfileGuard_shouldPassivelyObserveBootRadio(
    _env: *mut JNIEnv,
    _class: jclass,
    early_boot: jboolean,
    initial_state: jint,
    owned_pause: jboolean,
) -> jboolean {
    should_passively_observe_boot_radio(early_boot != 0, initial_state, owned_pause != 0) as jboolean
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_bluetooth_NativeBtProfileGuard_shouldAttemptRecovery(
    _env: *mut JNIEnv,
    _class: jclass,
    applied: jboolean,
    has_session: jboolean,
    recovery_pending: jboolean,
) -> jboolean {
    should_attempt_recovery(applied != 0, has_session != 0, recovery_pending != 0) as jboolean
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_bluetooth_NativeBtProfileGuard_rejectAdapterReady(
    _env: *mut JNIEnv,
    _class: jclass,
    owned_pause: jboolean,
    initial_state: jint,
    observe_boot_radio: jboolean,
) -> jboolean {
    reject_adapter_ready(owned_pause != 0, initial_state, observe_boot_radio != 0) as jboolean
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn yield_mode_prefers_media_audio_skip_then_adapter_off_then_arm() {
        // Media-only but CarPlay uses car Bluetooth audio: skip even if adapter-off were also on.
        assert_eq!(yield_mode(true, true, false), YieldMode::SkipMediaUsesCarAudio);
        // Neither mode enabled (media-only off, adapter-off off): nothing to do.
        assert_eq!(yield_mode(false, false, true), YieldMode::SkipAdapterOff);
        // Media-only, CarPlay audio not on car BT: arm media-only.
        assert_eq!(yield_mode(true, false, true), YieldMode::ArmMediaOnly);
        // Adapter-off enabled and not media-only: arm adapter mode.
        assert_eq!(yield_mode(false, true, true), YieldMode::ArmAdapter);
        // adapterEnabled is ignored while media-only holds.
        assert_eq!(yield_mode(true, true, true), YieldMode::ArmMediaOnly);
    }

    #[test]
    fn arm_delay_is_zero_for_media_and_clamped_seconds_for_adapter() {
        for raw in [i32::MIN, 0, 1, 3, 10, 11, i32::MAX] {
            assert_eq!(arm_delay_ms(YieldMode::ArmMediaOnly, raw), 0);
            assert_eq!(arm_delay_ms(YieldMode::SkipAdapterOff, raw), 0);
            let clamped = raw.clamp(1, 10) as i64 * 1000;
            assert_eq!(arm_delay_ms(YieldMode::ArmAdapter, raw), clamped);
        }
        assert_eq!(arm_delay_ms(YieldMode::ArmAdapter, 3), 3000);
    }

    #[test]
    fn paused_by_us_requires_applied_adapter_mode_and_off_radio() {
        assert!(paused_by_us(true, true, true));
        assert!(!paused_by_us(false, true, true));
        assert!(!paused_by_us(true, false, true));
        assert!(!paused_by_us(true, true, false));
    }

    #[test]
    fn restore_phase_prefers_superseded_then_settled_then_interrupted() {
        assert_eq!(restore_phase(false, true), RestorePhase::Superseded);
        assert_eq!(restore_phase(false, false), RestorePhase::Superseded);
        assert_eq!(restore_phase(true, true), RestorePhase::Settled);
        assert_eq!(restore_phase(true, false), RestorePhase::Interrupted);
    }

    #[test]
    fn boot_radio_observation_needs_early_boot_off_radio_and_not_our_pause() {
        assert!(should_passively_observe_boot_radio(true, STATE_OFF, false));
        assert!(!should_passively_observe_boot_radio(false, STATE_OFF, false));
        assert!(!should_passively_observe_boot_radio(true, STATE_OFF, true));
        assert!(!should_passively_observe_boot_radio(true, STATE_ON, false));
        assert!(!should_passively_observe_boot_radio(true, STATE_TURNING_ON, false));
    }

    #[test]
    fn recovery_gate_needs_journal_no_session_and_no_pending_attempt() {
        assert!(should_attempt_recovery(true, false, false));
        assert!(!should_attempt_recovery(false, false, false));
        assert!(!should_attempt_recovery(true, true, false));
        assert!(!should_attempt_recovery(true, false, true));
    }

    #[test]
    fn adapter_ready_rejects_only_when_not_ours_not_on_and_not_observed() {
        assert!(reject_adapter_ready(false, STATE_OFF, false));
        assert!(!reject_adapter_ready(true, STATE_OFF, false)); // owned pause may enable
        assert!(!reject_adapter_ready(false, STATE_ON, false));
        assert!(!reject_adapter_ready(false, STATE_TURNING_ON, false));
        assert!(!reject_adapter_ready(false, STATE_OFF, true)); // passively observed
        assert!(reject_adapter_ready(false, 13, false)); // TURNING_OFF
    }
}
