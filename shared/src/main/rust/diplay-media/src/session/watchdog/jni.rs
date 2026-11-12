use super::{budget, elapsed, remaining, start_again, Watchdog, WORDS};
use jni_sys::{jboolean, jclass, jint, jlong, jlongArray, JNIEnv};
use std::ptr::null_mut;

unsafe fn invalid(env: *mut JNIEnv) {
    let f = &**env;
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return;
    }
    let class = (f.FindClass.unwrap())(env, c"java/lang/IllegalArgumentException".as_ptr());
    if !class.is_null() {
        (f.ThrowNew.unwrap())(env, class, c"Invalid startup watchdog value".as_ptr());
    }
}
unsafe fn read(env: *mut JNIEnv, token: jlongArray) -> Option<Watchdog> {
    let f = &**env;
    if token.is_null() || (f.GetArrayLength.unwrap())(env, token) != WORDS as jint {
        invalid(env);
        return None;
    }
    let mut words = [0; WORDS];
    (f.GetLongArrayRegion.unwrap())(env, token, 0, WORDS as jint, words.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return None;
    }
    let state = Watchdog::from_words(words);
    if state.is_none() {
        invalid(env);
    }
    state
}
unsafe fn array(env: *mut JNIEnv, values: &[i64]) -> jlongArray {
    let f = &**env;
    let array = (f.NewLongArray.unwrap())(env, values.len() as jint);
    if !array.is_null() {
        (f.SetLongArrayRegion.unwrap())(env, array, 0, values.len() as jint, values.as_ptr());
    }
    array
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_orchestration_NativeStartupWatchdog_create(
    env: *mut JNIEnv,
    _class: jclass,
    kind: jint,
    now: jlong,
    timeout: jlong,
) -> jlongArray {
    if !(0..=2).contains(&kind) {
        invalid(env);
        return null_mut();
    }
    array(env, &Watchdog::new(kind, now, timeout).words)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_orchestration_NativeStartupWatchdog_finished(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
) -> jboolean {
    read(env, token)
        .map(|s| s.words[1] & 1 != 0)
        .unwrap_or(false) as jboolean
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_orchestration_NativeStartupWatchdog_event(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    event: jint,
    value: jint,
    time: jlong,
    condition: jboolean,
) -> jlongArray {
    let Some(mut state) = read(env, token) else {
        return null_mut();
    };
    if matches!(event, 6 | 14) && !(0..=6).contains(&value) {
        invalid(env);
        return null_mut();
    }
    let effect = state.event(event, value, time, condition != 0);
    ((**env).SetLongArrayRegion.unwrap())(env, token, 0, WORDS as jint, state.words.as_ptr());
    if ((**env).ExceptionCheck.unwrap())(env) != 0 {
        return null_mut();
    }
    array(env, &effect)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_orchestration_NativeStartupWatchdog_budget(
    _env: *mut JNIEnv,
    _class: jclass,
    missing: jint,
) -> jlong {
    budget(missing)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_orchestration_NativeStartupWatchdog_elapsed(
    _env: *mut JNIEnv,
    _class: jclass,
    start: jlong,
    now: jlong,
) -> jlong {
    elapsed(start, now)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_orchestration_NativeStartupWatchdog_remaining(
    _env: *mut JNIEnv,
    _class: jclass,
    timeout: jlong,
    start: jlong,
    now: jlong,
) -> jlong {
    remaining(timeout, start, now)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_orchestration_NativeStartupWatchdog_bluetoothStall(
    _env: *mut JNIEnv,
    _class: jclass,
    reached: jint,
) -> jboolean {
    (reached == 0) as jboolean
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_orchestration_NativeStartupWatchdog_startAgain(
    _env: *mut JNIEnv,
    _class: jclass,
    now: jlong,
    start: jlong,
    opened: jlong,
    has_opened: jboolean,
    active: jboolean,
    connection_attempt: jboolean,
) -> jint {
    start_again(
        now,
        start,
        (has_opened != 0).then_some(opened),
        active != 0,
        connection_attempt != 0,
    )
}
