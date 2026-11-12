use jni_sys::{jbyteArray, jclass, jint, JNIEnv};
use std::ptr::null_mut;
struct Env(*mut JNIEnv);
impl Env {
    unsafe fn ok(&self) -> Option<()> {
        (((**self.0).ExceptionCheck.unwrap())(self.0) == 0).then_some(())
    }
    unsafe fn length(&self, a: jbyteArray) -> Option<usize> {
        let n = ((**self.0).GetArrayLength.unwrap())(self.0, a);
        self.ok()?;
        Some(n as usize)
    }
    unsafe fn bytes(&self, bytes: &[u8]) -> Option<jbyteArray> {
        let a = ((**self.0).NewByteArray.unwrap())(self.0, bytes.len() as i32);
        self.ok()?;
        ((**self.0).SetByteArrayRegion.unwrap())(
            self.0,
            a,
            0,
            bytes.len() as i32,
            bytes.as_ptr() as *const i8,
        );
        self.ok()?;
        Some(a)
    }
}
unsafe fn array(env: *mut JNIEnv, body: impl FnOnce(&Env) -> Option<jbyteArray>) -> jbyteArray {
    let e = Env(env);
    ((**env).PushLocalFrame.unwrap())(env, 4);
    if e.ok().is_none() {
        return null_mut();
    }
    let out = body(&e).unwrap_or(null_mut());
    ((**env).PopLocalFrame.unwrap())(env, out)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeAac_frequencyIndex(
    _env: *mut JNIEnv,
    _: jclass,
    rate: jint,
) -> jint {
    super::frequency_index(rate) as i32
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeAac_writeAdtsHeader(
    env: *mut JNIEnv,
    _: jclass,
    target: jbyteArray,
    access_unit_bytes: jint,
    rate: jint,
    channels: jint,
) -> jint {
    let e = Env(env);
    let Some(n) = e.length(target) else { return -1 };
    let bytes = super::header(access_unit_bytes, rate, channels);
    ((**env).SetByteArrayRegion.unwrap())(
        env,
        target,
        0,
        n.min(7) as i32,
        bytes.as_ptr() as *const i8,
    );
    if e.ok().is_none() {
        return -1;
    }
    // Kotlin executes this one out-of-range SDK array write after returning. Both VMs keep their
    // original array-index exception message, and the already-written valid prefix remains visible.
    if n < 7 {
        n as i32
    } else {
        -1
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeAac_audioSpecificConfig(
    env: *mut JNIEnv,
    _: jclass,
    rate: jint,
    channels: jint,
) -> jbyteArray {
    array(env, |e| {
        e.bytes(&super::audio_specific_config(rate, channels))
    })
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeAac_accessUnit(
    env: *mut JNIEnv,
    _: jclass,
    payload: jbyteArray,
) -> jbyteArray {
    array(env, |e| {
        let n = e.length(payload)?;
        let mut first = [0; 4];
        ((**env).GetByteArrayRegion.unwrap())(
            env,
            payload,
            0,
            n.min(4) as i32,
            first.as_mut_ptr() as *mut i8,
        );
        e.ok()?;
        let Some(range) = super::access_unit_range(first, n) else {
            return e.bytes(&[]);
        };
        let mut out = vec![0u8; range.len()];
        ((**env).GetByteArrayRegion.unwrap())(
            env,
            payload,
            range.start as i32,
            range.len() as i32,
            out.as_mut_ptr() as *mut i8,
        );
        e.ok()?;
        e.bytes(&out)
    })
}
