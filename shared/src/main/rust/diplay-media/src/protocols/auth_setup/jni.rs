use super::{Error, Sap};
use jni_sys::{jbyteArray, jclass, jint, jobject, jobjectArray, jvalue, JNIEnv};
use std::{ffi::CStr, ptr::null_mut};
struct Env(*mut JNIEnv);
impl Env {
    unsafe fn ok(&self) -> Option<()> {
        (((**self.0).ExceptionCheck.unwrap())(self.0) == 0).then_some(())
    }
    unsafe fn throw(&self, class: &CStr, message: &CStr) {
        let c = ((**self.0).FindClass.unwrap())(self.0, class.as_ptr());
        if self.ok().is_some() {
            ((**self.0).ThrowNew.unwrap())(self.0, c, message.as_ptr());
        }
    }
    unsafe fn read(&self, a: jbyteArray) -> Option<Vec<u8>> {
        if a.is_null() {
            self.throw(c"java/lang/NullPointerException", c"null MFi SDK buffer");
            return None;
        }
        let n = ((**self.0).GetArrayLength.unwrap())(self.0, a);
        self.ok()?;
        let mut bytes = vec![0u8; n as usize];
        ((**self.0).GetByteArrayRegion.unwrap())(self.0, a, 0, n, bytes.as_mut_ptr() as *mut i8);
        self.ok()?;
        Some(bytes)
    }
    unsafe fn bytes(&self, value: &[u8]) -> Option<jbyteArray> {
        let a = ((**self.0).NewByteArray.unwrap())(self.0, value.len() as i32);
        self.ok()?;
        ((**self.0).SetByteArrayRegion.unwrap())(
            self.0,
            a,
            0,
            value.len() as i32,
            value.as_ptr() as *const i8,
        );
        self.ok()?;
        Some(a)
    }
    unsafe fn method(
        &self,
        o: jobject,
        name: &CStr,
        signature: &CStr,
    ) -> Option<jni_sys::jmethodID> {
        let c = ((**self.0).GetObjectClass.unwrap())(self.0, o);
        self.ok()?;
        let m = ((**self.0).GetMethodID.unwrap())(self.0, c, name.as_ptr(), signature.as_ptr());
        self.ok()?;
        ((**self.0).DeleteLocalRef.unwrap())(self.0, c);
        Some(m)
    }
    unsafe fn number(&self, o: jobject, name: &CStr) -> Option<i32> {
        let m = self.method(o, name, c"()I")?;
        let n = ((**self.0).CallIntMethodA.unwrap())(self.0, o, m, std::ptr::null());
        self.ok()?;
        Some(n)
    }
    unsafe fn call(
        &self,
        o: jobject,
        name: &CStr,
        signature: &CStr,
        args: &[jvalue],
    ) -> Option<jobject> {
        let m = self.method(o, name, signature)?;
        let value = ((**self.0).CallObjectMethodA.unwrap())(self.0, o, m, args.as_ptr());
        self.ok()?;
        Some(value)
    }
    unsafe fn sign(&self, o: jobject, challenge: &[u8]) -> Option<Vec<u8>> {
        let challenge = self.bytes(challenge)?;
        let signature = self.call(o, c"sign", c"([B)[B", &[jvalue { l: challenge }])?;
        self.read(signature)
    }
    unsafe fn item(&self, a: jobjectArray, i: jint) -> Option<jobject> {
        let v = ((**self.0).GetObjectArrayElement.unwrap())(self.0, a, i);
        self.ok()?;
        Some(v)
    }
}

/// The complete ephemeral owner is local to this call. No native lock, handle or mutable borrow
/// crosses an SDK callback, so callbacks may close the session or reenter auth-setup safely.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMfiSap_handle(
    env: *mut JNIEnv,
    _: jclass,
    body: jbyteArray,
    sdk: jobject,
) -> jbyteArray {
    let e = Env(env);
    ((**env).PushLocalFrame.unwrap())(env, 24);
    if e.ok().is_none() {
        return null_mut();
    }
    let result = (|| {
        let body = e.read(body)?;
        let sap = match Sap::start(&body) {
            Ok(Some(sap)) => sap,
            Ok(None) => return None,
            Err(Error::Random) => {
                e.throw(
                    c"java/lang/IllegalStateException",
                    c"OS random source failed",
                );
                return None;
            }
            Err(Error::Agreement) => {
                e.throw(
                    c"java/lang/IllegalStateException",
                    c"X25519 agreement failed",
                );
                return None;
            }
        };
        let result = match e.number(sdk, c"kind")? {
            0 => {
                // Retain the actual SDK array until after both later SDK callbacks. They may
                // mutate their returned certificate, which the former Kotlin path also observed.
                let certificate = e.call(sdk, c"certificate", c"()[B", &[])?;
                let major = e.number(sdk, c"major")?;
                let signature = e.sign(sdk, &sap.challenge(major))?;
                let encrypted = sap.encrypt(signature);
                sap.response(&e.read(certificate)?, &encrypted, None)
            }
            1 => {
                let certificates = e.call(sdk, c"baa", c"()[[B", &[])?;
                let signature = e.sign(sdk, &sap.transcript())?;
                let encrypted = sap.encrypt(signature);
                let leaf = e.item(certificates, 0)?;
                let intermediate = e.item(certificates, 1)?;
                sap.response(&e.read(leaf)?, &encrypted, Some(&e.read(intermediate)?))
            }
            _ => unreachable!("typed MfiCertificateType SDK adapter"),
        };
        e.bytes(&result)
    })()
    .unwrap_or(null_mut());
    ((**env).PopLocalFrame.unwrap())(env, result)
}
