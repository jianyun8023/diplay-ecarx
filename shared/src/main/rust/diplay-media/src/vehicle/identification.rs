//! iAP2 IdentificationInformation vehicle components (Kotlin's old
//! `Iap2VehicleStatus.electricVehicleComponents`): pure TLV encoding of groups 20 and 21.
//! SDK queries, capability gates and subscriptions stay Kotlin.

/// `u16 total-length | u16 parameterId | payload`, length including the 4-byte header.
fn param(out: &mut Vec<u8>, id: u16, payload: &[u8]) {
    let total = payload.len() + 4;
    out.extend_from_slice(&(total as u16).to_be_bytes());
    out.extend_from_slice(&id.to_be_bytes());
    out.extend_from_slice(payload);
}

/// Java/Kotlin `String.encodeToByteArray()`: UTF-8 with `?` for each unpaired surrogate.
fn java_utf8(units: &[u16]) -> Vec<u8> {
    let mut out = Vec::with_capacity(units.len());
    let mut index = 0;
    while index < units.len() {
        let unit = units[index];
        if (0xd800..=0xdbff).contains(&unit) {
            if index + 1 < units.len() && (0xdc00..=0xdfff).contains(&units[index + 1]) {
                let low = units[index + 1];
                let code = 0x1_0000 + (((unit - 0xd800) as u32) << 10) + (low - 0xdc00) as u32;
                out.push(0xf0 | ((code >> 18) & 0x07) as u8);
                out.push(0x80 | ((code >> 12) & 0x3f) as u8);
                out.push(0x80 | ((code >> 6) & 0x3f) as u8);
                out.push(0x80 | (code & 0x3f) as u8);
                index += 2;
                continue;
            }
            out.push(b'?');
        } else if (0xdc00..=0xdfff).contains(&unit) {
            out.push(b'?');
        } else if unit < 0x80 {
            out.push(unit as u8);
        } else if unit < 0x800 {
            out.push(0xc0 | (unit >> 6) as u8);
            out.push(0x80 | (unit & 0x3f) as u8);
        } else {
            out.push(0xe0 | (unit >> 12) as u8);
            out.push(0x80 | ((unit >> 6) & 0x3f) as u8);
            out.push(0x80 | (unit & 0x3f) as u8);
        }
        index += 1;
    }
    out
}

fn string_payload(units: &[u16]) -> Vec<u8> {
    let mut bytes = java_utf8(units);
    bytes.push(0);
    bytes
}

/// Group 20 (VehicleInformation): Identifier 3, Name, EngineType electric 2, DisplayName, MapsDisplayName,
/// then the declared charging connectors as repeated u8 parameter 11.
pub fn group20(name: &[u16], connectors: &[i32]) -> Vec<u8> {
    let mut out = Vec::new();
    param(&mut out, 0, &[0, 3]);
    param(&mut out, 1, &string_payload(name));
    param(&mut out, 2, &[2]);
    param(&mut out, 6, &string_payload(name));
    param(&mut out, 8, &string_payload(name));
    for connector in connectors {
        param(&mut out, 11, &[*connector as u8]);
    }
    out
}

/// Group 21 (VehicleStatus): Identifier 4, Name, then the void value declarations 3/6/11/15/21/30.
pub fn group21(name: &[u16]) -> Vec<u8> {
    let mut out = Vec::new();
    param(&mut out, 0, &[0, 4]);
    param(&mut out, 1, &string_payload(name));
    for id in [3u16, 6, 11, 15, 21, 30] {
        param(&mut out, id, &[]);
    }
    out
}

// ---- JNI boundary: `com.shilapi.xcertplay.transport.NativeVehicleIdentification` ----

use crate::platform::jvm::Env;
use jni_sys::{jbyteArray, jclass, jintArray, jint, jstring, JNIEnv};

unsafe fn read_string(env: *mut JNIEnv, value: jstring) -> Option<Vec<u16>> {
    if value.is_null() { return None; }
    let f = &**env;
    let chars = (f.GetStringChars.unwrap())(env, value, std::ptr::null_mut());
    if chars.is_null() { return None; }
    let len = (f.GetStringLength.unwrap())(env, value) as usize;
    let units = std::slice::from_raw_parts(chars, len).to_vec();
    (f.ReleaseStringChars.unwrap())(env, value, chars);
    Some(units)
}

unsafe fn read_int_array(env: *mut JNIEnv, values: jintArray) -> Option<Vec<i32>> {
    if values.is_null() { return Some(Vec::new()); }
    let f = &**env;
    let count = (f.GetArrayLength.unwrap())(env, values) as usize;
    let mut out = vec![0i32; count];
    (f.GetIntArrayRegion.unwrap())(env, values, 0, count as jint, out.as_mut_ptr());
    if Env(env).clear_exception() { None } else { Some(out) }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeVehicleIdentification_group20(
    env: *mut JNIEnv, _class: jclass, name: jstring, connectors: jintArray,
) -> jbyteArray {
    let Some(name) = read_string(env, name) else { return std::ptr::null_mut() };
    let Some(connectors) = read_int_array(env, connectors) else { return std::ptr::null_mut() };
    if connectors.iter().any(|value| !(0..=255).contains(value)) { return std::ptr::null_mut(); }
    Env(env).byte_array(&group20(&name, &connectors))
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeVehicleIdentification_group21(
    env: *mut JNIEnv, _class: jclass, name: jstring,
) -> jbyteArray {
    let Some(name) = read_string(env, name) else { return std::ptr::null_mut() };
    Env(env).byte_array(&group21(&name))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::platform::jvm::Env;

    fn units(text: &str) -> Vec<u16> { text.encode_utf16().collect() }

    #[test]
    fn group20_keeps_parameter_order_and_repeats_connectors() {
        let bytes = group20(&units("Tesla"), &[1, 4]);
        // Parameter ids in wire order: 0, 1, 2, 6, 8, 11, 11.
        let mut ids = Vec::new();
        let mut offset = 0;
        while offset < bytes.len() {
            let length = u16::from_be_bytes([bytes[offset], bytes[offset + 1]]) as usize;
            ids.push(u16::from_be_bytes([bytes[offset + 2], bytes[offset + 3]]));
            offset += length;
        }
        assert_eq!(ids, vec![0, 1, 2, 6, 8, 11, 11]);
        assert_eq!(offset, bytes.len());
    }

    #[test]
    fn group21_declares_the_six_void_fields_after_name() {
        let bytes = group21(&units("Tesla"));
        let mut ids = Vec::new();
        let mut offset = 0;
        while offset < bytes.len() {
            let length = u16::from_be_bytes([bytes[offset], bytes[offset + 1]]) as usize;
            ids.push(u16::from_be_bytes([bytes[offset + 2], bytes[offset + 3]]));
            offset += length;
        }
        assert_eq!(ids, vec![0, 1, 3, 6, 11, 15, 21, 30]);
    }

    #[test]
    fn java_utf8_matches_kotlin_replacement_for_malformed_surrogates() {
        assert_eq!(java_utf8(&units("Bülli😀")), "Bülli😀".as_bytes());
        assert_eq!(java_utf8(&[0xd800, 0x0041]), vec![b'?', b'A']);
        assert_eq!(java_utf8(&[0xdc00]), vec![b'?']);
        assert_eq!(string_payload(&units("a")), vec![b'a', 0]);
    }
}
