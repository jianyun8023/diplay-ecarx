//! The wired control worker's handshake/drive deadline. Android supplies clock samples and I/O.
//! A token has two unchanged i64 values (start, timeout), or is empty after unlimited authentication.
//! The empty variant needs no clock or start value, avoiding a lossy sentinel in either i64.
use jni_sys::{jboolean, jclass, jlong, jlongArray, JNIEnv};
use std::ptr::null_mut;

const HANDSHAKE_MILLIS: i64 = 60_000;
const MAX_POLL_MILLIS: i64 = 30_000;

#[derive(Clone, Copy, Debug, PartialEq)]
enum Deadline {
    Budgeted { started: i64, timeout: i64 },
    AuthenticatedUnlimited,
}

impl Deadline {
    fn authenticated(self) -> Self {
        match self {
            Self::Budgeted { timeout: i64::MAX, .. } => Self::AuthenticatedUnlimited,
            _ => self,
        }
    }

    fn needs_clock(self) -> bool { matches!(self, Self::Budgeted { .. }) }

    fn remaining(self, now: i64) -> i64 {
        let Self::Budgeted { started, timeout } = self else { return MAX_POLL_MILLIS; };
        let budget = if timeout == i64::MAX { HANDSHAKE_MILLIS } else { timeout };
        let elapsed = now.wrapping_sub(started).max(0);
        // Preserve Kotlin Long overflow, including unusual constructor inputs outside the wired API.
        let remaining = budget.wrapping_mul(1_000_000).wrapping_sub(elapsed);
        if remaining <= 0 { return 0; }
        (remaining.wrapping_add(999_999) / 1_000_000).min(MAX_POLL_MILLIS)
    }
}

unsafe fn read(env: *mut JNIEnv, token: jlongArray) -> Option<Deadline> {
    let f = &**env;
    match (f.GetArrayLength.unwrap())(env, token) {
        0 => Some(Deadline::AuthenticatedUnlimited),
        2 => {
            let mut values = [0; 2];
            (f.GetLongArrayRegion.unwrap())(env, token, 0, 2, values.as_mut_ptr());
            if (f.ExceptionCheck.unwrap())(env) != 0 { return None; }
            Some(Deadline::Budgeted { started: values[0], timeout: values[1] })
        }
        _ => {
            let class = (f.FindClass.unwrap())(env, c"java/lang/IllegalArgumentException".as_ptr());
            if !class.is_null() {
                (f.ThrowNew.unwrap())(env, class, c"Invalid control deadline token".as_ptr());
            }
            None
        }
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeControlDeadline_create(
    env: *mut JNIEnv, _class: jclass, timeout: jlong, started: jlong,
) -> jlongArray {
    let f = &**env;
    let token = (f.NewLongArray.unwrap())(env, 2);
    if token.is_null() { return null_mut(); }
    (f.SetLongArrayRegion.unwrap())(env, token, 0, 2, [started, timeout].as_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 { null_mut() } else { token }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeControlDeadline_authenticated(
    env: *mut JNIEnv, _class: jclass, token: jlongArray,
) -> jlongArray {
    let Some(state) = read(env, token) else { return null_mut(); };
    if state.authenticated() == state { token } else { ((**env).NewLongArray.unwrap())(env, 0) }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeControlDeadline_needsClock(
    env: *mut JNIEnv, _class: jclass, token: jlongArray,
) -> jboolean { read(env, token).is_some_and(Deadline::needs_clock) as jboolean }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeControlDeadline_remainingMillis(
    env: *mut JNIEnv, _class: jclass, token: jlongArray, now: jlong,
) -> jlong { read(env, token).map(|state| state.remaining(now)).unwrap_or(0) }

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_an_authenticated_unlimited_drive_stops_using_its_clock() {
        let handshake = Deadline::Budgeted { started: 0, timeout: i64::MAX };
        assert!(handshake.needs_clock());
        assert_eq!(handshake.remaining(59_999_999_999), 1);
        assert_eq!(handshake.remaining(60_000_000_000), 0);
        let drive = handshake.authenticated();
        assert!(!drive.needs_clock());
        assert_eq!(drive.authenticated(), drive);
        for now in [i64::MIN, 0, 25 * 60 * 60 * 1_000_000_000, i64::MAX] {
            assert_eq!(drive.remaining(now), 30_000);
        }
    }

    #[test]
    fn finite_authentication_keeps_the_original_start_and_ceil_to_a_millisecond() {
        let state = Deadline::Budgeted { started: -1_000_000_000, timeout: 100 };
        assert_eq!(state.authenticated(), state);
        assert_eq!(state.remaining(-1_000_000_001), 100);
        assert_eq!(state.remaining(-900_000_001), 1);
        assert_eq!(state.remaining(-900_000_000), 0);
        assert_eq!(Deadline::Budgeted { started: 0, timeout: 86_400_000 }.remaining(0), 30_000);
    }

    #[test]
    fn all_long_bits_and_kotlin_wrapping_arithmetic_are_preserved() {
        let crossing = Deadline::Budgeted { started: i64::MAX - 10, timeout: 1 };
        assert_eq!(crossing.remaining(i64::MIN + 10), 1);
        assert_eq!(crossing.remaining(i64::MIN + 999_989), 0);
        assert_eq!(Deadline::Budgeted { started: 0, timeout: -1 }.remaining(0), 0);
        assert_eq!(Deadline::Budgeted { started: 0, timeout: -1 }.remaining(i64::MAX), -9_223_372_036_854);
        assert_eq!(Deadline::Budgeted { started: 0, timeout: i64::MAX - 1 }.remaining(i64::MAX), 30_000);
        assert_eq!(Deadline::Budgeted { started: 0, timeout: i64::MIN }.remaining(0), 0);
        // A positive remainingNanos can overflow during Kotlin's existing ceiling addition.
        assert_eq!(Deadline::Budgeted { started: 0, timeout: 9_223_372_036_854 }.remaining(0), -9_223_372_036_854);
        for started in [i64::MIN, -1, 0, i64::MAX] {
            for timeout in [i64::MIN, -1, 0, 1, i64::MAX - 1] {
                let state = Deadline::Budgeted { started, timeout };
                assert_eq!(state.authenticated(), state);
            }
        }
    }
}
