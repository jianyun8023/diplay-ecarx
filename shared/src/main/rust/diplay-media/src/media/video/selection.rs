//! Decoder choice and retry/probe policy. The host enumerates codecs and performs create/sleep.
use jni_sys::{jboolean, jclass, jint, jintArray, jlong, jlongArray, jstring, JNIEnv};
use std::ptr::null_mut;
mod configuration;

pub fn select_decoder(flags: &[i32], software: bool) -> i32 {
    flags.iter().position(|flags| flags & 2 == 0 && (flags & 1 != 0) == software).map_or(-1, |index| index as i32)
}

pub fn probe(now: i64, interval: i64, failed: bool) -> [i64; 2] {
    let next = if failed { interval.wrapping_mul(2).min(30_000) } else { interval };
    [now.wrapping_add(next.wrapping_mul(1_000_000)), next]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn codec_order_excludes_aliases_and_probe_preserves_bounds_and_wrapping_clock() {
        assert_eq!(select_decoder(&[2, 1, 0, 1], false), 2);
        assert_eq!(select_decoder(&[2, 1, 0, 1], true), 1);
        assert_eq!(select_decoder(&[2, 3], false), -1);
        assert_eq!(probe(5, 2000, true), [4_000_000_005, 4000]);
        assert_eq!(probe(5, 30000, true), [30_000_000_005, 30000]);
        assert_eq!(probe(i64::MAX, 1, false)[0], i64::MIN + 999_999);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeVideoPolicy_softwareName(env: *mut JNIEnv, _: jclass, name: jstring) -> jboolean {
    let f = &**env;
    let length = (f.GetStringLength.unwrap())(env, name) as usize;
    let chars = (f.GetStringChars.unwrap())(env, name, null_mut());
    if chars.is_null() { return 0; }
    let units = std::slice::from_raw_parts(chars, length);
    // The String adapter supplies Java's one-Char upper/lower fold, including API 28 Unicode.
    let result = units.starts_with(&(*b"omx.google.").map(u16::from))
        || units.starts_with(&(*b"c2.android.").map(u16::from));
    (f.ReleaseStringChars.unwrap())(env, name, chars);
    result as jboolean
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeVideoPolicy_select(env: *mut JNIEnv, _: jclass, input: jintArray, software: jboolean) -> jint {
    let f = &**env;
    let count = (f.GetArrayLength.unwrap())(env, input);
    let mut flags = vec![0; count as usize];
    if count != 0 { (f.GetIntArrayRegion.unwrap())(env, input, 0, count, flags.as_mut_ptr()); }
    if (f.ExceptionCheck.unwrap())(env) != 0 { return -1; }
    select_decoder(&flags, software != 0)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeVideoPolicy_probe(env: *mut JNIEnv, _: jclass, now: jlong, interval: jlong, failed: jboolean) -> jlongArray {
    let f = &**env;
    let result = (f.NewLongArray.unwrap())(env, 2);
    if !result.is_null() { (f.SetLongArrayRegion.unwrap())(env, result, 0, 2, probe(now, interval, failed != 0).as_ptr()); }
    result
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeVideoPolicy_probeDue(_: *mut JNIEnv, _: jclass, due: jlong, now: jlong) -> jboolean {
    (now >= due) as jboolean
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeVideoPolicy_retryCreate(env: *mut JNIEnv, _: jclass) -> jlongArray {
    ((**env).NewLongArray.unwrap())(env, 1)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeVideoPolicy_retryAttempt(env: *mut JNIEnv, _: jclass, token: jlongArray, count: jint) -> jint {
    let f = &**env;
    let mut value = 0;
    (f.GetLongArrayRegion.unwrap())(env, token, 0, 1, &mut value);
    if (f.ExceptionCheck.unwrap())(env) != 0 { return 0; }
    let attempt = value as i32;
    if attempt >= count { 0 } else { attempt.wrapping_add(1) }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeVideoPolicy_retryCommit(env: *mut JNIEnv, _: jclass, token: jlongArray) -> jint {
    let f = &**env;
    let mut value = 0;
    (f.GetLongArrayRegion.unwrap())(env, token, 0, 1, &mut value);
    if (f.ExceptionCheck.unwrap())(env) != 0 { return 0; }
    let attempt = value as i32;
    let next = attempt.wrapping_add(1) as i64;
    (f.SetLongArrayRegion.unwrap())(env, token, 0, 1, &next);
    attempt
}
