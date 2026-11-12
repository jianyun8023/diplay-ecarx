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

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_audioQueueCreate(
    _env: *mut JNIEnv, _class: jclass, clock_rate: jint, media: jboolean,
) -> jlong {
    if clock_rate <= 0 { return 0; }
    Box::into_raw(Box::new(Mutex::new(AudioQueue::new(clock_rate as i64, media != 0)))) as jlong
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_audioQueuePush(
    _env: *mut JNIEnv, _class: jclass, handle: jlong, packet: jlong, sample: jint, arrived_ns: jlong,
) -> jint {
    if packet == 0 { return 0; }
    let packet = AudioPacket { handle: packet, sample: sample as u32, arrived_ns };
    if handle == 0 { return 0; }
    let (accepted, removed) = (&*(handle as *const Mutex<AudioQueue>)).lock().unwrap().push(packet);
    ((removed as i32) << 1) | accepted as i32
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_audioQueuePoll(
    env: *mut JNIEnv, _class: jclass, handle: jlong, out: jlongArray,
) -> jlong {
    if handle == 0 || Env(env).array_len(out) < 2 { return 0; }
    let mut queue = (&*(handle as *const Mutex<AudioQueue>)).lock().unwrap();
    let Some(packet) = queue.packets.pop_front() else { return 0 };
    let values = [packet.sample as i64, packet.arrived_ns];
    ((**env).SetLongArrayRegion.unwrap())(env, out, 0, 2, values.as_ptr());
    if Env(env).clear_exception() { return 0; }
    packet.into_handle()
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_audioQueueSize(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) -> jint { if handle == 0 { 0 } else { (&*(handle as *const Mutex<AudioQueue>)).lock().unwrap().packets.len() as jint } }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_audioQueuePending(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) -> jlong { if handle == 0 { 0 } else { (&*(handle as *const Mutex<AudioQueue>)).lock().unwrap().pending_frames() } }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_audioQueueArrivals(
    _env: *mut JNIEnv, _class: jclass, handle: jlong, since_ns: jlong, now_ns: jlong, sample_rate: jint,
) -> f64 {
    if handle == 0 || sample_rate <= 0 { return 0.0; }
    (&*(handle as *const Mutex<AudioQueue>)).lock().unwrap().arrived_frame_ns(since_ns, now_ns, sample_rate as i64)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_audioQueueDestroy(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) { if handle != 0 { drop(Box::from_raw(handle as *mut Mutex<AudioQueue>)); } }

#[cfg(test)]
mod tests {
    use super::*;

    fn frame(pool: &std::sync::Arc<packet::Pool>, sample: u32, arrived_ns: i64) -> AudioPacket {
        let mut packet = pool.take(13);
        unsafe { std::ptr::write_bytes(packet.spare(), 0, 13); packet.set_len(13); }
        AudioPacket { handle: packet.into_handle(), sample, arrived_ns }
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
