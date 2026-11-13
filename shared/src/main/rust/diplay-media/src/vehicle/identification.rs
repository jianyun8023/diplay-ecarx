//! IdentificationInformation groups 20/21. Kotlin supplies the existing NUL-terminated string bytes.

fn param(out: &mut Vec<u8>, id: u16, payload: &[u8]) {
    out.extend_from_slice(&((payload.len() + 4) as u16).to_be_bytes());
    out.extend_from_slice(&id.to_be_bytes());
    out.extend_from_slice(payload);
}

pub fn group20(name_payload: &[u8], connectors: &[i32]) -> Vec<u8> {
    let mut out = Vec::new();
    param(&mut out, 0, &[0, 3]);
    param(&mut out, 1, name_payload);
    param(&mut out, 2, &[2]);
    param(&mut out, 6, name_payload);
    param(&mut out, 8, name_payload);
    for connector in connectors {
        param(&mut out, 11, &[*connector as u8]);
    }
    out
}

pub fn group21(name_payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    param(&mut out, 0, &[0, 4]);
    param(&mut out, 1, name_payload);
    for id in [3u16, 6, 11, 15, 21, 30] {
        param(&mut out, id, &[]);
    }
    out
}

use crate::platform::jvm::Env;
use jni_sys::{jbyteArray, jclass, jintArray, jint, JNIEnv};

unsafe fn read_name(env: *mut JNIEnv, value: jbyteArray) -> Option<Vec<u8>> {
    if value.is_null() { return None; }
    let e = Env(env);
    let mut bytes = vec![0u8; e.array_len(value)];
    e.read_array(value, 0, &mut bytes).then_some(bytes)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeVehicleIdentification_group20(
    env: *mut JNIEnv, _class: jclass, name_payload: jbyteArray, connectors: jintArray,
) -> jbyteArray {
    let Some(name) = read_name(env, name_payload) else { return std::ptr::null_mut() };
    if connectors.is_null() { return std::ptr::null_mut(); }
    let f = &**env;
    let count = (f.GetArrayLength.unwrap())(env, connectors) as usize;
    let mut values = vec![0i32; count];
    (f.GetIntArrayRegion.unwrap())(env, connectors, 0, count as jint, values.as_mut_ptr());
    if Env(env).clear_exception() || values.iter().any(|value| !(0..=255).contains(value)) {
        return std::ptr::null_mut();
    }
    Env(env).byte_array(&group20(&name, &values))
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeVehicleIdentification_group21(
    env: *mut JNIEnv, _class: jclass, name_payload: jbyteArray,
) -> jbyteArray {
    let Some(name) = read_name(env, name_payload) else { return std::ptr::null_mut() };
    Env(env).byte_array(&group21(&name))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn group20_preserves_complete_pinned_bytes_and_repeated_connectors() {
        assert_eq!(group20(b"T\0", &[1, 4]), vec![
            0, 6, 0, 0, 0, 3, 0, 6, 0, 1, b'T', 0, 0, 5, 0, 2, 2,
            0, 6, 0, 6, b'T', 0, 0, 6, 0, 8, b'T', 0,
            0, 5, 0, 11, 1, 0, 5, 0, 11, 4,
        ]);
    }

    #[test]
    fn group21_preserves_complete_pinned_bytes() {
        assert_eq!(group21(b"T\0"), vec![
            0, 6, 0, 0, 0, 4, 0, 6, 0, 1, b'T', 0,
            0, 4, 0, 3, 0, 4, 0, 6, 0, 4, 0, 11,
            0, 4, 0, 15, 0, 4, 0, 21, 0, 4, 0, 30,
        ]);
    }
}
