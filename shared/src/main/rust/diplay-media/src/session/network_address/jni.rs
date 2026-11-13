use jni_sys::{jboolean, jclass, jint, jintArray, jlong, jlongArray, JNIEnv};
use std::ptr::null_mut;
unsafe fn facts(env: *mut JNIEnv, array: jintArray) -> Option<Vec<i32>> {
    let f = &**env;
    let length = (f.GetArrayLength.unwrap())(env, array);
    let mut values = vec![0; length as usize];
    (f.GetIntArrayRegion.unwrap())(env, array, 0, length, values.as_mut_ptr());
    ((f.ExceptionCheck.unwrap())(env) == 0).then_some(values)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_network_NativeHotspotAddress_select(
    env: *mut JNIEnv,
    _: jclass,
    input: jintArray,
    operation: jint,
    strict: jboolean,
) -> jint {
    facts(env, input).map_or(-1, |facts| super::select(&facts, operation, strict != 0))
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_network_NativeHotspotAddress_readiness(
    env: *mut JNIEnv,
    _: jclass,
    prefer_ipv6: jboolean,
    input: jintArray,
) -> jint {
    facts(env, input).map_or(0, |facts| super::readiness(prefer_ipv6 != 0, &facts))
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_network_NativeHotspotAddress_filter(
    env: *mut JNIEnv,
    _: jclass,
    input: jintArray,
) -> jintArray {
    let Some(facts) = facts(env, input) else {
        return null_mut();
    };
    let indices: Vec<i32> = facts
        .iter()
        .enumerate()
        .filter(|(_, value)| super::permitted(**value, false))
        .map(|(index, _)| index as i32)
        .collect();
    let f = &**env;
    let result = (f.NewIntArray.unwrap())(env, indices.len() as jint);
    if !result.is_null() {
        (f.SetIntArrayRegion.unwrap())(env, result, 0, indices.len() as jint, indices.as_ptr());
    }
    result
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_network_NativeHotspotAddress_flipGate(
    _: *mut JNIEnv,
    _: jclass,
    phone: jboolean,
    reachable: jboolean,
) -> jint {
    super::flip_gate(phone != 0, reachable != 0)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_network_NativeHotspotAddress_deadline(
    env: *mut JNIEnv,
    _: jclass,
    start: jlong,
    timeout: jlong,
) -> jlongArray {
    let f = &**env;
    let result = (f.NewLongArray.unwrap())(env, 2);
    if !result.is_null() {
        (f.SetLongArrayRegion.unwrap())(
            env,
            result,
            0,
            2,
            [start, start.wrapping_add(timeout)].as_ptr(),
        );
    }
    result
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_network_NativeHotspotAddress_time(
    env: *mut JNIEnv,
    _: jclass,
    token: jlongArray,
    elapsed: jboolean,
    now: jlong,
) -> jlong {
    let f = &**env;
    if token.is_null() || (f.GetArrayLength.unwrap())(env, token) != 2 {
        let class = (f.FindClass.unwrap())(env, c"java/lang/IllegalArgumentException".as_ptr());
        if !class.is_null() {
            (f.ThrowNew.unwrap())(env, class, c"Invalid hotspot address deadline".as_ptr());
        }
        return 0;
    }
    let mut words = [0; 2];
    (f.GetLongArrayRegion.unwrap())(env, token, 0, 2, words.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return 0;
    }
    super::time(words, elapsed != 0, now)
}
