//! Screen configuration layout and the SDK byte-array adapters for receiver wire conversions.
use super::annexb;
use jni_sys::{jbyteArray, jclass, jint, jintArray, jlong, JNIEnv};
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

unsafe fn read(env: *mut JNIEnv, array: jbyteArray) -> Option<Vec<u8>> {
    let f = &**env;
    let count = (f.GetArrayLength.unwrap())(env, array);
    let mut bytes = vec![0; count as usize];
    if count != 0 { (f.GetByteArrayRegion.unwrap())(env, array, 0, count, bytes.as_mut_ptr().cast()); }
    if (f.ExceptionCheck.unwrap())(env) != 0 { None } else { Some(bytes) }
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
}
