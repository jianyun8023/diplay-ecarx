//! iAP2 CSM body parameter framing (Kotlin's old `Iap2ParameterList.encode/parse`).
//! Parameters are `u16 total-length | u16 id | payload`; order and repeated ids are preserved.
//! Scalar/string codecs and the builder/reader APIs stay Kotlin.

const HEADER_BYTES: usize = 4;
const MAX_PARAM_BYTES: usize = 0xffff;
const MAX_FRAME_BYTES: usize = 0xffff;
const MAX_BODY_BYTES: usize = MAX_FRAME_BYTES - 6;

pub fn encode_parameter_list(parameters: &[(u16, Vec<u8>)]) -> Result<Vec<u8>, String> {
    let mut out = Vec::new();
    for (id, payload) in parameters {
        if payload.len() > MAX_PARAM_BYTES - HEADER_BYTES {
            return Err(format!("CSM parameter payload exceeds {} bytes", MAX_PARAM_BYTES - HEADER_BYTES));
        }
        let total = payload.len() + HEADER_BYTES;
        if out.len() + total > MAX_BODY_BYTES {
            return Err(format!("Encoded CSM parameters exceed the {MAX_BODY_BYTES}-byte message body limit"));
        }
        out.extend_from_slice(&(total as u16).to_be_bytes());
        out.extend_from_slice(&id.to_be_bytes());
        out.extend_from_slice(payload);
    }
    Ok(out)
}

pub fn parse_parameter_list(encoded: &[u8]) -> Result<Vec<(u16, Vec<u8>)>, String> {
    let mut parameters = Vec::new();
    let mut offset = 0;
    while offset < encoded.len() {
        if encoded.len() - offset < HEADER_BYTES {
            return Err("Truncated CSM parameter header".into());
        }
        let length = u16::from_be_bytes([encoded[offset], encoded[offset + 1]]) as usize;
        if length < HEADER_BYTES || length > encoded.len() - offset {
            return Err(format!("Invalid CSM parameter length {length}"));
        }
        let id = u16::from_be_bytes([encoded[offset + 2], encoded[offset + 3]]);
        parameters.push((id, encoded[offset + HEADER_BYTES..offset + length].to_vec()));
        offset += length;
    }
    Ok(parameters)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn order_and_repeated_ids_survive_a_round_trip() {
        let input = vec![
            (10u16, vec![1, 2, 3]),
            (7u16, vec![]),
            (10u16, vec![9]),
        ];
        let encoded = encode_parameter_list(&input).unwrap();
        assert_eq!(encoded[..6], [0, 7, 0, 10, 1, 2]);
        assert_eq!(parse_parameter_list(&encoded).unwrap(), input);
    }

    #[test]
    fn truncated_headers_and_bad_lengths_match_the_kotlin_messages() {
        assert_eq!(parse_parameter_list(&[0, 4, 0]), Err("Truncated CSM parameter header".into()));
        assert_eq!(parse_parameter_list(&[0, 3, 0, 1]), Err("Invalid CSM parameter length 3".into()));
        assert_eq!(parse_parameter_list(&[0, 9, 0, 1, 0]), Err("Invalid CSM parameter length 9".into()));
        assert_eq!(parse_parameter_list(&[]).unwrap(), Vec::new());
    }

    #[test]
    fn payload_and_body_limits_are_enforced() {
        let too_large = vec![(1u16, vec![0; MAX_PARAM_BYTES - HEADER_BYTES + 1])];
        assert!(encode_parameter_list(&too_large).unwrap_err().contains("CSM parameter payload exceeds"));
        // A single max-sized payload plus another cannot fit the body limit.
        let two = vec![(1u16, vec![0; 40_000]), (2u16, vec![0; 40_000])];
        assert!(encode_parameter_list(&two).unwrap_err().contains("message body limit"));
    }
}

// ---- JNI boundary: `com.shilapi.xcertplay.iap2.wire.NativeIap2Body` ----

use jni_sys::{jbyteArray, jclass, jint, jintArray, jobjectArray, jvalue, JNIEnv};
use std::ptr::null_mut;

pub(super) unsafe fn throw(env: *mut JNIEnv, class: &str, message: &str) {
    let f = &**env;
    if (f.ExceptionCheck.unwrap())(env) != 0 { return; }
    let class = std::ffi::CString::new(class).unwrap();
    let target = (f.FindClass.unwrap())(env, class.as_ptr());
    if target.is_null() { return; }
    // Kotlin's protocol exception has (String, Throwable), not ThrowNew's String-only constructor.
    let init = (f.GetMethodID.unwrap())(env, target, c"<init>".as_ptr(), c"(Ljava/lang/String;Ljava/lang/Throwable;)V".as_ptr());
    if init.is_null() { return; }
    let message = std::ffi::CString::new(message).unwrap();
    let text = (f.NewStringUTF.unwrap())(env, message.as_ptr());
    if text.is_null() { return; }
    let args = [jvalue { l: text }, jvalue { l: null_mut() }];
    let error = (f.NewObjectA.unwrap())(env, target, init, args.as_ptr());
    if (f.ExceptionCheck.unwrap())(env) == 0 && !error.is_null() { (f.Throw.unwrap())(env, error); }
}

pub(super) unsafe fn read_bytes(env: *mut JNIEnv, array: jbyteArray) -> Option<Vec<u8>> {
    if array.is_null() { return Some(Vec::new()); }
    let f = &**env;
    let length = (f.GetArrayLength.unwrap())(env, array) as usize;
    let mut bytes = vec![0u8; length];
    (f.GetByteArrayRegion.unwrap())(env, array, 0, length as jint, bytes.as_mut_ptr() as *mut i8);
    if (f.ExceptionCheck.unwrap())(env) != 0 { None } else { Some(bytes) }
}

pub(super) unsafe fn byte_array(env: *mut JNIEnv, bytes: &[u8]) -> jbyteArray {
    let f = &**env;
    let array = (f.NewByteArray.unwrap())(env, bytes.len() as jint);
    if array.is_null() { return null_mut(); }
    (f.SetByteArrayRegion.unwrap())(env, array, 0, bytes.len() as jint, bytes.as_ptr() as *const i8);
    if (f.ExceptionCheck.unwrap())(env) != 0 { null_mut() } else { array }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_iap2_wire_NativeIap2Body_encode(
    env: *mut JNIEnv, _class: jclass, ids: jintArray, payloads: jobjectArray,
) -> jbyteArray {
    if ids.is_null() || payloads.is_null() { return null_mut(); }
    let f = &**env;
    let count = (f.GetArrayLength.unwrap())(env, ids) as usize;
    if (f.GetArrayLength.unwrap())(env, payloads) as usize != count { return null_mut(); }
    let mut id_data = vec![0i32; count];
    (f.GetIntArrayRegion.unwrap())(env, ids, 0, count as jint, id_data.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 { return null_mut(); }
    let mut parameters = Vec::with_capacity(count);
    for (index, id) in id_data.iter().enumerate() {
        if !(0..=0xffff).contains(id) { throw(env, "java/lang/IllegalArgumentException", "CSM parameter id must fit in u16"); return null_mut(); }
        let array = (f.GetObjectArrayElement.unwrap())(env, payloads, index as jint);
        if (f.ExceptionCheck.unwrap())(env) != 0 { return null_mut(); }
        let Some(payload) = read_bytes(env, array) else { (f.DeleteLocalRef.unwrap())(env, array); return null_mut(); };
        (f.DeleteLocalRef.unwrap())(env, array);
        parameters.push((*id as u16, payload));
    }
    match encode_parameter_list(&parameters) {
        Ok(encoded) => byte_array(env, &encoded),
        Err(message) => { throw(env, "java/lang/IllegalArgumentException", &message); null_mut() }
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_iap2_wire_NativeIap2Body_parse(
    env: *mut JNIEnv, _class: jclass, encoded: jbyteArray,
) -> jobjectArray {
    let Some(body) = read_bytes(env, encoded) else { return null_mut(); };
    let parameters = match parse_parameter_list(&body) {
        Ok(parameters) => parameters,
        Err(message) => { throw(env, "com/shilapi/xcertplay/iap2/wire/Iap2ProtocolException", &message); return null_mut(); }
    };
    let f = &**env;
    if (f.PushLocalFrame.unwrap())(env, 16) < 0 { return null_mut(); }
    let result = (|| -> Option<jobjectArray> {
        let class = (f.FindClass.unwrap())(env, c"com/shilapi/xcertplay/iap2/wire/Iap2Parameter".as_ptr());
        if class.is_null() { return None; }
        let init = (f.GetMethodID.unwrap())(env, class, c"<init>".as_ptr(), c"(I[B)V".as_ptr());
        if init.is_null() { return None; }
        let array = (f.NewObjectArray.unwrap())(env, parameters.len() as jint, class, null_mut());
        if array.is_null() { return None; }
        for (index, (id, payload)) in parameters.iter().enumerate() {
            let bytes = byte_array(env, payload);
            if bytes.is_null() { return None; }
            let args = [jvalue { i: *id as jint }, jvalue { l: bytes }];
            let parameter = (f.NewObjectA.unwrap())(env, class, init, args.as_ptr());
            (f.DeleteLocalRef.unwrap())(env, bytes);
            if (f.ExceptionCheck.unwrap())(env) != 0 || parameter.is_null() { return None; }
            (f.SetObjectArrayElement.unwrap())(env, array, index as jint, parameter);
            (f.DeleteLocalRef.unwrap())(env, parameter);
            if (f.ExceptionCheck.unwrap())(env) != 0 { return None; }
        }
        Some(array)
    })();
    (f.PopLocalFrame.unwrap())(env, result.unwrap_or(null_mut()))
}
