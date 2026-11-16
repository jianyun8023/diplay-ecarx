//! Whole-stack recovery decisions. Retry counters and scheduling remain with the Android host.
use jni_sys::{jboolean, jclass, jint, jlong, JNIEnv};

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

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_orchestration_NativeConnectionRecovery_retryDelay(
    _env: *mut JNIEnv, _class: jclass, attempts: jint, tunnel: jboolean,
) -> jlong { retry_delay(attempts, tunnel != 0) }

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
}
