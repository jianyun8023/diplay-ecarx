//! Big-endian scalars and NUL/list layout. Platform UTF-8 conversion remains at the string boundary.
use super::iap2_body::{byte_array, read_bytes, throw};
use jni_sys::{jboolean, jbyteArray, jclass, jint, jintArray, jlong, jobjectArray, JNIEnv};
use std::ptr::null_mut;

fn width(kind: i32) -> Option<usize> {
    match kind { 0 | 1 | 7 | 8 => Some(1), 2 | 3 => Some(2), 4 | 5 => Some(4), 6 => Some(8), _ => None }
}

pub fn encode_scalar(kind: i32, value: i64) -> Result<Vec<u8>, &'static str> {
    let valid = match kind {
        0 => (0..=255).contains(&value), 1 => (-128..=127).contains(&value),
        2 => (0..=65535).contains(&value), 3 => (-32768..=32767).contains(&value),
        4 => (0..=0xffff_ffff).contains(&value), 5 => (i32::MIN as i64..=i32::MAX as i64).contains(&value),
        6 => value >= 0, _ => return Err("Unknown iAP2 scalar kind"),
    };
    if !valid {
        return Err(match kind {
            0 => "u8 value must be in 0..255", 1 => "i8 value must be in -128..127",
            2 => "u16 value must be in 0..65535", 3 => "i16 value must be in -32768..32767",
            4 => "u32 value must be in 0..4294967295", 5 => "i32 value must fit in 32 bits",
            _ => "u64 value must be non-negative in this API",
        });
    }
    let bytes = value.to_be_bytes();
    Ok(bytes[8 - width(kind).unwrap()..].to_vec())
}

fn read_scalar(kind: i32, bytes: &[u8]) -> i64 {
    let value = bytes.iter().fold(0u64, |a, b| (a << 8) | *b as u64);
    match kind {
        1 => value as i8 as i64, 3 => value as i16 as i64, 5 => value as i32 as i64,
        7 => (value != 0) as i64, 8 => (value == 1) as i64, _ => value as i64,
    }
}

unsafe fn int_array(env: *mut JNIEnv, values: &[i32]) -> jintArray {
    let f = &**env;
    let array = (f.NewIntArray.unwrap())(env, values.len() as jint);
    if !array.is_null() && !values.is_empty() { (f.SetIntArrayRegion.unwrap())(env, array, 0, values.len() as jint, values.as_ptr()); }
    array
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_iap2_wire_NativeIap2Wire_encodeScalar(env: *mut JNIEnv, _: jclass, kind: jint, value: jlong) -> jbyteArray {
    match encode_scalar(kind, value) {
        Ok(bytes) => byte_array(env, &bytes),
        Err(message) => { throw(env, "java/lang/IllegalArgumentException", message); null_mut() }
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_iap2_wire_NativeIap2Wire_readScalar(env: *mut JNIEnv, _: jclass, kind: jint, input: jbyteArray, offset: jint, field_id: jint) -> jlong {
    let Some(bytes) = read_bytes(env, input) else { return 0; };
    let Some(width) = width(kind) else { throw(env, "java/lang/IllegalArgumentException", "Unknown iAP2 scalar kind"); return 0; };
    if field_id >= 0 && bytes.len() != width {
        throw(env, "com/shilapi/xcertplay/iap2/wire/Iap2ProtocolException", &format!("Parameter 0x{field_id:04x} expected {width} bytes, got {}", bytes.len()));
        return 0;
    }
    if offset < 0 || offset as usize >= bytes.len() || width > bytes.len() - offset as usize {
        let index = if offset < 0 || offset as usize >= bytes.len() { offset } else { bytes.len() as jint };
        throw(env, "java/lang/ArrayIndexOutOfBoundsException", &format!("Index {index} out of bounds for length {}", bytes.len()));
        return 0;
    }
    read_scalar(kind, &bytes[offset as usize..offset as usize + width])
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_iap2_wire_NativeIap2Wire_encodeU16List(env: *mut JNIEnv, _: jclass, input: jintArray) -> jbyteArray {
    let f = &**env;
    let count = (f.GetArrayLength.unwrap())(env, input);
    let mut values = vec![0; count as usize];
    if count != 0 { (f.GetIntArrayRegion.unwrap())(env, input, 0, count, values.as_mut_ptr()); }
    if (f.ExceptionCheck.unwrap())(env) != 0 { return null_mut(); }
    let mut bytes = Vec::with_capacity(values.len() * 2);
    for value in values {
        if !(0..=65535).contains(&value) { throw(env, "java/lang/IllegalArgumentException", "u16[] value must be in 0..65535"); return null_mut(); }
        bytes.extend_from_slice(&(value as u16).to_be_bytes());
    }
    byte_array(env, &bytes)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_iap2_wire_NativeIap2Wire_readU16List(env: *mut JNIEnv, _: jclass, input: jbyteArray) -> jintArray {
    let Some(bytes) = read_bytes(env, input) else { return null_mut(); };
    if bytes.len() % 2 != 0 {
        throw(env, "com/shilapi/xcertplay/iap2/wire/Iap2ProtocolException", &format!("CSM u16[] payload has odd length {}", bytes.len()));
        return null_mut();
    }
    int_array(env, &bytes.chunks_exact(2).map(|p| u16::from_be_bytes([p[0], p[1]]) as i32).collect::<Vec<_>>())
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_iap2_wire_NativeIap2Wire_joinStrings(env: *mut JNIEnv, _: jclass, input: jobjectArray) -> jbyteArray {
    let f = &**env;
    let count = (f.GetArrayLength.unwrap())(env, input);
    let mut bytes = Vec::new();
    for index in 0..count {
        let item = (f.GetObjectArrayElement.unwrap())(env, input, index);
        if (f.ExceptionCheck.unwrap())(env) != 0 { return null_mut(); }
        let value = read_bytes(env, item);
        if !item.is_null() { (f.DeleteLocalRef.unwrap())(env, item); }
        let Some(value) = value else { return null_mut(); };
        // Each platform-encoded string was validated at its String boundary before materialization.
        bytes.extend_from_slice(&value);
        bytes.push(0);
    }
    byte_array(env, &bytes)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_iap2_wire_NativeIap2Wire_stringRanges(env: *mut JNIEnv, _: jclass, input: jbyteArray, multiple: jboolean) -> jintArray {
    let Some(bytes) = read_bytes(env, input) else { return null_mut(); };
    let multiple = multiple != 0;
    if multiple && bytes.is_empty() { return int_array(env, &[]); }
    if bytes.last() != Some(&0) {
        throw(env, "com/shilapi/xcertplay/iap2/wire/Iap2ProtocolException", if multiple { "CSM string[] is not NUL terminated" } else { "CSM string is not NUL terminated" });
        return null_mut();
    }
    if !multiple { return int_array(env, &[0, bytes.len() as i32 - 1]); }
    let mut ranges = Vec::new();
    let mut start = 0;
    for (index, byte) in bytes.iter().enumerate() {
        if *byte == 0 { ranges.extend_from_slice(&[start, index as i32]); start = index as i32 + 1; }
    }
    int_array(env, &ranges)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_iap2_wire_NativeIap2Wire_requireVoid(env: *mut JNIEnv, _: jclass, id: jint, input: jbyteArray) {
    let Some(bytes) = read_bytes(env, input) else { return; };
    if !bytes.is_empty() { throw(env, "com/shilapi/xcertplay/iap2/wire/Iap2ProtocolException", &format!("Parameter 0x{id:04x} must be void")); }
}
