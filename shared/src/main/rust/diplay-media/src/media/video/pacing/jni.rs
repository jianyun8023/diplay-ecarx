//! GC-managed opaque timing values. No platform decoder, JNI global reference, or native heap lease.
use super::{BacklogRecovery, FramePacer, PacingDelay};
use jni_sys::{jboolean, jclass, jdouble, jint, jlong, jlongArray, JNIEnv};
use std::ptr::null_mut;

unsafe fn invalid(env: *mut JNIEnv) {
    let f = &**env;
    if (f.ExceptionCheck.unwrap())(env) != 0 { return; }
    let class = (f.FindClass.unwrap())(env, c"java/lang/IllegalArgumentException".as_ptr());
    if !class.is_null() { (f.ThrowNew.unwrap())(env, class, c"Invalid video timing state or configuration".as_ptr()); }
}
unsafe fn read(env: *mut JNIEnv, input: jlongArray) -> Option<Vec<i64>> {
    if input.is_null() { invalid(env); return None; }
    let f = &**env;
    let count = (f.GetArrayLength.unwrap())(env, input);
    let mut values = vec![0; count as usize];
    if count > 0 { (f.GetLongArrayRegion.unwrap())(env, input, 0, count, values.as_mut_ptr()); }
    ((f.ExceptionCheck.unwrap())(env) == 0).then_some(values)
}
unsafe fn write(env: *mut JNIEnv, target: jlongArray, values: &[i64]) {
    ((**env).SetLongArrayRegion.unwrap())(env, target, 0, values.len() as i32, values.as_ptr());
}
unsafe fn array(env: *mut JNIEnv, values: &[i64]) -> jlongArray {
    let out = ((**env).NewLongArray.unwrap())(env, values.len() as i32);
    if !out.is_null() { write(env, out, values); }
    out
}

impl FramePacer {
    fn values(&self) -> Vec<i64> {
        let mut v = vec![0; 11 + self.window];
        v[..11].copy_from_slice(&[self.window as i64, self.percentile as i64, self.refresh_every as i64,
            self.slew_per_second.to_bits() as i64, self.reset_nanos, self.max_early_nanos, self.frames as i64,
            self.candidate, self.offset.unwrap_or(i64::MIN), self.last_arrival_nanos, self.residuals.len() as i64]);
        for (i, value) in self.residuals.iter().enumerate() { v[11 + i] = *value; }
        v
    }
    fn from_values(v: &[i64]) -> Option<Self> {
        if v.len() < 11 || v[0] <= 0 || v.len() - 11 != v[0] as usize || !(0..=v[0]).contains(&v[10]) { return None; }
        Some(Self { window: v[0] as usize, percentile: v[1] as usize, refresh_every: v[2] as i32,
            slew_per_second: f64::from_bits(v[3] as u64), reset_nanos: v[4], max_early_nanos: v[5], frames: v[6] as i32,
            candidate: v[7], offset: (v[8] != i64::MIN).then_some(v[8]), last_arrival_nanos: v[9],
            residuals: v[11..11 + v[10] as usize].iter().copied().collect() })
    }
}
impl PacingDelay {
    fn values(&self) -> Vec<i64> {
        let mut v = vec![0; 13 + self.needs.len()];
        v[..13].copy_from_slice(&[self.min_nanos, self.max_nanos, self.percentile as i64, self.refresh_every as i64,
            self.margin_nanos, self.rise_per_frame, self.fall_per_frame, self.count as i64, self.next as i64,
            self.frames as i64, self.nanos, self.goal, self.needs.len() as i64]);
        v[13..].copy_from_slice(&self.needs);
        v
    }
    fn from_values(v: &[i64]) -> Option<Self> {
        if v.len() < 13 || v[12] <= 0 || v.len() - 13 != v[12] as usize ||
            !(0..=v[12]).contains(&v[7]) || !(0..v[12]).contains(&v[8]) { return None; }
        Some(Self { min_nanos: v[0], max_nanos: v[1], percentile: v[2] as usize, refresh_every: v[3] as i32,
            margin_nanos: v[4], rise_per_frame: v[5], fall_per_frame: v[6], count: v[7] as usize,
            next: v[8] as usize, frames: v[9] as i32, nanos: v[10], goal: v[11], needs: v[13..].to_vec() })
    }
}
impl BacklogRecovery {
    fn values(&self) -> [i64; 6] {
        [(self.startup_ends_ns.is_some() as i64) | ((self.episode_start_ns.is_some() as i64) << 1) |
            ((self.last_observed_ns.is_some() as i64) << 2), self.startup_ends_ns.unwrap_or(0),
            self.episode_start_ns.unwrap_or(0), self.initial_span_ns, self.initial_pending_frames as i64,
            self.last_observed_ns.unwrap_or(0)]
    }
    fn from_values(v: &[i64]) -> Option<Self> {
        if v.len() != 6 { return None; }
        Some(Self { startup_ends_ns: (v[0] & 1 != 0).then_some(v[1]), episode_start_ns: (v[0] & 2 != 0).then_some(v[2]),
            initial_span_ns: v[3], initial_pending_frames: v[4] as i32, last_observed_ns: (v[0] & 4 != 0).then_some(v[5]) })
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeVideoTiming_pacerCreate(
    env: *mut JNIEnv, _: jclass, window: jint, percentile: jint, refresh: jint, slew: jdouble, reset: jlong, max_early: jlong,
) -> jlongArray {
    if window <= 0 || !(0..=100).contains(&percentile) || refresh <= 0 || slew < 0.0 { invalid(env); return null_mut(); }
    let owner = FramePacer { window: window as usize, percentile: percentile as usize, refresh_every: refresh,
        slew_per_second: slew, reset_nanos: reset, max_early_nanos: max_early, ..FramePacer::default() };
    array(env, &owner.values())
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeVideoTiming_pacerLocal(
    env: *mut JNIEnv, _: jclass, state: jlongArray, sender: jlong, arrival: jlong,
) -> jlong {
    let Some(v) = read(env, state) else { return 0; };
    let Some(mut owner) = FramePacer::from_values(&v) else { invalid(env); return 0; };
    let result = owner.local_time(sender, arrival);
    write(env, state, &owner.values());
    result
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeVideoTiming_delayCreate(
    env: *mut JNIEnv, _: jclass, initial: jlong, min: jlong, max: jlong, window: jint, percentile: jint,
    refresh: jint, margin: jlong, rise: jlong, fall: jlong,
) -> jlongArray {
    if window <= 0 || !(0..=100).contains(&percentile) || refresh <= 0 || min > max || max < 0 || rise < 0 || fall < 0 {
        invalid(env); return null_mut();
    }
    let nanos = initial.clamp(min, max);
    let owner = PacingDelay { min_nanos: min, max_nanos: max, percentile: percentile as usize, refresh_every: refresh,
        margin_nanos: margin, rise_per_frame: rise, fall_per_frame: fall, needs: vec![0; window as usize],
        count: 0, next: 0, frames: 0, nanos, goal: nanos };
    array(env, &owner.values())
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeVideoTiming_delayFrame(
    env: *mut JNIEnv, _: jclass, state: jlongArray, needed: jlong,
) {
    let Some(v) = read(env, state) else { return; };
    let Some(mut owner) = PacingDelay::from_values(&v) else { invalid(env); return; };
    owner.on_frame(needed);
    write(env, state, &owner.values());
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeVideoTiming_delayNanos(
    env: *mut JNIEnv, _: jclass, state: jlongArray,
) -> jlong {
    let Some(v) = read(env, state) else { return 0; };
    let Some(owner) = PacingDelay::from_values(&v) else { invalid(env); return 0; };
    owner.nanos
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeVideoTiming_backlogCreate(
    env: *mut JNIEnv, _: jclass,
) -> jlongArray { array(env, &BacklogRecovery::default().values()) }
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeVideoTiming_backlogEvent(
    env: *mut JNIEnv, _: jclass, state: jlongArray, event: jint, now: jlong, received: jlong, pending: jint,
    newest: jlong, has_newest: jboolean,
) -> jboolean {
    let Some(v) = read(env, state) else { return 0; };
    let Some(mut owner) = BacklogRecovery::from_values(&v) else { invalid(env); return 0; };
    let result = match event {
        0 => owner.observe(now, received, pending.max(0) as usize, (has_newest != 0).then_some(newest)),
        1 => { owner.reset(); false },
        2 => { owner.decoder_started(now); false },
        _ => { invalid(env); return 0; },
    };
    write(env, state, &owner.values());
    result as jboolean
}

#[cfg(test)]
mod contracts {
    use super::*;
    #[test]
    fn gc_values_preserve_window_parameters_and_wrapping_clock_between_calls() {
        let mut pacer = FramePacer { window: 3, refresh_every: 1, ..FramePacer::default() };
        for (sender, arrival) in [(i64::MAX - 2, i64::MIN + 7), (i64::MAX - 1, i64::MIN + 8), (0, 10)] {
            let mut restored = FramePacer::from_values(&pacer.values()).unwrap();
            assert_eq!(pacer.local_time(sender, arrival), restored.local_time(sender, arrival));
            assert_eq!(pacer.values(), restored.values());
        }
        let mut delay = PacingDelay::new(50_000_000);
        for need in (0..40).map(|n| n * 5_000_000) {
            let mut restored = PacingDelay::from_values(&delay.values()).unwrap();
            delay.on_frame(need); restored.on_frame(need);
            assert_eq!(delay.values(), restored.values());
        }
        let mut recovery = BacklogRecovery::default();
        recovery.decoder_started(i64::MAX);
        let mut restored = BacklogRecovery::from_values(&recovery.values()).unwrap();
        recovery.reset(); restored.reset();
        assert_eq!(recovery.values(), restored.values());
        assert_eq!(recovery.startup_ends_ns, Some(i64::MIN + 2_999_999_999));
    }
}
