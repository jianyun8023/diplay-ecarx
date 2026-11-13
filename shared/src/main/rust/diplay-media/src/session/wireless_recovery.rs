//! One wireless bootstrap run's retry budgets and its attempts. Tokens are values, not handles.
//! Android supplies cause-chain type facts and executes validation, sockets, logging and sleeps.
use jni_sys::{jclass, jint, jlong, JNIEnv};

#[derive(Clone, Copy, Default, Debug, PartialEq)]
struct Budget { flags: u8, number: u8, action: u8, remaining: u16 }

impl Budget {
    fn decode(token: i64) -> Option<Self> {
        if token < 0 || token & !0x3ff737 != 0 || ((token >> 8) & 7) > 4 || token >> 12 > 800 { return None; }
        Some(Self { flags: (token & 7) as u8, number: ((token >> 8) & 7) as u8,
            action: ((token >> 4) & 3) as u8, remaining: (token >> 12) as u16 })
    }
    fn encode(self) -> i64 { self.flags as i64 | ((self.action as i64) << 4) | ((self.number as i64) << 8) | ((self.remaining as i64) << 12) }
    fn advance(&mut self, stage: i32) -> bool {
        if stage == -2 { self.remaining = self.remaining.saturating_sub(100); return true; }
        self.action = 0;
        match stage {
            -1 if self.number < 4 => self.number += 1,
            0 if self.flags & 1 == 0 => { self.flags |= 1; self.action = 1; }
            0 if self.flags & 4 == 0 => { self.flags |= 4; self.action = 2; }
            1 | 2 if self.flags & 2 == 0 => { self.flags |= 2; self.action = 3; }
            0..=3 => {}
            _ => return false,
        }
        self.remaining = match self.action { 1 => 400, 3 => 800, _ => 0 };
        true
    }
    fn query(self, field: i32) -> i32 {
        match field {
            0 => self.number as i32, 1 => self.action as i32, 2 => (self.flags & 4 != 0) as i32,
            3 => match self.action { 1 => 400, 3 => 800, _ => 0 },
            4 => self.remaining.min(100) as i32,
            _ => -1,
        }
    }
}

#[derive(Clone, Copy)]
struct Attempt { valid: bool, stage: u8, timed_out: bool, timeout_link: bool }
impl Attempt {
    fn decode(token: i64) -> Option<Self> {
        if !(0..=31).contains(&token) { return None; }
        Some(Self { valid: token & 1 != 0, stage: ((token >> 1) & 3) as u8,
            timed_out: token & 8 != 0, timeout_link: token & 16 != 0 })
    }
    fn encode(self) -> i64 { self.valid as i64 | ((self.stage as i64) << 1) |
        ((self.timed_out as i64) << 3) | ((self.timeout_link as i64) << 4) }
    fn update(&mut self, event: i32, phase: i32) -> bool {
        match event {
            0 => self.valid = false,
            // Iap2BootstrapPhase.RFCOMM_CONNECTED is the only Bluetooth-link stall.
            1 if self.valid => { self.valid = false; self.timed_out = true; self.timeout_link = phase == 0; }
            1 => {},
            2..=5 => self.stage = (event - 2) as u8,
            _ => return false,
        }
        true
    }
    fn query(self, field: i32) -> i32 { match field { 0 => self.valid as i32, 1 => self.stage as i32, _ => -1 } }
    fn failure(self, facts: i32) -> i32 {
        if facts & (TOP_ERROR | TOP_BOOTSTRAP) != 0 { return 0; }
        let classification = classify(facts);
        if self.timed_out {
            return if classification & 1 != 0 || facts & TOP_CANCEL == 0 && classification & 2 == 0 { 0 }
                else if self.timeout_link { 4 } else { 1 };
        }
        if classification & 2 == 0 { return 0; }
        match self.stage { 1 => 2, 2 => 3, _ => 0 }
    }
}

const TOP_ERROR: i32 = 1;
const TOP_BOOTSTRAP: i32 = 1 << 1;
const TOP_CANCEL: i32 = 1 << 2;
const TOP_IO: i32 = 1 << 3;
const SECURITY: i32 = 1 << 4;
const MFI_REJECTED: i32 = 1 << 5;
const RADIO_OFF: i32 = 1 << 6;
const IDENTIFICATION: i32 = 1 << 7;
const MFI_TERMINAL: i32 = 1 << 8;
const NO_PAIRED: i32 = 1 << 9;
const ANY_ERROR: i32 = 1 << 10;
const ARGUMENT: i32 = 1 << 11;
const STATE: i32 = 1 << 12;
const INTERRUPTED: i32 = 1 << 13;
const INTERRUPTED_IO: i32 = 1 << 14;
const CANCEL: i32 = 1 << 15;
const STARTUP: i32 = 1 << 16;
const CONNECTION: i32 = 1 << 17;
const MFI_RETRY: i32 = 1 << 18;
const MFI: i32 = 1 << 19;
const CHOSEN_UNPAIRED: i32 = 1 << 20;
const NOT_CHOSEN: i32 = 1 << 21;
const UNAVAILABLE: i32 = 1 << 22;

fn classify(facts: i32) -> i32 {
    if facts & (SECURITY | MFI_REJECTED | RADIO_OFF | IDENTIFICATION | MFI_TERMINAL | NO_PAIRED |
        CHOSEN_UNPAIRED | NOT_CHOSEN | UNAVAILABLE) != 0 { return 1; }
    if facts & (ANY_ERROR | SECURITY | ARGUMENT | STATE | INTERRUPTED | INTERRUPTED_IO | CANCEL |
        STARTUP | CONNECTION | IDENTIFICATION) != 0 { return 0; }
    if facts & MFI_RETRY != 0 { return 2; }
    if facts & MFI != 0 { return 0; }
    if facts & TOP_IO != 0 { 2 } else { 0 }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_orchestration_NativeWirelessRecovery_budgetCreate(
    _env: *mut JNIEnv, _class: jclass,
) -> jlong { Budget::default().encode() }
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_orchestration_NativeWirelessRecovery_budgetAdvance(
    _env: *mut JNIEnv, _class: jclass, token: jlong, stage: jint,
) -> jlong {
    let Some(mut budget) = Budget::decode(token) else { return -1; };
    if budget.advance(stage) { budget.encode() } else { -1 }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_orchestration_NativeWirelessRecovery_budgetQuery(
    _env: *mut JNIEnv, _class: jclass, token: jlong, field: jint,
) -> jint { Budget::decode(token).map(|budget| budget.query(field)).unwrap_or(-1) }
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_orchestration_NativeWirelessRecovery_attemptCreate(
    _env: *mut JNIEnv, _class: jclass,
) -> jlong { Attempt { valid: true, stage: 0, timed_out: false, timeout_link: false }.encode() }
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_orchestration_NativeWirelessRecovery_attemptUpdate(
    _env: *mut JNIEnv, _class: jclass, token: jlong, event: jint, phase: jint,
) -> jlong {
    let Some(mut attempt) = Attempt::decode(token) else { return -1; };
    if attempt.update(event, phase) { attempt.encode() } else { -1 }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_orchestration_NativeWirelessRecovery_attemptQuery(
    _env: *mut JNIEnv, _class: jclass, token: jlong, field: jint,
) -> jint { Attempt::decode(token).map(|attempt| attempt.query(field)).unwrap_or(-1) }
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_orchestration_NativeWirelessRecovery_classify(
    _env: *mut JNIEnv, _class: jclass, facts: jint,
) -> jint { classify(facts) }
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_orchestration_NativeWirelessRecovery_attemptFailure(
    _env: *mut JNIEnv, _class: jclass, token: jlong, facts: jint,
) -> jint { Attempt::decode(token).map(|attempt| attempt.failure(facts)).unwrap_or(-1) }

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn budgets_are_run_scoped_and_do_not_reset_when_the_target_changes() {
        let mut budget = Budget::default();
        for (stage, action, delay, fallback) in [(0, 1, 400, 0), (1, 3, 800, 0), (0, 2, 0, 1), (2, 0, 0, 1)] {
            assert!(budget.advance(-1));
            assert!(budget.advance(stage));
            assert_eq!((budget.query(1), budget.query(3), budget.query(2)), (action, delay, fallback));
            budget = Budget::decode(budget.encode()).unwrap();
        }
        assert_eq!(budget.query(0), 4);
        assert!(!budget.advance(-1));
        for stage in 0..4 { budget.advance(stage); assert_eq!(budget.action, 0); }
    }

    #[test]
    fn csm_and_bootstrap_share_one_retry_and_control_never_consumes_a_budget() {
        for stages in [[1, 2], [2, 1]] {
            let mut budget = Budget::default();
            assert!(budget.advance(3)); assert_eq!(budget, Budget::default());
            budget.advance(stages[0]); assert_eq!(budget.query(3), 800);
            budget.advance(stages[1]); assert_eq!(budget.action, 0);
            budget.advance(0); assert_eq!(budget.query(3), 400);
            budget.advance(0); assert_eq!(budget.action, 2);
            budget.advance(0); assert_eq!(budget.action, 0);
        }
    }

    #[test]
    fn invalidation_and_timeout_cannot_restore_an_attempt() {
        for stage in 0..4 {
            let mut attempt = Attempt { valid: true, stage, timed_out: false, timeout_link: false };
            assert!(attempt.update(1, 1));
            assert!(!attempt.valid); assert!(attempt.timed_out);
            attempt.update(0, 0); attempt.update(1, 1); attempt.update(5, 0);
            assert!(!attempt.valid); assert!(attempt.timed_out);
            let mut invalidated = Attempt { valid: false, stage, timed_out: false, timeout_link: false };
            invalidated.update(1, 1);
            assert!(!invalidated.timed_out);
        }
    }

    #[test]
    fn terminal_prerequisites_and_interruptions_take_precedence_over_mfi_timeouts() {
        let user_bits = [SECURITY, MFI_REJECTED, RADIO_OFF, IDENTIFICATION, MFI_TERMINAL,
            NO_PAIRED, CHOSEN_UNPAIRED, NOT_CHOSEN, UNAVAILABLE];
        for bit in user_bits { assert_eq!(classify(TOP_IO | MFI_RETRY | bit), 1); }
        for bit in [ANY_ERROR, ARGUMENT, STATE, INTERRUPTED, INTERRUPTED_IO, CANCEL, STARTUP, CONNECTION] {
            assert_eq!(classify(TOP_IO | MFI_RETRY | bit), 0);
        }
        assert_eq!(classify(MFI_RETRY | MFI), 2);
        assert_eq!(classify(MFI | TOP_IO), 0);
        assert_eq!(classify(TOP_IO), 2);
        assert_eq!(classify(0), 0);
    }

    #[test]
    fn timeout_eof_and_cancellation_use_the_stall_but_never_hide_a_terminal_failure() {
        for stage in 0..4 {
            let plain = Attempt { valid: true, stage, timed_out: false, timeout_link: false };
            assert_eq!(plain.failure(TOP_IO), match stage { 1 => 2, 2 => 3, _ => 0 });
            let timeout = Attempt { valid: false, timed_out: true, ..plain };
            assert_eq!(timeout.failure(TOP_IO), 1);
            assert_eq!(timeout.failure(TOP_CANCEL | CANCEL | STATE), 1);
            let mut link_timeout = plain;
            link_timeout.update(1, 0);
            assert_eq!(link_timeout.failure(TOP_IO), 4);
            for facts in [TOP_ERROR, TOP_BOOTSTRAP | TOP_IO, TOP_IO | SECURITY, TOP_IO | MFI_TERMINAL,
                TOP_CANCEL | CANCEL | SECURITY, TOP_IO | INTERRUPTED_IO, TOP_IO | STATE] {
                assert_eq!(timeout.failure(facts), 0, "facts={facts}");
            }
            assert_eq!(plain.failure(MFI_RETRY), plain.failure(TOP_IO));
        }
    }

    #[test]
    fn primitive_jni_tokens_reject_invalid_values_without_retaining_native_allocations() {
        unsafe {
            let env = std::ptr::null_mut(); let class = std::ptr::null_mut();
            assert_eq!(Java_com_shihab_diplay_orchestration_NativeWirelessRecovery_budgetCreate(env, class), 0);
            assert_eq!(Java_com_shihab_diplay_orchestration_NativeWirelessRecovery_attemptCreate(env, class), 1);
            for token in 0..32 {
                assert_eq!(Attempt::decode(token).unwrap().encode(), token);
                for event in 0..6 {
                    assert!((0..32).contains(&Java_com_shihab_diplay_orchestration_NativeWirelessRecovery_attemptUpdate(env, class, token, event, 0)));
                }
            }
            for number in 0..5 { for flags in 0..8 { for action in 0..4 {
                let token = number << 8 | action << 4 | flags;
                assert_eq!(Budget::decode(token).unwrap().encode(), token);
                for field in 0..4 { assert!(Java_com_shihab_diplay_orchestration_NativeWirelessRecovery_budgetQuery(env, class, token, field) >= 0); }
            } } }
            for token in [-1, 32, i64::MAX] {
                assert_eq!(Java_com_shihab_diplay_orchestration_NativeWirelessRecovery_attemptUpdate(env, class, token, 0, 0), -1);
                assert_eq!(Java_com_shihab_diplay_orchestration_NativeWirelessRecovery_attemptFailure(env, class, token, 0), -1);
            }
            for token in [-1, 8, 64, 2048, i64::MAX] {
                assert_eq!(Java_com_shihab_diplay_orchestration_NativeWirelessRecovery_budgetAdvance(env, class, token, 0), -1);
            }
            assert_eq!(Java_com_shihab_diplay_orchestration_NativeWirelessRecovery_budgetQuery(env, class, 0, 5), -1);
            assert_eq!(Java_com_shihab_diplay_orchestration_NativeWirelessRecovery_attemptQuery(env, class, 1, 2), -1);
        }
    }
}
