//! Java monitor serializes handles; a per-call Arc keeps a reentrantly closed owner alive.
use super::{error, PairSetup, Step};
use jni_sys::{jboolean, jbyteArray, jclass, jint, jlong, jobject, jvalue, JNIEnv};
use std::{
    ptr::null_mut,
    sync::{Arc, Mutex},
};
type Owner = Arc<Mutex<PairSetup>>;
unsafe fn input(env: *mut JNIEnv, array: jbyteArray) -> Option<Vec<u8>> {
    let f = &**env;
    let len = (f.GetArrayLength.unwrap())(env, array);
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return None;
    }
    let mut out = vec![0; len as usize];
    (f.GetByteArrayRegion.unwrap())(env, array, 0, len, out.as_mut_ptr() as *mut i8);
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        None
    } else {
        Some(out)
    }
}
unsafe fn bytes(env: *mut JNIEnv, value: &[u8]) -> Option<jbyteArray> {
    let f = &**env;
    let array = (f.NewByteArray.unwrap())(env, value.len() as jint);
    if array.is_null() {
        return None;
    }
    (f.SetByteArrayRegion.unwrap())(
        env,
        array,
        0,
        value.len() as jint,
        value.as_ptr() as *const i8,
    );
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        None
    } else {
        Some(array)
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativePairSetup_create(
    env: *mut JNIEnv,
    _class: jclass,
    seed: jbyteArray,
    public: jbyteArray,
    id: jbyteArray,
) -> jlong {
    (|| -> Option<jlong> {
        let core = PairSetup::new(&input(env, seed)?, input(env, public)?, input(env, id)?);
        Some(Box::into_raw(Box::new(Arc::new(Mutex::new(core)))) as jlong)
    })()
    .unwrap_or(0)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativePairSetup_handle(
    env: *mut JNIEnv,
    class: jclass,
    handle: jlong,
    body: jbyteArray,
    pairings: jobject,
) -> jbyteArray {
    let f = &**env;
    if (f.PushLocalFrame.unwrap())(env, 16) < 0 {
        return null_mut();
    }
    let result = (|| -> Option<jobject> {
        let body = input(env, body)?;
        let owner = Arc::clone(&*(handle as *const Owner));
        let step = owner.lock().unwrap().handle(&body);
        let reply = match step {
            Step::Reply(reply) => reply,
            Step::Save(saved) => {
                let exception = (f.FindClass.unwrap())(env, c"java/lang/Exception".as_ptr());
                if exception.is_null() {
                    return None;
                }
                let save = (f.GetStaticMethodID.unwrap())(
                    env,
                    class,
                    c"save".as_ptr(),
                    c"(Lcom/shihab/diplay/airplay/PairingStore;[B[B)V".as_ptr(),
                );
                if save.is_null() {
                    return None;
                }
                let args = [
                    jvalue { l: pairings },
                    jvalue {
                        l: bytes(env, &saved.identifier)?,
                    },
                    jvalue {
                        l: bytes(env, &saved.public)?,
                    },
                ];
                (f.CallStaticVoidMethodA.unwrap())(env, class, save, args.as_ptr());
                if (f.ExceptionCheck.unwrap())(env) != 0 {
                    let thrown = (f.ExceptionOccurred.unwrap())(env);
                    (f.ExceptionClear.unwrap())(env);
                    let ordinary = (f.IsInstanceOf.unwrap())(env, thrown, exception) != 0;
                    if !ordinary {
                        (f.Throw.unwrap())(env, thrown);
                        return None;
                    }
                    // Kotlin caught ordinary Exception, after PairingStore had already updated its map.
                    error(5)
                } else {
                    owner.lock().unwrap().after_save(saved)
                }
            }
        };
        bytes(env, &reply)
    })();
    (f.PopLocalFrame.unwrap())(env, result.unwrap_or(null_mut()))
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativePairSetup_complete(
    _env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
) -> jboolean {
    (&*(handle as *const Owner)).lock().unwrap().complete as jboolean
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativePairSetup_destroy(
    _env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
) {
    if handle != 0 {
        drop(Box::from_raw(handle as *mut Owner));
    }
}
