use super::{Receiver, WORDS};
use jni_sys::{jbyteArray, jclass, jint, jintArray, jobject, jobjectArray, JNIEnv};
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
    unsafe fn array(&self, n: usize, bytes: bool) -> Option<jobjectArray> {
        let c = ((**self.0).FindClass.unwrap())(
            self.0,
            if bytes { c"[B" } else { c"java/lang/Object" }.as_ptr(),
        );
        self.ok()?;
        let out = ((**self.0).NewObjectArray.unwrap())(self.0, n as i32, c, null_mut());
        self.ok()?;
        ((**self.0).DeleteLocalRef.unwrap())(self.0, c);
        Some(out)
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
    unsafe fn ints(&self, values: &[i32]) -> Option<jintArray> {
        let a = ((**self.0).NewIntArray.unwrap())(self.0, values.len() as i32);
        self.ok()?;
        ((**self.0).SetIntArrayRegion.unwrap())(self.0, a, 0, values.len() as i32, values.as_ptr());
        self.ok()?;
        Some(a)
    }
    unsafe fn read(&self, state: jobjectArray) -> Option<(jintArray, Receiver)> {
        let a = self.item(state, 0)?;
        let mut words = [0; WORDS];
        ((**self.0).GetIntArrayRegion.unwrap())(self.0, a, 0, WORDS as i32, words.as_mut_ptr());
        self.ok()?;
        Some((a, Receiver::decode(&words)))
    }
    unsafe fn save(&self, a: jintArray, receiver: &Receiver) -> Option<()> {
        ((**self.0).SetIntArrayRegion.unwrap())(
            self.0,
            a,
            0,
            WORDS as i32,
            receiver.encode().as_ptr(),
        );
        self.ok()
    }
}
unsafe fn export(env: *mut JNIEnv, body: impl FnOnce(&Env) -> Option<jobject>) -> jobject {
    let e = Env(env);
    ((**env).PushLocalFrame.unwrap())(env, 24);
    if e.ok().is_none() {
        return null_mut();
    }
    let value = body(&e).unwrap_or(null_mut());
    ((**env).PopLocalFrame.unwrap())(env, value)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeIap2FileTransfer_create(
    env: *mut JNIEnv,
    _: jclass,
    maximum: jint,
) -> jobjectArray {
    export(env, |e| {
        let a = e.array(5, false)?;
        let receiver = Receiver {
            maximum: maximum as usize,
            entries: Vec::new(),
        };
        e.set(a, 0, e.ints(&receiver.encode())?)?;
        Some(a)
    })
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeIap2FileTransfer_clear(
    env: *mut JNIEnv,
    _: jclass,
    state: jobjectArray,
) {
    export(env, |e| {
        let (a, mut receiver) = e.read(state)?;
        receiver.entries.clear();
        for slot in 1..=4 {
            e.set(state, slot, null_mut())?;
        }
        e.save(a, &receiver)?;
        Some(null_mut())
    });
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeIap2FileTransfer_accept(
    env: *mut JNIEnv,
    _: jclass,
    state: jobjectArray,
    datagram: jbyteArray,
) -> jobjectArray {
    export(env, |e| {
        let length = ((**env).GetArrayLength.unwrap())(env, datagram);
        e.ok()?;
        let mut header = [0; 12];
        ((**env).GetByteArrayRegion.unwrap())(
            env,
            datagram,
            0,
            length.min(12),
            header.as_mut_ptr() as *mut i8,
        );
        e.ok()?;
        let (words, mut receiver) = e.read(state)?;
        let outcome = receiver.accept(&header, length as usize);
        // Capture a completing buffer before removing its state reference. Only native code can
        // address these slots; the returned bytes are no longer modified by this receiver.
        let completed = match outcome.completed {
            Some((_, Some(slot))) => e.item(state, slot + 1)?,
            _ => null_mut(),
        };
        if let Some((slot, offset, count)) = outcome.append {
            if count != 0 {
                let target = e.item(state, slot + 1)?;
                let mut bytes = vec![0u8; count];
                ((**env).GetByteArrayRegion.unwrap())(
                    env,
                    datagram,
                    2,
                    count as i32,
                    bytes.as_mut_ptr() as *mut i8,
                );
                e.ok()?;
                ((**env).SetByteArrayRegion.unwrap())(
                    env,
                    target,
                    offset as i32,
                    count as i32,
                    bytes.as_ptr() as *const i8,
                );
                e.ok()?;
            }
        }
        for slot in outcome.retired {
            e.set(state, slot + 1, null_mut())?;
        }
        if let Some((slot, size)) = outcome.allocate {
            // Old Setup first removes/retires its prior ID, then allocates the new payload. A JVM
            // allocation failure must not resurrect that old pending transfer.
            let entry = receiver.entries.pop().unwrap();
            e.save(words, &receiver)?;
            let bytes = ((**env).NewByteArray.unwrap())(env, size as i32);
            e.ok()?;
            e.set(state, slot + 1, bytes)?;
            receiver.entries.push(entry);
        }
        e.save(words, &receiver)?;
        let replies = e.array(outcome.replies.len(), true)?;
        for (i, reply) in outcome.replies.iter().enumerate() {
            let bytes = e.bytes(reply)?;
            e.set(replies, i, bytes)?;
            ((**env).DeleteLocalRef.unwrap())(env, bytes);
        }
        let out = e.array(3, false)?;
        e.set(out, 0, replies)?;
        e.set(
            out,
            1,
            e.ints(&[outcome.completed.map(|(id, _)| id as i32).unwrap_or(-1)])?,
        )?;
        if let Some((_, slot)) = outcome.completed {
            e.set(
                out,
                2,
                if slot.is_some() {
                    completed
                } else {
                    e.bytes(&[])?
                },
            )?;
        }
        Some(out)
    })
}
