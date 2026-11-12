//! Screen configuration layout and the SDK byte-array adapters for receiver wire conversions.
use super::annexb;
use jni_sys::{jboolean, jbyteArray, jclass, jint, jintArray, jlong, jobjectArray, JNIEnv};
use std::ptr::null_mut;

fn config(payload: &[u8]) -> [i32; 2] {
    if payload.len() >= 8 {
        for index in 4..=payload.len() - 4 {
            let codec = match &payload[index..index + 4] {
                b"avcC" => 0,
                b"hvcC" => 1,
                _ => continue,
            };
            return [codec, (index + 4) as i32];
        }
    }
    let avc = payload.len() >= 9 && payload[5] & 0x1f != 0
        && 8 + u16::from_be_bytes([payload[6], payload[7]]) as usize <= payload.len()
        && payload[8] & 0x1f == 7;
    [if avc { 0 } else { 1 }, 0]
}

fn parameter_sets(source: &[u8], mut cursor: usize, count: usize) -> Vec<Vec<u8>> {
    let mut sets = Vec::new();
    for _ in 0..count {
        if cursor + 2 > source.len() { break; }
        let len = u16::from_be_bytes([source[cursor], source[cursor + 1]]) as usize;
        cursor += 2;
        if cursor + len > source.len() { break; }
        sets.push(source[cursor..cursor + len].to_vec()); cursor += len;
    }
    sets
}
fn avc_parameter_sets(source: &[u8]) -> [Vec<u8>; 2] {
    if source.len() < 7 { return [Vec::new(), Vec::new()]; }
    let sps = parameter_sets(source, 6, (source[5] & 31) as usize);
    // Preserve the SDK adapter's partial-record behavior: only complete SPS entries advance this cursor.
    let cursor = 6 + sps.iter().map(|s| s.len() + 2).sum::<usize>();
    if cursor >= source.len() { return [Vec::new(), Vec::new()]; }
    let pps = parameter_sets(source, cursor + 1, source[cursor] as usize);
    [sps.into_iter().next().unwrap_or_default(), pps.into_iter().next().unwrap_or_default()]
}
fn hevc_csd(source: &[u8]) -> Vec<u8> {
    if source.len() < 23 || source[0] != 1 { return Vec::new(); }
    let mut cursor = 23;
    let mut out = Vec::new();
    for _ in 0..source[22] {
        if cursor + 3 > source.len() { return Vec::new(); }
        let count = u16::from_be_bytes([source[cursor + 1], source[cursor + 2]]);
        cursor += 3;
        for _ in 0..count {
            if cursor + 2 > source.len() { return Vec::new(); }
            let len = u16::from_be_bytes([source[cursor], source[cursor + 1]]) as usize;
            cursor += 2;
            if cursor + len > source.len() { return Vec::new(); }
            out.extend_from_slice(&[0, 0, 0, 1]); out.extend_from_slice(&source[cursor..cursor + len]);
            cursor += len;
        }
    }
    out
}

unsafe fn read(env: *mut JNIEnv, array: jbyteArray) -> Option<Vec<u8>> {
    let f = &**env;
    let count = (f.GetArrayLength.unwrap())(env, array);
    let mut bytes = vec![0; count as usize];
    if count != 0 { (f.GetByteArrayRegion.unwrap())(env, array, 0, count, bytes.as_mut_ptr().cast()); }
    if (f.ExceptionCheck.unwrap())(env) != 0 { None } else { Some(bytes) }
}
unsafe fn bytes(env: *mut JNIEnv, values: &[u8]) -> jbyteArray {
    let out = ((**env).NewByteArray.unwrap())(env, values.len() as i32);
    if !out.is_null() { ((**env).SetByteArrayRegion.unwrap())(env, out, 0, values.len() as i32, values.as_ptr().cast()); }
    out
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeScreenCodec_avcParameterSets(env: *mut JNIEnv, _: jclass, input: jbyteArray) -> jobjectArray {
    let Some(input) = read(env, input) else { return null_mut(); };
    let f = &**env;
    if (f.PushLocalFrame.unwrap())(env, 8) < 0 { return null_mut(); }
    let result = (|| {
        let c = (f.FindClass.unwrap())(env, c"[B".as_ptr());
        if (f.ExceptionCheck.unwrap())(env) != 0 { return None; }
        let out = (f.NewObjectArray.unwrap())(env, 2, c, null_mut());
        if out.is_null() { return None; }
        for (i, value) in avc_parameter_sets(&input).iter().enumerate() {
            let value = bytes(env, value);
            if (f.ExceptionCheck.unwrap())(env) != 0 { return None; }
            (f.SetObjectArrayElement.unwrap())(env, out, i as i32, value);
            (f.DeleteLocalRef.unwrap())(env, value);
            if (f.ExceptionCheck.unwrap())(env) != 0 { return None; }
        }
        Some(out)
    })().unwrap_or(null_mut());
    (f.PopLocalFrame.unwrap())(env, result)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeScreenCodec_hevcCsd(env: *mut JNIEnv, _: jclass, input: jbyteArray) -> jbyteArray {
    let Some(input) = read(env, input) else { return null_mut(); };
    bytes(env, &hevc_csd(&input))
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeScreenCodec_annexBCopy(env: *mut JNIEnv, _: jclass, input: jbyteArray) -> jbyteArray {
    let Some(mut value) = read(env, input) else { return null_mut(); };
    if annexb::starts_with_start_code(&value) { return input; }
    if !annexb::to_annex_b_in_place(&mut value) { value.clear(); }
    bytes(env, &value)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeScreenCodec_randomAccess(env: *mut JNIEnv, _: jclass, input: jbyteArray, hevc: jboolean) -> jboolean {
    let Some(input) = read(env, input) else { return 0; };
    let flag = if hevc != 0 { annexb::FLAG_RANDOM_ACCESS_H265 } else { annexb::FLAG_RANDOM_ACCESS_H264 };
    (annexb::random_access_flags(&input) & flag != 0) as jboolean
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeScreenCodec_senderNanos(env: *mut JNIEnv, _: jclass, header: jbyteArray) -> jlong {
    read(env, header).map_or(0, |bytes| annexb::sender_nanos(&bytes))
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeScreenCodec_annexB(env: *mut JNIEnv, _: jclass, payload: jbyteArray) {
    let Some(mut bytes) = read(env, payload) else { return };
    if annexb::to_annex_b_in_place(&mut bytes) {
        ((**env).SetByteArrayRegion.unwrap())(env, payload, 0, bytes.len() as jint, bytes.as_ptr().cast());
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeScreenCodec_config(env: *mut JNIEnv, _: jclass, payload: jbyteArray) -> jintArray {
    let Some(bytes) = read(env, payload) else { return null_mut() };
    let f = &**env;
    let result = (f.NewIntArray.unwrap())(env, 2);
    if !result.is_null() { (f.SetIntArrayRegion.unwrap())(env, result, 0, 2, config(&bytes).as_ptr()); }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn config_offsets_keep_raw_identity_and_first_fourcc_order() {
        assert_eq!(config(&[]), [1, 0]);
        assert_eq!(config(&[1, 100, 0, 31, 255, 225, 0, 1, 103]), [0, 0]);
        assert_eq!(config(b"1234avcC"), [0, 8]);
        assert_eq!(config(b"1234hvcCdataavcC"), [1, 8]);
        assert_eq!(config(b"avcC"), [1, 0]);
        assert_eq!(config(&[1, 100, 0, 31, 255, 225, 1, 0, 103]), [1, 0]);
    }
    #[test]
    fn avcc_preserves_complete_prefix_and_hevcc_rejects_partial_arrays() {
        assert_eq!(avc_parameter_sets(&[1, 0, 0, 0, 0, 1, 0, 2, 0x67, 1, 1, 0, 1, 0x68]), [vec![0x67, 1], vec![0x68]]);
        assert_eq!(avc_parameter_sets(&[1, 0, 0, 0, 0, 1, 0, 2, 0x67, 1]), [vec![], vec![]]);
        let mut h = vec![0; 23]; h[0] = 1; h[22] = 1;
        h.extend([32, 0, 2, 0, 2, 0x40, 1, 0, 0]);
        assert_eq!(hevc_csd(&h), [0, 0, 0, 1, 0x40, 1, 0, 0, 0, 1]);
        h.pop(); assert!(hevc_csd(&h).is_empty());
    }
}
