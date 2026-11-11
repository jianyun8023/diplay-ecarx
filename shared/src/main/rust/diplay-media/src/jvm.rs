//! A thin layer over jni-sys: the cached JavaVM, thread attachment, and the few calls the
//! receivers make. Every call clears a pending Java exception, so a callback that throws never
//! leaves the receive thread unable to call Java again.

use jni_sys::{
    jbyteArray, jclass, jint, jmethodID, jobject, jsize, jstring, jvalue, JNIEnv, JavaVM,
    JavaVMAttachArgs, JNI_OK, JNI_VERSION_1_6,
};
use std::ffi::CString;
use std::ptr::null_mut;
use std::sync::atomic::{AtomicPtr, Ordering};

static VM: AtomicPtr<JavaVM> = AtomicPtr::new(null_mut());

#[no_mangle]
pub unsafe extern "system" fn JNI_OnLoad(vm: *mut JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    VM.store(vm, Ordering::Release);
    JNI_VERSION_1_6
}

#[derive(Clone, Copy)]
pub struct Env(pub *mut JNIEnv);

impl Env {
    #[inline]
    unsafe fn f(&self) -> &jni_sys::JNINativeInterface_ { &**self.0 }

    /// True (and cleared, after printing it to logcat) when the last call threw.
    pub fn clear_exception(&self) -> bool {
        unsafe {
            if (self.f().ExceptionCheck.unwrap())(self.0) != 0 {
                (self.f().ExceptionDescribe.unwrap())(self.0);
                (self.f().ExceptionClear.unwrap())(self.0);
                true
            } else {
                false
            }
        }
    }

    pub fn object_class(&self, obj: jobject) -> jclass {
        unsafe { (self.f().GetObjectClass.unwrap())(self.0, obj) }
    }

    pub fn method(&self, class: jclass, name: &str, sig: &str) -> jmethodID {
        let (n, s) = (CString::new(name).unwrap(), CString::new(sig).unwrap());
        let id = unsafe { (self.f().GetMethodID.unwrap())(self.0, class, n.as_ptr(), s.as_ptr()) };
        self.clear_exception();
        id
    }

    pub fn new_global(&self, obj: jobject) -> jobject {
        unsafe { (self.f().NewGlobalRef.unwrap())(self.0, obj) }
    }

    pub fn delete_global(&self, obj: jobject) {
        if !obj.is_null() {
            unsafe { (self.f().DeleteGlobalRef.unwrap())(self.0, obj) }
        }
    }

    pub fn delete_local(&self, obj: jobject) {
        if !obj.is_null() {
            unsafe { (self.f().DeleteLocalRef.unwrap())(self.0, obj) }
        }
    }

    pub fn call_void(&self, obj: jobject, method: jmethodID, args: &[jvalue]) {
        unsafe { (self.f().CallVoidMethodA.unwrap())(self.0, obj, method, args.as_ptr()) };
        self.clear_exception();
    }

    /// A Java string for ASCII text (logs and reports); interior NULs are dropped.
    pub fn string(&self, text: &str) -> jstring {
        let c = CString::new(text.replace('\0', "")).unwrap();
        unsafe { (self.f().NewStringUTF.unwrap())(self.0, c.as_ptr()) }
    }

    pub fn byte_array(&self, bytes: &[u8]) -> jbyteArray {
        unsafe {
            let array = (self.f().NewByteArray.unwrap())(self.0, bytes.len() as jsize);
            if !array.is_null() {
                (self.f().SetByteArrayRegion.unwrap())(self.0, array, 0, bytes.len() as jsize, bytes.as_ptr() as *const i8);
            }
            self.clear_exception();
            array
        }
    }

    pub fn array_len(&self, array: jbyteArray) -> usize {
        unsafe { (self.f().GetArrayLength.unwrap())(self.0, array) as usize }
    }

    pub fn read_array(&self, array: jbyteArray, offset: usize, out: &mut [u8]) -> bool {
        unsafe {
            (self.f().GetByteArrayRegion.unwrap())(self.0, array, offset as jsize, out.len() as jsize, out.as_mut_ptr() as *mut i8);
        }
        !self.clear_exception()
    }

    pub fn write_array(&self, array: jbyteArray, offset: usize, bytes: &[u8]) -> bool {
        unsafe {
            (self.f().SetByteArrayRegion.unwrap())(self.0, array, offset as jsize, bytes.len() as jsize, bytes.as_ptr() as *const i8);
        }
        !self.clear_exception()
    }

    /// The address and capacity of a direct ByteBuffer, or None for a heap buffer.
    pub fn direct_buffer(&self, buffer: jobject) -> Option<(*mut u8, usize)> {
        unsafe {
            let address = (self.f().GetDirectBufferAddress.unwrap())(self.0, buffer) as *mut u8;
            let capacity = (self.f().GetDirectBufferCapacity.unwrap())(self.0, buffer);
            if address.is_null() || capacity < 0 { None } else { Some((address, capacity as usize)) }
        }
    }

    /// `android.os.Process.setThreadPriority(priority)` for the calling thread.
    pub fn set_thread_priority(&self, priority: jint) {
        unsafe {
            let name = CString::new("android/os/Process").unwrap();
            let class = (self.f().FindClass.unwrap())(self.0, name.as_ptr());
            if self.clear_exception() || class.is_null() { return; }
            let (m, s) = (CString::new("setThreadPriority").unwrap(), CString::new("(I)V").unwrap());
            let method = (self.f().GetStaticMethodID.unwrap())(self.0, class, m.as_ptr(), s.as_ptr());
            if !self.clear_exception() && !method.is_null() {
                let args = [jvalue { i: priority }];
                (self.f().CallStaticVoidMethodA.unwrap())(self.0, class, method, args.as_ptr());
                self.clear_exception();
            }
            self.delete_local(class);
        }
    }
}

/// Attaches the calling native thread as a daemon under its own name; detaches on drop.
pub struct Attached {
    pub env: Env,
}

impl Attached {
    pub fn current(name: &str) -> Option<Attached> {
        let vm = VM.load(Ordering::Acquire);
        if vm.is_null() { return None; }
        let name = CString::new(name).ok()?;
        let mut args = JavaVMAttachArgs { version: JNI_VERSION_1_6, name: name.as_ptr() as *mut _, group: null_mut() };
        let mut env: *mut JNIEnv = null_mut();
        let result = unsafe {
            ((**vm).AttachCurrentThreadAsDaemon.unwrap())(vm, &mut env as *mut _ as *mut _, &mut args as *mut _ as *mut _)
        };
        if result != JNI_OK || env.is_null() { None } else { Some(Attached { env: Env(env) }) }
    }
}

impl Drop for Attached {
    fn drop(&mut self) {
        let vm = VM.load(Ordering::Acquire);
        if !vm.is_null() {
            unsafe { ((**vm).DetachCurrentThread.unwrap())(vm) };
        }
    }
}
