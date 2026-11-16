//! Wired bring-up control policy.
//!
//! The platform performs USBMUX, Lockdown pairing, CarKit, iAP2 CSM and NCM/VPN I/O; this owner
//! decides the saved-pair-record rejection/retry outcome so the platform only executes a named
//! action. The caller classifies its own platform exception into a rejection code (a Java fact).
use jni_sys::{jboolean, jclass, jint, jlong, JNIEnv};

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

// ---- Wired bring-up stage machine ----
//
// The platform executes one I/O block per action; the core owns the order and the three abort
// branches. Kotlin starts at BRING_UP_BEGIN and calls `advance(action, approved)` after each block,
// where `approved` is the gate fact it reported (begin / adopt / CSM / VPN). Codes are shared with
// NativeWiredStack.kt.
pub const BRING_UP_BEGIN: i32 = 0;
pub const BRING_UP_OPEN_MUX: i32 = 1;
pub const BRING_UP_ADOPT_MUX: i32 = 2;
pub const BRING_UP_LOAD_PAIR: i32 = 3;
pub const BRING_UP_OPEN_CARKIT: i32 = 4;
pub const BRING_UP_OPEN_CSM: i32 = 5;
pub const BRING_UP_ATTACH_VPN: i32 = 6;
pub const BRING_UP_BUILD_ENDPOINT: i32 = 7;
pub const BRING_UP_RUN_CONTROL: i32 = 8;
pub const BRING_UP_DONE: i32 = 9;
pub const BRING_UP_ABORT_CLOSE_MUX: i32 = 10;
pub const BRING_UP_ABORT_CLOSE_CSM: i32 = 11;
pub const BRING_UP_ABORT_DETACH_VPN: i32 = 12;

/// The wired bring-up order — USBMUX, pairing record, CarKit, iAP2 CSM, NCM/VPN, endpoint, control —
/// and its abort branches live here; the platform only executes each emitted action.
pub fn advance(action: i32, approved: i32) -> i32 {
    let ok = approved != 0;
    match action {
        BRING_UP_BEGIN => if ok { BRING_UP_OPEN_MUX } else { BRING_UP_DONE },
        BRING_UP_OPEN_MUX => BRING_UP_ADOPT_MUX,
        BRING_UP_ADOPT_MUX => if ok { BRING_UP_LOAD_PAIR } else { BRING_UP_ABORT_CLOSE_MUX },
        BRING_UP_LOAD_PAIR => BRING_UP_OPEN_CARKIT,
        BRING_UP_OPEN_CARKIT => BRING_UP_OPEN_CSM,
        BRING_UP_OPEN_CSM => if ok { BRING_UP_ATTACH_VPN } else { BRING_UP_ABORT_CLOSE_CSM },
        BRING_UP_ATTACH_VPN => if ok { BRING_UP_BUILD_ENDPOINT } else { BRING_UP_ABORT_DETACH_VPN },
        BRING_UP_BUILD_ENDPOINT => BRING_UP_RUN_CONTROL,
        BRING_UP_RUN_CONTROL => BRING_UP_DONE,
        _ => BRING_UP_DONE,
    }
}

// ---- Wired teardown / release plan ----
//
// The platform owns the actual Closeable/socket objects; the core owns which resources to release,
// in what order, and the NCM-close vs VPN-detach branch, for the exception and finally paths. Kotlin
// iterates the packed plan and calls the existing close/detach statements verbatim.
pub const TEARDOWN_CATCH: i32 = 0;
pub const TEARDOWN_FINALLY: i32 = 1;

pub const TD_CLOSE_CSM: i32 = 0;
pub const TD_CLOSE_MUX: i32 = 1;
pub const TD_CLOSE_NCM_CATCH: i32 = 2;
pub const TD_DETACH_VPN: i32 = 3;
pub const TD_TERMINATE_WATCHDOG: i32 = 4;
pub const TD_CLOSE_OWNED_IF_STALE: i32 = 5;
pub const TD_CLOSE_USB_SESSION: i32 = 6;
pub const TD_CLOSE_NCM_FINALLY: i32 = 7;

// Fact bits: 1 = stale run (not current), 2 = NCM owned locally, 4 = USB session owned locally.
pub fn teardown_plan(phase: i32, facts: i32) -> Vec<i32> {
    let stale = facts & 1 != 0;
    let ncm_owned = facts & 2 != 0;
    let usb_owned = facts & 4 != 0;
    match phase {
        TEARDOWN_CATCH => {
            let mut plan = vec![TD_CLOSE_CSM, TD_CLOSE_MUX];
            plan.push(if ncm_owned { TD_CLOSE_NCM_CATCH } else { TD_DETACH_VPN });
            plan
        }
        TEARDOWN_FINALLY => {
            let mut plan = vec![TD_TERMINATE_WATCHDOG];
            if stale {
                plan.push(TD_CLOSE_OWNED_IF_STALE);
            }
            if usb_owned {
                plan.push(TD_CLOSE_USB_SESSION);
            }
            if ncm_owned {
                plan.push(TD_CLOSE_NCM_FINALLY);
            }
            plan
        }
        _ => Vec::new(),
    }
}

/// Packs the ordered plan as nibbles (action+1, 0 = end): at most four actions fit in a jlong.
pub fn teardown_plan_packed(phase: i32, facts: i32) -> i64 {
    let mut packed: i64 = 0;
    for (i, action) in teardown_plan(phase, facts).into_iter().enumerate().take(15) {
        packed |= ((action as i64) + 1) << (4 * i as i64);
    }
    packed
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

#[no_mangle]
pub extern "system" fn Java_com_shihab_diplay_orchestration_NativeWiredStack_advance(
    _: *mut JNIEnv, _: jclass, action: jint, approved: jint,
) -> jint {
    advance(action, approved)
}

#[no_mangle]
pub extern "system" fn Java_com_shihab_diplay_orchestration_NativeWiredStack_teardownPlan(
    _: *mut JNIEnv, _: jclass, phase: jint, facts: jint,
) -> jlong {
    teardown_plan_packed(phase, facts)
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

    #[test]
    fn bring_up_order_and_abort_branches_are_the_cores_decision() {
        assert_eq!(advance(BRING_UP_BEGIN, 1), BRING_UP_OPEN_MUX);
        assert_eq!(advance(BRING_UP_OPEN_MUX, 0), BRING_UP_ADOPT_MUX);
        assert_eq!(advance(BRING_UP_ADOPT_MUX, 1), BRING_UP_LOAD_PAIR);
        assert_eq!(advance(BRING_UP_LOAD_PAIR, 0), BRING_UP_OPEN_CARKIT);
        assert_eq!(advance(BRING_UP_OPEN_CARKIT, 0), BRING_UP_OPEN_CSM);
        assert_eq!(advance(BRING_UP_OPEN_CSM, 1), BRING_UP_ATTACH_VPN);
        assert_eq!(advance(BRING_UP_ATTACH_VPN, 1), BRING_UP_BUILD_ENDPOINT);
        assert_eq!(advance(BRING_UP_BUILD_ENDPOINT, 0), BRING_UP_RUN_CONTROL);
        assert_eq!(advance(BRING_UP_RUN_CONTROL, 0), BRING_UP_DONE);
        // A rejected gate aborts with the matching cleanup action.
        assert_eq!(advance(BRING_UP_BEGIN, 0), BRING_UP_DONE);
        assert_eq!(advance(BRING_UP_ADOPT_MUX, 0), BRING_UP_ABORT_CLOSE_MUX);
        assert_eq!(advance(BRING_UP_OPEN_CSM, 0), BRING_UP_ABORT_CLOSE_CSM);
        assert_eq!(advance(BRING_UP_ATTACH_VPN, 0), BRING_UP_ABORT_DETACH_VPN);
        // Unknown actions are inert and stop the loop.
        assert_eq!(advance(999, 1), BRING_UP_DONE);
    }

    #[test]
    fn teardown_plan_orders_release_and_reflects_ownership_facts() {
        // catch: CSM then MUX, then NCM close when NCM is owned...
        assert_eq!(
            teardown_plan(TEARDOWN_CATCH, 2),
            vec![TD_CLOSE_CSM, TD_CLOSE_MUX, TD_CLOSE_NCM_CATCH]
        );
        // ...otherwise detach VPN.
        assert_eq!(
            teardown_plan(TEARDOWN_CATCH, 0),
            vec![TD_CLOSE_CSM, TD_CLOSE_MUX, TD_DETACH_VPN]
        );
        // finally with nothing owned and a current run: only terminate the watchdog.
        assert_eq!(teardown_plan(TEARDOWN_FINALLY, 0), vec![TD_TERMINATE_WATCHDOG]);
        // finally stale + USB + NCM owned: ordered plan in the documented order.
        assert_eq!(
            teardown_plan(TEARDOWN_FINALLY, 1 | 4 | 2),
            vec![
                TD_TERMINATE_WATCHDOG,
                TD_CLOSE_OWNED_IF_STALE,
                TD_CLOSE_USB_SESSION,
                TD_CLOSE_NCM_FINALLY,
            ]
        );
        // packed nibbles preserve order and count.
        assert_eq!(
            teardown_plan_packed(TEARDOWN_CATCH, 0),
            (TD_CLOSE_CSM as i64 + 1)
                | ((TD_CLOSE_MUX as i64 + 1) << 4)
                | ((TD_DETACH_VPN as i64 + 1) << 8)
        );
    }
}
