//! Buffered music: TCP framing/decryption, bounded intake, pacing and the playback state owner.

#[cfg(any(target_os = "android", target_os = "linux"))]
use crate::protocols::crypto::{StreamKey, TAG_LEN};
use crate::platform::jvm::Env;
use crate::media::packet::{self, shared_pool};
use jni_sys::{jboolean, jbyteArray, jclass, jint, jlong, jlongArray, jobject, jstring, jvalue, JNIEnv};
use crate::media::audio::buffered_state::BufferedAudio;
use crate::protocols::bplist::{self, Value};
#[cfg(any(target_os = "android", target_os = "linux"))]
use crate::media::audio::queue::AudioPacket;
use std::sync::{Arc, Mutex, Condvar};

struct Buffered { state: Mutex<BufferedAudio>, changed: Condvar }

// Actions: 0 no-op, 1 set rate, 2 anchor, 3 flush. Timestamp presence is separate from its full i64 value.
fn control_plan(method: &str, rtp: Option<i64>, rate: Option<f64>, flush: Option<i64>) -> [i64; 4] {
    let (action, timestamp, rate) = match method {
        "SETRATE" | "SETRATEANCHORTIME" if rate == Some(0.0) => (1, rtp, 0),
        "SETRATE" | "SETRATEANCHORTIME" if rate == Some(1.0) => (1, rtp, 1),
        "GETANCHOR" => (2, None, 0),
        "FLUSHBUFFERED" if flush.is_some() => (3, flush, 0),
        _ => (0, None, 0),
    };
    [action, timestamp.is_some() as i64, timestamp.unwrap_or(0), rate]
}

#[cfg(test)]
mod control_plan_tests {
    use super::*;

    #[test]
    fn preserves_full_timestamp_and_rate_decisions() {
        for value in [i64::MIN, -1, 0, i64::MAX] {
            assert_eq!(control_plan("SETRATE", Some(value), Some(1.0), None), [1, 1, value, 1]);
            assert_eq!(control_plan("FLUSHBUFFERED", None, None, Some(value)), [3, 1, value, 0]);
        }
        assert_eq!(control_plan("SETRATE", None, Some(-0.0), None), [1, 0, 0, 0]);
        assert_eq!(control_plan("SETRATE", None, Some(f64::NAN), None), [0, 0, 0, 0]);
        assert_eq!(control_plan("GETANCHOR", None, None, None), [2, 0, 0, 0]);
        assert_eq!(control_plan("FLUSHBUFFERED", None, None, None), [0, 0, 0, 0]);
    }
}

unsafe fn control_field(env: *mut JNIEnv, map: jobject, name: &std::ffi::CStr) -> Option<jobject> {
    let f = &**env;
    let class = (f.GetObjectClass.unwrap())(env, map);
    if class.is_null() { return None; }
    let method = (f.GetMethodID.unwrap())(env, class, c"get".as_ptr(), c"(Ljava/lang/Object;)Ljava/lang/Object;".as_ptr());
    if method.is_null() { return None; }
    let key = (f.NewStringUTF.unwrap())(env, name.as_ptr());
    if key.is_null() { return None; }
    let value = (f.CallObjectMethodA.unwrap())(env, map, method, [jvalue { l: key }].as_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 { None } else { Some(value) }
}

unsafe fn control_number_long(env: *mut JNIEnv, value: jobject) -> Option<Option<i64>> {
    let f = &**env;
    let number = (f.FindClass.unwrap())(env, c"java/lang/Number".as_ptr());
    if number.is_null() { return None; }
    if value.is_null() || (f.IsInstanceOf.unwrap())(env, value, number) == 0 { return Some(None); }
    let method = (f.GetMethodID.unwrap())(env, number, c"longValue".as_ptr(), c"()J".as_ptr());
    if method.is_null() { return None; }
    let result = (f.CallLongMethodA.unwrap())(env, value, method, std::ptr::null());
    if (f.ExceptionCheck.unwrap())(env) != 0 { None } else { Some(Some(result)) }
}

unsafe fn control_number_double(env: *mut JNIEnv, value: jobject) -> Option<Option<f64>> {
    let f = &**env;
    let number = (f.FindClass.unwrap())(env, c"java/lang/Number".as_ptr());
    if number.is_null() { return None; }
    if value.is_null() || (f.IsInstanceOf.unwrap())(env, value, number) == 0 { return Some(None); }
    let method = (f.GetMethodID.unwrap())(env, number, c"doubleValue".as_ptr(), c"()D".as_ptr());
    if method.is_null() { return None; }
    let result = (f.CallDoubleMethodA.unwrap())(env, value, method, std::ptr::null());
    if (f.ExceptionCheck.unwrap())(env) != 0 { None } else { Some(Some(result)) }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_bufferedPlan(
    env: *mut JNIEnv, _class: jclass, method: jstring, body: jobject,
) -> jlongArray {
    let f = &**env;
    if (f.PushLocalFrame.unwrap())(env, 24) < 0 { return std::ptr::null_mut(); }
    let result = (|| -> Option<jlongArray> {
        let rtp = control_number_long(env, control_field(env, body, c"rtpTime")?)?;
        let rate = control_number_double(env, control_field(env, body, c"rate")?)?;
        let size = (f.GetStringLength.unwrap())(env, method);
        let mut units = vec![0u16; size as usize];
        (f.GetStringRegion.unwrap())(env, method, 0, size, units.as_mut_ptr());
        if (f.ExceptionCheck.unwrap())(env) != 0 { return None; }
        let method = String::from_utf16_lossy(&units);
        let flush = if method == "FLUSHBUFFERED" {
            control_number_long(env, control_field(env, body, c"flushUntilTS")?)?
        } else { None };
        let words = control_plan(&method, rtp, rate, flush);
        let out = (f.NewLongArray.unwrap())(env, words.len() as jint);
        if out.is_null() { return None; }
        (f.SetLongArrayRegion.unwrap())(env, out, 0, words.len() as jint, words.as_ptr());
        if (f.ExceptionCheck.unwrap())(env) != 0 { None } else { Some(out) }
    })();
    (f.PopLocalFrame.unwrap())(env, result.unwrap_or(std::ptr::null_mut())) as jlongArray
}

#[cfg(any(target_os = "android", target_os = "linux"))]
fn open_packet(key: &StreamKey, packet: &mut packet::Packet) -> Option<usize> {
    let length = packet.len();
    if length < RTP_HEADER_LEN + TAG_LEN + NONCE_LEN { return None; }
    let wire = packet.bytes_mut();
    if wire[0] != 0x80 { return None; }
    let mut nonce = [0u8; 12];
    nonce[4..].copy_from_slice(&wire[length - NONCE_LEN..]);
    let mut aad = [0u8; 8];
    aad.copy_from_slice(&wire[4..RTP_HEADER_LEN]);
    key.open(nonce, &aad, &mut wire[RTP_HEADER_LEN..length - NONCE_LEN])
}

#[cfg(any(target_os = "android", target_os = "linux"))]
const RTP_HEADER_LEN: usize = 12;
#[cfg(any(target_os = "android", target_os = "linux"))]
const NONCE_LEN: usize = 8;

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_bufferedCreate(
    _env: *mut JNIEnv, _class: jclass, sample_rate: jint,
) -> jlong {
    if sample_rate <= 0 { return 0; }
    Box::into_raw(Box::new(Arc::new(Buffered {
        state: Mutex::new(BufferedAudio::new(sample_rate as i64)), changed: Condvar::new(),
    }))) as jlong
}

unsafe fn owner<'a>(handle: jlong) -> &'a Arc<Buffered> { &*(handle as *const Arc<Buffered>) }

unsafe fn write_longs(env: *mut JNIEnv, out: jni_sys::jlongArray, values: &[i64]) -> bool {
    ((**env).SetLongArrayRegion.unwrap())(env, out, 0, values.len() as i32, values.as_ptr());
    !Env(env).clear_exception()
}

/// The step's separately retained packet remains valid across a reentrant platform callback.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_bufferedNext(
    env: *mut JNIEnv, _class: jclass, handle: jlong, now_ns: jlong, out: jni_sys::jlongArray,
) -> jlong {
    if handle == 0 || Env(env).array_len(out) < 3 { return 0; }
    let owner = owner(handle);
    let step = owner.state.lock().unwrap().next(now_ns);
    owner.changed.notify_all();
    let Some((packet, sample, start, generation)) = step else { return 0 };
    if !write_longs(env, out, &[sample as i64, start as i64, generation]) { packet::release(packet); return 0; }
    packet
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_bufferedCommit(
    _env: *mut JNIEnv, _class: jclass, handle: jlong, generation: jlong,
) -> jni_sys::jboolean {
    (handle != 0 && owner(handle).state.lock().unwrap().commit(generation)) as u8
}

/// op 0 GETANCHOR, 1 SETRATE, 2 FLUSH, 3 feedback. All times are sampled by the caller once.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_bufferedControl(
    env: *mut JNIEnv, _class: jclass, handle: jlong, op: jint, has_timestamp: jboolean, timestamp: jlong, rate: jint,
    ntp_seconds: jlong, ntp_fraction: jlong, now_ns: jlong, out: jni_sys::jlongArray,
) {
    if handle == 0 || Env(env).array_len(out) < 5 { return; }
    let owner = owner(handle);
    let mut state = owner.state.lock().unwrap();
    let timestamp = (has_timestamp != 0).then_some(timestamp as u32);
    let ntp = ((ntp_seconds as i128) << 32) + ntp_fraction as u32 as i128;
    let stop = match op {
        1 => state.set_rate(timestamp, rate, ntp, now_ns),
        2 => state.flush(timestamp),
        _ => false,
    };
    let position = if op == 3 { state.position(now_ns) } else { state.anchor_rtp };
    let ntp = state.anchor_ntp.unwrap_or(0);
    write_longs(env, out, &[position.map(|p| p as i64).unwrap_or(-1), (ntp >> 32) as i64,
        (ntp & 0xffff_ffff) as i64, state.rate as i64, stop as i64]);
    owner.changed.notify_all();
}

fn anchor_reply(snapshot: &[i64; 5]) -> Value {
    Value::Dict(vec![
        ("rtpTime".encode_utf16().collect(), Value::Int(snapshot[0] as u32 as u64)),
        ("networkTimeSecs".encode_utf16().collect(), Value::Int(snapshot[1].wrapping_sub(2_208_988_800) as u64)),
        ("networkTimeFrac".encode_utf16().collect(), Value::Null),
        ("rate".encode_utf16().collect(), Value::Int32(snapshot[3] as i32)),
    ])
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_bufferedAnchor(
    env: *mut JNIEnv, _: jclass, snapshot: jni_sys::jlongArray,
) -> jni_sys::jobject {
    let f = &**env;
    let mut values = [0; 5];
    (f.GetLongArrayRegion.unwrap())(env, snapshot, 0, 5, values.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 || values[0] < 0 { return std::ptr::null_mut(); }
    let Some(map) = bplist::jni::write_value(env, &anchor_reply(&values)) else { return std::ptr::null_mut(); };
    let class = (f.FindClass.unwrap())(env, c"java/math/BigInteger".as_ptr());
    if class.is_null() { return std::ptr::null_mut(); }
    let ctor = (f.GetMethodID.unwrap())(env, class, c"<init>".as_ptr(), c"(Ljava/lang/String;)V".as_ptr());
    if ctor.is_null() { return std::ptr::null_mut(); }
    let fraction = ((values[2] as u32 as u64) << 32).to_string();
    let big = (f.NewObjectA.unwrap())(env, class, ctor, [jni_sys::jvalue { l: Env(env).string(&fraction) }].as_ptr());
    if big.is_null() { return std::ptr::null_mut(); }
    bplist::jni::replace_map_value(env, map, "networkTimeFrac", big);
    map
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_bufferedFeedback(
    env: *mut JNIEnv, _: jclass, stream_type: jint, rate: jint, position: jlong,
    connection: jni_sys::jobject, ntp: jni_sys::jobject, now: jlong,
) -> jni_sys::jobject {
    let mut fields = vec![
        ("type".encode_utf16().collect(), Value::Int32(stream_type)),
        ("sampleRate".encode_utf16().collect(), Value::Int32(rate)),
    ];
    if position >= 0 {
        fields.extend([
            ("streamConnectionID".encode_utf16().collect(), Value::Null),
            ("timestamp".encode_utf16().collect(), Value::Null),
            ("timestampRawNs".encode_utf16().collect(), Value::Int(now as u64)),
            ("sampleTime".encode_utf16().collect(), Value::Int(position as u64)),
        ]);
    }
    let Some(map) = bplist::jni::write_value(env, &Value::Dict(fields)) else { return std::ptr::null_mut(); };
    if position >= 0 {
        bplist::jni::replace_map_value(env, map, "streamConnectionID", connection);
        if ((**env).ExceptionCheck.unwrap())(env) != 0 { return std::ptr::null_mut(); }
        bplist::jni::replace_map_value(env, map, "timestamp", ntp);
    }
    map
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_bufferedDestroy(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) -> jni_sys::jboolean {
    if handle == 0 { return 0; }
    let owner = Box::from_raw(handle as *mut Arc<Buffered>);
    let stop = owner.state.lock().unwrap().close();
    owner.changed.notify_all();
    stop as u8
}

#[cfg(any(target_os = "android", target_os = "linux"))]
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_bufferedStart(
    env: *mut JNIEnv, _class: jclass, handle: jlong, fd: jint, key: jbyteArray, callbacks: jni_sys::jobject,
) -> jlong {
    use crate::platform::stream::{self, Callbacks, Control};
    let e = Env(env);
    let mut bytes = [0u8; 32];
    if handle == 0 || fd < 0 || e.array_len(key) != 32 || !e.read_array(key, 0, &mut bytes) { return 0; }
    let Some(key) = StreamKey::new(&bytes) else { return 0 };
    let Some(callbacks) = Callbacks::new(e, callbacks) else { return 0 };
    let owner = owner(handle).clone();
    stream::spawn(e, "airplay-buffered-rx", Control::new(fd), callbacks, move |env, control, callbacks| {
        let Some(fd) = control.socket() else { return };
        let pool = shared_pool().clone();
        let (mut received_bytes, mut failures) = (0u64, 0u64);
        let mut window_start = crate::media::stats::clock(libc::CLOCK_MONOTONIC);
        let error = (|| -> Result<(), String> {
            loop {
                if control.closed() { return Ok(()); }
                let mut prefix = [0u8; 2];
                if !stream::recv_exact(fd, prefix.as_mut_ptr(), 2).map_err(|e| e.to_string())? { return Ok(()); }
                let length = u16::from_be_bytes(prefix) as usize;
                if length < 2 + RTP_HEADER_LEN + TAG_LEN + NONCE_LEN {
                    return Err(format!("invalid frame length {length}"));
                }
                let mut packet = pool.take(length - 2);
                match stream::recv_exact(fd, packet.spare(), length - 2) {
                    Ok(true) => unsafe { packet.set_len(length - 2); },
                    Ok(false) => { packet.recycle(); return Ok(()); }
                    Err(e) => { packet.recycle(); return Err(e.to_string()); }
                }
                let Some(plain) = open_packet(&key, &mut packet) else {
                    packet.recycle();
                    failures += 1;
                    if failures <= 3 { callbacks.log(env, 1, "Buffered audio: frame did not open"); }
                    continue;
                };
                packet.truncate(RTP_HEADER_LEN + plain);
                if plain == 0 { packet.recycle(); continue; }
                let timestamp = u32::from_be_bytes(packet.bytes()[4..8].try_into().unwrap());
                let frame = AudioPacket { handle: packet.into_handle(), sample: timestamp, arrived_ns: 0 };
                received_bytes += length as u64;
                let mut state = owner.state.lock().unwrap();
                while state.accepts(timestamp) && state.full(frame.size()) {
                    state = owner.changed.wait(state).unwrap();
                }
                if state.closed { return Ok(()); }
                if !state.accepts(timestamp) { continue; }
                let delay_ms = state.push(frame);
                owner.changed.notify_all();
                let now = crate::media::stats::clock(libc::CLOCK_MONOTONIC);
                if now - window_start >= 10_000_000_000 {
                    let queued_seconds = state.queue.len() as i64 * 1024 / state.sample_rate;
                    drop(state);
                    callbacks.log(env, 0, &format!("Buffered audio: queued {queued_seconds} s, received {} kbit/s impl=rust",
                        received_bytes * 8 * 1_000_000 / ((now - window_start) as u64 / 1000) / 1000));
                    received_bytes = 0;
                    window_start = now;
                    state = owner.state.lock().unwrap();
                }
                // An interruptible timed wait paces intake without holding the queue lock.
                // Spurious wakes/control changes do not turn a paced fill into a burst.
                if delay_ms > 0 {
                    let _ = owner.changed.wait_timeout_while(state, std::time::Duration::from_millis(delay_ms as u64),
                        |state| !state.closed).unwrap();
                }
            }
        })().err();
        if !control.closed() { callbacks.ended(env, error.as_deref()); }
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_packetSize(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) -> jint {
    packet::size(handle) as jint
}

/// A pooled packet holding a copy of `length` bytes of `bytes`, for a Kotlin receiver's packet that
/// native code must read (Opus decoding); 0 on bad arguments.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_packetWrap(
    env: *mut JNIEnv, _class: jclass, bytes: jbyteArray, length: jint,
) -> jlong {
    let e = Env(env);
    if length < 0 || length as usize > e.array_len(bytes) { return 0; }
    let length = length as usize;
    let mut packet = shared_pool().take(length.max(1));
    if !e.read_array(bytes, 0, std::slice::from_raw_parts_mut(packet.spare(), length)) {
        packet.recycle();
        return 0;
    }
    packet.set_len(length);
    packet.into_handle()
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_bufferedEngineCreate(
    env: *mut JNIEnv, _class: jclass,
) -> jlongArray {
    use super::buffered_state::engine::{Engine, ELIGIBLE};
    let f = &**env;
    let values = [Engine::Idle.encode(), ELIGIBLE];
    let result = (f.NewLongArray.unwrap())(env, values.len() as jint);
    if !result.is_null() { (f.SetLongArrayRegion.unwrap())(env, result, 0, values.len() as jint, values.as_ptr()); }
    result
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_bufferedEngineStep(
    env: *mut JNIEnv, _class: jclass, state: jlong, operation: jint, eligibility: jlong, facts: jint,
) -> jlongArray {
    use super::buffered_state::engine::Engine;
    let f = &**env;
    let Some(values) = Engine::decode(state).and_then(|state| state.step(operation, eligibility, facts)) else {
        let class = (f.FindClass.unwrap())(env, c"java/lang/IllegalArgumentException".as_ptr());
        if !class.is_null() { (f.ThrowNew.unwrap())(env, class, c"Invalid buffered engine value".as_ptr()); }
        return std::ptr::null_mut();
    };
    let result = (f.NewLongArray.unwrap())(env, values.len() as jint);
    if !result.is_null() { (f.SetLongArrayRegion.unwrap())(env, result, 0, values.len() as jint, values.as_ptr()); }
    result
}
