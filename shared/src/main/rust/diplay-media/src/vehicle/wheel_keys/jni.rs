use super::{press_decision, siri_step, MIN_DOUBLE_PRESS_GAP_MILLIS, REPEAT_GAP_MILLIS};
use jni_sys::{jboolean, jclass, jint, jlong, jlongArray, JNIEnv};
use std::ptr::null_mut;

unsafe fn invalid(env: *mut JNIEnv) {
    let f = &**env;
    if (f.ExceptionCheck.unwrap())(env) != 0 { return; }
    let class = (f.FindClass.unwrap())(env, c"java/lang/IllegalArgumentException".as_ptr());
    if !class.is_null() { (f.ThrowNew.unwrap())(env, class, c"Invalid wheel input value".as_ptr()); }
}

unsafe fn read(env: *mut JNIEnv, token: jlongArray) -> Option<[i64; 3]> {
    let f = &**env;
    if token.is_null() || (f.GetArrayLength.unwrap())(env, token) != 3 {
        invalid(env);
        return None;
    }
    let mut words = [0; 3];
    (f.GetLongArrayRegion.unwrap())(env, token, 0, 3, words.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 { None } else { Some(words) }
}

unsafe fn array(env: *mut JNIEnv, words: [i64; 3]) -> jlongArray {
    let f = &**env;
    let result = (f.NewLongArray.unwrap())(env, 3);
    if result.is_null() { return null_mut(); }
    (f.SetLongArrayRegion.unwrap())(env, result, 0, 3, words.as_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 { null_mut() } else { result }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_vehicle_NativeWheelInput_pressConsumed(
    _env: *mut JNIEnv, _class: jclass, token: jlong, present: jboolean,
) -> jboolean { (present != 0 && token == 1) as jboolean }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_vehicle_NativeWheelInput_pressDecision(
    _env: *mut JNIEnv, _class: jclass, token: jlong, present: jboolean, down: jboolean, first: jboolean,
) -> jint { press_decision(token, present != 0, down != 0, first != 0) }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_vehicle_NativeWheelInput_pressCommit(
    _env: *mut JNIEnv, _class: jclass, consumed: jboolean,
) -> jlong { (consumed != 0) as jlong }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_vehicle_NativeWheelInput_siriCreate(
    env: *mut JNIEnv, _class: jclass,
) -> jlongArray { array(env, [0, 0, 0]) }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_vehicle_NativeWheelInput_siriStep(
    env: *mut JNIEnv, _class: jclass, token: jlongArray, event: jint, now: jlong, maximum_gap: jlong,
) -> jlongArray {
    let Some(token) = read(env, token) else { return null_mut(); };
    match siri_step(token, event, now, maximum_gap) {
        Some(next) => array(env, next),
        None => { invalid(env); null_mut() }
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_vehicle_NativeWheelInput_siriResult(
    env: *mut JNIEnv, _class: jclass, token: jlongArray,
) -> jint { read(env, token).map_or(0, |token| token[2] as jint) }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_vehicle_NativeWheelInput_siriConstant(
    _env: *mut JNIEnv, _class: jclass, field: jint,
) -> jlong { match field { 0 => REPEAT_GAP_MILLIS, 1 => MIN_DOUBLE_PRESS_GAP_MILLIS, _ => 0 } }
