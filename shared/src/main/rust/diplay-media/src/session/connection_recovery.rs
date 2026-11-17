//! Whole-stack recovery decisions and host-lifetime retry state.
use jni_sys::{jboolean, jclass, jint, jlong, JNIEnv};
const STABLE_SESSION_MILLIS: i64 = 60_000;

// ConnectionRecovery ordinals; preserve the host's existing precedence, including Wi-Fi reset.
fn action(facts: i32, startup: i32) -> i32 {
    if facts & 1 != 0 {
        return 5;
    } // USB trust outcomes
    if facts & 2 != 0 {
        return 4;
    } // explicit Wi-Fi reset
    if facts & (4 | 8) != 0 {
        return 3;
    } // prerequisite or protocol rejection
    match startup {
        0 | 1 => 1, // hotspot readiness / first TCP: consume the host's five-retry budget
        2..=4 => 2, // configuration / unusable Bluetooth / rejected P2P prompt
        _ => 0,     // ordinary transport loss: capped delay, no attempt limit
    }
}

fn retry_delay(attempts: i32, tunnel: bool) -> i64 {
    if tunnel {
        return 15_000;
    }
    // Keep the JVM shift/wrapping semantics of the former host expression.
    2_000i64
        .wrapping_mul(1i64.wrapping_shl(attempts.min(4) as u32))
        .min(30_000)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_orchestration_NativeConnectionRecovery_action(
    _env: *mut JNIEnv,
    _class: jclass,
    facts: jint,
    startup: jint,
) -> jint {
    // Menu deferral retains terminal failures even when the immediate action is a Wi-Fi reset.
    action(facts, startup) | if facts & (1 | 4 | 8) != 0 { 8 } else { 0 }
}

// Bits 0..32 hold the wrapping JVM Int, bits 32..35 the startup count;
// bits 35..37 describe the most recent schedule result.
fn retry_transition(token: i64, event: i32) -> i64 {
    let bits = token as u64;
    let attempts = bits as u32 as i32;
    let startup = ((bits >> 32) & 7) as i32;
    let (attempts, startup, result) = match event {
        0 if startup >= 5 => (attempts, startup, 3),
        0 => (attempts.wrapping_add(1), startup + 1, 0),
        1 => (attempts.wrapping_add(1), startup, 1),
        2 => (attempts.wrapping_add(1), startup, 2),
        3 => (0, startup, 0),
        4 => (attempts, 0, 0),
        _ => return token,
    };
    ((attempts as u32 as u64) | ((startup as u64) << 32) | (result << 35)) as i64
}

fn retry_query(token: i64, field: i32) -> i64 {
    let bits = token as u64;
    let attempts = bits as u32 as i32;
    let startup = ((bits >> 32) & 7) as i32;
    let result = (bits >> 35) & 3;
    match field {
        0 => attempts as i64,
        1 => startup as i64,
        2 => (result == 3) as i64,
        3 => match result {
            0 => retry_delay(startup - 1, false),
            1 => retry_delay(attempts.wrapping_sub(1), true),
            2 => retry_delay(attempts.wrapping_sub(1), false),
            _ => 0,
        },
        4 => STABLE_SESSION_MILLIS,
        _ => 0,
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_orchestration_NativeConnectionRecovery_retryTransition(
    _env: *mut JNIEnv,
    _class: jclass,
    token: jlong,
    event: jint,
) -> jlong {
    retry_transition(token, event)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_orchestration_NativeConnectionRecovery_retryQuery(
    _env: *mut JNIEnv,
    _class: jclass,
    token: jlong,
    field: jint,
) -> jlong {
    retry_query(token, field)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_orchestration_NativeConnectionRecovery_stable(
    _env: *mut JNIEnv,
    _class: jclass,
    first: jlong,
    now: jlong,
) -> jboolean {
    (now.wrapping_sub(first) >= STABLE_SESSION_MILLIS) as jboolean
}

// The low 32 bits are the wrapping JVM count; bit 32 marks a later loss.
fn control_transition(token: i64, event: i32) -> i64 {
    match event {
        0 => (token as u32).wrapping_add(1) as i64,
        1 => token | (1i64 << 32),
        _ => token,
    }
}

fn control_recovered(token: i64, snapshot: i64) -> bool {
    token as u32 != snapshot as u32 && token & (1i64 << 32) == 0
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_orchestration_NativeConnectionRecovery_controlTransition(
    _env: *mut JNIEnv,
    _class: jclass,
    token: jlong,
    event: jint,
) -> jlong {
    control_transition(token, event)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_orchestration_NativeConnectionRecovery_controlRecovered(
    _env: *mut JNIEnv,
    _class: jclass,
    token: jlong,
    snapshot: jlong,
) -> jboolean {
    control_recovered(token, snapshot) as jboolean
}

fn menu_failure(existing_manual: bool, incoming_manual: bool) -> i32 {
    i32::from(!existing_manual) | (i32::from(incoming_manual) << 1)
}

fn menu_exit(facts: i32) -> i32 {
    let has_failure = facts & 1 != 0;
    let startup_or_manual = facts & (2 | 4) != 0;
    let wifi_reset = facts & 8 != 0;
    let reconnect = facts & 16 != 0;
    let wireless = facts & 32 != 0;
    if has_failure
        && ((startup_or_manual && !reconnect) || (wifi_reset && (!reconnect || wireless)))
    {
        0
    } else if facts & 64 != 0 {
        1
    } else if (reconnect || facts & 128 != 0) && facts & 256 != 0 {
        2
    } else {
        3
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_orchestration_NativeConnectionRecovery_menuFailure(
    _env: *mut JNIEnv,
    _class: jclass,
    existing_manual: jboolean,
    incoming_manual: jboolean,
) -> jint {
    menu_failure(existing_manual != 0, incoming_manual != 0)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_orchestration_NativeConnectionRecovery_menuExit(
    _env: *mut JNIEnv,
    _class: jclass,
    facts: jint,
) -> jint {
    menu_exit(facts)
}

// 0 = visible/no retained attempt, 1 = retained, 2 = just consumed by failure.
fn background_transition(token: i64, event: i32) -> i64 {
    match event {
        0 => 1,
        1 => 0,
        2 => {
            if token == 1 {
                2
            } else {
                0
            }
        }
        _ => token,
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_orchestration_NativeConnectionRecovery_backgroundTransition(
    _env: *mut JNIEnv,
    _class: jclass,
    token: jlong,
    event: jint,
) -> jlong {
    background_transition(token, event)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_orchestration_NativeConnectionRecovery_backgroundConsumed(
    _env: *mut JNIEnv,
    _class: jclass,
    token: jlong,
) -> jboolean {
    (token == 2) as jboolean
}

// Startup generation is XOR encoded so the all-zero initial token represents -1.
// Bit 32 is the global stop, bit 33 the most recent startup-duplicate result.
fn failure_transition(token: i64, event: i32, generation: i32) -> i64 {
    let bits = token as u64;
    let stopped = bits & (1 << 32);
    let encoded = bits as u32;
    match event {
        0 => (encoded as u64 | (1 << 32)) as i64,
        1 => encoded as i64,
        2 => {
            let next = (generation as u32) ^ u32::MAX;
            (u64::from(next) | stopped | if encoded == next { 1 << 33 } else { 0 }) as i64
        }
        _ => token & !(1 << 33),
    }
}

fn failure_query(token: i64, field: i32) -> bool {
    match field {
        0 => token & (1 << 32) != 0,
        1 => token & (1 << 33) != 0,
        _ => false,
    }
}

// USB's presence bit is separate, so every Int value including -1 is a valid generation.
fn usb_stop_transition(token: i64, event: i32, generation: i32) -> i64 {
    match event {
        0 => (u64::from(generation as u32) | (1 << 32)) as i64,
        1 => 0,
        _ => token,
    }
}

fn usb_stop_matches(token: i64, generation: i32) -> bool {
    token & (1 << 32) != 0 && token as u32 == generation as u32
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_orchestration_NativeConnectionRecovery_failureTransition(
    _env: *mut JNIEnv,
    _class: jclass,
    token: jlong,
    event: jint,
    generation: jint,
) -> jlong {
    failure_transition(token, event, generation)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_orchestration_NativeConnectionRecovery_failureQuery(
    _env: *mut JNIEnv,
    _class: jclass,
    token: jlong,
    field: jint,
) -> jboolean {
    failure_query(token, field) as jboolean
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_orchestration_NativeConnectionRecovery_usbStopTransition(
    _env: *mut JNIEnv,
    _class: jclass,
    token: jlong,
    event: jint,
    generation: jint,
) -> jlong {
    usb_stop_transition(token, event, generation)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_orchestration_NativeConnectionRecovery_usbStopMatches(
    _env: *mut JNIEnv,
    _class: jclass,
    token: jlong,
    generation: jint,
) -> jboolean {
    usb_stop_matches(token, generation) as jboolean
}

fn usb_takeover(facts: i32) -> bool {
    facts & (4 | 8) != 0 || facts & 1 == 0 || facts & 2 == 0
}

fn usb_transition(token: i64, event: i32) -> i64 {
    match event {
        0 => 1,
        1..=3 => 0,
        _ => token,
    }
}

fn usb_query(token: i64, field: i32, takeover: bool) -> i64 {
    match field {
        0 => (token == 1) as i64,
        1 if !takeover => -1,
        1 if token == 1 => 10_000,
        1 => 2_000,
        _ => -1,
    }
}

fn usb_fallback_action(menu: bool, saved_wireless: bool, has_controller: bool) -> i32 {
    if menu {
        0
    } else if !saved_wireless {
        1
    } else if has_controller {
        3
    } else {
        2
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_orchestration_NativeConnectionRecovery_usbTakeover(
    _env: *mut JNIEnv,
    _class: jclass,
    facts: jint,
) -> jboolean {
    usb_takeover(facts) as jboolean
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_orchestration_NativeConnectionRecovery_usbTransition(
    _env: *mut JNIEnv,
    _class: jclass,
    token: jlong,
    event: jint,
) -> jlong {
    usb_transition(token, event)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_orchestration_NativeConnectionRecovery_usbQuery(
    _env: *mut JNIEnv,
    _class: jclass,
    token: jlong,
    field: jint,
    takeover: jboolean,
) -> jlong {
    usb_query(token, field, takeover != 0)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_orchestration_NativeConnectionRecovery_usbFallbackAction(
    _env: *mut JNIEnv,
    _class: jclass,
    menu: jboolean,
    saved_wireless: jboolean,
    has_controller: jboolean,
) -> jint {
    usb_fallback_action(menu != 0, saved_wireless != 0, has_controller != 0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn recovery_precedence_keeps_trust_reset_and_prerequisite_behavior() {
        for (facts, startup, expected) in [
            (0, -1, 0),
            (0, 0, 1),
            (0, 1, 1),
            (0, 2, 2),
            (0, 3, 2),
            (0, 4, 2),
            (4, 0, 3),
            (8, 1, 3),
            (2, 0, 4),
            (14, 0, 4),
            (15, 0, 5),
        ] {
            assert_eq!(action(facts, startup), expected);
        }
    }

    #[test]
    fn ordinary_retries_keep_backoff_without_an_attempt_limit() {
        assert_eq!(
            (0..7).map(|n| retry_delay(n, false)).collect::<Vec<_>>(),
            vec![2_000, 4_000, 8_000, 16_000, 30_000, 30_000, 30_000]
        );
        assert_eq!(retry_delay(i32::MAX, false), 30_000);
        for n in [0, 1, 4, 100] {
            assert_eq!(retry_delay(n, true), 15_000);
        }
    }

    #[test]
    fn host_token_preserves_wrapping_and_separate_budgets() {
        let mut state = retry_transition(i32::MAX as i64, 2);
        assert_eq!(retry_query(state, 0), i32::MIN as i64);
        assert_eq!(retry_query(state, 3), 30_000);
        state = retry_transition(state, 2);
        assert_eq!(retry_query(state, 0), i32::MIN as i64 + 1);
        assert_eq!(retry_query(state, 3), retry_delay(i32::MIN, false));
        for delay in [2_000, 4_000, 8_000, 16_000, 30_000] {
            state = retry_transition(state, 0);
            assert_eq!(retry_query(state, 3), delay);
        }
        let attempts = retry_query(state, 0);
        state = retry_transition(state, 0);
        assert_eq!(retry_query(state, 2), 1);
        assert_eq!(retry_query(state, 0), attempts);
        state = retry_transition(state, 3);
        assert_eq!(retry_query(state, 0), 0);
        assert_eq!(retry_query(state, 1), 5);
        state = retry_transition(state, 4);
        assert_eq!(retry_query(state, 1), 0);
    }

    #[test]
    fn invalid_selectors_are_safe_at_the_jni_boundary() {
        let state = retry_transition(0, 0);
        assert_eq!(retry_transition(state, -1), state);
        assert_eq!(retry_query(state, -1), 0);
        assert_eq!(retry_query(3 << 35, 3), 0);
        assert_eq!(retry_query(state, 4), 60_000);
    }

    #[test]
    fn control_recovery_uses_wrapping_count_and_latest_loss() {
        let old = control_transition(i32::MAX as i64, 1);
        let running = control_transition(old, 0);
        assert_eq!(running as u32, i32::MIN as u32);
        assert!(control_recovered(running, old));
        assert!(!control_recovered(control_transition(running, 1), old));
        assert!(!control_recovered(running, running | (1i64 << 32)));
        assert_eq!(control_transition(running, -1), running);
    }

    #[test]
    fn menu_replay_and_stop_are_independent_and_precede_exit_actions() {
        assert_eq!(menu_failure(false, false), 1);
        assert_eq!(menu_failure(true, true), 2);
        assert_eq!(menu_failure(true, false), 0);
        assert_eq!(menu_exit(1 | 4 | 64 | 128 | 256), 0);
        assert_eq!(menu_exit(1 | 8 | 16 | 32 | 64), 0);
        assert_eq!(menu_exit(1 | 8 | 16 | 256), 2);
        assert_eq!(menu_exit(64 | 128 | 256), 1);
        assert_eq!(menu_exit(256), 3);
    }

    #[test]
    fn background_attempt_is_consumed_once_and_invalid_event_preserves_it() {
        let kept = background_transition(0, 0);
        assert_eq!(background_transition(kept, -1), kept);
        let consumed = background_transition(kept, 2);
        assert_eq!(consumed, 2);
        assert_eq!(background_transition(consumed, 2), 0);
        assert_eq!(background_transition(kept, 1), 0);
    }

    #[test]
    fn independent_failure_latches_keep_all_generations_and_initial_sentinels() {
        let mut failure = failure_transition(0, 2, -1);
        assert!(failure_query(failure, 1));
        assert!(!usb_stop_matches(0, -1));
        for generation in [i32::MIN, -1, 0, i32::MAX] {
            failure = failure_transition(failure, 2, generation);
            assert!(!failure_query(failure, 1));
            failure = failure_transition(failure, 2, generation);
            assert!(failure_query(failure, 1));
            failure = failure_transition(failure, 0, 0);
            assert!(failure_query(failure, 0));
            failure = failure_transition(failure, 1, 0);
            assert!(!failure_query(failure, 0));
            assert!(failure_query(failure_transition(failure, 2, generation), 1));
            let usb = usb_stop_transition(0, 0, generation);
            assert!(usb_stop_matches(usb, generation));
            assert!(!usb_stop_matches(
                usb_stop_transition(usb, 1, 0),
                generation
            ));
            assert_eq!(usb_stop_transition(usb, -1, 0), usb);
        }
        assert_eq!(failure_transition(failure, -1, 0), failure & !(1 << 33));
        assert!(!failure_query(failure, -1));
    }

    #[test]
    fn usb_reenumeration_delay_and_fallback_actions_are_independent() {
        assert!(!usb_takeover(3));
        for facts in [0, 1, 2, 7, 11, 15] {
            assert!(usb_takeover(facts));
        }
        let waiting = usb_transition(0, 0);
        assert_eq!(usb_query(waiting, 1, true), 10_000);
        assert_eq!(usb_query(waiting, 1, false), -1);
        for event in [1, 2, 3] {
            assert_eq!(usb_query(usb_transition(waiting, event), 1, true), 2_000);
        }
        assert_eq!(usb_transition(waiting, -1), waiting);
        assert_eq!(usb_query(waiting, -1, true), -1);
        assert_eq!(usb_fallback_action(true, true, true), 0);
        assert_eq!(usb_fallback_action(false, false, true), 1);
        assert_eq!(usb_fallback_action(false, true, false), 2);
        assert_eq!(usb_fallback_action(false, true, true), 3);
    }
}
