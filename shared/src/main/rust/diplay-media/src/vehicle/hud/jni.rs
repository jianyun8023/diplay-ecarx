use super::*;
use jni_sys::*;
use std::ptr::null_mut;

unsafe fn text(env: *mut JNIEnv, value: jstring) -> Option<Vec<u16>> {
    if value.is_null() {
        return None;
    }
    let f = &**env;
    let p = (f.GetStringChars.unwrap())(env, value, null_mut());
    if p.is_null() {
        return None;
    }
    let out =
        std::slice::from_raw_parts(p, (f.GetStringLength.unwrap())(env, value) as usize).to_vec();
    (f.ReleaseStringChars.unwrap())(env, value, p);
    Some(out)
}
unsafe fn string(env: *mut JNIEnv, value: Option<&[u16]>) -> jstring {
    value
        .map(|v| ((**env).NewString.unwrap())(env, v.as_ptr(), v.len() as i32))
        .unwrap_or(null_mut())
}
unsafe fn ints(env: *mut JNIEnv, value: jintArray) -> Option<Vec<i32>> {
    if value.is_null() {
        return None;
    }
    let f = &**env;
    let mut out = vec![0; (f.GetArrayLength.unwrap())(env, value) as usize];
    (f.GetIntArrayRegion.unwrap())(env, value, 0, out.len() as i32, out.as_mut_ptr());
    Some(out)
}
unsafe fn array(env: *mut JNIEnv, value: Option<&[i32]>) -> jintArray {
    let Some(value) = value else {
        return null_mut();
    };
    let f = &**env;
    let out = (f.NewIntArray.unwrap())(env, value.len() as i32);
    if !out.is_null() {
        (f.SetIntArrayRegion.unwrap())(env, out, 0, value.len() as i32, value.as_ptr());
    }
    out
}
unsafe fn guidance(env: *mut JNIEnv, values: jlongArray, road: jstring) -> Option<Guidance> {
    if values.is_null() {
        return None;
    }
    let mut fields = [0; 8];
    ((**env).GetLongArrayRegion.unwrap())(env, values, 0, 8, fields.as_mut_ptr());
    Some(Guidance {
        values: fields,
        road: text(env, road)?,
    })
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeHudPublication_create(
    _: *mut JNIEnv,
    _: jclass,
) -> jlong {
    Box::into_raw(Box::new(Hud::default())) as jlong
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeHudPublication_destroy(
    _: *mut JNIEnv,
    _: jclass,
    handle: jlong,
) {
    if handle != 0 {
        drop(Box::from_raw(handle as *mut Hud));
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeHudPublication_get(
    _: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    field: jint,
) -> jlong {
    (&*(handle as *const Hud)).scalar[field as usize]
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeHudPublication_set(
    _: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    field: jint,
    value: jlong,
) {
    (&mut *(handle as *mut Hud)).scalar[field as usize] = value;
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeHudPublication_step(
    _: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    event: jint,
    a: jlong,
    b: jlong,
    c: jlong,
) -> jlong {
    let h = &mut *(handle as *mut Hud);
    match event {
        0 => {
            *h = Hud::default();
            0
        }
        1 => h.intent(a != 0, b != 0) as i64,
        2 => h.cruise(a != 0, b != 0) as i64,
        3 => h.option(a as i32, b != 0) as i64,
        4 => {
            h.visibility(a != 0);
            0
        }
        5 => {
            h.accept_presentation(a != 0);
            0
        }
        6 => {
            h.start_guidance(a != 0, b != 0);
            0
        }
        7 => {
            h.idle_visibility(a != 0);
            0
        }
        8 => h.due(a != 0, b != 0) as i64,
        9 => h.finished(a != 0, b as i32, c != 0) as i64,
        10 => h.refresh(a != 0) as i64,
        11 => {
            h.clear_cache(a != 0);
            0
        }
        12 => {
            h.cache.retain(|(i, _)| *i >= a as i32);
            0
        }
        13 => h.clear_lanes(a != 0) as i64,
        14 => h.stop_plan(a != 0, b != 0, c != 0) as i64,
        15 => {
            h.stopped();
            0
        }
        _ => unreachable!(),
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeHudPublication_cache(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    index: jint,
    lanes: jintArray,
    turn: jint,
    cursor: jint,
) {
    (&mut *(handle as *mut Hud)).cache(index, ints(env, lanes), turn, cursor);
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeHudPublication_lanePlan(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    force: jboolean,
    cursor: jint,
    showing: jint,
    has_turn: jboolean,
    turn: jint,
    enabled: jint,
) -> jintArray {
    array(
        env,
        Some(&(&mut *(handle as *mut Hud)).lanes(
            force != 0,
            cursor,
            showing,
            has_turn != 0,
            turn,
            enabled,
        )),
    )
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeHudPublication_laneSnapshot(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    field: jint,
) -> jintArray {
    let h = &*(handle as *const Hud);
    match field {
        0 => array(env, h.applied_lanes.as_deref()),
        1 => array(
            env,
            Some(&h.cache.iter().map(|(i, _)| *i).collect::<Vec<_>>()),
        ),
        _ => array(env, h.selected.as_deref()),
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeHudPublication_laneCommit(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    lanes: jintArray,
) {
    (&mut *(handle as *mut Hud)).applied_lanes = ints(env, lanes);
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeHudPublication_guidanceChanged(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    fields: jlongArray,
    road: jstring,
    force: jboolean,
) -> jboolean {
    (force != 0 || (&*(handle as *const Hud)).applied_guidance != guidance(env, fields, road))
        as jboolean
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeHudPublication_guidanceCommit(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    fields: jlongArray,
    road: jstring,
) {
    (&mut *(handle as *mut Hud)).applied_guidance = guidance(env, fields, road);
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeHudPublication_guidanceSnapshot(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
) -> jobjectArray {
    let Some(guidance) = &(&*(handle as *const Hud)).applied_guidance else {
        return null_mut();
    };
    let f = &**env;
    let class = (f.FindClass.unwrap())(env, c"java/lang/Object".as_ptr());
    if class.is_null() {
        return null_mut();
    }
    let out = (f.NewObjectArray.unwrap())(env, 2, class, null_mut());
    let fields = (f.NewLongArray.unwrap())(env, 8);
    let road = string(env, Some(&guidance.road));
    if out.is_null() || fields.is_null() || road.is_null() {
        return null_mut();
    }
    (f.SetLongArrayRegion.unwrap())(env, fields, 0, 8, guidance.values.as_ptr());
    (f.SetObjectArrayElement.unwrap())(env, out, 0, fields);
    (f.SetObjectArrayElement.unwrap())(env, out, 1, road);
    out
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeHudPublication_destinationSnapshot(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
) -> jstring {
    string(env, (&*(handle as *const Hud)).destination.as_deref())
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeHudPublication_destinationCommit(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    value: jstring,
) {
    (&mut *(handle as *mut Hud)).destination = text(env, value);
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeHudPublication_destinationChanged(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    value: jstring,
) -> jboolean {
    ((&*(handle as *const Hud)).destination != text(env, value)) as jboolean
}
