//! Synchronous MediaCodec and PCM boundary; no mutable renderer borrow survives a host callback.
use super::*;
use crate::media::packet;
use jni_sys::*;
use std::ffi::CStr;
use std::ptr::{null, null_mut};

unsafe fn renderer<'a>(handle: jlong) -> &'a mut Renderer {
    &mut *(handle as *mut Renderer)
}
fn direct(env: *mut JNIEnv, buffer: jobject) -> Option<&'static mut [u8]> {
    crate::platform::jvm::Env(env)
        .direct_buffer(buffer)
        .map(|(address, capacity)| unsafe { std::slice::from_raw_parts_mut(address, capacity) })
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_pcmCreate(
    _: *mut JNIEnv,
    _: jclass,
    rate: jint,
    channels: jint,
    voice_filter: jboolean,
    codec: jint,
) -> jlong {
    Renderer::new(rate, channels, voice_filter != 0, codec)
        .map(|s| Box::into_raw(Box::new(s)) as jlong)
        .unwrap_or(0)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_pcmDestroy(
    _: *mut JNIEnv,
    _: jclass,
    handle: jlong,
) {
    if handle != 0 {
        drop(Box::from_raw(handle as *mut Renderer));
    }
}
/// Runs the renderer's PCM steps in place on the first `length` bytes of `buffer`, in Kotlin's order:
/// call high-pass, sound detection, attenuation, fade-in. Returns 1 when [DETECT_AUDIBLE] found sound,
/// 0 when not, -1 on bad arguments.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_pcmProcess(
    env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
    buffer: jobject,
    length: jint,
    flags: jint,
    attenuation_db: jint,
    peak: jint,
) -> jint {
    if handle == 0 || length < 0 {
        return -1;
    }
    let state = renderer(handle);
    let Some(buffer) = direct(env, buffer) else {
        return -1;
    };
    if length as usize > buffer.len() {
        return -1;
    }
    let data = &mut buffer[..length as usize];
    if let Some(filter) = state.filter.as_mut() {
        filter.process(data);
    }
    let audible = flags & DETECT_AUDIBLE != 0 && pcm::is_audible(data, peak);
    if flags & ATTENUATE != 0 {
        pcm::attenuate(data, attenuation_db);
    }
    if flags & FADE_IN != 0 {
        pcm::fade_in(data, state.sample_rate);
    }
    audible as jint
}

struct SdkEnv(*mut JNIEnv);
impl SdkEnv {
    unsafe fn ok(&self) -> Option<()> {
        (((**self.0).ExceptionCheck.unwrap())(self.0) == 0).then_some(())
    }
    unsafe fn method(&self, class: jclass, name: &CStr, signature: &CStr) -> Option<jmethodID> {
        let method =
            ((**self.0).GetMethodID.unwrap())(self.0, class, name.as_ptr(), signature.as_ptr());
        self.ok()?;
        (!method.is_null()).then_some(method)
    }
    unsafe fn bytes(&self, bytes: &[u8]) -> Option<jbyteArray> {
        let f = &**self.0;
        let array = (f.NewByteArray.unwrap())(self.0, bytes.len() as i32);
        if array.is_null() {
            return None;
        }
        (f.SetByteArrayRegion.unwrap())(
            self.0,
            array,
            0,
            bytes.len() as i32,
            bytes.as_ptr().cast(),
        );
        self.ok()?;
        Some(array)
    }
}
/// Writes a native access unit and its ADTS header directly into the SDK input buffer.
/// Kotlin owns its position/limit and the packet lease; no packet bytes are changed here.
unsafe fn write_input(
    env: *mut JNIEnv,
    adts: bool,
    packet: jlong,
    offset: jint,
    length: jint,
    target: jobject,
    position: jint,
    rate: jint,
    channels: jint,
) -> jint {
    if packet == 0 || offset < 0 || length < 0 || position < 0 {
        return -1;
    }
    let source = crate::media::packet::bytes(packet);
    let (offset, length, position) = (offset as usize, length as usize, position as usize);
    let Some(payload) = source.get(offset..offset + length) else {
        return -1;
    };
    let header_bytes = if adts { 7 } else { 0 };
    let total = header_bytes + length;
    let e = SdkEnv(env);
    let f = &**env;
    let write = || -> Option<jint> {
        let class = (f.GetObjectClass.unwrap())(env, target);
        e.ok()?;
        let readonly = e.method(class, c"isReadOnly", c"()Z")?;
        if (f.CallBooleanMethodA.unwrap())(env, target, readonly, std::ptr::null()) != 0 {
            return None;
        }
        e.ok()?;
        if let Some((address, capacity)) = crate::platform::jvm::Env(env).direct_buffer(target) {
            if position > capacity || total > capacity - position {
                return None;
            }
            let out = std::slice::from_raw_parts_mut(address.add(position), total);
            if adts {
                out[..7].copy_from_slice(&crate::media::audio::aac::header(
                    length as i32,
                    rate,
                    channels,
                ));
            }
            out[header_bytes..].copy_from_slice(payload);
        } else {
            // Heap SDK buffers use their backing array; assembly remains in this native owner.
            let capacity = e.method(class, c"capacity", c"()I")?;
            let capacity =
                (f.CallIntMethodA.unwrap())(env, target, capacity, std::ptr::null()) as usize;
            e.ok()?;
            if position > capacity || total > capacity - position {
                return None;
            }
            let array = e.method(class, c"array", c"()[B")?;
            let array = (f.CallObjectMethodA.unwrap())(env, target, array, std::ptr::null());
            e.ok()?;
            let base = e.method(class, c"arrayOffset", c"()I")?;
            let base = (f.CallIntMethodA.unwrap())(env, target, base, std::ptr::null());
            e.ok()?;
            if adts {
                (f.SetByteArrayRegion.unwrap())(
                    env,
                    array,
                    base + position as i32,
                    7,
                    crate::media::audio::aac::header(length as i32, rate, channels)
                        .as_ptr()
                        .cast(),
                );
                e.ok()?;
            }
            (f.SetByteArrayRegion.unwrap())(
                env,
                array,
                base + position as i32 + header_bytes as i32,
                length as i32,
                payload.as_ptr().cast(),
            );
            e.ok()?;
        }
        Some(total as jint)
    };
    write().unwrap_or(-1)
}

/// The same input pump used by video, with the established audio 10 ms dequeue / 100 ms budget.
unsafe fn feed_decoder(
    env: *mut JNIEnv,
    adts: bool,
    packet: jlong,
    offset: jint,
    length: jint,
    rate: jint,
    channels: jint,
    time_us: jlong,
    codec: jobject,
    host: jobject,
) {
    let e = SdkEnv(env);
    let f = &**env;
    if (f.PushLocalFrame.unwrap())(env, 24) < 0 {
        return;
    }
    let feed = || -> Option<()> {
        let host_class = (f.GetObjectClass.unwrap())(env, host);
        e.ok()?;
        let running = e.method(host_class, c"aacInputRunning", c"()Z")?;
        let drain = e.method(host_class, c"drainCodec", c"(Landroid/media/MediaCodec;)V")?;
        let result = e.method(host_class, c"decoderInputResult", c"(II)V")?;
        let codec_class = (f.GetObjectClass.unwrap())(env, codec);
        e.ok()?;
        let dequeue = e.method(codec_class, c"dequeueInputBuffer", c"(J)I")?;
        let input = e.method(codec_class, c"getInputBuffer", c"(I)Ljava/nio/ByteBuffer;")?;
        let queue = e.method(codec_class, c"queueInputBuffer", c"(IIIJI)V")?;
        let system = (f.FindClass.unwrap())(env, c"java/lang/System".as_ptr());
        e.ok()?;
        let nano_time =
            (f.GetStaticMethodID.unwrap())(env, system, c"nanoTime".as_ptr(), c"()J".as_ptr());
        e.ok()?;
        let start = (f.CallStaticLongMethodA.unwrap())(env, system, nano_time, std::ptr::null());
        e.ok()?;
        let mut state = [0, -1, start, 100_000_000];
        let codec_arg = [jvalue { l: codec }];
        let dequeue_arg = [jvalue { j: 10_000 }];
        loop {
            let (event, value) = match state[0] {
                0 => (
                    1,
                    (f.CallBooleanMethodA.unwrap())(env, host, running, std::ptr::null()) as i64,
                ),
                1 => {
                    (f.CallVoidMethodA.unwrap())(env, host, drain, codec_arg.as_ptr());
                    (2, 0)
                }
                2 => (
                    3,
                    (f.CallIntMethodA.unwrap())(env, codec, dequeue, dequeue_arg.as_ptr()) as i64,
                ),
                3 => (
                    4,
                    (f.CallStaticLongMethodA.unwrap())(env, system, nano_time, std::ptr::null()),
                ),
                _ => break,
            };
            e.ok()?;
            crate::media::video::flow::input_step(&mut state, event, value);
        }
        let total = length.checked_add(if adts { 7 } else { 0 })?;
        let index = state[1] as i32;
        if index < 0 {
            (f.CallVoidMethodA.unwrap())(
                env,
                host,
                result,
                [jvalue { i: 1 }, jvalue { i: total }].as_ptr(),
            );
            return e.ok();
        }
        let buffer =
            (f.CallObjectMethodA.unwrap())(env, codec, input, [jvalue { i: index }].as_ptr());
        e.ok()?;
        if buffer.is_null() {
            return Some(());
        }
        let buffer_class = (f.GetObjectClass.unwrap())(env, buffer);
        e.ok()?;
        let clear = e.method(buffer_class, c"clear", c"()Ljava/nio/Buffer;")?;
        (f.CallObjectMethodA.unwrap())(env, buffer, clear, std::ptr::null());
        e.ok()?;
        let capacity = e.method(buffer_class, c"capacity", c"()I")?;
        let capacity = (f.CallIntMethodA.unwrap())(env, buffer, capacity, std::ptr::null());
        e.ok()?;
        let fits = total <= capacity;
        if fits {
            let written = write_input(env, adts, packet, offset, length, buffer, 0, rate, channels);
            e.ok()?;
            if written != total {
                let error =
                    (f.FindClass.unwrap())(env, c"java/lang/IllegalStateException".as_ptr());
                e.ok()?;
                (f.ThrowNew.unwrap())(env, error, c"AAC decoder input write failed".as_ptr());
                return None;
            }
            let position = e.method(buffer_class, c"position", c"(I)Ljava/nio/Buffer;")?;
            (f.CallObjectMethodA.unwrap())(env, buffer, position, [jvalue { i: total }].as_ptr());
            e.ok()?;
        }
        (f.CallVoidMethodA.unwrap())(
            env,
            codec,
            queue,
            [
                jvalue { i: index },
                jvalue { i: 0 },
                jvalue {
                    i: if fits { total } else { 0 },
                },
                jvalue {
                    j: if fits { time_us } else { 0 },
                },
                jvalue { i: 0 },
            ]
            .as_ptr(),
        );
        e.ok()?;
        (f.CallVoidMethodA.unwrap())(
            env,
            host,
            result,
            [
                jvalue {
                    i: if fits { 2 } else { 3 },
                },
                jvalue { i: total },
            ]
            .as_ptr(),
        );
        e.ok()?;
        (f.CallVoidMethodA.unwrap())(env, host, drain, codec_arg.as_ptr());
        e.ok()
    };
    feed();
    (f.PopLocalFrame.unwrap())(env, null_mut());
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAac_writeDecoderInputNative(
    env: *mut JNIEnv,
    _: jclass,
    packet: jlong,
    offset: jint,
    length: jint,
    target: jobject,
    position: jint,
    rate: jint,
    channels: jint,
) -> jint {
    write_input(
        env, true, packet, offset, length, target, position, rate, channels,
    )
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAac_feedDecoderNative(
    env: *mut JNIEnv,
    _: jclass,
    packet: jlong,
    offset: jint,
    length: jint,
    rate: jint,
    channels: jint,
    time_us: jlong,
    codec: jobject,
    host: jobject,
) {
    feed_decoder(
        env, true, packet, offset, length, rate, channels, time_us, codec, host,
    )
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_pcmConfiguration(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
) -> jobjectArray {
    let Some((mime, adts, csd)) = renderer(handle).configuration() else {
        return null_mut();
    };
    let e = SdkEnv(env);
    let f = &**env;
    if (f.PushLocalFrame.unwrap())(env, 16) < 0 {
        return null_mut();
    }
    let build = || -> Option<jobjectArray> {
        let object = (f.FindClass.unwrap())(env, c"java/lang/Object".as_ptr());
        e.ok()?;
        let result = (f.NewObjectArray.unwrap())(env, 3, object, null_mut());
        e.ok()?;
        let mime: Vec<u16> = mime.encode_utf16().collect();
        let mime = (f.NewString.unwrap())(env, mime.as_ptr(), mime.len() as i32);
        e.ok()?;
        (f.SetObjectArrayElement.unwrap())(env, result, 0, mime);
        e.ok()?;
        let boolean = (f.FindClass.unwrap())(env, c"java/lang/Boolean".as_ptr());
        e.ok()?;
        let value_of = (f.GetStaticMethodID.unwrap())(
            env,
            boolean,
            c"valueOf".as_ptr(),
            c"(Z)Ljava/lang/Boolean;".as_ptr(),
        );
        e.ok()?;
        let flag = (f.CallStaticObjectMethodA.unwrap())(
            env,
            boolean,
            value_of,
            [jvalue { z: adts as u8 }].as_ptr(),
        );
        e.ok()?;
        (f.SetObjectArrayElement.unwrap())(env, result, 1, flag);
        e.ok()?;
        let bytes = (f.FindClass.unwrap())(env, c"[B".as_ptr());
        e.ok()?;
        let configs = (f.NewObjectArray.unwrap())(env, csd.len() as i32, bytes, null_mut());
        e.ok()?;
        for (i, bytes) in csd.iter().enumerate() {
            let bytes = e.bytes(bytes)?;
            (f.SetObjectArrayElement.unwrap())(env, configs, i as i32, bytes);
            e.ok()?;
            (f.DeleteLocalRef.unwrap())(env, bytes);
        }
        (f.SetObjectArrayElement.unwrap())(env, result, 2, configs);
        e.ok()?;
        Some(result)
    };
    let result = build().unwrap_or(null_mut());
    (f.PopLocalFrame.unwrap())(env, result)
}

// The host may call pcmProcess on this view. End the owner borrow before crossing back to Java.
unsafe fn emit_pcm(
    env: *mut JNIEnv,
    handle: jlong,
    host: jobject,
    write: jmethodID,
    length: i32,
) -> Option<()> {
    let address = renderer(handle).pcm.as_mut_ptr();
    let f = &**env;
    let buffer = (f.NewDirectByteBuffer.unwrap())(env, address.cast(), length as i64);
    if buffer.is_null() {
        return None;
    }
    (f.CallVoidMethodA.unwrap())(
        env,
        host,
        write,
        [jvalue { l: buffer }, jvalue { i: length }].as_ptr(),
    );
    SdkEnv(env).ok()
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_pcmFeed(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    packet: jlong,
    size: jint,
    sample: jint,
    codec: jobject,
    host: jobject,
) {
    let e = SdkEnv(env);
    let f = &**env;
    if (f.PushLocalFrame.unwrap())(env, 16) < 0 {
        return;
    }
    let feed = || -> Option<()> {
        let (backend, rate, channels) = {
            let s = renderer(handle);
            (s.backend, s.sample_rate, s.wire_channels)
        };
        let class = (f.GetObjectClass.unwrap())(env, host);
        e.ok()?;
        let result = e.method(class, c"pcmInputResult", c"(III)V")?;
        let write = e.method(class, c"writePcm", c"(Ljava/nio/ByteBuffer;I)V")?;
        let length = size.saturating_sub(12).max(0);
        let event = |event, value| -> Option<()> {
            (f.CallVoidMethodA.unwrap())(
                env,
                host,
                result,
                [
                    jvalue { i: event },
                    jvalue { i: length },
                    jvalue { i: value },
                ]
                .as_ptr(),
            );
            e.ok()
        };
        if matches!(backend, Backend::Opus | Backend::SdkOpus) && length < 4 {
            return event(3, 0);
        }
        if backend == Backend::Aac {
            if length == 0 {
                return Some(());
            }
            event(0, 0)?;
        }
        if matches!(backend, Backend::Aac | Backend::SdkOpus) {
            if codec.is_null() {
                return event(1, 0);
            }
            event(2, 0)?;
            let timestamp = (sample as u32 as i64) * 1_000_000 / rate as i64;
            feed_decoder(
                env,
                backend == Backend::Aac,
                packet,
                12,
                length,
                rate,
                channels,
                timestamp,
                codec,
                host,
            );
            return e.ok();
        }
        if backend == Backend::Opus {
            event(2, 0)?;
        }
        let Some(payload) = packet::bytes(packet).get(12..12 + length as usize) else {
            return Some(());
        };
        let bytes = renderer(handle).decode(payload);
        if backend == Backend::Opus {
            event(4, bytes)?;
        }
        if bytes > 0 {
            emit_pcm(env, handle, host, write, bytes)?;
        }
        Some(())
    };
    feed();
    (f.PopLocalFrame.unwrap())(env, null_mut());
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_pcmDrain(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    codec: jobject,
    info: jobject,
    host: jobject,
) {
    let e = SdkEnv(env);
    let f = &**env;
    if (f.PushLocalFrame.unwrap())(env, 24) < 0 {
        return;
    }
    let drain = || -> Option<()> {
        let class = (f.GetObjectClass.unwrap())(env, host);
        e.ok()?;
        let running = e.method(class, c"decoderOutputRunning", c"()Z")?;
        let format = e.method(
            class,
            c"decoderOutputFormat",
            c"(Landroid/media/MediaCodec;)V",
        )?;
        let output_size = e.method(class, c"decoderOutputSize", c"(I)V")?;
        let write = e.method(class, c"writePcm", c"(Ljava/nio/ByteBuffer;I)V")?;
        let class = (f.GetObjectClass.unwrap())(env, codec);
        e.ok()?;
        let dequeue = e.method(
            class,
            c"dequeueOutputBuffer",
            c"(Landroid/media/MediaCodec$BufferInfo;J)I",
        )?;
        let output = e.method(class, c"getOutputBuffer", c"(I)Ljava/nio/ByteBuffer;")?;
        let release = e.method(class, c"releaseOutputBuffer", c"(IZ)V")?;
        let class = (f.GetObjectClass.unwrap())(env, info);
        e.ok()?;
        let size_field = (f.GetFieldID.unwrap())(env, class, c"size".as_ptr(), c"I".as_ptr());
        e.ok()?;
        let offset_field = (f.GetFieldID.unwrap())(env, class, c"offset".as_ptr(), c"I".as_ptr());
        e.ok()?;
        let flags_field = (f.GetFieldID.unwrap())(env, class, c"flags".as_ptr(), c"I".as_ptr());
        e.ok()?;
        loop {
            let active = (f.CallBooleanMethodA.unwrap())(env, host, running, null());
            e.ok()?;
            if active == 0 {
                return Some(());
            }
            if (f.PushLocalFrame.unwrap())(env, 16) < 0 {
                return None;
            }
            let step = || -> Option<bool> {
                let index = (f.CallIntMethodA.unwrap())(
                    env,
                    codec,
                    dequeue,
                    [jvalue { l: info }, jvalue { j: 0 }].as_ptr(),
                );
                e.ok()?;
                if index == -2 {
                    (f.CallVoidMethodA.unwrap())(env, host, format, [jvalue { l: codec }].as_ptr());
                    e.ok()?;
                    return Some(true);
                }
                if index < 0 {
                    return Some(false);
                }
                let size = (f.GetIntField.unwrap())(env, info, size_field);
                if size > 0 {
                    (f.CallVoidMethodA.unwrap())(
                        env,
                        host,
                        output_size,
                        [jvalue { i: size }].as_ptr(),
                    );
                    e.ok()?;
                }
                let copy = || -> Option<bool> {
                    if size <= 0 {
                        return Some(false);
                    }
                    let buffer = (f.CallObjectMethodA.unwrap())(
                        env,
                        codec,
                        output,
                        [jvalue { i: index }].as_ptr(),
                    );
                    e.ok()?;
                    if buffer.is_null() {
                        return Some(false);
                    }
                    let class = (f.GetObjectClass.unwrap())(env, buffer);
                    e.ok()?;
                    let position = e.method(class, c"position", c"(I)Ljava/nio/Buffer;")?;
                    let limit = e.method(class, c"limit", c"(I)Ljava/nio/Buffer;")?;
                    let offset = (f.GetIntField.unwrap())(env, info, offset_field);
                    (f.CallObjectMethodA.unwrap())(
                        env,
                        buffer,
                        position,
                        [jvalue { i: offset }].as_ptr(),
                    );
                    e.ok()?;
                    (f.CallObjectMethodA.unwrap())(
                        env,
                        buffer,
                        limit,
                        [jvalue {
                            i: offset.wrapping_add(size),
                        }]
                        .as_ptr(),
                    );
                    e.ok()?;
                    let address = {
                        let state = renderer(handle);
                        state.pcm.resize(size as usize, 0);
                        state.pcm.as_mut_ptr()
                    };
                    if let Some((source, _)) = crate::platform::jvm::Env(env).direct_buffer(buffer)
                    {
                        std::ptr::copy_nonoverlapping(
                            source.add(offset as usize),
                            address,
                            size as usize,
                        );
                        (f.CallObjectMethodA.unwrap())(
                            env,
                            buffer,
                            position,
                            [jvalue { i: offset + size }].as_ptr(),
                        );
                        e.ok()?;
                    } else {
                        // SDK heap/read-only buffers remain readable; the worker still owns the PCM allocation.
                        let bytes = (f.NewByteArray.unwrap())(env, size);
                        e.ok()?;
                        let get = e.method(class, c"get", c"([B)Ljava/nio/ByteBuffer;")?;
                        (f.CallObjectMethodA.unwrap())(
                            env,
                            buffer,
                            get,
                            [jvalue { l: bytes }].as_ptr(),
                        );
                        e.ok()?;
                        (f.GetByteArrayRegion.unwrap())(env, bytes, 0, size, address.cast());
                        e.ok()?;
                    }
                    Some(true)
                };
                let copied = copy();
                // Match Java finally: always release before a track write; a release failure wins.
                let failure = (f.ExceptionOccurred.unwrap())(env);
                if !failure.is_null() {
                    (f.ExceptionClear.unwrap())(env);
                }
                (f.CallVoidMethodA.unwrap())(
                    env,
                    codec,
                    release,
                    [jvalue { i: index }, jvalue { z: 0 }].as_ptr(),
                );
                e.ok()?;
                if !failure.is_null() {
                    (f.Throw.unwrap())(env, failure);
                    return None;
                }
                if copied? {
                    emit_pcm(env, handle, host, write, size)?;
                }
                Some((f.GetIntField.unwrap())(env, info, flags_field) & 4 == 0)
            };
            let next = step();
            (f.PopLocalFrame.unwrap())(env, null_mut());
            if !next? {
                return Some(());
            }
        }
    };
    drain();
    (f.PopLocalFrame.unwrap())(env, null_mut());
}
