use jni_sys::{jbyteArray, jclass, jint, jlong, jmethodID, jobject, jvalue, JNIEnv};
use std::ffi::CStr;
use std::ptr::null_mut;
struct Env(*mut JNIEnv);
impl Env {
    unsafe fn method(&self, class: jclass, name: &CStr, signature: &CStr) -> Option<jmethodID> {
        let method = ((**self.0).GetMethodID.unwrap())(self.0, class, name.as_ptr(), signature.as_ptr());
        self.ok()?;
        (!method.is_null()).then_some(method)
    }
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

/// Writes a native access unit and its ADTS header directly into the SDK input buffer.
/// Kotlin owns its position/limit and the packet lease; no packet bytes are changed here.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAac_writeDecoderInputNative(
    env: *mut JNIEnv, _: jclass, packet: jlong, offset: jint, length: jint,
    target: jobject, position: jint, rate: jint, channels: jint,
) -> jint {
    if packet == 0 || offset < 0 || length < 0 || position < 0 { return -1; }
    let source = crate::media::packet::bytes(packet);
    let (offset, length, position) = (offset as usize, length as usize, position as usize);
    let Some(payload) = source.get(offset..offset + length) else { return -1 };
    let total = super::HEADER_BYTES as usize + length;
    let e = Env(env);
    let f = &**env;
    let write = || -> Option<jint> {
        let class = (f.GetObjectClass.unwrap())(env, target); e.ok()?;
        let readonly = e.method(class, c"isReadOnly", c"()Z")?;
        if (f.CallBooleanMethodA.unwrap())(env, target, readonly, std::ptr::null()) != 0 { return None; }
        e.ok()?;
        if let Some((address, capacity)) = crate::platform::jvm::Env(env).direct_buffer(target) {
            if position > capacity || total > capacity - position { return None; }
            let out = std::slice::from_raw_parts_mut(address.add(position), total);
            out[..super::HEADER_BYTES as usize].copy_from_slice(&super::header(length as i32, rate, channels));
            out[super::HEADER_BYTES as usize..].copy_from_slice(payload);
        } else {
            // Heap SDK buffers use their backing array; assembly remains in this native owner.
            let capacity = e.method(class, c"capacity", c"()I")?;
            let capacity = (f.CallIntMethodA.unwrap())(env, target, capacity, std::ptr::null()) as usize; e.ok()?;
            if position > capacity || total > capacity - position { return None; }
            let array = e.method(class, c"array", c"()[B")?;
            let array = (f.CallObjectMethodA.unwrap())(env, target, array, std::ptr::null()); e.ok()?;
            let base = e.method(class, c"arrayOffset", c"()I")?;
            let base = (f.CallIntMethodA.unwrap())(env, target, base, std::ptr::null()); e.ok()?;
            (f.SetByteArrayRegion.unwrap())(env, array, base + position as i32, 7, super::header(length as i32, rate, channels).as_ptr().cast()); e.ok()?;
            (f.SetByteArrayRegion.unwrap())(env, array, base + position as i32 + 7, length as i32, payload.as_ptr().cast()); e.ok()?;
        }
        Some(total as jint)
    };
    write().unwrap_or(-1)
}

/// The same input pump used by video, with the established audio 10 ms dequeue / 100 ms budget.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAac_feedDecoderNative(
    env: *mut JNIEnv, _: jclass, packet: jlong, offset: jint, length: jint, rate: jint, channels: jint,
    time_us: jlong, codec: jobject, host: jobject,
) {
    let e = Env(env);
    let f = &**env;
    if (f.PushLocalFrame.unwrap())(env, 24) < 0 { return; }
    let feed = || -> Option<()> {
        let host_class = (f.GetObjectClass.unwrap())(env, host); e.ok()?;
        let running = e.method(host_class, c"aacInputRunning", c"()Z")?;
        let drain = e.method(host_class, c"drainCodec", c"(Landroid/media/MediaCodec;)V")?;
        let result = e.method(host_class, c"decoderInputResult", c"(II)V")?;
        let codec_class = (f.GetObjectClass.unwrap())(env, codec); e.ok()?;
        let dequeue = e.method(codec_class, c"dequeueInputBuffer", c"(J)I")?;
        let input = e.method(codec_class, c"getInputBuffer", c"(I)Ljava/nio/ByteBuffer;")?;
        let queue = e.method(codec_class, c"queueInputBuffer", c"(IIIJI)V")?;
        let system = (f.FindClass.unwrap())(env, c"java/lang/System".as_ptr()); e.ok()?;
        let nano_time = (f.GetStaticMethodID.unwrap())(env, system, c"nanoTime".as_ptr(), c"()J".as_ptr()); e.ok()?;
        let start = (f.CallStaticLongMethodA.unwrap())(env, system, nano_time, std::ptr::null()); e.ok()?;
        let mut state = [0, -1, start, 100_000_000];
        let codec_arg = [jvalue { l: codec }];
        let dequeue_arg = [jvalue { j: 10_000 }];
        loop {
            let (event, value) = match state[0] {
                0 => (1, (f.CallBooleanMethodA.unwrap())(env, host, running, std::ptr::null()) as i64),
                1 => { (f.CallVoidMethodA.unwrap())(env, host, drain, codec_arg.as_ptr()); (2, 0) }
                2 => (3, (f.CallIntMethodA.unwrap())(env, codec, dequeue, dequeue_arg.as_ptr()) as i64),
                3 => (4, (f.CallStaticLongMethodA.unwrap())(env, system, nano_time, std::ptr::null())),
                _ => break,
            };
            e.ok()?;
            crate::media::video::flow::input_step(&mut state, event, value);
        }
        let total = length.checked_add(7)?;
        let index = state[1] as i32;
        if index < 0 {
            (f.CallVoidMethodA.unwrap())(env, host, result, [jvalue { i: 1 }, jvalue { i: total }].as_ptr());
            return e.ok();
        }
        let buffer = (f.CallObjectMethodA.unwrap())(env, codec, input, [jvalue { i: index }].as_ptr()); e.ok()?;
        if buffer.is_null() { return Some(()); }
        let buffer_class = (f.GetObjectClass.unwrap())(env, buffer); e.ok()?;
        let clear = e.method(buffer_class, c"clear", c"()Ljava/nio/Buffer;")?;
        (f.CallObjectMethodA.unwrap())(env, buffer, clear, std::ptr::null()); e.ok()?;
        let capacity = e.method(buffer_class, c"capacity", c"()I")?;
        let capacity = (f.CallIntMethodA.unwrap())(env, buffer, capacity, std::ptr::null()); e.ok()?;
        let fits = total <= capacity;
        if fits {
            let written = Java_com_shihab_diplay_media_NativeAac_writeDecoderInputNative(env, null_mut(), packet, offset, length, buffer, 0, rate, channels);
            e.ok()?;
            if written != total {
                let error = (f.FindClass.unwrap())(env, c"java/lang/IllegalStateException".as_ptr()); e.ok()?;
                (f.ThrowNew.unwrap())(env, error, c"AAC decoder input write failed".as_ptr());
                return None;
            }
            let position = e.method(buffer_class, c"position", c"(I)Ljava/nio/Buffer;")?;
            (f.CallObjectMethodA.unwrap())(env, buffer, position, [jvalue { i: total }].as_ptr()); e.ok()?;
        }
        (f.CallVoidMethodA.unwrap())(env, codec, queue, [
            jvalue { i: index }, jvalue { i: 0 }, jvalue { i: if fits { total } else { 0 } },
            jvalue { j: if fits { time_us } else { 0 } }, jvalue { i: 0 },
        ].as_ptr()); e.ok()?;
        (f.CallVoidMethodA.unwrap())(env, host, result, [jvalue { i: if fits { 2 } else { 3 } }, jvalue { i: total }].as_ptr()); e.ok()?;
        (f.CallVoidMethodA.unwrap())(env, host, drain, codec_arg.as_ptr());
        e.ok()
    };
    feed();
    (f.PopLocalFrame.unwrap())(env, null_mut());
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAac_frequencyIndex(
    _env: *mut JNIEnv,
    _: jclass,
    rate: jint,
) -> jint {
    super::frequency_index(rate) as i32
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAac_writeAdtsHeader(
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
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAac_audioSpecificConfig(
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
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAac_accessUnit(
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
