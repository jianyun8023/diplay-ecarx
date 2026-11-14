use jni_sys::{jclass, jint, jlong, jlongArray, JNIEnv};
use std::ptr::null_mut;

unsafe fn ok(env: *mut JNIEnv) -> Option<()> { (((**env).ExceptionCheck.unwrap())(env) == 0).then_some(()) }
unsafe fn invalid(env: *mut JNIEnv) {
    if ok(env).is_none() { return; }
    let c = ((**env).FindClass.unwrap())(env, c"java/lang/IllegalArgumentException".as_ptr());
    if !c.is_null() { ((**env).ThrowNew.unwrap())(env, c, c"Invalid video queue state or operation".as_ptr()); }
}
unsafe fn longs(env: *mut JNIEnv, values: &[i64]) -> Option<jlongArray> {
    let a = ((**env).NewLongArray.unwrap())(env, values.len() as i32); ok(env)?;
    ((**env).SetLongArrayRegion.unwrap())(env, a, 0, values.len() as i32, values.as_ptr()); ok(env)?;
    Some(a)
}
#[no_mangle]
pub extern "system" fn Java_com_shihab_diplay_media_NativeVideoFlow_touchSent(_: *mut JNIEnv, _: jclass, sent: jlong) { super::touch_sent(sent); }
#[no_mangle]
pub extern "system" fn Java_com_shihab_diplay_media_NativeVideoFlow_touchFrame(_: *mut JNIEnv, _: jclass, now: jlong) -> jlong { super::touch_latency(now).unwrap_or(-1) }
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeVideoFlow_inputCreate(env: *mut JNIEnv, _: jclass, start: jlong, timeout: jlong) -> jlongArray {
    longs(env, &[0, -1, start, timeout]).unwrap_or(null_mut())
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeVideoFlow_inputStep(env: *mut JNIEnv, _: jclass, state: jlongArray, event: jint, value: jlong) -> jint {
    if !(0..=4).contains(&event) { invalid(env); return 4; }
    let mut v = [0; 4]; ((**env).GetLongArrayRegion.unwrap())(env, state, 0, 4, v.as_mut_ptr());
    if ok(env).is_none() { return 4; }
    let action = super::input_step(&mut v, event, value);
    ((**env).SetLongArrayRegion.unwrap())(env, state, 0, 4, v.as_ptr()); action
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeVideoFlow_inputResult(env: *mut JNIEnv, _: jclass, state: jlongArray) -> jint {
    let mut result = -1; ((**env).GetLongArrayRegion.unwrap())(env, state, 1, 1, &mut result); result as jint
}
