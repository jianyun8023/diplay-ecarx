use super::{Error, Event, Mode, Owner};
use jni_sys::{jbyteArray, jclass, jint, jlong, jobjectArray, JNIEnv};
use std::{
    ffi::{CStr, CString},
    ptr::null_mut,
};
unsafe fn ok(env: *mut JNIEnv) -> Option<()> {
    (((**env).ExceptionCheck.unwrap())(env) == 0).then_some(())
}
unsafe fn read(env: *mut JNIEnv, a: jbyteArray) -> Option<Vec<u8>> {
    if a.is_null() {
        return Some(Vec::new());
    }
    let n = ((**env).GetArrayLength.unwrap())(env, a);
    ok(env)?;
    let mut bytes = vec![0u8; n as usize];
    ((**env).GetByteArrayRegion.unwrap())(env, a, 0, n, bytes.as_mut_ptr() as *mut i8);
    ok(env)?;
    Some(bytes)
}
unsafe fn bytes(env: *mut JNIEnv, value: &[u8]) -> Option<jbyteArray> {
    let a = ((**env).NewByteArray.unwrap())(env, value.len() as i32);
    ok(env)?;
    ((**env).SetByteArrayRegion.unwrap())(
        env,
        a,
        0,
        value.len() as i32,
        value.as_ptr() as *const i8,
    );
    ok(env)?;
    Some(a)
}
unsafe fn throw(env: *mut JNIEnv, class: &CStr, message: &str) {
    let c = ((**env).FindClass.unwrap())(env, class.as_ptr());
    if ok(env).is_none() {
        return;
    }
    let message = CString::new(message).unwrap();
    ((**env).ThrowNew.unwrap())(env, c, message.as_ptr());
}
unsafe fn owner<'a>(handle: jlong) -> &'a mut Owner {
    &mut *(handle as *mut Owner)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeDataStream_create(
    _: *mut JNIEnv,
    _: jclass,
    mode: jint,
) -> jlong {
    Box::into_raw(Box::new(Owner::new(if mode == 0 {
        Mode::Iap
    } else {
        Mode::Settings
    }))) as jlong
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeDataStream_begin(
    _: *mut JNIEnv,
    _: jclass,
    handle: jlong,
) -> jlong {
    owner(handle).begin() as i64
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeDataStream_append(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    id: jlong,
    chunk: jbyteArray,
) {
    if let Some(bytes) = read(env, chunk) {
        owner(handle).append(id as u64, &bytes)
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeDataStream_next(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    id: jlong,
    read_key: jbyteArray,
    write_key: jbyteArray,
) -> jobjectArray {
    ((**env).PushLocalFrame.unwrap())(env, 8);
    if ok(env).is_none() {
        return null_mut();
    }
    let result = (|| {
        let read_key = read(env, read_key)?;
        let write_key = read(env, write_key)?;
        let event = match owner(handle).next(id as u64, &read_key, &write_key) {
            Ok(Some(event)) => event,
            Ok(None) => return None,
            Err(Error::Key) => {
                throw(
                    env,
                    c"java/lang/IllegalArgumentException",
                    "ChaCha20 requires a 256 bit key",
                );
                return None;
            }
            Err(Error::Tag) => {
                throw(
                    env,
                    c"javax/crypto/AEADBadTagException",
                    "ChaCha20-Poly1305 tag mismatch",
                );
                return None;
            }
            Err(Error::Package(size)) => {
                throw(
                    env,
                    c"java/lang/IllegalStateException",
                    &format!("bad package size {size}"),
                );
                return None;
            }
        };
        let (kind, value) = match event {
            Event::Iap(b) => (1, b),
            Event::Reply(b) => (2, b),
        };
        let c = ((**env).FindClass.unwrap())(env, c"java/lang/Object".as_ptr());
        ok(env)?;
        let out = ((**env).NewObjectArray.unwrap())(env, 2, c, null_mut());
        ok(env)?;
        let meta = ((**env).NewIntArray.unwrap())(env, 1);
        ok(env)?;
        ((**env).SetIntArrayRegion.unwrap())(env, meta, 0, 1, &kind);
        ok(env)?;
        ((**env).SetObjectArrayElement.unwrap())(env, out, 0, meta);
        ok(env)?;
        let value = bytes(env, &value)?;
        ((**env).SetObjectArrayElement.unwrap())(env, out, 1, value);
        ok(env)?;
        Some(out)
    })()
    .unwrap_or(null_mut());
    ((**env).PopLocalFrame.unwrap())(env, result)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeDataStream_end(
    _: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    id: jlong,
) {
    owner(handle).connections.remove(&(id as u64));
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeDataStream_destroy(
    _: *mut JNIEnv,
    _: jclass,
    handle: jlong,
) {
    if handle != 0 {
        drop(Box::from_raw(handle as *mut Owner));
    }
}
