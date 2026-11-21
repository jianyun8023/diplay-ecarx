//! Decode-chain policy shared with the Android SDK adapter. The media queue, its backlog and
//! reference chains live in the native vpipe; Kotlin only carries control jobs in a mailbox.
mod jni;
use std::sync::atomic::{AtomicI64, Ordering};

static PENDING_TOUCH_NS: AtomicI64 = AtomicI64::new(0);
pub fn touch_sent(sent_at: i64) {
    let _ = PENDING_TOUCH_NS.compare_exchange(0, sent_at, Ordering::Relaxed, Ordering::Relaxed);
}
pub fn touch_latency(now: i64) -> Option<i64> {
    let sent = PENDING_TOUCH_NS.swap(0, Ordering::Relaxed);
    (sent != 0).then_some(now.wrapping_sub(sent))
}
pub fn take_stalled_touch(now: i64, timeout_ns: i64) -> bool {
    let sent = PENDING_TOUCH_NS.load(Ordering::Relaxed);
    if sent == 0 || now.wrapping_sub(sent) < timeout_ns {
        return false;
    }
    PENDING_TOUCH_NS
        .compare_exchange(sent, 0, Ordering::Relaxed, Ordering::Relaxed)
        .is_ok()
}
#[allow(clippy::too_many_arguments)]
pub fn idle_keyframe_due(
    accepting: bool,
    has_codec: bool,
    retired: bool,
    pending_configs: usize,
    queued_frames: usize,
    first_frame_sent: bool,
    last_activity_ns: i64,
    now: i64,
    timeout_ns: i64,
) -> bool {
    accepting
        && has_codec
        && !retired
        && pending_configs == 0
        && queued_frames == 0
        && first_frame_sent
        && last_activity_ns > 0
        && now.wrapping_sub(last_activity_ns) >= timeout_ns
}
pub fn keyframe_due(last: i64, now: i64) -> bool {
    last == 0 || now.wrapping_sub(last) >= 1_000_000_000
}
pub fn keeps_unused_decoder(requested: bool, has_codec: bool, queued_frames: u32) -> bool {
    requested && has_codec && queued_frames == 0
}
pub fn keyframe_request(last: &mut i64, now: i64, force: bool) -> bool {
    if !force && !keyframe_due(*last, now) {
        return false;
    }
    *last = now;
    true
}

#[derive(Default)]
pub struct ConfigurationMemory {
    value: Option<(u8, Vec<u8>)>,
    duplicate_logged: bool,
}
impl ConfigurationMemory {
    pub fn remember(&mut self, codec: u8, data: Vec<u8>, has_codec: bool) -> i32 {
        if has_codec
            && self
                .value
                .as_ref()
                .is_some_and(|(previous, bytes)| *previous == codec && *bytes == data)
        {
            if self.duplicate_logged {
                return 2;
            }
            self.duplicate_logged = true;
            return 1;
        }
        self.value = Some((codec, data));
        self.duplicate_logged = false;
        0
    }
    pub fn encoded(&self) -> Option<Vec<u8>> {
        self.value.as_ref().map(|(codec, data)| {
            let mut encoded = Vec::with_capacity(data.len() + 1);
            encoded.push(*codec);
            encoded.extend(data);
            encoded
        })
    }
}

// The SDK adapter executes one running/drain/dequeue/clock action at a time; no I/O lives here.
pub(in crate::media) fn input_step(v: &mut [i64; 4], event: i32, value: i64) -> i32 {
    match event {
        0 => {}
        1 => v[0] = if value != 0 { 1 } else { 4 },
        2 => v[0] = 2,
        3 => {
            if value >= 0 {
                v[1] = value;
                v[0] = 4;
            } else {
                v[0] = 3;
            }
        }
        4 => {
            v[0] = if value.wrapping_sub(v[2]) >= v[3] {
                4
            } else {
                0
            }
        }
        _ => unreachable!(),
    }
    v[0] as i32
}

pub fn frame_overflow(
    frames: usize,
    bytes: i64,
    incoming: i64,
    max_frames: i32,
    max_bytes: i32,
) -> bool {
    frames as i64 >= max_frames as i64 || bytes.wrapping_add(incoming) > max_bytes as i64
}

pub struct ReferenceChain {
    needs_keyframe: bool,
}
impl Default for ReferenceChain {
    fn default() -> Self {
        Self {
            needs_keyframe: true,
        }
    }
}
impl ReferenceChain {
    pub fn reset(&mut self) {
        self.needs_keyframe = true;
    }
    pub fn queued(&mut self) {
        self.needs_keyframe = false;
    }
    pub fn needs_keyframe(&self) -> bool {
        self.needs_keyframe
    }
    pub fn accepts(&self, random_access: bool) -> bool {
        !self.needs_keyframe || random_access
    }
}

#[cfg(test)]
mod contracts {
    use super::*;
    #[test]
    fn reference_is_accepted_only_after_input_queued() {
        let mut c = ReferenceChain::default();
        assert!(!c.accepts(false));
        assert!(c.accepts(true));
        assert!(c.needs_keyframe());
        c.queued();
        assert!(c.accepts(false));
        c.reset();
        assert!(!c.accepts(false));
    }
    #[test]
    fn sdk_input_budget_keeps_drain_dequeue_clock_order_and_accepts_late_success() {
        let mut v = [0, -1, 0, 100];
        assert_eq!(input_step(&mut v, 1, 1), 1);
        assert_eq!(input_step(&mut v, 2, 0), 2);
        assert_eq!(input_step(&mut v, 3, -1), 3);
        assert_eq!(input_step(&mut v, 4, 260), 4);
        assert_eq!(v[1], -1);
        let mut v = [0, -1, 0, 100];
        input_step(&mut v, 1, 1);
        input_step(&mut v, 2, 0);
        assert_eq!(input_step(&mut v, 3, 4), 4);
        assert_eq!(v[1], 4);
        assert!(!keyframe_due(i64::MAX - 100, i64::MIN + 100));
        assert!(keyframe_due(i64::MAX - 100, i64::MIN + 1_000_000_000));
    }
    #[test]
    fn forced_refresh_and_automatic_recovery_share_request_deadline() {
        let mut last = 0;
        assert!(keyframe_request(&mut last, 100, true));
        assert!(!keyframe_request(&mut last, 200, false));
        assert!(keyframe_request(&mut last, 300, true));
        assert!(!keyframe_request(&mut last, 1_000_000_200, false));
        assert!(keyframe_request(&mut last, 1_000_000_300, false));
        assert!(!keyframe_request(&mut last, 1_000_000_301, false));
    }
    #[test]
    fn recovery_keeps_only_an_existing_unused_decoder_when_requested() {
        assert!(keeps_unused_decoder(true, true, 0));
        assert!(!keeps_unused_decoder(false, true, 0));
        assert!(!keeps_unused_decoder(true, false, 0));
        assert!(!keeps_unused_decoder(true, true, 1));
    }
    #[test]
    fn remembered_configuration_keeps_duplicate_diagnostic_once_per_change() {
        let mut memory = ConfigurationMemory::default();
        assert!(memory.encoded().is_none());
        assert_eq!(memory.remember(0, vec![1, 2], false), 0);
        assert_eq!(memory.remember(0, vec![1, 2], true), 1);
        assert_eq!(memory.remember(0, vec![1, 2], true), 2);
        assert_eq!(memory.remember(0, vec![1, 2], false), 0);
        assert_eq!(memory.remember(1, vec![1, 2], true), 0);
        assert_eq!(memory.encoded(), Some(vec![1, 1, 2]));
    }
    #[test]
    fn stalled_touch_fires_once_after_timeout_and_is_cleared_by_a_frame() {
        PENDING_TOUCH_NS.store(0, Ordering::Relaxed);
        assert!(!take_stalled_touch(5_000_000_000, 1_500_000_000));
        touch_sent(1_000);
        assert!(!take_stalled_touch(1_500_000_999, 1_500_000_000));
        assert!(take_stalled_touch(1_500_001_000, 1_500_000_000));
        assert!(!take_stalled_touch(3_000_000_000, 1_500_000_000));
        touch_sent(2_000);
        assert!(touch_latency(2_500).is_some());
        assert!(!take_stalled_touch(10_000_000_000, 1_500_000_000));
    }
    #[test]
    fn idle_keyframe_nudge_requires_active_rendered_stream_and_timeout() {
        let timeout = 4_000_000_000;
        assert!(!idle_keyframe_due(
            false,
            true,
            false,
            0,
            0,
            true,
            100,
            timeout + 100,
            timeout
        ));
        assert!(!idle_keyframe_due(
            true,
            false,
            false,
            0,
            0,
            true,
            100,
            timeout + 100,
            timeout
        ));
        assert!(!idle_keyframe_due(
            true,
            true,
            true,
            0,
            0,
            true,
            100,
            timeout + 100,
            timeout
        ));
        assert!(!idle_keyframe_due(
            true,
            true,
            false,
            1,
            0,
            true,
            100,
            timeout + 100,
            timeout
        ));
        assert!(!idle_keyframe_due(
            true,
            true,
            false,
            0,
            1,
            true,
            100,
            timeout + 100,
            timeout
        ));
        assert!(!idle_keyframe_due(
            true,
            true,
            false,
            0,
            0,
            false,
            100,
            timeout + 100,
            timeout
        ));
        assert!(!idle_keyframe_due(
            true,
            true,
            false,
            0,
            0,
            true,
            0,
            timeout + 100,
            timeout
        ));
        assert!(!idle_keyframe_due(
            true,
            true,
            false,
            0,
            0,
            true,
            100,
            timeout + 99,
            timeout
        ));
        assert!(idle_keyframe_due(
            true,
            true,
            false,
            0,
            0,
            true,
            100,
            timeout + 100,
            timeout
        ));
    }
}
