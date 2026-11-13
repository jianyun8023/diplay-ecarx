use super::{features, teardown_types, Session, WORDS};
use jni_sys::{
    jboolean, jbooleanArray, jclass, jint, jintArray, jlong, jlongArray, jobjectArray, jstring,
    JNIEnv,
};
use std::ptr::null_mut;

unsafe fn invalid(env: *mut JNIEnv) {
    let f = &**env;
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return;
    }
    let class = (f.FindClass.unwrap())(env, c"java/lang/IllegalArgumentException".as_ptr());
    if !class.is_null() {
        (f.ThrowNew.unwrap())(env, class, c"Invalid AirPlay negotiation value".as_ptr());
    }
}
unsafe fn read(env: *mut JNIEnv, token: jlongArray) -> Option<Session> {
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
    let state = Session::from_token(words);
    if state.is_none() {
        invalid(env);
    }
    state
}
unsafe fn save(env: *mut JNIEnv, token: jlongArray, state: &Session) {
    ((**env).SetLongArrayRegion.unwrap())(env, token, 0, WORDS as jint, state.token().as_ptr());
}
unsafe fn string(env: *mut JNIEnv, value: jstring) -> Option<String> {
    let f = &**env;
    if value.is_null() {
        invalid(env);
        return None;
    }
    let size = (f.GetStringLength.unwrap())(env, value);
    let mut text = vec![0u16; size as usize];
    (f.GetStringRegion.unwrap())(env, value, 0, size, text.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        None
    } else {
        Some(String::from_utf16_lossy(&text))
    }
}
unsafe fn ints(env: *mut JNIEnv, array: jintArray) -> Option<Vec<i32>> {
    if array.is_null() {
        return None;
    }
    let f = &**env;
    let size = (f.GetArrayLength.unwrap())(env, array);
    let mut values = vec![0; size as usize];
    (f.GetIntArrayRegion.unwrap())(env, array, 0, size, values.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        None
    } else {
        Some(values)
    }
}
unsafe fn int_array(env: *mut JNIEnv, values: &[i32]) -> jintArray {
    let f = &**env;
    let array = (f.NewIntArray.unwrap())(env, values.len() as jint);
    if !array.is_null() {
        (f.SetIntArrayRegion.unwrap())(env, array, 0, values.len() as jint, values.as_ptr());
    }
    array
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayNegotiation_create(
    env: *mut JNIEnv,
    _class: jclass,
    capabilities: jint,
) -> jlongArray {
    if !(0..32).contains(&capabilities) {
        invalid(env);
        return null_mut();
    }
    let array = ((**env).NewLongArray.unwrap())(env, WORDS as jint);
    if !array.is_null() {
        save(env, array, &Session::new(capabilities as u32));
    }
    array
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayNegotiation_query(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    field: jint,
) -> jlong {
    read(env, token).map(|s| s.query(field)).unwrap_or(0)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayNegotiation_event(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    event: jint,
    value: jint,
) -> jboolean {
    let Some(mut state) = read(env, token) else {
        return 0;
    };
    let result = state.event(event, value);
    save(env, token, &state);
    result as jboolean
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayNegotiation_streamAction(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    stream: jint,
) -> jint {
    read(env, token)
        .map(|s| s.stream_action(stream))
        .unwrap_or(0)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayNegotiation_accepted(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    stream: jint,
) -> jint {
    let Some(mut state) = read(env, token) else {
        return 0;
    };
    let action = state.accepted(stream);
    save(env, token, &state);
    action
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayNegotiation_streams(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
) -> jintArray {
    let Some(state) = read(env, token) else {
        return null_mut();
    };
    int_array(env, &state.streams)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayNegotiation_route(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    method: jstring,
    path: jstring,
    size: jint,
) -> jint {
    let Some(state) = read(env, token) else {
        return 0;
    };
    let Some(method) = string(env, method) else {
        return 0;
    };
    let Some(path) = string(env, path) else {
        return 0;
    };
    state.route(&method, &path, size)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayNegotiation_timingAction(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    port: jint,
    has_peer: jboolean,
) -> jint {
    read(env, token)
        .map(|s| s.timing_action(port, has_peer != 0))
        .unwrap_or(0)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayNegotiation_prepareTeardown(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    types: jintArray,
) -> jint {
    let Some(mut state) = read(env, token) else {
        return 0;
    };
    let types = ints(env, types);
    if ((**env).ExceptionCheck.unwrap())(env) != 0 {
        return 0;
    }
    let actions = state.prepare_teardown(types.as_deref());
    save(env, token, &state);
    actions
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayNegotiation_finishTeardown(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    all: jboolean,
) -> jboolean {
    let Some(mut state) = read(env, token) else {
        return 0;
    };
    let clear_cluster = state.finish_teardown(all != 0);
    save(env, token, &state);
    clear_cluster as jboolean
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayNegotiation_nightResponse(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    seq: jint,
) -> jint {
    let Some(mut state) = read(env, token) else {
        return -1;
    };
    let night = state.night_response(seq);
    save(env, token, &state);
    night
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayNegotiation_navigation(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    event: jint,
    value: jlong,
    present: jboolean,
) -> jint {
    let Some(mut state) = read(env, token) else {
        return -1;
    };
    let action = state.navigation(event, value, present != 0);
    save(env, token, &state);
    action
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayNegotiation_features(
    env: *mut JNIEnv,
    _class: jclass,
    capabilities: jint,
    proposed: jobjectArray,
) -> jobjectArray {
    let f = &**env;
    if proposed.is_null() {
        invalid(env);
        return null_mut();
    }
    let size = (f.GetArrayLength.unwrap())(env, proposed);
    let mut names = Vec::new();
    for index in 0..size {
        let value = (f.GetObjectArrayElement.unwrap())(env, proposed, index);
        if (f.ExceptionCheck.unwrap())(env) != 0 {
            return null_mut();
        }
        let name = string(env, value);
        if !value.is_null() {
            (f.DeleteLocalRef.unwrap())(env, value);
        }
        let Some(name) = name else {
            return null_mut();
        };
        names.push(name);
    }
    let selected = features(capabilities as u32, &names);
    let class = (f.FindClass.unwrap())(env, c"java/lang/String".as_ptr());
    if class.is_null() {
        return null_mut();
    }
    let array = (f.NewObjectArray.unwrap())(env, selected.len() as jint, class, null_mut());
    if array.is_null() {
        return null_mut();
    }
    for (index, name) in selected.iter().enumerate() {
        let text: Vec<u16> = name.encode_utf16().collect();
        let value = (f.NewString.unwrap())(env, text.as_ptr(), text.len() as jint);
        if value.is_null() {
            return null_mut();
        }
        (f.SetObjectArrayElement.unwrap())(env, array, index as jint, value);
        (f.DeleteLocalRef.unwrap())(env, value);
        if (f.ExceptionCheck.unwrap())(env) != 0 {
            return null_mut();
        }
    }
    array
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayNegotiation_teardownTypes(
    env: *mut JNIEnv,
    _class: jclass,
    types: jintArray,
    ids: jlongArray,
    present: jbooleanArray,
) -> jintArray {
    let Some(types) = ints(env, types) else {
        return null_mut();
    };
    let f = &**env;
    if ids.is_null()
        || present.is_null()
        || (f.GetArrayLength.unwrap())(env, ids) != types.len() as jint
        || (f.GetArrayLength.unwrap())(env, present) != types.len() as jint
    {
        invalid(env);
        return null_mut();
    }
    let mut values = vec![0; types.len()];
    let mut flags = vec![0; types.len()];
    (f.GetLongArrayRegion.unwrap())(env, ids, 0, values.len() as jint, values.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return null_mut();
    }
    (f.GetBooleanArrayRegion.unwrap())(env, present, 0, flags.len() as jint, flags.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return null_mut();
    }
    teardown_types(&types, &values, &flags)
        .map(|types| int_array(env, &types))
        .unwrap_or(null_mut())
}
