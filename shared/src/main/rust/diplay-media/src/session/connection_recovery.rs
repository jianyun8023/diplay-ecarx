//! Whole-stack recovery decisions and host-lifetime retry state.
use jni_sys::{jboolean, jclass, jint, jlong, JNIEnv};
const STABLE_SESSION_MILLIS: i64 = 60_000;

// ConnectionRecovery ordinals; preserve the host's existing precedence, including Wi-Fi reset.
fn action(facts: i32, startup: i32) -> i32 {
    if facts & 1 != 0 { return 5; } // USB trust outcomes
    if facts & 2 != 0 { return 4; } // explicit Wi-Fi reset
    if facts & (4 | 8) != 0 { return 3; } // prerequisite or protocol rejection
    match startup {
        0 | 1 => 1, // hotspot readiness / first TCP: consume the host's five-retry budget
        2..=4 => 2, // configuration / unusable Bluetooth / rejected P2P prompt
        _ => 0,    // ordinary transport loss: capped delay, no attempt limit
    }
}

fn retry_delay(attempts: i32, tunnel: bool) -> i64 {
    if tunnel { return 15_000; }
    // Keep the JVM shift/wrapping semantics of the former host expression.
    2_000i64.wrapping_mul(1i64.wrapping_shl(attempts.min(4) as u32)).min(30_000)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_orchestration_NativeConnectionRecovery_action(
    _env: *mut JNIEnv, _class: jclass, facts: jint, startup: jint,
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
    _env: *mut JNIEnv, _class: jclass, token: jlong, event: jint,
) -> jlong { retry_transition(token, event) }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_orchestration_NativeConnectionRecovery_retryQuery(
    _env: *mut JNIEnv, _class: jclass, token: jlong, field: jint,
) -> jlong { retry_query(token, field) }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_orchestration_NativeConnectionRecovery_stable(
    _env: *mut JNIEnv, _class: jclass, first: jlong, now: jlong,
) -> jboolean { (now.wrapping_sub(first) >= STABLE_SESSION_MILLIS) as jboolean }

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
    _env: *mut JNIEnv, _class: jclass, token: jlong, event: jint,
) -> jlong { control_transition(token, event) }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_orchestration_NativeConnectionRecovery_controlRecovered(
    _env: *mut JNIEnv, _class: jclass, token: jlong, snapshot: jlong,
) -> jboolean { control_recovered(token, snapshot) as jboolean }

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn recovery_precedence_keeps_trust_reset_and_prerequisite_behavior() {
        for (facts, startup, expected) in [
            (0, -1, 0), (0, 0, 1), (0, 1, 1), (0, 2, 2), (0, 3, 2), (0, 4, 2),
            (4, 0, 3), (8, 1, 3), (2, 0, 4), (14, 0, 4), (15, 0, 5),
        ] {
            assert_eq!(action(facts, startup), expected);
        }
    }

    #[test]
    fn ordinary_retries_keep_backoff_without_an_attempt_limit() {
        assert_eq!((0..7).map(|n| retry_delay(n, false)).collect::<Vec<_>>(),
            vec![2_000, 4_000, 8_000, 16_000, 30_000, 30_000, 30_000]);
        assert_eq!(retry_delay(i32::MAX, false), 30_000);
        for n in [0, 1, 4, 100] { assert_eq!(retry_delay(n, true), 15_000); }
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
}
