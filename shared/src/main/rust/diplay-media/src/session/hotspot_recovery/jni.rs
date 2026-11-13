use super::{outage_event, return_wait, Readiness};
use jni_sys::{jboolean, jclass, jint, jlong, jlongArray, JNIEnv};
unsafe fn invalid(env: *mut JNIEnv) {
    let f = &**env;
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return;
    }
    let class = (f.FindClass.unwrap())(env, c"java/lang/IllegalArgumentException".as_ptr());
    if !class.is_null() {
        (f.ThrowNew.unwrap())(env, class, c"Invalid hotspot recovery value".as_ptr());
    }
}
unsafe fn read<const N: usize>(env: *mut JNIEnv, token: jlongArray) -> Option<[i64; N]> {
    let f = &**env;
    if token.is_null() || (f.GetArrayLength.unwrap())(env, token) != N as jint {
        invalid(env);
        return None;
    }
    let mut words = [0; N];
    (f.GetLongArrayRegion.unwrap())(env, token, 0, N as jint, words.as_mut_ptr());
    ((f.ExceptionCheck.unwrap())(env) == 0).then_some(words)
}
unsafe fn array(env: *mut JNIEnv, values: &[i64]) -> jlongArray {
    let f = &**env;
    let token = (f.NewLongArray.unwrap())(env, values.len() as jint);
    if !token.is_null() {
        (f.SetLongArrayRegion.unwrap())(env, token, 0, values.len() as jint, values.as_ptr());
    }
    token
}
unsafe fn readiness(env: *mut JNIEnv, token: jlongArray) -> Option<Readiness> {
    let words = read(env, token)?;
    if words[1] != words[1] as i32 as i64
        || !(-1..=1).contains(&words[2])
        || !(0..=1).contains(&words[3])
    {
        invalid(env);
        return None;
    }
    Some(Readiness { words })
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_network_NativeHotspotRecovery_readinessCreate(
    env: *mut JNIEnv,
    _: jclass,
    now: jlong,
    timeout: jlong,
) -> jlongArray {
    array(env, &[now.wrapping_add(timeout), 0, -1, 0])
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_network_NativeHotspotRecovery_readinessSample(
    env: *mut JNIEnv,
    _: jclass,
    token: jlongArray,
    selected: jboolean,
    had_previous: jboolean,
    same: jboolean,
    confirmed: jboolean,
) -> jboolean {
    let Some(mut state) = readiness(env, token) else {
        return 0;
    };
    let result = state.sample(selected != 0, had_previous != 0, same != 0, confirmed != 0);
    ((**env).SetLongArrayRegion.unwrap())(env, token, 0, 4, state.words.as_ptr());
    result as jboolean
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_network_NativeHotspotRecovery_readinessTime(
    env: *mut JNIEnv,
    _: jclass,
    token: jlongArray,
    expired: jboolean,
    now: jlong,
) -> jlong {
    readiness(env, token).map_or(0, |state| state.time(expired != 0, now))
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_network_NativeHotspotRecovery_returnWait(
    _: *mut JNIEnv,
    _: jclass,
    start: jlong,
    timeout: jlong,
    now: jlong,
) -> jlong {
    return_wait(start, timeout, now)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_network_NativeHotspotRecovery_outageCreate(
    env: *mut JNIEnv,
    _: jclass,
) -> jlongArray {
    array(env, &[0])
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_network_NativeHotspotRecovery_outageEvent(
    env: *mut JNIEnv,
    _: jclass,
    token: jlongArray,
    operation: jint,
    now: jlong,
    budget: jlong,
) -> jboolean {
    let Some(mut words): Option<[i64; 1]> = read(env, token) else {
        return 0;
    };
    let result = outage_event(&mut words[0], operation, now, budget);
    ((**env).SetLongArrayRegion.unwrap())(env, token, 0, 1, words.as_ptr());
    result as jboolean
}
