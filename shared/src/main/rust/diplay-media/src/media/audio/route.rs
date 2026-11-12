//! A live audio receiver's queue destination and receive-clock diagnostics.
use super::queue::{self, AudioPacket, QueueOwner};
use jni_sys::{jboolean, jclass, jint, jlong, jlongArray, JNIEnv};
use std::sync::{Arc, Mutex};

pub struct AudioRoute { state: Mutex<State>, clock_rate: i64, direct: bool }
struct State {
    target: Option<Arc<QueueOwner>>,
    closed: bool,
    newest: Option<u32>,
    baseline_logged: bool,
    last_overrun_ns: Option<i64>,
    probe: ClockProbe,
}
#[derive(Default)]
struct ClockProbe { stream_start: Option<i64>, first: Option<(u32, i64)>, done: bool }

impl ClockProbe {
    fn observe(&mut self, sample: u32, now: i64) -> Option<i32> {
        if self.done { return None; }
        let start = *self.stream_start.get_or_insert(now);
        let Some((first_sample, first_ns)) = self.first else {
            if now.wrapping_sub(start) >= 1_000_000_000 { self.first = Some((sample, now)); }
            return None;
        };
        let elapsed = now.wrapping_sub(first_ns);
        if elapsed < 3_000_000_000 { return None; }
        self.done = true;
        let rate = sample.wrapping_sub(first_sample) as f64 * 1_000_000_000.0 / elapsed as f64;
        // Math.round(double), then Java's narrowing Long -> Int (not Rust's saturating Int cast).
        let bits = rate.to_bits();
        let shift = 1074 - ((bits >> 52) & 0x7ff) as i32;
        let rounded = if (0..64).contains(&shift) {
            let significand = (bits & 0x000f_ffff_ffff_ffff) | 0x0010_0000_0000_0000;
            (((significand >> shift) + 1) >> 1) as i64
        } else { rate as i64 };
        Some(rounded as i32)
    }
}

pub enum Delivery { Callback(AudioPacket), Queued(Arc<QueueOwner>), Closed }
impl AudioRoute {
    pub fn new(clock_rate: i64, direct: bool) -> Arc<Self> {
        Arc::new(Self { state: Mutex::new(State { target: None, closed: false, newest: None,
            baseline_logged: false, last_overrun_ns: None, probe: ClockProbe::default() }), clock_rate, direct })
    }

    /// Sampled after the first-start callback, exactly where the old media-engine observer ran.
    pub fn observe(&self, sample: u32, now: i64) -> Option<i32> {
        let mut state = self.state.lock().unwrap();
        if state.closed { return None; }
        if state.newest.is_none_or(|old| sample.wrapping_sub(old) as i32 > 0) { state.newest = Some(sample); }
        state.probe.observe(sample, now)
    }

    pub fn capture_wire(&self) -> bool { !self.direct }

    pub fn closed(&self) -> bool { self.state.lock().unwrap().closed }

    pub fn deliver(&self, packet: AudioPacket) -> Delivery {
        let state = self.state.lock().unwrap();
        if state.closed { return Delivery::Closed; }
        let target = self.direct.then(|| state.target.clone()).flatten();
        drop(state);
        if let Some(target) = target {
            target.push(packet);
            Delivery::Queued(target)
        } else { Delivery::Callback(packet) }
    }

    fn set_queue(&self, target: Option<Arc<QueueOwner>>) {
        let mut state = self.state.lock().unwrap();
        if !state.closed && self.direct { state.target = target; }
    }

    fn close(&self) {
        let mut state = self.state.lock().unwrap();
        state.closed = true;
        state.target = None;
    }

    fn feedback(&self, reported: u32, now: i64) -> Option<[i64; 3]> {
        let mut state = self.state.lock().unwrap();
        if state.closed { return None; }
        let received = state.newest?;
        let ahead = reported.wrapping_sub(received) as i32 as i64;
        let overrun = ahead as f64 > 0.1 * self.clock_rate as f64;
        let first = !state.baseline_logged;
        if !first && (!overrun || state.last_overrun_ns.is_some_and(|last| now.wrapping_sub(last) < 10_000_000_000)) {
            return None;
        }
        state.baseline_logged = true;
        if overrun { state.last_overrun_ns = Some(now); }
        Some([received as i64, ahead * 1000 / self.clock_rate, overrun as i64])
    }
}

pub unsafe fn retain(handle: jlong) -> Option<Arc<AudioRoute>> {
    if handle == 0 { return None; }
    Arc::increment_strong_count(handle as *const AudioRoute);
    Some(Arc::from_raw(handle as *const AudioRoute))
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_audioRouteCreate(
    _env: *mut JNIEnv, _class: jclass, rate: jint, direct: jboolean,
) -> jlong {
    if rate <= 0 { return 0; }
    Arc::into_raw(AudioRoute::new(rate as i64, direct != 0)) as jlong
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_audioRouteSetQueue(
    _env: *mut JNIEnv, _class: jclass, handle: jlong, queue: jlong,
) {
    let target = queue::consume_reference(queue);
    if handle != 0 { (&*(handle as *const AudioRoute)).set_queue(target); }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_audioRouteFeedback(
    env: *mut JNIEnv, _class: jclass, handle: jlong, reported: jlong, now: jlong,
) -> jlongArray {
    if handle == 0 { return std::ptr::null_mut(); }
    let Some(row) = (&*(handle as *const AudioRoute)).feedback(reported as u32, now) else { return std::ptr::null_mut() };
    let out = ((**env).NewLongArray.unwrap())(env, 3);
    if out.is_null() || ((**env).ExceptionCheck.unwrap())(env) != 0 { return std::ptr::null_mut(); }
    ((**env).SetLongArrayRegion.unwrap())(env, out, 0, 3, row.as_ptr());
    if ((**env).ExceptionCheck.unwrap())(env) != 0 { return std::ptr::null_mut(); }
    out
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_audioRouteDestroy(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) { if handle != 0 { Arc::from_raw(handle as *const AudioRoute).close(); } }

/// Negotiated audioFormat bits. Opus wins over AAC; PCM formats require an exact table key.
fn format_bits(bits: u64) -> [i32; 4] {
    if bits & 0x7000_0000 != 0 {
        let clock = if bits & 0x4000_0000 != 0 { 48_000 } else if bits & 0x2000_0000 != 0 { 24_000 } else { 16_000 };
        return [1, 48_000, 1, clock];
    }
    if bits & 0x00c0_0000 != 0 {
        let rate = if bits & 0x0080_0000 != 0 { 48_000 } else { 44_100 };
        return [0, rate, 2, rate];
    }
    let (rate, channels) = match bits {
        0x4 => (8_000, 1), 0x8 => (8_000, 2),
        0x10 => (16_000, 1), 0x20 => (16_000, 2),
        0x40 => (24_000, 1), 0x80 => (24_000, 2),
        0x100 => (32_000, 1), 0x200 => (32_000, 2),
        0x400 => (44_100, 1), 0x800 => (44_100, 2),
        0x4000 => (48_000, 1), 0x8000 => (48_000, 2),
        _ => (44_100, 2),
    };
    [2, rate, channels, rate]
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_audioFormatBits(
    env: *mut JNIEnv, _class: jclass, bits: jlong,
) -> jni_sys::jintArray {
    let row = format_bits(bits as u64);
    let out = ((**env).NewIntArray.unwrap())(env, 4);
    if out.is_null() || ((**env).ExceptionCheck.unwrap())(env) != 0 { return std::ptr::null_mut(); }
    ((**env).SetIntArrayRegion.unwrap())(env, out, 0, 4, row.as_ptr());
    if ((**env).ExceptionCheck.unwrap())(env) != 0 { return std::ptr::null_mut(); }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::media::packet::Pool;

    fn packet(pool: &Arc<Pool>, sample: u32) -> AudioPacket {
        let mut packet = pool.take(12);
        unsafe { std::ptr::write_bytes(packet.spare(), 0, 12); packet.set_len(12); }
        AudioPacket { handle: packet.into_handle(), sample, arrived_ns: 1 }
    }

    #[test]
    fn target_replacement_close_and_legacy_callbacks_keep_one_packet_owner() {
        let pool = Pool::new();
        let route = AudioRoute::new(48_000, true);
        assert!(matches!(route.deliver(packet(&pool, 1)), Delivery::Callback(_)));
        let queue = QueueOwner::new(48_000, false);
        let weak = Arc::downgrade(&queue);
        route.set_queue(Some(queue.clone()));
        assert!(matches!(route.deliver(packet(&pool, 2)), Delivery::Queued(_)));
        assert_eq!(pool.outstanding(), 1);
        queue.close();
        assert_eq!(pool.outstanding(), 0);
        // An already-closed target drops incoming packets, never hands them to a stale JVM sink.
        assert!(matches!(route.deliver(packet(&pool, 3)), Delivery::Queued(_)));
        assert_eq!(pool.outstanding(), 0);
        route.set_queue(None);
        drop(queue);
        assert!(weak.upgrade().is_none());
        assert!(matches!(route.deliver(packet(&pool, 4)), Delivery::Callback(_)));
        route.close();
        assert!(matches!(route.deliver(packet(&pool, 5)), Delivery::Closed));
        assert_eq!(pool.outstanding(), 0);
    }

    #[test]
    fn wiretap_refuses_direct_targets_and_keeps_callback_ownership() {
        let pool = Pool::new();
        let route = AudioRoute::new(48_000, false);
        let queue = QueueOwner::new(48_000, false);
        let weak = Arc::downgrade(&queue);
        route.set_queue(Some(queue));
        assert!(weak.upgrade().is_none());
        assert!(matches!(route.deliver(packet(&pool, 7)), Delivery::Callback(_)));
        assert_eq!(pool.outstanding(), 0);
    }

    #[test]
    fn receive_clock_preserves_warmup_window_rtp_wrap_and_long_nano_wrap() {
        for origin in [0i64, -10_000_000_000, i64::MAX - 2_000_000_000] {
            let route = AudioRoute::new(16_000, true);
            let sample = u32::MAX - 255;
            assert_eq!(route.observe(sample, origin), None);
            assert_eq!(route.observe(sample.wrapping_add(15_999), origin.wrapping_add(999_999_999)), None);
            assert_eq!(route.observe(sample.wrapping_add(16_000), origin.wrapping_add(1_000_000_000)), None);
            assert_eq!(route.observe(sample.wrapping_add(63_999), origin.wrapping_add(3_999_999_999)), None);
            assert_eq!(route.observe(sample.wrapping_add(64_000), origin.wrapping_add(4_000_000_000)), Some(16_000));
            assert_eq!(route.observe(0, origin.wrapping_add(5_000_000_000)), None);
        }
    }

    #[test]
    fn feedback_keeps_newest_sample_threshold_repeat_and_clear_semantics() {
        let route = AudioRoute::new(48_000, true);
        assert_eq!(route.feedback(0, 0), None);
        route.observe(u32::MAX - 15, 0);
        route.observe(16, 1);
        route.observe(u32::MAX - 15, 2); // late packet must not move the received frontier back
        assert_eq!(route.feedback(16, 1_000), Some([16, 0, 0]));
        assert_eq!(route.feedback(4816, 2_000), None); // exactly 100 ms is not an overrun
        assert_eq!(route.feedback(4817, 3_000), Some([16, 100, 1]));
        assert_eq!(route.feedback(4817, 2_999), None); // clock rollback preserves rate limiting
        assert_eq!(route.feedback(4817, 10_000_002_999), None);
        assert_eq!(route.feedback(4817, 10_000_003_000), Some([16, 100, 1]));
        route.close();
        assert_eq!(route.feedback(4817, 20_000_003_000), None);
    }

    #[test]
    fn format_priority_and_exact_pcm_keys_match_the_original_public_contract() {
        assert_eq!(format_bits(0x1080_0000), [1, 48_000, 1, 16_000]);
        assert_eq!(format_bits(0x7000_0000), [1, 48_000, 1, 48_000]);
        assert_eq!(format_bits(0x00c0_0000), [0, 48_000, 2, 48_000]);
        assert_eq!(format_bits(0xc), [2, 44_100, 2, 44_100]);
        assert_eq!(format_bits((1u64 << 63) | 4), [2, 44_100, 2, 44_100]);
        assert_eq!(format_bits(u64::MAX), [1, 48_000, 1, 48_000]);
    }
}
