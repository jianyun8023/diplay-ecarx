//! Synchronous JNI: Kotlin serializes every use and destruction of its sole route handle.
use super::RouteGuidance;
use jni_sys::{jboolean, jbyteArray, jclass, jint, jlong, jobject, jstring, jvalue, JNIEnv, JNI_ABORT};
use std::ptr::null_mut;

unsafe fn text(env: *mut JNIEnv, value: &str) -> Option<jstring> {
    let value: Vec<u16> = value.encode_utf16().collect();
    let result = ((**env).NewString.unwrap())(env, value.as_ptr(), value.len() as jint);
    (!result.is_null()).then_some(result)
}

unsafe fn boxed(env: *mut JNIEnv, value: Option<i64>) -> Option<jobject> {
    let Some(value) = value else { return Some(null_mut()); };
    let f = &**env;
    let class = (f.FindClass.unwrap())(env, c"java/lang/Long".as_ptr());
    if class.is_null() { return None; }
    let method = (f.GetStaticMethodID.unwrap())(env, class, c"valueOf".as_ptr(), c"(J)Ljava/lang/Long;".as_ptr());
    if method.is_null() { return None; }
    let result = (f.CallStaticObjectMethodA.unwrap())(env, class, method, [jvalue { j: value }].as_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 { return None; }
    (!result.is_null()).then_some(result)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_hud_NativeRouteGuidance_create(
    _env: *mut JNIEnv, _class: jclass, stale_ns: jlong, empty_ns: jlong, keep: jboolean,
) -> jlong { Box::into_raw(Box::new(RouteGuidance::new(stale_ns, empty_ns, keep != 0))) as jlong }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_hud_NativeRouteGuidance_accept(
    env: *mut JNIEnv, _class: jclass, handle: jlong, id: jint, payload: jbyteArray,
    now: jlong, wall: jlong, live: jboolean,
) -> jint {
    let f = &**env;
    let len = (f.GetArrayLength.unwrap())(env, payload) as usize;
    let data = (f.GetByteArrayElements.unwrap())(env, payload, null_mut());
    if data.is_null() { return 0; }
    let result = (&mut *(handle as *mut RouteGuidance)).accept(id, std::slice::from_raw_parts(data as *const u8, len), now, wall, live != 0);
    (f.ReleaseByteArrayElements.unwrap())(env, payload, data, JNI_ABORT);
    result as jint
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_hud_NativeRouteGuidance_current(
    env: *mut JNIEnv, _class: jclass, handle: jlong, now: jlong, live: jboolean, dim: jboolean,
) -> jobject {
    let state = &*(handle as *const RouteGuidance);
    let Some(current) = state.current(now, live != 0, dim != 0) else { return null_mut(); };
    let f = &**env;
    if (f.PushLocalFrame.unwrap())(env, 16) < 0 { return null_mut(); }
    let result = (|| -> Option<jobject> {
        let class = (f.FindClass.unwrap())(env, c"com/shilapi/xcertplay/hud/AppleManeuver".as_ptr());
        if class.is_null() { return None; }
        let init = (f.GetMethodID.unwrap())(env, class, c"<init>".as_ptr(),
            c"(IIILjava/lang/String;Ljava/lang/Long;Ljava/lang/Long;Ljava/lang/Long;Ljava/lang/Long;)V".as_ptr());
        if init.is_null() { return None; }
        let args = [jvalue { i: current.distance }, jvalue { i: current.kind }, jvalue { i: current.side },
            jvalue { l: text(env, current.road)? }, jvalue { l: boxed(env, current.remaining_seconds)? },
            jvalue { l: boxed(env, current.remaining_meters)? }, jvalue { l: boxed(env, current.arrival)? },
            jvalue { l: boxed(env, current.eta_raw)? }];
        let result = (f.NewObjectA.unwrap())(env, class, init, args.as_ptr());
        (!result.is_null()).then_some(result)
    })();
    (f.PopLocalFrame.unwrap())(env, result.unwrap_or(null_mut()))
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_hud_NativeRouteGuidance_query(
    _env: *mut JNIEnv, _class: jclass, handle: jlong, field: jint,
) -> jlong {
    let state = &*(handle as *const RouteGuidance);
    match field {
        0 => state.index as i64, 1 => state.announced as i64,
        2 => state.input_state.map(i64::from).unwrap_or(-1), 3 => state.phone_state.map(i64::from).unwrap_or(-1),
        4 => state.lane_index.map(i64::from).unwrap_or(-1), 5 => state.lane_showing.map(i64::from).unwrap_or(-1),
        6 => state.arrival.unwrap_or(0), 7 => state.remaining_meters.unwrap_or(-1), 8 => state.arrival.is_some() as i64,
        _ => -1,
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_hud_NativeRouteGuidance_phoneStateAge(
    _env: *mut JNIEnv, _class: jclass, handle: jlong, now: jlong,
) -> jlong { (&*(handle as *const RouteGuidance)).phone_age_ms(now) }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_hud_NativeRouteGuidance_expired(
    _env: *mut JNIEnv, _class: jclass, handle: jlong, now: jlong, live: jboolean,
) -> jboolean { (&*(handle as *const RouteGuidance)).expired(now, live != 0) as jboolean }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_hud_NativeRouteGuidance_text(
    env: *mut JNIEnv, _class: jclass, handle: jlong, field: jint, now: jlong, live: jboolean,
) -> jstring {
    let state = &*(handle as *const RouteGuidance);
    let value = match field {
        0 => &state.destination, 1 => &state.diagnostic_input,
        _ => return text(env, &state.diagnostic_state(now, live != 0)).unwrap_or(null_mut()),
    };
    text(env, value).unwrap_or(null_mut())
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_hud_NativeRouteGuidance_clear(
    env: *mut JNIEnv, _class: jclass, handle: jlong, reason: jstring,
) -> jboolean {
    let f = &**env;
    let chars = (f.GetStringChars.unwrap())(env, reason, null_mut());
    if chars.is_null() { return 0; }
    let len = (f.GetStringLength.unwrap())(env, reason) as usize;
    let reason_text = String::from_utf16_lossy(std::slice::from_raw_parts(chars, len));
    (f.ReleaseStringChars.unwrap())(env, reason, chars);
    (&mut *(handle as *mut RouteGuidance)).clear(&reason_text) as jboolean
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_hud_NativeRouteGuidance_destroy(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) { if handle != 0 { drop(Box::from_raw(handle as *mut RouteGuidance)); } }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_iap2_message_NativeLaneGuidance_parse(
    env: *mut JNIEnv, _: jclass, payload: jbyteArray,
) -> jni_sys::jobjectArray {
    let f = &**env;
    let length = (f.GetArrayLength.unwrap())(env, payload);
    let mut bytes = vec![0u8; length as usize];
    (f.GetByteArrayRegion.unwrap())(env, payload, 0, length, bytes.as_mut_ptr() as *mut i8);
    if (f.ExceptionCheck.unwrap())(env) != 0 { return null_mut(); }
    let Some(rows) = super::lane_update(&bytes) else { return null_mut(); };
    let class = (f.FindClass.unwrap())(env, c"[I".as_ptr());
    if class.is_null() { return null_mut(); }
    let result = (f.NewObjectArray.unwrap())(env, rows.len() as jint, class, null_mut());
    (f.DeleteLocalRef.unwrap())(env, class);
    if result.is_null() { return null_mut(); }
    for (index, row) in rows.iter().enumerate() {
        let array = (f.NewIntArray.unwrap())(env, row.len() as jint);
        if array.is_null() { return null_mut(); }
        (f.SetIntArrayRegion.unwrap())(env, array, 0, row.len() as jint, row.as_ptr());
        if (f.ExceptionCheck.unwrap())(env) != 0 { return null_mut(); }
        (f.SetObjectArrayElement.unwrap())(env, result, index as jint, array);
        (f.DeleteLocalRef.unwrap())(env, array);
        if (f.ExceptionCheck.unwrap())(env) != 0 { return null_mut(); }
    }
    result
}
