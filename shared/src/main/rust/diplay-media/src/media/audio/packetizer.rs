//! The SDK DatagramSocket path shares the native sender's wire seal, clock and frame calibration.
use super::uplink;
use jni_sys::{jboolean, jbyteArray, jclass, jint, jintArray, jlong, jlongArray, JNIEnv};
use ring::aead::{LessSafeKey, UnboundKey, CHACHA20_POLY1305};
use std::ptr::null_mut;

fn plan(opus: bool, rate: i32, channels: i32, millis: i32, clock: i32) -> [i32; 4] {
    let samples = if opus { 960 } else { (rate.wrapping_mul(millis) / 1000).max(1) };
    [samples, if opus { clock } else { rate }, if opus { clock.wrapping_mul(20) / 1000 } else { samples }, samples.wrapping_mul(channels).wrapping_mul(2)]
}

fn opus_clock(bits: i64) -> i32 {
    if bits & 0x4000_0000 != 0 { 48_000 } else if bits & 0x2000_0000 != 0 { 24_000 }
    else if bits & 0x1000_0000 != 0 { 16_000 } else { 48_000 }
}

fn frame_millis(rate: i32, channels: i32) -> i32 {
    [20, 10, 5].into_iter().find(|millis| {
        36i32.wrapping_add((rate.wrapping_mul(*millis) / 1000).wrapping_mul(channels).wrapping_mul(2)) <= 1452
    }).unwrap_or(5)
}

fn advance(state: &mut [i64; 3], samples: i32) {
    state[0] = ((state[0] as i32).wrapping_add(1) & 0xffff) as i64;
    state[1] = (state[1] as i32).wrapping_add(samples) as i64;
    state[2] = state[2].wrapping_add(1);
}

unsafe fn read(env: *mut JNIEnv, array: jbyteArray) -> Option<Vec<u8>> {
    let f = &**env;
    let count = (f.GetArrayLength.unwrap())(env, array);
    let mut bytes = vec![0; count as usize];
    if count != 0 { (f.GetByteArrayRegion.unwrap())(env, array, 0, count, bytes.as_mut_ptr().cast()); }
    if (f.ExceptionCheck.unwrap())(env) != 0 { None } else { Some(bytes) }
}

unsafe fn bytes(env: *mut JNIEnv, value: &[u8]) -> jbyteArray {
    let f = &**env;
    let result = (f.NewByteArray.unwrap())(env, value.len() as jint);
    if !result.is_null() && !value.is_empty() { (f.SetByteArrayRegion.unwrap())(env, result, 0, value.len() as jint, value.as_ptr().cast()); }
    result
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMicrophonePacketizer_counters(env: *mut JNIEnv, _: jclass, sequence: jint, timestamp: jint, nonce: jlong) -> jlongArray {
    let f = &**env;
    let result = (f.NewLongArray.unwrap())(env, 3);
    if !result.is_null() { (f.SetLongArrayRegion.unwrap())(env, result, 0, 3, [sequence as i64, timestamp as i64, nonce].as_ptr()); }
    result
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMicrophonePacketizer_counter(env: *mut JNIEnv, _: jclass, owner: jlongArray, field: jint, write: jboolean, value: jlong) -> jlong {
    let f = &**env;
    if !(0..3).contains(&field) {
        let class = (f.FindClass.unwrap())(env, c"java/lang/IllegalArgumentException".as_ptr());
        if !class.is_null() { (f.ThrowNew.unwrap())(env, class, c"Invalid microphone counter".as_ptr()); }
        return 0;
    }
    if write != 0 { (f.SetLongArrayRegion.unwrap())(env, owner, field, 1, &value); return value; }
    let mut result = 0;
    (f.GetLongArrayRegion.unwrap())(env, owner, field, 1, &mut result);
    result
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMicrophonePacketizer_seal(env: *mut JNIEnv, _: jclass, owner: jlongArray, key: jbyteArray, payload_type: jint, body: jbyteArray, samples: jint) -> jbyteArray {
    let f = &**env;
    let Some(key) = read(env, key) else { return null_mut() };
    let Ok(key) = UnboundKey::new(&CHACHA20_POLY1305, &key) else {
        let class = (f.FindClass.unwrap())(env, c"java/lang/IllegalArgumentException".as_ptr());
        if !class.is_null() { (f.ThrowNew.unwrap())(env, class, c"ChaCha20 requires a 256 bit key".as_ptr()); }
        return null_mut();
    };
    let Some(body) = read(env, body) else { return null_mut() };
    let mut state = [0; 3];
    (f.GetLongArrayRegion.unwrap())(env, owner, 0, 3, state.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 { return null_mut(); }
    let mut packet = vec![0; 12];
    packet.extend_from_slice(&body);
    if !uplink::seal(&LessSafeKey::new(key), (payload_type & 0x7f) as u8, state[0] as u16, state[1] as u32, state[2] as u64, &mut packet) {
        let class = (f.FindClass.unwrap())(env, c"java/lang/IllegalStateException".as_ptr());
        if !class.is_null() { (f.ThrowNew.unwrap())(env, class, c"Could not seal microphone packet".as_ptr()); }
        return null_mut();
    }
    let result = bytes(env, &packet);
    if !result.is_null() && (f.ExceptionCheck.unwrap())(env) == 0 {
        advance(&mut state, samples);
        (f.SetLongArrayRegion.unwrap())(env, owner, 0, 3, state.as_ptr());
    }
    result
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMicrophonePacketizer_wirePcm(env: *mut JNIEnv, _: jclass, pcm: jbyteArray) -> jbyteArray {
    let Some(mut pcm) = read(env, pcm) else { return null_mut() };
    for pair in pcm.chunks_exact_mut(2) { pair.swap(0, 1); }
    bytes(env, &pcm)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMicrophonePacketizer_plan(env: *mut JNIEnv, _: jclass, opus: jboolean, rate: jint, channels: jint, millis: jint, clock: jint) -> jintArray {
    let f = &**env;
    let result = (f.NewIntArray.unwrap())(env, 4);
    if !result.is_null() { (f.SetIntArrayRegion.unwrap())(env, result, 0, 4, plan(opus != 0, rate, channels, millis, clock).as_ptr()); }
    result
}

#[no_mangle]
pub extern "system" fn Java_com_shihab_diplay_airplay_NativeMicrophonePacketizer_opusClockRate(_: *mut JNIEnv, _: jclass, bits: jlong) -> jint { opus_clock(bits) }

#[no_mangle]
pub extern "system" fn Java_com_shihab_diplay_airplay_NativeMicrophonePacketizer_pcmFrameMillis(_: *mut JNIEnv, _: jclass, rate: jint, channels: jint) -> jint { frame_millis(rate, channels) }

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn capture_calibration_and_counter_wrap_keep_wire_contract() {
        assert_eq!(plan(true, 48_000, 1, 20, 24_000), [960, 24_000, 480, 1920]);
        assert_eq!(plan(false, 24_000, 1, 20, 0), [480, 24_000, 480, 960]);
        assert_eq!([frame_millis(48_000, 1), frame_millis(24_000, 1), frame_millis(48_000, 2)], [10, 20, 5]);
        assert_eq!([opus_clock(0x7000_0000), opus_clock(0x3000_0000), opus_clock(0x1000_0000)], [48_000, 24_000, 16_000]);
        let mut state = [65535, i32::MAX as i64, i64::MAX];
        advance(&mut state, 1);
        assert_eq!(state, [0, i32::MIN as i64, i64::MIN]);
    }
}
