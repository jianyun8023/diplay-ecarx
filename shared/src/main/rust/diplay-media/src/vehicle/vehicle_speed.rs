//! Apple `$PASCD` layout and checksum. Kotlin supplies this platform's Formatter output.

/// Parts are start time, then offset/speed pairs. Gear is P/R/N/D in ordinal order.
pub fn pascd_encode(gear: i32, parts: &[String]) -> Option<String> {
    if parts.len() < 3 || parts.len() % 2 == 0 || !(0..=3).contains(&gear) { return None; }
    let mut body = format!("PASCD,{},C,{},0,{}", parts[0], ['P', 'R', 'N', 'D'][gear as usize], (parts.len() - 1) / 2);
    for part in &parts[1..] {
        body.push(',');
        body.push_str(part);
    }
    let checksum = body.bytes().fold(0u8, |acc, byte| acc ^ byte);
    Some(format!("${body}*{checksum:02X}\r\n"))
}

use crate::platform::jvm::Env;
use jni_sys::{jclass, jint, jlong, jlongArray, jdouble, jdoubleArray, jobjectArray, jstring, JNIEnv};

// A value token holds one typed P/R/N/D gear and timestamp/speed-bit pairs. Empty is unknown.
// A gear transition discards the previous segment: PASCD has one gear for its entire batch.
fn speed_gear(state: &[i64], gear: i32) -> Vec<i64> {
    if !(0..=3).contains(&gear) { return Vec::new(); }
    if state.first() == Some(&(gear as i64)) { state.to_vec() } else { vec![gear as i64] }
}

fn speed_sample(state: &[i64], elapsed: i64, speed: f64) -> Vec<i64> {
    if state.is_empty() { return Vec::new(); }
    if elapsed < 0 || !speed.is_finite() || !(0.0..=125.0).contains(&speed)
        || (state.len() > 1 && elapsed < state[state.len() - 2]) {
        return state[..1].to_vec();
    }
    let mut next = state.to_vec();
    // Keep a bounded latest batch if the control consumer stalls. 512 worst-width timestamp /
    // speed pairs still fit well below an iAP2 parameter's 65,531-byte payload budget.
    if (next.len() - 1) / 2 >= 512 { next.drain(1..3); }
    next.extend([elapsed, speed.to_bits() as i64]);
    next
}

fn speed_snapshot(state: &[i64]) -> Vec<f64> {
    if state.len() < 3 { return Vec::new(); }
    let mut result = vec![state[0] as f64];
    for pair in state[1..].chunks_exact(2) {
        result.extend([pair[0] as f64, f64::from_bits(pair[1] as u64)]);
    }
    result
}

unsafe fn speed_read(env: *mut JNIEnv, state: jlongArray) -> Option<Vec<i64>> {
    let f = &**env;
    if state.is_null() { return None; }
    let count = (f.GetArrayLength.unwrap())(env, state);
    let mut words = vec![0; count as usize];
    (f.GetLongArrayRegion.unwrap())(env, state, 0, count, words.as_mut_ptr());
    ((f.ExceptionCheck.unwrap())(env) == 0).then_some(words)
}

unsafe fn speed_array(env: *mut JNIEnv, words: &[i64]) -> jlongArray {
    let f = &**env;
    let result = (f.NewLongArray.unwrap())(env, words.len() as jint);
    if !result.is_null() {
        (f.SetLongArrayRegion.unwrap())(env, result, 0, words.len() as jint, words.as_ptr());
    }
    result
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeVehicleSpeed_speedGear(
    env: *mut JNIEnv, _: jclass, state: jlongArray, gear: jint,
) -> jlongArray {
    let Some(words) = speed_read(env, state) else { return std::ptr::null_mut(); };
    speed_array(env, &speed_gear(&words, gear))
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeVehicleSpeed_speedSample(
    env: *mut JNIEnv, _: jclass, state: jlongArray, elapsed: jlong, speed: jdouble,
) -> jlongArray {
    let Some(words) = speed_read(env, state) else { return std::ptr::null_mut(); };
    speed_array(env, &speed_sample(&words, elapsed, speed))
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeVehicleSpeed_speedSnapshot(
    env: *mut JNIEnv, _: jclass, state: jlongArray,
) -> jdoubleArray {
    let Some(words) = speed_read(env, state) else { return std::ptr::null_mut(); };
    let values = speed_snapshot(&words);
    let f = &**env;
    let result = (f.NewDoubleArray.unwrap())(env, values.len() as jint);
    if !result.is_null() {
        (f.SetDoubleArrayRegion.unwrap())(env, result, 0, values.len() as jint, values.as_ptr());
    }
    result
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeVehicleSpeed_pascdEncode(
    env: *mut JNIEnv, _class: jclass, gear: jint, formatted_parts: jobjectArray,
) -> jstring {
    if formatted_parts.is_null() { return std::ptr::null_mut(); }
    let f = &**env;
    let count = (f.GetArrayLength.unwrap())(env, formatted_parts);
    if count < 3 || count % 2 == 0 || !(0..=3).contains(&gear) { return std::ptr::null_mut(); }
    let mut parts = Vec::with_capacity(count as usize);
    for index in 0..count {
        let value = (f.GetObjectArrayElement.unwrap())(env, formatted_parts, index) as jstring;
        if value.is_null() { return std::ptr::null_mut(); }
        let chars = (f.GetStringChars.unwrap())(env, value, std::ptr::null_mut());
        if chars.is_null() {
            (f.DeleteLocalRef.unwrap())(env, value);
            return std::ptr::null_mut();
        }
        let length = (f.GetStringLength.unwrap())(env, value) as usize;
        parts.push(String::from_utf16_lossy(std::slice::from_raw_parts(chars, length)));
        (f.ReleaseStringChars.unwrap())(env, value, chars);
        (f.DeleteLocalRef.unwrap())(env, value);
    }
    match pascd_encode(gear, &parts) {
        Some(sentence) => Env(env).string(&sentence),
        None => std::ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pinned_head_unit_layout_and_checksum_match() {
        let parts = ["17877.092", "0.00", "0.000", "0.16", "0.000"].map(String::from);
        assert_eq!(pascd_encode(0, &parts).unwrap(), "$PASCD,17877.092,C,P,0,2,0.00,0.000,0.16,0.000*54\r\n");
    }

    #[test]
    fn platform_formatted_values_are_preserved_and_invalid_shapes_rejected() {
        let parts = ["1.000", "-0.00", "-0.000", "1.01", "Infinity"].map(String::from);
        assert!(pascd_encode(3, &parts).unwrap().starts_with("$PASCD,1.000,C,D,0,2,-0.00,-0.000,1.01,Infinity*"));
        assert!(pascd_encode(4, &parts).is_none());
        assert!(pascd_encode(0, &[]).is_none());
        assert!(pascd_encode(0, &parts[..4]).is_none());
    }

    #[test]
    fn speed_batches_preserve_meters_per_second_and_do_not_mix_gears() {
        assert!(speed_sample(&[], 1000, 5.0).is_empty());
        let drive = speed_gear(&[], 3);
        let drive = speed_sample(&drive, 1000, 10.0);
        let drive = speed_sample(&drive, 1250, 0.0);
        assert_eq!(speed_snapshot(&drive), [3.0, 1000.0, 10.0, 1250.0, 0.0]);
        assert_eq!(speed_snapshot(&speed_gear(&drive, 3)), speed_snapshot(&drive));
        assert!(speed_snapshot(&speed_gear(&drive, 1)).is_empty());
        assert!(speed_gear(&drive, -1).is_empty());
    }

    #[test]
    fn invalid_speed_or_clock_clears_pending_samples_without_inventing_zero() {
        let state = speed_sample(&speed_gear(&[], 0), 2000, 0.0);
        for value in [f64::NAN, f64::INFINITY, -0.1, 125.1] {
            assert_eq!(speed_sample(&state, 2100, value), [0]);
        }
        assert_eq!(speed_sample(&state, 1999, 1.0), [0]);
        assert_eq!(speed_sample(&state, -1, 1.0), [0]);
        assert_eq!(speed_snapshot(&speed_sample(&[2], 0, 125.0)), [2.0, 0.0, 125.0]);
    }

    #[test]
    fn stalled_consumer_retains_only_a_wire_sized_latest_batch() {
        let mut state = speed_gear(&[], 3);
        for time in 0..1024 { state = speed_sample(&state, time, 125.0); }
        let snapshot = speed_snapshot(&state);
        assert_eq!(snapshot.len(), 1 + 512 * 2);
        assert_eq!(&snapshot[..3], &[3.0, 512.0, 125.0]);
        assert_eq!(&snapshot[snapshot.len() - 2..], &[1023.0, 125.0]);
    }
}
