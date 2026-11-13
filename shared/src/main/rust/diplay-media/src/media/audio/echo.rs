//! Far-end PCM and calibrated speaker/capture clocks for the call echo canceller.
use std::sync::{Arc, Mutex};

const NOT_PLAYING: i64 = i64::MIN;

struct EchoReference {
    rate: i64,
    ring: Vec<i16>,
    written: i64,
    anchor_sample: i64,
    anchor_ns: i64,
    cursor: i64,
    settled: bool,
    errors: [i64; 10],
    error_at: usize,
    error_count: usize,
}

impl EchoReference {
    fn new(rate: i32, capacity: usize) -> Self {
        Self { rate: rate as i64, ring: vec![0; capacity], written: 0, anchor_sample: NOT_PLAYING,
            anchor_ns: 0, cursor: NOT_PLAYING, settled: false, errors: [0; 10], error_at: 0, error_count: 0 }
    }

    fn append(&mut self, pcm: &[u8], channels: usize, pending: Option<i64>, now: i64) {
        for frame in pcm.chunks_exact(2 * channels) {
            let sum = frame.chunks_exact(2).fold(0i32, |sum, pair| sum.wrapping_add(i16::from_le_bytes([pair[0], pair[1]]) as i32));
            let index = (self.written % self.ring.len() as i64) as usize;
            self.ring[index] = (sum / channels as i32) as i16;
            self.written = self.written.wrapping_add(1);
        }
        match pending {
            None => self.anchor_sample = NOT_PLAYING,
            Some(pending) => {
                self.anchor_sample = self.written - pending.clamp(0, self.written);
                self.anchor_ns = now;
            }
        }
    }

    fn reset(&mut self) {
        self.written = 0;
        self.anchor_sample = NOT_PLAYING;
        self.cursor = NOT_PLAYING;
    }

    fn read(&mut self, out: &mut [i16], end: i64) -> bool {
        if self.anchor_sample == NOT_PLAYING {
            self.cursor = NOT_PLAYING;
            out.fill(0);
            return false;
        }
        let estimate = self.anchor_sample.wrapping_add(end.wrapping_sub(self.anchor_ns).wrapping_mul(self.rate).div_euclid(1_000_000_000))
            .wrapping_sub(out.len() as i64);
        let start = self.steer(estimate);
        self.cursor = start.wrapping_add(out.len() as i64);
        let oldest = 0.max(self.written - self.ring.len() as i64);
        let mut heard = false;
        for (i, value) in out.iter_mut().enumerate() {
            let sample = start.wrapping_add(i as i64);
            *value = if (oldest..self.written).contains(&sample) {
                heard = true;
                self.ring[(sample % self.ring.len() as i64) as usize]
            } else { 0 };
        }
        heard
    }

    fn steer(&mut self, estimate: i64) -> i64 {
        if self.cursor == NOT_PLAYING {
            self.cursor = estimate;
            self.settled = false;
            self.error_count = 0;
            return self.cursor;
        }
        self.errors[self.error_at] = estimate.wrapping_sub(self.cursor);
        self.error_at = (self.error_at + 1) % self.errors.len();
        self.error_count = (self.error_count + 1).min(self.errors.len());
        if self.error_count == self.errors.len() {
            let mean = self.errors.iter().fold(0i64, |sum, error| sum.wrapping_add(*error)) / self.errors.len() as i64;
            if mean.wrapping_abs() > self.rate * 20 / 1000 {
                self.cursor = estimate;
                self.settled = false;
                self.error_count = 0;
            } else if !self.settled {
                self.cursor = self.cursor.wrapping_add(mean);
                self.settled = true;
                self.error_count = 0;
            } else if mean.wrapping_abs() > self.rate * 5 / 1000 {
                self.cursor = self.cursor.wrapping_add(mean.signum());
            }
        }
        self.cursor
    }
}

// Opaque GC values keep this per-capture clock independent of native resource lifetimes.
fn capture_read(state: &mut [i64; 3], rate: i32, samples: i32, now: i64, stamped: bool, timestamp: i64, position: i64) {
    state[0] = state[0].wrapping_add(samples as i64);
    state[1] = samples as i64;
    state[2] = if stamped && timestamp > 0 {
        timestamp.wrapping_add(state[0].wrapping_sub(position).wrapping_mul(1_000_000_000) / rate as i64)
    } else { now.wrapping_sub(20_000_000) };
}

fn capture_end(state: &[i64; 3], rate: i32, samples: i32) -> i64 {
    state[2].wrapping_sub((state[1] as i32).wrapping_sub(samples).max(0) as i64 * 1_000_000_000 / rate as i64)
}

type SharedReference = Arc<Mutex<EchoReference>>;
use crate::platform::jvm::Env;
use jni_sys::{jboolean, jbyteArray, jclass, jint, jlong, jlongArray, jobject, jshortArray, JNIEnv, JNI_ABORT};

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeEchoReference_create(
    _: *mut JNIEnv, _: jclass, rate: jint, millis: jint,
) -> jlong {
    let capacity = rate as i64 * millis as i64 / 1000;
    if rate <= 0 || millis <= 0 || capacity <= 0 || capacity > i32::MAX as i64 { return 0; }
    Box::into_raw(Box::new(Arc::new(Mutex::new(EchoReference::new(rate, capacity as usize))))) as jlong
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeEchoReference_retain(
    _: *mut JNIEnv, _: jclass, handle: jlong,
) -> jlong { Box::into_raw(Box::new((&*(handle as *const SharedReference)).clone())) as jlong }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeEchoReference_destroy(
    _: *mut JNIEnv, _: jclass, handle: jlong,
) { if handle != 0 { drop(Box::from_raw(handle as *mut SharedReference)); } }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeEchoReference_append(
    env: *mut JNIEnv, _: jclass, handle: jlong, pcm: jbyteArray, offset: jint, length: jint, channels: jint, playing: jboolean, pending: jlong, now: jlong,
) {
    let f = &**env;
    let size = (f.GetArrayLength.unwrap())(env, pcm);
    if handle == 0 || offset < 0 || length < 0 || offset > size || length > size - offset || channels <= 0 { return; }
    let bytes = (f.GetByteArrayElements.unwrap())(env, pcm, std::ptr::null_mut());
    if bytes.is_null() { return; }
    (&*(handle as *const SharedReference)).lock().unwrap().append(
        std::slice::from_raw_parts(bytes.add(offset as usize).cast(), length as usize), channels as usize,
        if playing != 0 { Some(pending) } else { None }, now,
    );
    (f.ReleaseByteArrayElements.unwrap())(env, pcm, bytes, JNI_ABORT);
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeEchoReference_appendBuffer(
    env: *mut JNIEnv, _: jclass, handle: jlong, pcm: jobject, offset: jint, length: jint, channels: jint, playing: jboolean, pending: jlong, now: jlong,
) {
    let Some((bytes, size)) = Env(env).direct_buffer(pcm) else { return; };
    if handle == 0 || offset < 0 || length < 0 || offset as usize > size || length as usize > size - offset as usize || channels <= 0 { return; }
    (&*(handle as *const SharedReference)).lock().unwrap().append(
        std::slice::from_raw_parts(bytes.add(offset as usize), length as usize), channels as usize,
        if playing != 0 { Some(pending) } else { None }, now,
    );
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeEchoReference_reset(
    _: *mut JNIEnv, _: jclass, handle: jlong,
) { (&*(handle as *const SharedReference)).lock().unwrap().reset(); }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeEchoReference_read(
    env: *mut JNIEnv, _: jclass, handle: jlong, out: jshortArray, end: jlong,
) -> jboolean {
    let f = &**env;
    let size = (f.GetArrayLength.unwrap())(env, out) as usize;
    let values = (f.GetShortArrayElements.unwrap())(env, out, std::ptr::null_mut());
    if values.is_null() { return 0; }
    let heard = (&*(handle as *const SharedReference)).lock().unwrap().read(std::slice::from_raw_parts_mut(values, size), end);
    (f.ReleaseShortArrayElements.unwrap())(env, out, values, 0);
    heard as jboolean
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeEchoReference_captureRead(
    env: *mut JNIEnv, _: jclass, owner: jlongArray, rate: jint, samples: jint, now: jlong, stamped: jboolean, timestamp: jlong, position: jlong,
) {
    let f = &**env;
    let mut state = [0; 3];
    (f.GetLongArrayRegion.unwrap())(env, owner, 0, 3, state.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 { return; }
    capture_read(&mut state, rate, samples, now, stamped != 0, timestamp, position);
    (f.SetLongArrayRegion.unwrap())(env, owner, 0, 3, state.as_ptr());
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeEchoReference_captureEnd(
    env: *mut JNIEnv, _: jclass, owner: jlongArray, rate: jint, samples: jint,
) -> jlong {
    let f = &**env;
    let mut state = [0; 3];
    (f.GetLongArrayRegion.unwrap())(env, owner, 0, 3, state.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 { return 0; }
    capture_end(&state, rate, samples)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn echo_ring_downmixes_overwrites_and_returns_silence_outside_playback() {
        let mut owner = EchoReference::new(1000, 4);
        let pcm: Vec<_> = [100i16, 300, -200, -400, 600, 800, 1000, 1200, 1400, 1600].into_iter().flat_map(i16::to_le_bytes).collect();
        owner.append(&pcm, 2, Some(0), 0);
        let mut out = [0; 7];
        assert!(owner.read(&mut out, 2_000_000));
        assert_eq!(out, [0, -300, 700, 1100, 1500, 0, 0]);
        owner.reset();
        assert!(!owner.read(&mut out, 0));
        assert_eq!(out, [0; 7]);
    }

    #[test]
    fn capture_timestamps_and_fallback_keep_the_same_twenty_millisecond_calibration() {
        let mut state = [0; 3];
        capture_read(&mut state, 48000, 960, 1_000_000_000, false, 0, 0);
        assert_eq!(capture_end(&state, 48000, 480), 970_000_000);
        assert_eq!(capture_end(&state, 48000, 1920), 980_000_000);
        capture_read(&mut state, 48000, 960, 2_000_000_000, true, 1_000_000_000, 1440);
        assert_eq!(capture_end(&state, 48000, 960), 1_010_000_000);
        capture_read(&mut state, 48000, 480, 3_000_000_000, true, 0, 0);
        assert_eq!(capture_end(&state, 48000, 480), 2_980_000_000);
    }
}
