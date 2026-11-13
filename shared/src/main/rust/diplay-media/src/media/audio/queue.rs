//! Compressed audio ownership, sustained RTP backlog recovery and arrival-weighted clock input.
use crate::{platform::jvm::Env, media::packet};
use jni_sys::{jboolean, jclass, jint, jlong, jlongArray, JNIEnv};
use std::collections::VecDeque;
use std::sync::Mutex;

pub struct AudioPacket { pub handle: i64, pub sample: u32, pub arrived_ns: i64 }

impl AudioPacket {
    pub fn size(&self) -> usize { unsafe { packet::size(self.handle) } }
    pub fn share(&self) -> i64 { unsafe { packet::retain(self.handle); } self.handle }
    pub fn into_handle(self) -> i64 { let handle = self.handle; std::mem::forget(self); handle }
}

impl Drop for AudioPacket { fn drop(&mut self) { unsafe { packet::release(self.handle); } } }

pub struct AudioQueue {
    pub packets: VecDeque<AudioPacket>,
    clock_rate: i64,
    media: bool,
    newest: u32,
    congestion_since: Option<i64>,
    recovering: bool,
}

impl AudioQueue {
    pub fn new(clock_rate: i64, media: bool) -> Self {
        Self { packets: Default::default(), clock_rate, media, newest: 0,
            congestion_since: None, recovering: false }
    }

    pub fn pending_frames(&self) -> i64 {
        if !self.media { return 0; }
        self.packets.front().map(|p| self.newest.wrapping_sub(p.sample) as i64)
            .filter(|&frames| frames <= self.clock_rate * 16).unwrap_or(0)
    }

    fn recover(&mut self, now_ns: i64) -> usize {
        let delay_ns = self.pending_frames() * 1_000_000_000 / self.clock_rate;
        if !self.recovering {
            if delay_ns <= 1_500_000_000 { self.congestion_since = None; return 0; }
            let Some(since) = self.congestion_since else { self.congestion_since = Some(now_ns); return 0 };
            if now_ns - since < 1_500_000_000 { return 0; }
            self.recovering = true;
        }
        if delay_ns <= 200_000_000 { self.recovering = false; self.congestion_since = None; return 0; }
        let mut removed = 0;
        while !self.packets.is_empty() && self.pending_frames() * 1_000_000_000 / self.clock_rate > 500_000_000 {
            self.packets.pop_front();
            removed += 1;
        }
        removed
    }

    /// Consumes the packet on every path. Returns (accepted, recovered stale packets).
    pub fn push(&mut self, packet: AudioPacket) -> (bool, usize) {
        self.newest = packet.sample;
        let removed = if self.media { self.recover(packet.arrived_ns) } else { 0 };
        if self.packets.len() >= 192 { return (false, removed); }
        self.packets.push_back(packet);
        (true, removed)
    }

    pub fn arrived_frame_ns(&self, since_ns: i64, now_ns: i64, sample_rate: i64) -> f64 {
        let mut frame_ns = 0.0;
        let mut previous = None;
        for packet in &self.packets {
            if let Some(previous) = previous {
                let frames = packet.sample.wrapping_sub(previous) as i64;
                if packet.arrived_ns > since_ns && frames <= self.clock_rate {
                    frame_ns += frames as f64 * sample_rate as f64 / self.clock_rate as f64 * (now_ns - packet.arrived_ns) as f64;
                }
            }
            previous = Some(packet.sample);
        }
        frame_ns
    }
}

/// One queue shared by its producer, route and worker. Closing wakes a blocked worker, while its
/// retained reference keeps the allocation alive until that worker returns.
pub struct QueueOwner {
    state: Mutex<QueueState>,
    ready: std::sync::Condvar,
}

struct QueueState {
    queue: AudioQueue,
    closed: bool,
    received: i32,
    dropped: i32,
    dropped_total: i32,
    last_arrival: i64,
    max_gap_ms: i64,
    recovery_reports: i32,
    full_reports: i32,
    full_logged: bool,
    events: VecDeque<[i64; 3]>,
}

impl QueueOwner {
    pub fn new(clock_rate: i64, media: bool) -> std::sync::Arc<Self> {
        std::sync::Arc::new(Self {
            state: Mutex::new(QueueState { queue: AudioQueue::new(clock_rate, media), closed: false,
                received: 0, dropped: 0, dropped_total: 0, last_arrival: 0, max_gap_ms: 0,
                recovery_reports: 0, full_reports: 0, full_logged: false, events: VecDeque::new() }),
            ready: std::sync::Condvar::new(),
        })
    }

    /// Consumes the packet, including after close and when the bounded queue is full.
    pub fn push(&self, packet: AudioPacket) -> (bool, usize) {
        let mut state = self.state.lock().unwrap();
        if state.closed { return (false, 0); }
        state.received = state.received.wrapping_add(1);
        if state.last_arrival != 0 {
            state.max_gap_ms = state.max_gap_ms.max(packet.arrived_ns.wrapping_sub(state.last_arrival) / 1_000_000);
        }
        state.last_arrival = packet.arrived_ns;
        let result = state.queue.push(packet);
        let dropped = (result.1 as i32).wrapping_add((!result.0) as i32);
        state.dropped = state.dropped.wrapping_add(dropped);
        state.dropped_total = state.dropped_total.wrapping_add(dropped);
        if result.1 != 0 && state.recovery_reports < 5 {
            state.recovery_reports += 1;
            let size = state.queue.packets.len() as i64;
            state.events.push_back([1, result.1 as i64, size]);
        }
        if !result.0 {
            let warn = !state.full_logged;
            state.full_logged = true;
            let count = if state.full_reports < 5 { state.full_reports += 1; state.full_reports } else { 0 };
            if warn || count != 0 { state.events.push_back([2, count as i64, warn as i64]); }
        }
        drop(state);
        if result.0 { self.ready.notify_one(); }
        result
    }

    pub fn event(&self) -> Option<[i64; 3]> { self.state.lock().unwrap().events.pop_front() }

    fn poll(&self, timeout_ms: i64) -> Option<AudioPacket> {
        let state = self.state.lock().unwrap();
        let mut state = if timeout_ms > 0 && !state.closed && state.queue.packets.is_empty() {
            self.ready.wait_timeout_while(state, std::time::Duration::from_millis(timeout_ms as u64),
                |state| !state.closed && state.queue.packets.is_empty()).unwrap().0
        } else { state };
        let packet = state.queue.packets.pop_front();
        if packet.is_some() && state.queue.packets.is_empty() { state.full_logged = false; }
        packet
    }

    pub fn close(&self) -> [i64; 5] {
        let mut state = self.state.lock().unwrap();
        state.closed = true;
        let snapshot = [state.received as i64, state.dropped as i64, state.dropped_total as i64,
            state.last_arrival, state.max_gap_ms];
        state.queue.packets.clear();
        drop(state);
        self.ready.notify_all();
        snapshot
    }
}

/// The caller must own a queue reference for the duration of the borrow.
pub unsafe fn owner<'a>(handle: jlong) -> Option<&'a QueueOwner> {
    (handle != 0).then(|| &*(handle as *const QueueOwner))
}

/// A retained reference transferred by Kotlin's lifetime monitor (also consumed by a route).
pub unsafe fn consume_reference(handle: jlong) -> Option<std::sync::Arc<QueueOwner>> {
    (handle != 0).then(|| std::sync::Arc::from_raw(handle as *const QueueOwner))
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_audioQueueCreate(
    _env: *mut JNIEnv, _class: jclass, clock_rate: jint, media: jboolean,
) -> jlong {
    if clock_rate <= 0 { return 0; }
    std::sync::Arc::into_raw(QueueOwner::new(clock_rate as i64, media != 0)) as jlong
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_audioQueueRetain(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) -> jlong {
    if handle != 0 { std::sync::Arc::increment_strong_count(handle as *const QueueOwner); }
    handle
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_audioQueueRelease(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) { drop(consume_reference(handle)); }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_audioQueuePush(
    _env: *mut JNIEnv, _class: jclass, handle: jlong, packet: jlong, sample: jint, arrived_ns: jlong,
) -> jint {
    if packet == 0 { return 0; }
    let packet = AudioPacket { handle: packet, sample: sample as u32, arrived_ns };
    let Some(owner) = owner(handle) else { return 0 };
    let (accepted, removed) = owner.push(packet);
    ((removed as i32) << 1) | accepted as i32
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_audioQueuePoll(
    env: *mut JNIEnv, _class: jclass, handle: jlong, out: jlongArray, timeout_ms: jlong,
) -> jlong {
    let len = Env(env).array_len(out);
    if len < 2 { return 0; }
    let Some(owner) = owner(handle) else { return 0 };
    let Some(packet) = owner.poll(timeout_ms) else { return 0 };
    let values = [packet.sample as i64, packet.arrived_ns, packet.size() as i64];
    ((**env).SetLongArrayRegion.unwrap())(env, out, 0, len.min(3) as i32, values.as_ptr());
    if ((**env).ExceptionCheck.unwrap())(env) != 0 { return 0; }
    packet.into_handle()
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_audioQueueEvent(
    env: *mut JNIEnv, _class: jclass, handle: jlong, out: jlongArray,
) -> jboolean {
    if Env(env).array_len(out) < 3 { return 0; }
    let Some(event) = owner(handle).and_then(QueueOwner::event) else { return 0 };
    ((**env).SetLongArrayRegion.unwrap())(env, out, 0, 3, event.as_ptr());
    (((**env).ExceptionCheck.unwrap())(env) == 0) as jboolean
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_audioQueueStats(
    env: *mut JNIEnv, _class: jclass, handle: jlong, out: jlongArray, reset: jboolean,
) {
    if Env(env).array_len(out) < 5 { return; }
    let Some(owner) = owner(handle) else { return };
    let mut state = owner.state.lock().unwrap();
    let values = [state.received as i64, state.dropped as i64, state.dropped_total as i64,
        state.last_arrival, state.max_gap_ms];
    ((**env).SetLongArrayRegion.unwrap())(env, out, 0, 5, values.as_ptr());
    if ((**env).ExceptionCheck.unwrap())(env) == 0 && reset != 0 {
        state.received = 0; state.dropped = 0; state.max_gap_ms = 0;
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_audioQueueSize(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) -> jint { owner(handle).map_or(0, |o| o.state.lock().unwrap().queue.packets.len() as jint) }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_audioQueuePending(
    env: *mut JNIEnv, _class: jclass, handle: jlong, dropped: jlongArray,
) -> jlong {
    let Some(owner) = owner(handle) else { return 0 };
    let state = owner.state.lock().unwrap();
    if !dropped.is_null() && Env(env).array_len(dropped) >= 1 {
        let value = state.dropped_total as i64;
        ((**env).SetLongArrayRegion.unwrap())(env, dropped, 0, 1, &value);
        if ((**env).ExceptionCheck.unwrap())(env) != 0 { return 0; }
    }
    state.queue.pending_frames()
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_audioQueueArrivals(
    _env: *mut JNIEnv, _class: jclass, handle: jlong, since_ns: jlong, now_ns: jlong, sample_rate: jint,
) -> f64 {
    if sample_rate <= 0 { return 0.0; }
    owner(handle).map_or(0.0, |o| o.state.lock().unwrap().queue.arrived_frame_ns(since_ns, now_ns, sample_rate as i64))
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_audioQueueDestroy(
    env: *mut JNIEnv, _class: jclass, handle: jlong, stats: jlongArray,
) {
    if let Some(owner) = consume_reference(handle) {
        let row = owner.close();
        if !stats.is_null() && Env(env).array_len(stats) >= 5 {
            ((**env).SetLongArrayRegion.unwrap())(env, stats, 0, 5, row.as_ptr());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn frame(pool: &std::sync::Arc<packet::Pool>, sample: u32, arrived_ns: i64) -> AudioPacket {
        let mut packet = pool.take(13);
        unsafe { std::ptr::write_bytes(packet.spare(), 0, 13); packet.set_len(13); }
        AudioPacket { handle: packet.into_handle(), sample, arrived_ns }
    }

    #[test]
    fn closing_wakes_a_retained_poll_and_preserves_the_allocation_until_the_worker_returns() {
        let owner = QueueOwner::new(48_000, true);
        let weak = std::sync::Arc::downgrade(&owner);
        let reader = owner.clone();
        let (ready_tx, ready_rx) = std::sync::mpsc::channel();
        let (done_tx, done_rx) = std::sync::mpsc::channel();
        let worker = std::thread::spawn(move || {
            ready_tx.send(()).unwrap();
            done_tx.send(reader.poll(60_000).is_none()).unwrap();
        });
        ready_rx.recv_timeout(std::time::Duration::from_secs(5)).unwrap();
        assert_eq!(owner.close(), [0; 5]);
        drop(owner);
        assert!(done_rx.recv_timeout(std::time::Duration::from_secs(5)).unwrap());
        worker.join().unwrap();
        assert!(weak.upgrade().is_none());
    }

    #[test]
    fn input_statistics_close_cutoff_and_late_packet_release_have_one_rust_owner() {
        let pool = packet::Pool::new();
        let queue = QueueOwner::new(48_000, false);
        for (sample, arrived) in [(1, 10_000_000), (2, 5_000_000), (3, 25_000_000)] {
            assert_eq!(queue.push(frame(&pool, sample, arrived)), (true, 0));
        }
        assert_eq!(queue.close(), [3, 0, 0, 25_000_000, 20]);
        assert_eq!(pool.outstanding(), 0);
        assert_eq!(queue.push(frame(&pool, 4, 30_000_000)), (false, 0));
        assert_eq!(pool.outstanding(), 0);
        assert_eq!(queue.close(), [3, 0, 0, 25_000_000, 20]);
    }

    #[test]
    fn a_short_wifi_burst_stays_intact_but_sustained_decoder_backpressure_recovers() {
        let pool = packet::Pool::new();
        let mut queue = AudioQueue::new(48_000, true);
        for i in 0..151 { assert_eq!(queue.push(frame(&pool, i * 1024, 100_000_000)), (true, 0)); }
        assert_eq!(queue.push(frame(&pool, 151 * 1024, 1_600_000_000)), (true, 128));
        assert_eq!(queue.packets.len(), 24);
        assert!(queue.pending_frames() <= 24_000);
        // Stay in recovery until the live edge is reached, without another grace period.
        assert!(queue.push(frame(&pool, 180 * 1024, 1_610_000_000)).1 > 0);
        drop(queue);
        assert_eq!(pool.outstanding(), 0);
    }

    #[test]
    fn the_packet_budget_drops_newest_without_leaking_and_navigation_never_skips_old_audio() {
        let pool = packet::Pool::new();
        let mut queue = AudioQueue::new(48_000, false);
        for i in 0..192 { assert!(queue.push(frame(&pool, i * 48_000, i as i64 * 1_000_000_000)).0); }
        assert_eq!(queue.push(frame(&pool, 192 * 48_000, 200_000_000_000)), (false, 0));
        assert_eq!(queue.packets.front().unwrap().sample, 0);
        assert_eq!(pool.outstanding(), 192);
        drop(queue);
        assert_eq!(pool.outstanding(), 0);
    }

    #[test]
    fn rtp_wrap_restart_and_arrival_weighted_sampling_use_the_original_clock_domain() {
        let pool = packet::Pool::new();
        let mut queue = AudioQueue::new(48_000, true);
        queue.push(frame(&pool, 0xffff_fc00, 100));
        queue.push(frame(&pool, 0, 200));
        assert_eq!(queue.pending_frames(), 1024);
        assert_eq!(queue.arrived_frame_ns(150, 1200, 48_000), 1_024_000.0);
        queue.push(frame(&pool, 48_000 * 20, 300));
        assert_eq!(queue.pending_frames(), 0);
        assert_eq!(queue.arrived_frame_ns(150, 1200, 48_000), 1_024_000.0);
    }
}
