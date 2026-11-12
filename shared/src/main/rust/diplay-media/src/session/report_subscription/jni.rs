use super::{Subscription, WORDS};
use jni_sys::{jboolean, jclass, jlong, jlongArray, JNIEnv};
use std::ptr::null_mut;

unsafe fn invalid(env: *mut JNIEnv) {
    let f = &**env;
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return;
    }
    let class = (f.FindClass.unwrap())(env, c"java/lang/IllegalArgumentException".as_ptr());
    if !class.is_null() {
        (f.ThrowNew.unwrap())(env, class, c"Invalid iAP2 report subscription".as_ptr());
        (f.DeleteLocalRef.unwrap())(env, class);
    }
}
unsafe fn read(env: *mut JNIEnv, array: jlongArray) -> Option<Subscription> {
    let f = &**env;
    if array.is_null() || (f.GetArrayLength.unwrap())(env, array) != WORDS as i32 {
        invalid(env);
        return None;
    }
    let mut words = [0; WORDS];
    (f.GetLongArrayRegion.unwrap())(env, array, 0, WORDS as i32, words.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return None;
    }
    let state = Subscription::from_words(words);
    if state.is_none() {
        invalid(env);
    }
    state
}
unsafe fn save(env: *mut JNIEnv, array: jlongArray, state: Subscription) {
    ((**env).SetLongArrayRegion.unwrap())(env, array, 0, WORDS as i32, state.words().as_ptr());
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeIap2ReportSubscription_create(
    env: *mut JNIEnv,
    _class: jclass,
    vehicle: jboolean,
) -> jlongArray {
    let array = ((**env).NewLongArray.unwrap())(env, WORDS as i32);
    if array.is_null() {
        return null_mut();
    }
    save(env, array, Subscription::new(vehicle != 0));
    if ((**env).ExceptionCheck.unwrap())(env) != 0 {
        null_mut()
    } else {
        array
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeIap2ReportSubscription_start(
    env: *mut JNIEnv,
    _class: jclass,
    array: jlongArray,
    active: jboolean,
) {
    if let Some(mut state) = read(env, array) {
        state.start(active != 0);
        save(env, array, state);
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeIap2ReportSubscription_stop(
    env: *mut JNIEnv,
    _class: jclass,
    array: jlongArray,
) {
    if let Some(mut state) = read(env, array) {
        state.stop();
        save(env, array, state);
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeIap2ReportSubscription_active(
    env: *mut JNIEnv,
    _class: jclass,
    array: jlongArray,
) -> jboolean {
    read(env, array).is_some_and(|s| s.active) as jboolean
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeIap2ReportSubscription_due(
    env: *mut JNIEnv,
    _class: jclass,
    array: jlongArray,
    now: jlong,
) -> jboolean {
    read(env, array).is_some_and(|s| s.due(now)) as jboolean
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeIap2ReportSubscription_timeout(
    env: *mut JNIEnv,
    _class: jclass,
    array: jlongArray,
    remaining: jlong,
    now: jlong,
) -> jlong {
    read(env, array)
        .map(|s| s.timeout(remaining, now))
        .unwrap_or(0)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeIap2ReportSubscription_mark(
    env: *mut JNIEnv,
    _class: jclass,
    array: jlongArray,
    now: jlong,
) {
    if let Some(mut state) = read(env, array) {
        state.last = now;
        save(env, array, state);
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeIap2ReportSubscription_firstSent(
    env: *mut JNIEnv,
    _class: jclass,
    array: jlongArray,
) -> jboolean {
    let Some(mut state) = read(env, array) else {
        return 0;
    };
    let first = state.first_sent();
    save(env, array, state);
    first as jboolean
}
