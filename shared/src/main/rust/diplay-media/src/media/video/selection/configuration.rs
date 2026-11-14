//! One stream's decoder degradation policy; Android supplies capability/results and executes attempts.
use jni_sys::{jboolean, jclass, jint, jintArray, jlong, jlongArray, jstring, JNIEnv};
use std::ptr::null_mut;

fn attempts(rate: i32, software: bool, low_latency: bool, mtk: i32) -> Vec<i32> {
    let mut out = Vec::new();
    if low_latency {
        if rate > 0 { out.extend([1, 1, rate, 0]); }
        out.extend([1, 1, 0, 0]);
    }
    if rate > 0 { for mode in (0..=mtk).rev() { out.extend([0, 1, rate, mode]); } }
    for mode in (0..=mtk).rev() { out.extend([0, 1, 0, mode]); }
    out.extend([0, 0, 0, 0]);
    if software { out.extend([2, 0, 0, 0]); }
    out
}
fn round_next(v: &mut [i64; 26]) -> Option<[i32; 4]> {
    if v[24] != 0 { return None; }
    let flags = v[17] as i32;
    let list = attempts(v[16] as i32, flags & 1 != 0, flags & 2 != 0, v[18] as i32);
    while (v[15] as usize) < list.len() {
        let index = v[15] as usize;
        v[15] += 4;
        let name = list[index];
        let software = (name == 1 && flags & 4 != 0) || (name == 2 && flags & 8 != 0);
        if (name == 0 && v[11] != 0) || (v[19] < 29 && v[11] == 0 && software) { continue; }
        let candidate: [i32; 4] = list[index..index + 4].try_into().unwrap();
        for i in 0..4 { v[20 + i] = candidate[i] as i64; }
        return Some(candidate);
    }
    None
}
fn round_result(v: &mut [i64; 26], success: bool, same_low_latency: bool) -> i32 {
    if !success { return 0; }
    v[24] = 1;
    (v[16] > 0 && v[22] == 0) as i32 |
        (((v[17] & 2 != 0 && !same_low_latency) as i32) << 1)
}
fn name_flags(name: &[u16], advertised: bool) -> i32 {
    let starts = |prefix: &[u8]| name.iter().take(prefix.len()).copied().eq(prefix.iter().copied().map(u16::from));
    let ends = |suffix: &[u8]| name.len() >= suffix.len() && name[name.len() - suffix.len()..].iter().copied().eq(suffix.iter().copied().map(u16::from));
    let legacy = starts(b"omx.mtk.");
    let codec2 = starts(b"c2.mtk.");
    (if starts(b"c2.qti.") || starts(b"omx.qcom.") { 1 } else { 0 }) |
        (if starts(b"omx.qcom.video.decoder.") { 2 } else { 0 }) |
        (if legacy || codec2 { 4 } else { 0 }) | (if legacy { 8 } else { 0 }) |
        (if codec2 && (advertised || ends(b".lowlatency") || ends(b".low_latency")) { 16 } else { 0 })
}
fn configure_event(v: &mut [i64], event: i32, a: i32, b: i32, c: i32, flags: i32) -> i32 {
    match event {
        0 => { v[11] = 0; 0 }, // new configure pass
        1 => { // actual successful attempt and stream-wide MTK degradation
            v[1] = a as i64; v[3] = b as i64; v[4] = ((flags & 3) == 3) as i64;
            if flags & 5 == 5 && b < c { 1 } else { 0 }
        },
        2 => { // runtime private-parameter rejection
            if v[6] != 0 && v[7] == 0 { v[7] = 1; 1 } else { 0 }
        },
        3 => { let previous = v[0] as i32; v[0] = 0; previous },
        4 => {
            let previous = v[3] as i32;
            if previous == 0 { return -1; }
            let next = (previous - 1).max(0);
            v[2] = v[2].min(next as i64);
            previous | (next << 2)
        },
        5 => { if v[4] == 0 { 0 } else { v[4] = 0; v[8] = 1; 1 } },
        6 => { v[8] = 1; 0 },
        7 => { v[11] = a as i64; 0 },
        8 => { // facts about the codec which configure/start actually accepted
            v[5] = (flags & 1 != 0) as i64; v[6] = (flags & 2 != 0) as i64;
            0
        },
        9 => { v[12] = super::probe(0, v[12], true)[1]; 0 },
        10 => { v[9] = (flags & 1 != 0) as i64; v[10] = (flags & 2 != 0) as i64; 0 },
        11 => { v[2] = v[3]; 0 }, // commit only after the host's existing fallback diagnostic
        12 => (v[1] > 0 && flags & 1 != 0 && v[25] == 0) as i32, // codec failure before first rendered frame
        13 => { for index in [1, 3, 4, 5, 6] { v[index] = 0; } 0 },
        17 => { v[25] = 1; 0 },
        18 => { v[25] = 0; 0 },
        19 => { if flags & 1 != 0 { v[7] = 1; 1 } else { 0 } },
        _ => unreachable!(),
    }
}
unsafe fn invalid(env: *mut JNIEnv) {
    let f = &**env;
    if (f.ExceptionCheck.unwrap())(env) != 0 { return; }
    let c = (f.FindClass.unwrap())(env, c"java/lang/IllegalArgumentException".as_ptr());
    if !c.is_null() { (f.ThrowNew.unwrap())(env, c, c"Invalid decoder configuration state or mode".as_ptr()); }
}
unsafe fn ints(env: *mut JNIEnv, values: &[i32]) -> jintArray {
    let out = ((**env).NewIntArray.unwrap())(env, values.len() as i32);
    if !out.is_null() { ((**env).SetIntArrayRegion.unwrap())(env, out, 0, values.len() as i32, values.as_ptr()); }
    out
}
unsafe fn read_ints(env: *mut JNIEnv, input: jintArray) -> Option<Vec<i32>> {
    let f = &**env; let n = (f.GetArrayLength.unwrap())(env, input);
    let mut v = vec![0; n as usize];
    if n > 0 { (f.GetIntArrayRegion.unwrap())(env, input, 0, n, v.as_mut_ptr()); }
    ((f.ExceptionCheck.unwrap())(env) == 0).then_some(v)
}
unsafe fn read_state(env: *mut JNIEnv, input: jlongArray) -> Option<[i64; 26]> {
    if input.is_null() || ((**env).GetArrayLength.unwrap())(env, input) != 26 { invalid(env); return None; }
    let mut v = [0; 26]; ((**env).GetLongArrayRegion.unwrap())(env, input, 0, 26, v.as_mut_ptr());
    (((**env).ExceptionCheck.unwrap())(env) == 0).then_some(v)
}
unsafe fn write_state(env: *mut JNIEnv, input: jlongArray, v: &[i64; 26]) {
    ((**env).SetLongArrayRegion.unwrap())(env, input, 0, 26, v.as_ptr());
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeVideoPolicy_nameFlags(env: *mut JNIEnv, _: jclass, name: jstring, advertised: jboolean) -> jint {
    let f = &**env; let len = (f.GetStringLength.unwrap())(env, name) as usize;
    let chars = (f.GetStringChars.unwrap())(env, name, null_mut()); if chars.is_null() { return 0; }
    let result = name_flags(std::slice::from_raw_parts(chars, len), advertised != 0);
    (f.ReleaseStringChars.unwrap())(env, name, chars); result
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeVideoPolicy_mtkMode(env: *mut JNIEnv, _: jclass, mode: jint, op: jint) -> jintArray {
    if !(0..=2).contains(&mode) || !(0..=2).contains(&op) { invalid(env); return null_mut(); }
    let values = match op { 0 => vec![(mode - 1).max(0)], 1 => (0..=mode).rev().collect(), _ => (0..mode).collect() };
    ints(env, &values)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeVideoPolicy_mtkSelect(env: *mut JNIEnv, _: jclass, flags: jintArray, mode: jint) -> jint {
    let Some(flags) = read_ints(env, flags) else { return -1; };
    let low = flags.iter().position(|f| f & 16 != 0).map_or(-1, |i| i as i32);
    let legacy = if flags.first().is_some_and(|f| f & 8 != 0) { 0 } else { -1 };
    match mode { 0 => if low >= 0 { low } else { legacy }, 1 => low, 2 => legacy, _ => { invalid(env); -1 } }
}
#[no_mangle]
pub extern "system" fn Java_com_shihab_diplay_media_NativeVideoPolicy_appliedMtkMode(_: *mut JNIEnv, _: jclass, mode: jint, legacy: jboolean) -> jint {
    if legacy != 0 { mode } else { 0 }
}
#[no_mangle]
pub extern "system" fn Java_com_shihab_diplay_media_NativeVideoPolicy_operatingRate(_: *mut JNIEnv, _: jclass, stream_type: jint, labelled: jboolean, frame_rate: jint) -> jint {
    if stream_type == 110 && labelled == 0 && frame_rate > 0 { frame_rate } else { 0 }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeVideoPolicy_lowLatency(env: *mut JNIEnv, _: jclass, flags: jintArray) -> jint {
    let Some(v) = read_ints(env, flags) else { return -1; };
    v.iter().position(|f| f & 7 == 5).map_or(-1, |i| i as i32)
}
#[no_mangle]
pub extern "system" fn Java_com_shihab_diplay_media_NativeVideoPolicy_workerWait(_: *mut JNIEnv, _: jclass, drains: jboolean, last: jlong, now: jlong) -> jlong {
    if drains != 0 && last != 0 && now.wrapping_sub(last) < 500_000_000 { 5 } else { 50 }
}
#[no_mangle]
pub extern "system" fn Java_com_shihab_diplay_media_NativeVideoPolicy_prefersSoftware(_: *mut JNIEnv, _: jclass, sdk: jint, hevc: jboolean, requested: jboolean) -> jboolean {
    (sdk >= 29 && hevc != 0 && requested != 0) as jboolean
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeVideoPolicy_defaultDecoder(env: *mut JNIEnv, _: jclass, flags: jintArray, software: jboolean, hardware_failed: jboolean) -> jint {
    let Some(flags) = read_ints(env, flags) else { return -1; };
    if software != 0 {
        if let Some(index) = flags.iter().position(|f| f & 1 != 0) { return index as i32; }
    }
    if hardware_failed != 0 { -1 } else { super::select_decoder(&flags, false) }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeVideoPolicy_softwareOutcome(env: *mut JNIEnv, _: jclass, state: jlongArray, hardware: jboolean, software: jboolean, preferred: jboolean, same_hardware: jboolean) -> jint {
    let Some(mut v) = read_state(env, state) else { return 0; };
    if hardware == 0 || software == 0 || preferred != 0 {
        if hardware != 0 && same_hardware != 0 { v[12] = 2_000; write_state(env, state, &v); }
        0
    } else { 1 }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeVideoPolicy_configurationCreate(env: *mut JNIEnv, _: jclass, rate: jint, mtk: jboolean, vendor_refused: jboolean) -> jlongArray {
    let mut v = [0; 26]; v[0] = rate as i64; v[2] = if mtk != 0 { 2 } else { 0 };
    v[7] = (vendor_refused != 0) as i64; v[12] = 2_000; v[13] = (mtk != 0) as i64;
    let out = ((**env).NewLongArray.unwrap())(env, 26); if !out.is_null() { write_state(env, out, &v); } out
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeVideoPolicy_configurationBegin(
    env: *mut JNIEnv, _: jclass, state: jlongArray, rate: jint, flags: jint, mode: jint, sdk: jint,
) {
    let Some(mut v) = read_state(env, state) else { return; };
    if !(0..=2).contains(&mode) { invalid(env); return; }
    configure_event(&mut v, 0, 0, 0, 0, 0);
    v[15..25].copy_from_slice(&[0, rate as i64, flags as i64, mode as i64, sdk as i64, 0, 0, 0, 0, 0]);
    write_state(env, state, &v);
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeVideoPolicy_configurationNext(
    env: *mut JNIEnv, _: jclass, state: jlongArray,
) -> jintArray {
    let Some(mut v) = read_state(env, state) else { return null_mut(); };
    let candidate = round_next(&mut v);
    write_state(env, state, &v);
    ints(env, candidate.as_ref().map_or(&[], |candidate| candidate.as_slice()))
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeVideoPolicy_configurationResult(
    env: *mut JNIEnv, _: jclass, state: jlongArray, success: jboolean, same_low_latency: jboolean,
) -> jint {
    let Some(mut v) = read_state(env, state) else { return 0; };
    let result = round_result(&mut v, success != 0, same_low_latency != 0);
    write_state(env, state, &v);
    result
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeVideoPolicy_configurationQuery(env: *mut JNIEnv, _: jclass, state: jlongArray, field: jint, capability: jboolean) -> jlong {
    let Some(v) = read_state(env, state) else { return 0; };
    match field {
        0..=14 | 22 => v[field as usize],
        100 => (v[13] != 0 && v[8] == 0) as i64,
        101 => (v[8] == 0) as i64,
        102 => if v[13] != 0 && capability != 0 { v[2] } else { 0 },
        _ => { invalid(env); 0 },
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeVideoPolicy_configurationEvent(env: *mut JNIEnv, _: jclass, state: jlongArray, event: jint, a: jint, b: jint, c: jint, flags: jint) -> jint {
    let Some(mut v) = read_state(env, state) else { return 0; };
    if !(0..=13).contains(&event) && !(17..=19).contains(&event) { invalid(env); return 0; }
    let result = configure_event(&mut v, event, a, b, c, flags); write_state(env, state, &v); result
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeVideoPolicy_formatFlags(
    env: *mut JNIEnv, _: jclass, state: jlongArray, tuned: jboolean, at_once: jboolean, vendor_requested: jboolean,
    sdk: jint, advertised_low_latency: jboolean, picture_listed: jboolean, name_flags: jint,
) -> jint {
    let Some(v) = read_state(env, state) else { return 0; };
    let low = tuned != 0 && sdk >= 30 && advertised_low_latency != 0;
    let vendor = tuned != 0 && vendor_requested != 0 && v[7] == 0 && name_flags & 1 != 0;
    let picture = tuned != 0 && at_once != 0 && v[7] == 0 && (picture_listed != 0 || name_flags & 2 != 0);
    (low as i32) | ((vendor as i32) << 1) | ((picture as i32) << 2) |
        (((vendor || (picture && picture_listed == 0)) as i32) << 3)
}

#[cfg(test)]
mod contracts {
    use super::*;
    #[test]
    fn attempts_preserve_named_first_then_rate_mtk_minimal_and_software_order() {
        let a = attempts(30, true, true, 2);
        assert_eq!(&a[..8], [1, 1, 30, 0, 1, 1, 0, 0]);
        assert_eq!(&a[8..20], [0, 1, 30, 2, 0, 1, 30, 1, 0, 1, 30, 0]);
        assert_eq!(&a[a.len() - 8..], [0, 0, 0, 0, 2, 0, 0, 0]);
        assert_eq!(attempts(-1, false, false, 0), [0, 1, 0, 0, 0, 0, 0, 0]);
    }
    #[test]
    fn round_advances_after_failures_and_commits_only_success() {
        let mut v = [0; 26];
        v[16] = 60; v[17] = 11; v[18] = 2; v[19] = 28;
        assert_eq!(round_next(&mut v), Some([1, 1, 60, 0]));
        assert_eq!(round_result(&mut v, false, true), 0);
        assert_eq!(round_next(&mut v), Some([1, 1, 0, 0]));
        assert_eq!(round_result(&mut v, false, true), 0);
        assert_eq!(round_next(&mut v), Some([0, 1, 60, 2]));
        v[11] = 1; // hardware creation exhausted its retries
        assert_eq!(round_next(&mut v), Some([2, 0, 0, 0]));
        assert_eq!(round_result(&mut v, true, false), 3);
        assert_eq!(round_next(&mut v), None);
        let mut no_success = [0; 26];
        no_success[16] = 60; no_success[19] = 28;
        while round_next(&mut no_success).is_some() { assert_eq!(round_result(&mut no_success, false, false), 0); }
        assert_eq!(no_success[24], 0);
    }
    #[test]
    fn active_degradation_is_one_owner_and_runtime_vendor_rejection_notifies_once() {
        let mut v = [0; 26]; v[0] = 60; v[2] = 2; v[12] = 2_000;
        configure_event(&mut v, 8, 0, 0, 0, 3);
        assert_eq!(configure_event(&mut v, 2, 0, 0, 0, 0), 1);
        assert_eq!(configure_event(&mut v, 2, 0, 0, 0, 0), 0);
        assert_eq!(configure_event(&mut v, 3, 0, 0, 0, 0), 60); assert_eq!(v[0], 0);
        assert_eq!(configure_event(&mut v, 1, 30, 1, 2, 5), 1);
        assert_eq!(configure_event(&mut v, 12, 0, 0, 0, 1), 1);
        configure_event(&mut v, 17, 0, 0, 0, 0);
        assert_eq!(configure_event(&mut v, 12, 0, 0, 0, 1), 0);
        configure_event(&mut v, 18, 0, 0, 0, 0);
        assert_eq!(configure_event(&mut v, 12, 0, 0, 0, 1), 1);
        assert_eq!(configure_event(&mut v, 19, 0, 0, 0, 0), 0);
        assert_eq!(configure_event(&mut v, 19, 0, 0, 0, 1), 1);
        configure_event(&mut v, 11, 0, 0, 0, 0); assert_eq!(v[2], 1);
        assert_eq!(configure_event(&mut v, 4, 0, 0, 0, 0), 1); assert_eq!(v[2], 0);
        for expected in [4_000, 8_000, 16_000, 30_000, 30_000] { configure_event(&mut v, 9, 1, 0, 0, 0); assert_eq!(v[12], expected); }
        assert_eq!(name_flags(&"omx.qcom.video.decoder.avc".encode_utf16().collect::<Vec<_>>(), false), 3);
        assert_eq!(name_flags(&"c2.mtk.avc.low_latency".encode_utf16().collect::<Vec<_>>(), false), 20);
    }
}
