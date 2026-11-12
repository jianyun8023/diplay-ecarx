//! Synchronous boundary: ControlCipher holds its monitor across every handle use and destruction.
use crate::{
    control_cipher::{ControlCipher, Error},
    jvm::Env,
};
use jni_sys::{jbyteArray, jclass, jint, jlong, jobject, jvalue, JNIEnv};
use std::{ffi::CStr, ptr::null_mut};

unsafe fn throw(env: *mut JNIEnv, class: &CStr, message: &CStr) {
    let f = &**env;
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return;
    }
    let class = (f.FindClass.unwrap())(env, class.as_ptr());
    if !class.is_null() {
        (f.ThrowNew.unwrap())(env, class, message.as_ptr());
    }
}

unsafe fn error(env: *mut JNIEnv, error: Error) {
    match error {
        Error::AuthFailed => throw(
            env,
            c"javax/crypto/AEADBadTagException",
            c"ChaCha20-Poly1305 tag mismatch",
        ),
        Error::InvalidKey => throw(
            env,
            c"java/lang/IllegalArgumentException",
            c"ChaCha20Poly1305 key must be 256 bits",
        ),
        Error::SealFailed => throw(
            env,
            c"java/lang/IllegalStateException",
            c"ChaCha20-Poly1305 encryption failed",
        ),
    }
}

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
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeControlCipher_create(
    env: *mut JNIEnv,
    _class: jclass,
    read: jbyteArray,
    write: jbyteArray,
) -> jlong {
    let result = (|| -> Option<jlong> {
        let read = input(env, read)?;
        let write = input(env, write)?;
        Some(Box::into_raw(Box::new(ControlCipher::new(&read, &write))) as jlong)
    })();
    result.unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeControlCipher_encrypt(
    env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
    plain: jbyteArray,
) -> jbyteArray {
    let Some(plain) = input(env, plain) else {
        return null_mut();
    };
    match (&mut *(handle as *mut ControlCipher)).encrypt(&plain) {
        Ok(sealed) => bytes(env, &sealed).unwrap_or(null_mut()),
        Err(failure) => {
            error(env, failure);
            null_mut()
        }
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeControlCipher_decrypt(
    env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
    buffer: jbyteArray,
) -> jobject {
    let Some(input) = input(env, buffer) else {
        return null_mut();
    };
    let decrypted = match (&mut *(handle as *mut ControlCipher)).decrypt(&input) {
        Ok(decrypted) => decrypted,
        Err(failure) => {
            error(env, failure);
            return null_mut();
        }
    };
    let f = &**env;
    if (f.PushLocalFrame.unwrap())(env, 8) < 0 {
        return null_mut();
    }
    let result = (|| -> Option<jobject> {
        let class = (f.FindClass.unwrap())(
            env,
            c"com/shilapi/xcertplay/airplay/ControlCipher$Decrypted".as_ptr(),
        );
        if class.is_null() {
            return None;
        }
        let init = (f.GetMethodID.unwrap())(env, class, c"<init>".as_ptr(), c"([B[B)V".as_ptr());
        if init.is_null() {
            return None;
        }
        let rest = if decrypted.consumed == 0 {
            buffer
        } else {
            bytes(env, &input[decrypted.consumed..])?
        };
        let args = [
            jvalue {
                l: bytes(env, &decrypted.data)?,
            },
            jvalue { l: rest },
        ];
        let object = (f.NewObjectA.unwrap())(env, class, init, args.as_ptr());
        if (f.ExceptionCheck.unwrap())(env) != 0 {
            None
        } else {
            (!object.is_null()).then_some(object)
        }
    })();
    (f.PopLocalFrame.unwrap())(env, result.unwrap_or(null_mut()))
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeControlCipher_destroy(
    _env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
) {
    if handle != 0 {
        drop(Box::from_raw(handle as *mut ControlCipher));
    }
}
