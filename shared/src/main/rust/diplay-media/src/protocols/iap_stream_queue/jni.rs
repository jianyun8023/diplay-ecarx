use super::{Offer, Queue, WORDS};
use jni_sys::{jboolean, jbyteArray, jclass, jint, jintArray, jobject, jobjectArray, JNIEnv};
use std::ptr::null_mut;
struct Env(*mut JNIEnv);
impl Env {
    unsafe fn ok(&self) -> Option<()> {
        (((**self.0).ExceptionCheck.unwrap())(self.0) == 0).then_some(())
    }
    unsafe fn item(&self, a: jobjectArray, i: usize) -> Option<jobject> {
        let v = ((**self.0).GetObjectArrayElement.unwrap())(self.0, a, i as i32);
        self.ok()?;
        Some(v)
    }
    unsafe fn set(&self, a: jobjectArray, i: usize, v: jobject) -> Option<()> {
        ((**self.0).SetObjectArrayElement.unwrap())(self.0, a, i as i32, v);
        self.ok()
    }
    unsafe fn array(&self, n: usize) -> Option<jobjectArray> {
        let c = ((**self.0).FindClass.unwrap())(self.0, c"java/lang/Object".as_ptr());
        self.ok()?;
        let a = ((**self.0).NewObjectArray.unwrap())(self.0, n as i32, c, null_mut());
        self.ok()?;
        ((**self.0).DeleteLocalRef.unwrap())(self.0, c);
        Some(a)
    }
    unsafe fn len(&self, a: jobject) -> Option<usize> {
        let n = ((**self.0).GetArrayLength.unwrap())(self.0, a);
        self.ok()?;
        Some(n as usize)
    }
    unsafe fn read(&self, state: jobjectArray) -> Option<(jintArray, jobjectArray, Queue)> {
        let a = self.item(state, 0)?;
        let mut words = [0; WORDS];
        ((**self.0).GetIntArrayRegion.unwrap())(self.0, a, 0, WORDS as i32, words.as_mut_ptr());
        self.ok()?;
        Some((a, self.item(state, 1)?, Queue::decode(words)))
    }
    unsafe fn save(&self, a: jintArray, q: &Queue) -> Option<()> {
        ((**self.0).SetIntArrayRegion.unwrap())(self.0, a, 0, WORDS as i32, q.encode().as_ptr());
        self.ok()
    }
    unsafe fn copy(&self, source: jbyteArray, offset: usize, n: usize) -> Option<jbyteArray> {
        let a = ((**self.0).NewByteArray.unwrap())(self.0, n as i32);
        self.ok()?;
        let mut bytes = vec![0u8; n];
        ((**self.0).GetByteArrayRegion.unwrap())(
            self.0,
            source,
            offset as i32,
            n as i32,
            bytes.as_mut_ptr() as *mut i8,
        );
        self.ok()?;
        ((**self.0).SetByteArrayRegion.unwrap())(
            self.0,
            a,
            0,
            n as i32,
            bytes.as_ptr() as *const i8,
        );
        self.ok()?;
        Some(a)
    }
}
unsafe fn object(env: *mut JNIEnv, body: impl FnOnce(&Env) -> Option<jobject>) -> jobject {
    let e = Env(env);
    ((**env).PushLocalFrame.unwrap())(env, 16);
    if e.ok().is_none() {
        return null_mut();
    }
    let value = body(&e).unwrap_or(null_mut());
    ((**env).PopLocalFrame.unwrap())(env, value)
}
unsafe fn number(env: *mut JNIEnv, body: impl FnOnce(&Env) -> Option<jint>) -> jint {
    let e = Env(env);
    ((**env).PushLocalFrame.unwrap())(env, 16);
    if e.ok().is_none() {
        return 0;
    }
    let value = body(&e).unwrap_or(0);
    ((**env).PopLocalFrame.unwrap())(env, null_mut());
    value
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeIapStreamQueue_create(
    env: *mut JNIEnv,
    _: jclass,
) -> jobjectArray {
    object(env, |e| {
        let state = e.array(2)?;
        let words = ((**env).NewIntArray.unwrap())(env, WORDS as i32);
        e.ok()?;
        e.save(words, &Queue::default())?;
        e.set(state, 0, words)?;
        e.set(state, 1, e.array(8)?)?;
        Some(state)
    })
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeIapStreamQueue_offer(
    env: *mut JNIEnv,
    _: jclass,
    state: jobjectArray,
    bytes: jbyteArray,
) -> jint {
    number(env, |e| {
        let n = e.len(bytes)?;
        let (words, mut slots, mut q) = e.read(state)?;
        let capacity = e.len(slots)?;
        match q.offer(n, capacity) {
            Offer::Ignored => Some(0),
            Offer::Limit => {
                e.save(words, &q)?;
                Some(2)
            }
            Offer::Store { grow, slot } => {
                let copy = e.copy(bytes, 0, n)?;
                if grow {
                    let expanded = e.array(capacity * 2)?;
                    for i in 0..q.count {
                        let v = e.item(slots, (q.head + i) % capacity)?;
                        e.set(expanded, i, v)?;
                        ((**env).DeleteLocalRef.unwrap())(env, v);
                    }
                    e.set(state, 1, expanded)?;
                    slots = expanded;
                }
                e.set(slots, slot, copy)?;
                q.pushed(n, grow);
                e.save(words, &q)?;
                Some(1)
            }
        }
    })
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeIapStreamQueue_take(
    env: *mut JNIEnv,
    _: jclass,
    state: jobjectArray,
    maximum: jint,
) -> jbyteArray {
    object(env, |e| {
        let (words, slots, mut q) = e.read(state)?;
        if q.count == 0 {
            return None;
        }
        let capacity = e.len(slots)?;
        let chunk = e.item(slots, q.head)?;
        let n = e.len(chunk)?;
        e.set(slots, q.head, null_mut())?;
        q.popped(n, capacity);
        e.save(words, &q)?;
        if n <= maximum as usize {
            return Some(chunk);
        }
        // The old stream removes the whole chunk before allocating head/tail. Preserve that
        // partial progress even when a JVM allocation fails; no native handle is left behind.
        let head = e.copy(chunk, 0, maximum as usize)?;
        let tail_size = n - maximum as usize;
        let tail = e.copy(chunk, maximum as usize, tail_size)?;
        q.prepend_tail(tail_size, capacity);
        e.set(slots, q.head, tail)?;
        e.save(words, &q)?;
        Some(head)
    })
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeIapStreamQueue_status(
    env: *mut JNIEnv,
    _: jclass,
    state: jobjectArray,
) -> jint {
    number(env, |e| Some(e.read(state)?.2.flags))
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeIapStreamQueue_fail(
    env: *mut JNIEnv,
    _: jclass,
    state: jobjectArray,
) -> jboolean {
    number(env, |e| {
        let (words, _, mut q) = e.read(state)?;
        let changed = q.fail();
        e.save(words, &q)?;
        Some(changed as i32)
    }) as jboolean
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeIapStreamQueue_close(
    env: *mut JNIEnv,
    _: jclass,
    state: jobjectArray,
) -> jboolean {
    number(env, |e| {
        let (words, _, mut q) = e.read(state)?;
        let changed = q.close();
        e.save(words, &q)?;
        Some(changed as i32)
    }) as jboolean
}
