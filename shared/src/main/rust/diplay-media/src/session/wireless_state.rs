//! One wireless run's lifecycle and transport handoff. The token is a value, not a pointer:
//! closed runs still accept their first late failure without retaining native heap state.

use jni_sys::{jclass, jint, jlong, JNIEnv};

#[derive(Clone, Copy, Default, PartialEq, Debug)]
#[repr(u8)]
enum Lifecycle { #[default] Running, Retired, Closing, Closed }

#[derive(Clone, Copy, Default, PartialEq, Debug)]
#[repr(u8)]
enum Handoff { #[default] Bootstrap, Pending, BluetoothFallback, TunnelActive }

#[derive(Default)]
struct RunState {
    lifecycle: Lifecycle,
    handoff: Handoff,
    tunnel_ready: bool,
    failed_once: bool,
    signals: u16,
}

enum Event { Request, TunnelReady, Promote, Fallback, Fail, Retire, BeginClose, FinishClose,
    BootstrapDispatched, TunnelStart, StableFrame, Reachable, FirstTcpSample }

impl RunState {
    fn transition(&mut self, event: Event) -> bool {
        let running = self.lifecycle == Lifecycle::Running;
        match event {
            Event::Request if running && self.handoff == Handoff::Bootstrap => self.handoff = Handoff::Pending,
            Event::TunnelReady if running => self.tunnel_ready = true,
            Event::Promote if running && self.tunnel_ready && matches!(self.handoff, Handoff::Pending | Handoff::BluetoothFallback) => {
                self.handoff = Handoff::TunnelActive;
            }
            Event::Fallback if running && self.handoff == Handoff::Pending && !self.tunnel_ready => {
                self.handoff = Handoff::BluetoothFallback;
            }
            Event::Fail if !self.failed_once => {
                self.failed_once = true;
                if running { self.lifecycle = Lifecycle::Retired; }
            }
            Event::Retire => if running { self.lifecycle = Lifecycle::Retired; },
            Event::BeginClose if !matches!(self.lifecycle, Lifecycle::Closing | Lifecycle::Closed) => self.lifecycle = Lifecycle::Closing,
            Event::FinishClose if self.lifecycle == Lifecycle::Closing => self.lifecycle = Lifecycle::Closed,
            Event::BootstrapDispatched => self.signals |= 0x40,
            Event::TunnelStart if self.signals & 0x80 == 0 => self.signals |= 0x80,
            Event::StableFrame if self.signals & 0x200 == 0 => self.signals |= 0x200,
            Event::Reachable => self.signals |= 0x400,
            Event::FirstTcpSample if self.signals & 0x800 == 0 => self.signals |= 0x800,
            _ => return false,
        }
        true
    }

    fn encode(&self, accepted: bool) -> i64 {
        self.lifecycle as i64 | ((self.handoff as i64) << 2) | ((self.tunnel_ready as i64) << 4) |
            ((self.failed_once as i64) << 5) | self.signals as i64 | ((accepted as i64) << 8)
    }

    fn decode(token: i64) -> Option<Self> {
        if token < 0 || token & !0xfff != 0 { return None; }
        Some(Self {
            lifecycle: match token & 3 { 0 => Lifecycle::Running, 1 => Lifecycle::Retired, 2 => Lifecycle::Closing, _ => Lifecycle::Closed },
            handoff: match (token >> 2) & 3 { 0 => Handoff::Bootstrap, 1 => Handoff::Pending, 2 => Handoff::BluetoothFallback, _ => Handoff::TunnelActive },
            tunnel_ready: token & 0x10 != 0, failed_once: token & 0x20 != 0,
            signals: (token & 0xec0) as u16,
        })
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_wirelessRunCreate(
    _env: *mut JNIEnv, _class: jclass,
) -> jlong { RunState::default().encode(false) }

/// Cross-owner platform facts are typed by Controller's JVM adapter; lifecycle stays in this token.
pub(super) fn decision(token: i64, operation: i32, facts: i32) -> i32 {
    let Some(state) = RunState::decode(token) else { return -1; };
    let running = state.lifecycle == Lifecycle::Running;
    let requested = state.handoff != Handoff::Bootstrap;
    let active = matches!(state.handoff, Handoff::BluetoothFallback | Handoff::TunnelActive);
    match operation {
        0 => (running && requested && !active) as i32,
        1 => {
            if !running || !requested || active || state.tunnel_ready { 0 }
            else if facts & 3 == 3 { 1 } else { 2 }
        }
        2 => (running && state.signals & 0x40 == 0 && !active && !requested && facts & 7 == 1) as i32,
        3 => (running && facts & 3 == 3) as i32,
        4 => (running && facts & 1 != 0) as i32,
        5 => (state.signals & 0x40 != 0 || active || requested || facts & 3 != 0) as i32,
        _ => -1,
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_wirelessRunQuery(
    _env: *mut JNIEnv, _class: jclass, token: jlong, field: jint,
) -> jint {
    let Some(state) = RunState::decode(token) else { return -1; };
    match field {
        0 => state.lifecycle as i32,
        1 => state.handoff as i32,
        2 => state.tunnel_ready as i32,
        3 => state.failed_once as i32,
        4 => (state.lifecycle == Lifecycle::Running) as i32,
        5 => (state.handoff != Handoff::Bootstrap) as i32,
        6 => matches!(state.handoff, Handoff::BluetoothFallback | Handoff::TunnelActive) as i32,
        7 => (token & 0x100 != 0) as i32,
        8 => (state.signals & 0x40 != 0) as i32,
        9 => (state.signals & 0x80 != 0) as i32,
        10 => (state.signals & 0x200 != 0) as i32,
        11 => (state.signals & 0x400 != 0) as i32,
        12 => (state.signals & 0x800 != 0) as i32,
        _ => -1,
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_wirelessRunTransition(
    _env: *mut JNIEnv, _class: jclass, token: jlong, event: jint,
) -> jlong {
    let Some(mut state) = RunState::decode(token) else { return -1; };
    let event = match event {
        1 => Event::Request, 2 => Event::TunnelReady, 3 => Event::Promote, 4 => Event::Fallback,
        5 => Event::Fail, 6 => Event::Retire, 7 => Event::BeginClose, 8 => Event::FinishClose,
        9 => Event::BootstrapDispatched, 10 => Event::TunnelStart, 11 => Event::StableFrame,
        12 => Event::Reachable, 13 => Event::FirstTcpSample,
        _ => return -1,
    };
    let accepted = state.transition(event);
    state.encode(accepted)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tunnel_readiness_and_handoff_order_promote_once() {
        for tunnel_first in [false, true] {
            let mut state = RunState::default();
            if tunnel_first {
                assert!(state.transition(Event::TunnelReady));
                assert!(!state.transition(Event::Promote));
                assert!(state.transition(Event::Request));
            } else {
                assert!(state.transition(Event::Request));
                assert!(!state.transition(Event::Promote));
                assert!(state.transition(Event::TunnelReady));
            }
            assert!(state.transition(Event::Promote));
            assert!(!state.transition(Event::Promote));
            assert!(!state.transition(Event::Request));
            assert!(!state.transition(Event::Fallback));
            assert_eq!(state.handoff, Handoff::TunnelActive);
        }
    }

    #[test]
    fn bluetooth_fallback_is_preserved_until_the_tunnel_really_takes_over() {
        let mut state = RunState::default();
        assert!(!state.transition(Event::Fallback));
        assert!(state.transition(Event::Request));
        assert!(state.transition(Event::Fallback));
        assert_eq!(state.handoff, Handoff::BluetoothFallback);
        assert!(!state.transition(Event::Fallback));
        assert!(state.transition(Event::TunnelReady));
        assert!(state.transition(Event::Promote));
        assert_eq!(state.handoff, Handoff::TunnelActive);
    }

    #[test]
    fn retiring_and_closing_do_not_consume_the_first_shared_service_failure() {
        for lifecycle in [Lifecycle::Running, Lifecycle::Retired, Lifecycle::Closing, Lifecycle::Closed] {
            let mut state = RunState { lifecycle, ..Default::default() };
            assert!(state.transition(Event::Fail));
            assert_eq!(state.lifecycle, if lifecycle == Lifecycle::Running { Lifecycle::Retired } else { lifecycle });
            assert!(!state.transition(Event::Fail));
            assert!(!state.transition(Event::Request));
            assert!(!state.transition(Event::TunnelReady));
            assert!(!state.transition(Event::Promote));
            assert!(!state.transition(Event::Fallback));
        }
    }

    #[test]
    fn close_is_ordered_and_terminal_without_a_resource_handle() {
        let mut state = RunState::default();
        assert!(!state.transition(Event::FinishClose));
        assert!(state.transition(Event::BeginClose));
        assert!(!state.transition(Event::BeginClose));
        assert!(state.transition(Event::FinishClose));
        let mut restored = RunState::decode(state.encode(true)).unwrap();
        assert!(restored.transition(Event::Fail));
        assert!(restored.transition(Event::Retire));
        assert_eq!(restored.lifecycle, Lifecycle::Closed);
        assert!(!restored.transition(Event::BeginClose));
        assert!(!restored.transition(Event::FinishClose));
    }

    #[test]
    fn the_primitive_jni_contract_rejects_invalid_tokens_and_preserves_every_snapshot() {
        unsafe {
            let env = std::ptr::null_mut();
            let class = std::ptr::null_mut();
            assert_eq!(Java_com_shihab_diplay_airplay_NativeMedia_wirelessRunCreate(env, class), 0);
            for bits in 0..64 {
                for accepted in [0, 0x100] {
                    let token = bits | accepted;
                    assert_eq!(RunState::decode(token).unwrap().encode(accepted != 0), token);
                    for field in 0..8 { assert!(Java_com_shihab_diplay_airplay_NativeMedia_wirelessRunQuery(env, class, token, field) >= 0); }
                    for event in 1..9 { assert!(Java_com_shihab_diplay_airplay_NativeMedia_wirelessRunTransition(env, class, token, event) >= 0); }
                }
            }
            for token in [-1, 0x1000, 0x2000, i64::MAX] {
                assert_eq!(Java_com_shihab_diplay_airplay_NativeMedia_wirelessRunQuery(env, class, token, 0), -1);
                assert_eq!(Java_com_shihab_diplay_airplay_NativeMedia_wirelessRunTransition(env, class, token, 1), -1);
            }
            assert_eq!(Java_com_shihab_diplay_airplay_NativeMedia_wirelessRunQuery(env, class, 0, 13), -1);
            assert_eq!(Java_com_shihab_diplay_airplay_NativeMedia_wirelessRunTransition(env, class, 0, 0), -1);
        }
    }
}
