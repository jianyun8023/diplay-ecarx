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
use jni_sys::{jclass, jint, jobjectArray, jstring, JNIEnv};

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
}
