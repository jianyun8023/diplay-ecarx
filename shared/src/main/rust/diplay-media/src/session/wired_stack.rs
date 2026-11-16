//! Wired bring-up control policy.
//!
//! The platform performs USBMUX, Lockdown pairing, CarKit, iAP2 CSM and NCM/VPN I/O; this owner
//! decides the saved-pair-record rejection/retry outcome so the platform only executes a named
//! action. The caller classifies its own platform exception into a rejection code (a Java fact).
use jni_sys::{jboolean, jclass, jint, JNIEnv};

/// Platform classification of a CarKit-service open failure.
pub const REJECTION_NONE: i32 = 0;
pub const REJECTION_INVALID_PAIR_RECORD: i32 = 1;
pub const REJECTION_INVALID_HOST_ID: i32 = 2;

/// End state of the wired control loop, mirrored from `Iap2WiredControlTerminal` (its ordinal order).
pub const TERMINAL_CHANNEL_CLOSED: i32 = 0;
pub const TERMINAL_TIMED_OUT: i32 = 1;

/// 1 = clear the saved record and pair again; 0 = rethrow the original failure. Only a saved record
/// rejected as invalid (pair record or host id) is retried; a fresh pairing or any other failure is
/// not, so a rejected saved record never loops.
pub fn carkit_error_action(has_saved: bool, rejection: i32) -> i32 {
    let rejected = matches!(rejection, REJECTION_INVALID_PAIR_RECORD | REJECTION_INVALID_HOST_ID);
    (has_saved && rejected) as i32
}

/// 1 = the control channel failed; 0 = it ended normally after a timeout. A closed channel is a
/// failure the driver sees; a timed-out control loop is a normal end, not an NCM/AirPlay verdict.
pub fn terminal_outcome(terminal: i32) -> i32 {
    (terminal == TERMINAL_CHANNEL_CLOSED) as i32
}

#[no_mangle]
pub extern "system" fn Java_com_shihab_diplay_orchestration_NativeWiredStack_carkitErrorAction(
    _: *mut JNIEnv, _: jclass, has_saved: jboolean, rejection: jint,
) -> jint {
    carkit_error_action(has_saved != 0, rejection)
}

#[no_mangle]
pub extern "system" fn Java_com_shihab_diplay_orchestration_NativeWiredStack_terminalOutcome(
    _: *mut JNIEnv, _: jclass, terminal: jint,
) -> jint {
    terminal_outcome(terminal)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_a_saved_record_rejected_as_invalid_pair_or_host_id_retries() {
        for (saved, rejection, expected) in [
            (true, REJECTION_INVALID_PAIR_RECORD, 1),
            (true, REJECTION_INVALID_HOST_ID, 1),
            (true, REJECTION_NONE, 0),
            (true, 99, 0),
            (false, REJECTION_INVALID_PAIR_RECORD, 0),
            (false, REJECTION_NONE, 0),
        ] {
            assert_eq!(carkit_error_action(saved, rejection), expected);
        }
    }

    #[test]
    fn a_closed_channel_is_a_failure_but_a_timeout_is_a_normal_end() {
        assert_eq!(terminal_outcome(TERMINAL_CHANNEL_CLOSED), 1);
        assert_eq!(terminal_outcome(TERMINAL_TIMED_OUT), 0);
        assert_eq!(terminal_outcome(99), 0);
    }
}
