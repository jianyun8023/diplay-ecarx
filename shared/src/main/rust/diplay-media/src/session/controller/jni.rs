use super::{
    configuration, expected_control_close, handoff_in_progress, timing_policy, wireless_close_plan,
    Controller, Proof, Vpn, HEADER,
};
use jni_sys::{
    jboolean, jclass, jint, jintArray, jlong, jlongArray, jobjectArray, jstring, JNIEnv,
};
use std::ptr::null_mut;

unsafe fn invalid(env: *mut JNIEnv) {
    let f = &**env;
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return;
    }
    let class = (f.FindClass.unwrap())(env, c"java/lang/IllegalArgumentException".as_ptr());
    if !class.is_null() {
        (f.ThrowNew.unwrap())(env, class, c"Invalid controller lifecycle value".as_ptr());
    }
}
unsafe fn long_array(env: *mut JNIEnv, values: &[i64]) -> jlongArray {
    let f = &**env;
    let array = (f.NewLongArray.unwrap())(env, values.len() as jint);
    if !array.is_null() {
        (f.SetLongArrayRegion.unwrap())(env, array, 0, values.len() as jint, values.as_ptr());
    }
    array
}
unsafe fn int_array(env: *mut JNIEnv, values: &[i32]) -> jintArray {
    let f = &**env;
    let array = (f.NewIntArray.unwrap())(env, values.len() as jint);
    if !array.is_null() {
        (f.SetIntArrayRegion.unwrap())(env, array, 0, values.len() as jint, values.as_ptr());
    }
    array
}
unsafe fn value_array(env: *mut JNIEnv, owner: jobjectArray) -> Option<jlongArray> {
    let f = &**env;
    if owner.is_null() || (f.GetArrayLength.unwrap())(env, owner) != 1 {
        invalid(env);
        return None;
    }
    let array = (f.GetObjectArrayElement.unwrap())(env, owner, 0);
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return None;
    }
    if array.is_null() {
        invalid(env);
        None
    } else {
        Some(array)
    }
}
unsafe fn read(env: *mut JNIEnv, owner: jobjectArray) -> Option<Controller> {
    let array = value_array(env, owner)?;
    let f = &**env;
    let size = (f.GetArrayLength.unwrap())(env, array);
    if size < HEADER as jint {
        invalid(env);
        return None;
    }
    let mut words = vec![0; size as usize];
    (f.GetLongArrayRegion.unwrap())(env, array, 0, size, words.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return None;
    }
    let state = Controller::decode(&words);
    if state.is_none() {
        invalid(env);
    }
    state
}
unsafe fn save(env: *mut JNIEnv, owner: jobjectArray, state: &Controller) {
    let array = long_array(env, &state.encode());
    if !array.is_null() {
        ((**env).SetObjectArrayElement.unwrap())(env, owner, 0, array);
    }
}
unsafe fn read_header(env: *mut JNIEnv, owner: jobjectArray) -> Option<[i64; HEADER]> {
    let array = value_array(env, owner)?;
    let f = &**env;
    if (f.GetArrayLength.unwrap())(env, array) < HEADER as jint {
        invalid(env);
        return None;
    }
    let mut header = [0; HEADER];
    (f.GetLongArrayRegion.unwrap())(env, array, 0, HEADER as jint, header.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return None;
    }
    if !Controller::valid_header(&header) {
        invalid(env);
        return None;
    }
    Some(header)
}
unsafe fn pair(env: *mut JNIEnv, token: jlongArray) -> Option<[i64; 2]> {
    let f = &**env;
    if token.is_null() || (f.GetArrayLength.unwrap())(env, token) != 2 {
        invalid(env);
        return None;
    }
    let mut words = [0; 2];
    (f.GetLongArrayRegion.unwrap())(env, token, 0, 2, words.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        None
    } else {
        Some(words)
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_orchestration_NativeCarPlayController_create(
    env: *mut JNIEnv,
    _class: jclass,
    wireless: jboolean,
    location: jboolean,
) -> jlongArray {
    long_array(env, &Controller::new(wireless != 0, location != 0).encode())
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_orchestration_NativeCarPlayController_query(
    env: *mut JNIEnv,
    _class: jclass,
    owner: jobjectArray,
    field: jint,
) -> jlong {
    // Hot read-only gates need no allocation or decoding of unrelated USB device keys.
    read_header(env, owner)
        .map(|header| Controller::query(&header, field))
        .unwrap_or(0)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_orchestration_NativeCarPlayController_event(
    env: *mut JNIEnv,
    _class: jclass,
    owner: jobjectArray,
    event: jint,
    value: jlong,
    present: jboolean,
) -> jlong {
    let Some(mut state) = read(env, owner) else {
        return 0;
    };
    let result = state.event(event, value, present != 0);
    save(env, owner, &state);
    result
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_orchestration_NativeCarPlayController_decide(
    env: *mut JNIEnv,
    _class: jclass,
    owner: jobjectArray,
    decision: jint,
    value: jlong,
    other: jlong,
    facts: jlong,
) -> jlong {
    read_header(env, owner)
        .map(|header| {
            Controller {
                header,
                grant_attempts: Vec::new(),
                pending_grants: Vec::new(),
            }
            .decide(decision, value, other, facts)
        })
        .unwrap_or(0)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_orchestration_NativeCarPlayController_key(
    env: *mut JNIEnv,
    _class: jclass,
    owner: jobjectArray,
    event: jint,
    key: jstring,
) -> jboolean {
    let Some(mut state) = read(env, owner) else {
        return 0;
    };
    let f = &**env;
    if key.is_null() {
        invalid(env);
        return 0;
    }
    let size = (f.GetStringLength.unwrap())(env, key);
    let mut units = vec![0; size as usize];
    (f.GetStringRegion.unwrap())(env, key, 0, size, units.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return 0;
    }
    let result = state.key(event, units);
    save(env, owner, &state);
    result as jboolean
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_orchestration_NativeCarPlayController_wirelessDecision(
    _env: *mut JNIEnv,
    _class: jclass,
    token: jlong,
    operation: jint,
    facts: jint,
) -> jint {
    crate::session::wireless_state::decision(token, operation, facts)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_orchestration_NativeCarPlayController_timingPolicy(
    _env: *mut JNIEnv,
    _class: jclass,
    field: jint,
) -> jlong {
    timing_policy(field)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_orchestration_NativeCarPlayController_permissionDeadline(
    _env: *mut JNIEnv,
    _class: jclass,
    now: jlong,
) -> jlong {
    now.wrapping_add(timing_policy(0).wrapping_mul(1_000_000))
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_orchestration_NativeCarPlayController_permissionExpired(
    _env: *mut JNIEnv,
    _class: jclass,
    deadline: jlong,
    now: jlong,
) -> jboolean {
    (now >= deadline) as jboolean
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_orchestration_NativeCarPlayController_teardownPlan(
    env: *mut JNIEnv,
    _class: jclass,
    owner: jobjectArray,
) -> jintArray {
    let Some(state) = read(env, owner) else {
        return null_mut();
    };
    int_array(env, &state.teardown_plan())
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_orchestration_NativeCarPlayController_wirelessClosePlan(
    env: *mut JNIEnv,
    _class: jclass,
    current: jboolean,
) -> jintArray {
    int_array(env, &wireless_close_plan(current != 0))
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_orchestration_NativeCarPlayController_handoff(
    _env: *mut JNIEnv,
    _class: jclass,
    requested: jboolean,
    tunnel: jboolean,
    session: jboolean,
) -> jboolean {
    handoff_in_progress(requested != 0, tunnel != 0, session != 0) as jboolean
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_orchestration_NativeCarPlayController_expectedControlClose(
    _env: *mut JNIEnv,
    _class: jclass,
    stage: jint,
    facts: jint,
) -> jboolean {
    expected_control_close(stage, facts) as jboolean
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_orchestration_NativeCarPlayController_configuration(
    _env: *mut JNIEnv,
    _class: jclass,
    found: jboolean,
    mode: jboolean,
    started: jboolean,
    tried: jint,
    maximum: jint,
) -> jint {
    configuration(found != 0, mode != 0, started != 0, tried, maximum)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_orchestration_NativeCarPlayController_configurationFallback(
    _env: *mut JNIEnv,
    _class: jclass,
    found: jboolean,
    mode: jboolean,
    started: jboolean,
) -> jboolean {
    (found != 0 && (mode == 0 || started != 0)) as jboolean
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_orchestration_NativeCarPlayController_vpnCreate(
    env: *mut JNIEnv,
    _class: jclass,
) -> jlongArray {
    long_array(env, &[0, 0])
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_orchestration_NativeCarPlayController_vpnQuery(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    field: jint,
) -> jint {
    let Some(words) = pair(env, token) else {
        return 0;
    };
    if !(0..=1).contains(&words[0]) || words[1] != words[1] as i32 as i64 {
        invalid(env);
        return 0;
    }
    if field == 0 {
        words[0] as jint
    } else {
        words[1] as jint
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_orchestration_NativeCarPlayController_vpnEvent(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    event: jint,
    generation: jint,
    current: jboolean,
) -> jint {
    let Some(words) = pair(env, token) else {
        return 0;
    };
    if !(0..=1).contains(&words[0]) || words[1] != words[1] as i32 as i64 {
        invalid(env);
        return 0;
    }
    let mut state = Vpn {
        active: words[0] != 0,
        generation: words[1] as i32,
    };
    let result = state.event(event, generation, current != 0);
    ((**env).SetLongArrayRegion.unwrap())(
        env,
        token,
        0,
        2,
        [state.active as i64, state.generation as i64].as_ptr(),
    );
    result
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_orchestration_NativeCarPlayController_proofCreate(
    env: *mut JNIEnv,
    _class: jclass,
) -> jlongArray {
    long_array(env, &[-1, 0])
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_orchestration_NativeCarPlayController_proofEvent(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    event: jint,
    generation: jint,
    same_session: jboolean,
) -> jboolean {
    let Some(words) = pair(env, token) else {
        return 0;
    };
    if words[0] != words[0] as i32 as i64 || !(0..16).contains(&words[1]) {
        invalid(env);
        return 0;
    }
    let mut proof = Proof {
        generation: words[0] as i32,
        flags: words[1],
    };
    let result = proof.event(event, generation, same_session != 0);
    ((**env).SetLongArrayRegion.unwrap())(
        env,
        token,
        0,
        2,
        [proof.generation as i64, proof.flags].as_ptr(),
    );
    result as jboolean
}
