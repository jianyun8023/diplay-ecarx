//! PairVerify's monitor serializes use/destroy; references stay within each synchronous call.
use super::PairVerify;
use crate::platform::jvm::Env;
use jni_sys::{jboolean, jbyteArray, jclass, jint, jlong, jobject, jvalue, JNIEnv};
use std::ptr::null_mut;

unsafe fn input(env: *mut JNIEnv, array: jbyteArray) -> Option<Vec<u8>> {
    let f = &**env;
    let mut bytes = vec![0; Env(env).array_len(array)];
    (f.GetByteArrayRegion.unwrap())(
        env,
        array,
        0,
        bytes.len() as jint,
        bytes.as_mut_ptr() as *mut i8,
    );
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        None
    } else {
        Some(bytes)
    }
}

unsafe fn bytes(env: *mut JNIEnv, bytes: &[u8]) -> Option<jbyteArray> {
    let f = &**env;
    let array = (f.NewByteArray.unwrap())(env, bytes.len() as jint);
    if array.is_null() {
        return None;
    }
    (f.SetByteArrayRegion.unwrap())(
        env,
        array,
        0,
        bytes.len() as jint,
        bytes.as_ptr() as *const i8,
    );
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        None
    } else {
        Some(array)
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativePairVerify_create(
    env: *mut JNIEnv,
    _class: jclass,
    seed: jbyteArray,
    identifier: jbyteArray,
) -> jlong {
    (|| -> Option<jlong> {
        Some(Box::into_raw(Box::new(PairVerify::new(
            input(env, seed)?,
            input(env, identifier)?,
        ))) as jlong)
    })()
    .unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativePairVerify_handle(
    env: *mut JNIEnv,
    class: jclass,
    handle: jlong,
    body: jbyteArray,
    pairings: jobject,
) -> jbyteArray {
    let f = &**env;
    if (f.PushLocalFrame.unwrap())(env, 8) < 0 {
        return null_mut();
    }
    let result = (|| -> Option<jobject> {
        let body = input(env, body)?;
        let output = (&mut *(handle as *mut PairVerify)).handle(&body, |identifier| {
            let method = (f.GetStaticMethodID.unwrap())(
                env,
                class,
                c"lookup".as_ptr(),
                c"(Lcom/shihab/diplay/airplay/PairingStore;[B)[B".as_ptr(),
            );
            if method.is_null() {
                return None;
            }
            let args = [
                jvalue { l: pairings },
                jvalue {
                    l: bytes(env, identifier)?,
                },
            ];
            let key = (f.CallStaticObjectMethodA.unwrap())(env, class, method, args.as_ptr());
            if (f.ExceptionCheck.unwrap())(env) != 0 || key.is_null() {
                return None;
            }
            input(env, key)
        });
        if (f.ExceptionCheck.unwrap())(env) != 0 {
            return None;
        }
        bytes(env, &output)
    })();
    (f.PopLocalFrame.unwrap())(env, result.unwrap_or(null_mut()))
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativePairVerify_shared(
    env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
) -> jbyteArray {
    (&*(handle as *const PairVerify))
        .shared
        .as_ref()
        .and_then(|value| bytes(env, value))
        .unwrap_or(null_mut())
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativePairVerify_controllerId(
    env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
) -> jbyteArray {
    (&*(handle as *const PairVerify))
        .controller_id
        .as_ref()
        .and_then(|value| bytes(env, value))
        .unwrap_or(null_mut())
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativePairVerify_verified(
    _env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
) -> jboolean {
    (&*(handle as *const PairVerify)).keys.is_some() as jboolean
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativePairVerify_keys(
    env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
) -> jobject {
    let Some((read, write)) = (&*(handle as *const PairVerify)).keys.as_ref() else {
        return null_mut();
    };
    let f = &**env;
    if (f.PushLocalFrame.unwrap())(env, 8) < 0 {
        return null_mut();
    }
    let result = (|| -> Option<jobject> {
        let class = (f.FindClass.unwrap())(
            env,
            c"com/shihab/diplay/airplay/PairVerify$ControlKeys".as_ptr(),
        );
        if class.is_null() {
            return None;
        }
        let init = (f.GetMethodID.unwrap())(env, class, c"<init>".as_ptr(), c"([B[B)V".as_ptr());
        if init.is_null() {
            return None;
        }
        let args = [
            jvalue {
                l: bytes(env, read)?,
            },
            jvalue {
                l: bytes(env, write)?,
            },
        ];
        let object = (f.NewObjectA.unwrap())(env, class, init, args.as_ptr());
        if (f.ExceptionCheck.unwrap())(env) != 0 {
            None
        } else {
            Some(object)
        }
    })();
    (f.PopLocalFrame.unwrap())(env, result.unwrap_or(null_mut()))
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativePairVerify_destroy(
    _env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
) {
    if handle != 0 {
        drop(Box::from_raw(handle as *mut PairVerify));
    }
}
