use super::{omittable, rejection_metadata, Deadline, Handshake, TransportWait};
use jni_sys::{
    jboolean, jbyteArray, jclass, jint, jintArray, jlong, jlongArray, jobjectArray, JNIEnv,
};
use std::ptr::null_mut;

unsafe fn invalid(env: *mut JNIEnv) {
    let f = &**env;
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return;
    }
    let class = (f.FindClass.unwrap())(env, c"java/lang/IllegalArgumentException".as_ptr());
    if !class.is_null() {
        (f.ThrowNew.unwrap())(env, class, c"Invalid iAP2 handshake value".as_ptr());
    }
}
unsafe fn longs(env: *mut JNIEnv, array: jlongArray) -> Option<Vec<i64>> {
    let f = &**env;
    if array.is_null() {
        invalid(env);
        return None;
    }
    let size = (f.GetArrayLength.unwrap())(env, array);
    let mut words = vec![0; size as usize];
    (f.GetLongArrayRegion.unwrap())(env, array, 0, size, words.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        None
    } else {
        Some(words)
    }
}
unsafe fn ints(env: *mut JNIEnv, array: jintArray) -> Option<Vec<i32>> {
    let f = &**env;
    if array.is_null() {
        invalid(env);
        return None;
    }
    let size = (f.GetArrayLength.unwrap())(env, array);
    let mut words = vec![0; size as usize];
    (f.GetIntArrayRegion.unwrap())(env, array, 0, size, words.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        None
    } else {
        Some(words)
    }
}
unsafe fn long_array(env: *mut JNIEnv, words: &[i64]) -> jlongArray {
    let f = &**env;
    let array = (f.NewLongArray.unwrap())(env, words.len() as jint);
    if !array.is_null() {
        (f.SetLongArrayRegion.unwrap())(env, array, 0, words.len() as jint, words.as_ptr());
    }
    array
}
unsafe fn int_array(env: *mut JNIEnv, words: &[i32]) -> jintArray {
    let f = &**env;
    let array = (f.NewIntArray.unwrap())(env, words.len() as jint);
    if !array.is_null() {
        (f.SetIntArrayRegion.unwrap())(env, array, 0, words.len() as jint, words.as_ptr());
    }
    array
}
unsafe fn read(env: *mut JNIEnv, owner: jobjectArray) -> Option<Handshake> {
    let f = &**env;
    if owner.is_null() || (f.GetArrayLength.unwrap())(env, owner) != 1 {
        invalid(env);
        return None;
    }
    let array = (f.GetObjectArrayElement.unwrap())(env, owner, 0);
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return None;
    }
    let words = longs(env, array)?;
    let state = Handshake::decode(&words);
    if state.is_none() {
        invalid(env);
    }
    state
}
unsafe fn save(env: *mut JNIEnv, owner: jobjectArray, state: &Handshake) {
    let value = long_array(env, &state.encode());
    if !value.is_null() {
        ((**env).SetObjectArrayElement.unwrap())(env, owner, 0, value);
    }
}
unsafe fn deadline(env: *mut JNIEnv, token: jlongArray) -> Option<Deadline> {
    let words = longs(env, token)?;
    if words.len() != 3 || !(0..=2).contains(&words[0]) || !(0..4).contains(&words[1]) {
        invalid(env);
        return None;
    }
    Some(Deadline {
        kind: words[0] as i32,
        flags: words[1] as u8,
        value: words[2],
    })
}
unsafe fn save_deadline(env: *mut JNIEnv, token: jlongArray, value: &Deadline) {
    ((**env).SetLongArrayRegion.unwrap())(
        env,
        token,
        0,
        3,
        [value.kind as i64, value.flags as i64, value.value].as_ptr(),
    );
}
unsafe fn wait(env: *mut JNIEnv, token: jlongArray) -> Option<TransportWait> {
    let words = longs(env, token)?;
    if words.len() != 2 {
        invalid(env);
        return None;
    }
    Some(TransportWait {
        wait: words[0],
        start: words[1],
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeIap2Handshake_create(
    env: *mut JNIEnv,
    _class: jclass,
    kind: jint,
) -> jlongArray {
    let Some(state) = Handshake::new(kind) else {
        invalid(env);
        return null_mut();
    };
    long_array(env, &state.encode())
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeIap2Handshake_query(
    env: *mut JNIEnv,
    _class: jclass,
    owner: jobjectArray,
    field: jint,
) -> jlong {
    read(env, owner).map(|s| s.query(field)).unwrap_or(0)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeIap2Handshake_event(
    env: *mut JNIEnv,
    _class: jclass,
    owner: jobjectArray,
    event: jint,
    value: jint,
    condition: jboolean,
) {
    let Some(mut state) = read(env, owner) else {
        return;
    };
    if !state.event(event, value, condition != 0) {
        invalid(env);
        return;
    }
    save(env, owner, &state);
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeIap2Handshake_message(
    env: *mut JNIEnv,
    _class: jclass,
    owner: jobjectArray,
    id: jint,
) -> jint {
    let Some(mut state) = read(env, owner) else {
        return 0;
    };
    let action = state.message(id);
    save(env, owner, &state);
    action
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeIap2Handshake_omitted(
    env: *mut JNIEnv,
    _class: jclass,
    owner: jobjectArray,
) -> jintArray {
    let Some(Handshake::Identification { omitted, .. }) = read(env, owner) else {
        invalid(env);
        return null_mut();
    };
    int_array(env, &omitted)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeIap2Handshake_rejected(
    env: *mut JNIEnv,
    _class: jclass,
    owner: jobjectArray,
    parameters: jintArray,
    ids: jintArray,
    counts: jintArray,
) -> jboolean {
    let Some(mut state) = read(env, owner) else {
        return 0;
    };
    let Some(parameters) = ints(env, parameters) else {
        return 0;
    };
    let Some(ids) = ints(env, ids) else {
        return 0;
    };
    let Some(counts) = ints(env, counts) else {
        return 0;
    };
    let accepted = state.rejected(omittable(&parameters, &ids, &counts));
    save(env, owner, &state);
    accepted as jboolean
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeIap2Handshake_wifiPlan(
    env: *mut JNIEnv,
    _class: jclass,
    owner: jobjectArray,
) -> jint {
    read(env, owner).map(|s| s.wifi_plan()).unwrap_or(0)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeIap2Handshake_startAgain(
    env: *mut JNIEnv,
    _class: jclass,
    owner: jobjectArray,
    decision: jint,
) -> jboolean {
    let Some(mut state) = read(env, owner) else {
        return 0;
    };
    if !(0..=2).contains(&decision) {
        invalid(env);
        return 0;
    }
    let send = state.start_again(decision);
    save(env, owner, &state);
    send as jboolean
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeIap2Handshake_boundPoll(
    env: *mut JNIEnv,
    _class: jclass,
    owner: jobjectArray,
    requested: jlong,
) -> jlong {
    read(env, owner)
        .map(|s| {
            if s.query(12) != 0 {
                requested.min(500)
            } else {
                requested
            }
        })
        .unwrap_or(0)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeIap2Handshake_challengeValid(
    _env: *mut JNIEnv,
    _class: jclass,
    length: jint,
) -> jboolean {
    (1..=128).contains(&length) as jboolean
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeIap2Handshake_rejectionMetadata(
    env: *mut JNIEnv,
    _class: jclass,
    ids: jintArray,
    bodies: jobjectArray,
) -> jobjectArray {
    let Some(ids) = ints(env, ids) else {
        return null_mut();
    };
    let f = &**env;
    if bodies.is_null() || (f.GetArrayLength.unwrap())(env, bodies) != ids.len() as jint {
        invalid(env);
        return null_mut();
    }
    let mut payloads = Vec::new();
    for index in 0..ids.len() {
        let body: jbyteArray = (f.GetObjectArrayElement.unwrap())(env, bodies, index as jint);
        if (f.ExceptionCheck.unwrap())(env) != 0 {
            return null_mut();
        }
        if body.is_null() {
            invalid(env);
            return null_mut();
        }
        let size = (f.GetArrayLength.unwrap())(env, body);
        let mut bytes = vec![0u8; size as usize];
        (f.GetByteArrayRegion.unwrap())(env, body, 0, size, bytes.as_mut_ptr().cast());
        (f.DeleteLocalRef.unwrap())(env, body);
        if (f.ExceptionCheck.unwrap())(env) != 0 {
            return null_mut();
        }
        payloads.push(bytes);
    }
    let metadata = rejection_metadata(&ids, &payloads);
    let class = (f.FindClass.unwrap())(env, c"[I".as_ptr());
    if class.is_null() {
        return null_mut();
    }
    let array = (f.NewObjectArray.unwrap())(env, 3, class, null_mut());
    if array.is_null() {
        return null_mut();
    }
    for (index, value) in metadata.iter().enumerate() {
        if let Some(values) = value {
            let row = int_array(env, values);
            if row.is_null() {
                return null_mut();
            }
            (f.SetObjectArrayElement.unwrap())(env, array, index as jint, row);
            (f.DeleteLocalRef.unwrap())(env, row);
            if (f.ExceptionCheck.unwrap())(env) != 0 {
                return null_mut();
            }
        }
    }
    array
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeIap2Handshake_omittable(
    env: *mut JNIEnv,
    _class: jclass,
    parameters: jintArray,
    ids: jintArray,
    counts: jintArray,
) -> jintArray {
    let Some(parameters) = ints(env, parameters) else {
        return null_mut();
    };
    let Some(ids) = ints(env, ids) else {
        return null_mut();
    };
    let Some(counts) = ints(env, counts) else {
        return null_mut();
    };
    omittable(&parameters, &ids, &counts)
        .map(|ids| int_array(env, &ids))
        .unwrap_or(null_mut())
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeIap2Handshake_deadlinePrepare(
    env: *mut JNIEnv,
    _class: jclass,
    kind: jint,
    timeout: jlong,
) -> jlongArray {
    let Some(value) = Deadline::prepare(kind, timeout) else {
        invalid(env);
        return null_mut();
    };
    long_array(env, &[value.kind as i64, value.flags as i64, value.value])
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeIap2Handshake_deadlineNeedsStart(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
) -> jboolean {
    deadline(env, token)
        .map(|s| s.flags & 1 != 0)
        .unwrap_or(false) as jboolean
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeIap2Handshake_deadlineStart(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    now: jlong,
) {
    let Some(mut value) = deadline(env, token) else {
        return;
    };
    if value.flags & 1 == 0 {
        invalid(env);
        return;
    }
    value.start(now);
    save_deadline(env, token, &value);
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeIap2Handshake_deadlinePlan(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
) -> jlongArray {
    let Some(value) = deadline(env, token) else {
        return null_mut();
    };
    if value.flags & 1 != 0 {
        invalid(env);
        return null_mut();
    }
    long_array(env, &value.plan())
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeIap2Handshake_deadlineAt(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    now: jlong,
) -> jlongArray {
    let Some(value) = deadline(env, token) else {
        return null_mut();
    };
    if value.flags & 1 != 0 {
        invalid(env);
        return null_mut();
    }
    long_array(env, &value.at(now))
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeIap2Handshake_deadlineKeepAlive(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    plan: jint,
    alive: jboolean,
) -> jlong {
    let Some(mut value) = deadline(env, token) else {
        return 0;
    };
    if value.kind != 2 || value.flags & 1 != 0 || !matches!(plan, 2 | 3) {
        invalid(env);
        return 0;
    }
    let remaining = value.keep_alive(plan, alive != 0);
    save_deadline(env, token, &value);
    remaining
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeIap2Handshake_waitCreate(
    env: *mut JNIEnv,
    _class: jclass,
    millis: jlong,
    now: jlong,
) -> jlongArray {
    long_array(env, &[millis, now])
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeIap2Handshake_waitNeedsClock(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    sent: jint,
    notified: jboolean,
) -> jboolean {
    wait(env, token)
        .map(|s| s.needs_clock(sent, notified != 0))
        .unwrap_or(false) as jboolean
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeIap2Handshake_waitExpired(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    sent: jint,
    notified: jboolean,
    now: jlong,
) -> jboolean {
    wait(env, token)
        .map(|s| s.expired(sent, notified != 0, now))
        .unwrap_or(false) as jboolean
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeIap2Handshake_waitBound(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    requested: jlong,
    sent: jint,
    notified: jboolean,
    now: jlong,
) -> jlong {
    wait(env, token)
        .map(|s| s.bound(requested, sent, notified != 0, now))
        .unwrap_or(0)
}
