//! Buffered music: TCP framing/decryption, bounded intake, pacing and the playback state owner.

use crate::protocols::crypto::{StreamKey, TAG_LEN};
use crate::platform::jvm::Env;
use crate::media::packet::{self, shared_pool};
use jni_sys::{jbyteArray, jclass, jint, jlong, JNIEnv};
use crate::media::audio::buffered_state::BufferedAudio;
use crate::protocols::bplist::{self, Value};
#[cfg(any(target_os = "android", target_os = "linux"))]
use crate::media::audio::queue::AudioPacket;
use std::sync::{Arc, Mutex, Condvar};

struct Buffered { state: Mutex<BufferedAudio>, changed: Condvar }

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

const RTP_HEADER_LEN: usize = 12;
const NONCE_LEN: usize = 8;

/// The opened RTP packet's handle, or 0 when the frame is malformed or does not open. The frame is
/// a 12-byte RTP v2 header, ciphertext and tag sealed with `key` (timestamp and SSRC as associated
/// data), then an 8-byte nonce.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_openRtp(
    env: *mut JNIEnv, _class: jclass, key: jbyteArray, body: jbyteArray, length: jint,
) -> jlong {
    let e = Env(env);
    let length = length.max(0) as usize;
    let mut key_bytes = [0u8; 32];
    if length < RTP_HEADER_LEN + TAG_LEN + NONCE_LEN || length > e.array_len(body) ||
        e.array_len(key) != 32 || !e.read_array(key, 0, &mut key_bytes) { return 0; }
    let Some(stream_key) = StreamKey::new(&key_bytes) else { return 0 };
    let mut packet = shared_pool().take(length);
    let filled = e.read_array(body, 0, std::slice::from_raw_parts_mut(packet.spare(), length));
    if !filled { packet.recycle(); return 0; }
    packet.set_len(length);
    match open_packet(&stream_key, &mut packet) {
        Some(plain) => { packet.truncate(RTP_HEADER_LEN + plain); packet.into_handle() }
        None => { packet.recycle(); 0 }
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_bufferedCreate(
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
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_bufferedNext(
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
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_bufferedCommit(
    _env: *mut JNIEnv, _class: jclass, handle: jlong, generation: jlong,
) -> jni_sys::jboolean {
    (handle != 0 && owner(handle).state.lock().unwrap().commit(generation)) as u8
}

/// op 0 GETANCHOR, 1 SETRATE, 2 FLUSH, 3 feedback. All times are sampled by the caller once.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_bufferedControl(
    env: *mut JNIEnv, _class: jclass, handle: jlong, op: jint, timestamp: jlong, rate: jint,
    ntp_seconds: jlong, ntp_fraction: jlong, now_ns: jlong, out: jni_sys::jlongArray,
) {
    if handle == 0 || Env(env).array_len(out) < 5 { return; }
    let owner = owner(handle);
    let mut state = owner.state.lock().unwrap();
    let timestamp = (timestamp >= 0).then_some(timestamp as u32);
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

unsafe fn replace_reply_value(env: *mut JNIEnv, map: jni_sys::jobject, name: &str, value: jni_sys::jobject) {
    let f = &**env;
    let class = (f.GetObjectClass.unwrap())(env, map);
    let method = (f.GetMethodID.unwrap())(env, class, c"put".as_ptr(), c"(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;".as_ptr());
    if method.is_null() { return; }
    (f.CallObjectMethodA.unwrap())(env, map, method, [jni_sys::jvalue { l: Env(env).string(name) }, jni_sys::jvalue { l: value }].as_ptr());
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_bufferedAnchor(
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
    replace_reply_value(env, map, "networkTimeFrac", big);
    map
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_bufferedFeedback(
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
        replace_reply_value(env, map, "streamConnectionID", connection);
        if ((**env).ExceptionCheck.unwrap())(env) != 0 { return std::ptr::null_mut(); }
        replace_reply_value(env, map, "timestamp", ntp);
    }
    map
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_bufferedDestroy(
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
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_bufferedStart(
    env: *mut JNIEnv, _class: jclass, handle: jlong, fd: jint, key: jbyteArray, callbacks: jni_sys::jobject,
) -> jlong {
    use crate::platform::stream::{self, Callbacks, Control};
    let e = Env(env);
    let mut bytes = [0u8; 32];
    if handle == 0 || fd < 0 || e.array_len(key) != 32 || !e.read_array(key, 0, &mut bytes) { return 0; }
    let (Some(key), Some(callbacks)) = (StreamKey::new(&bytes), Callbacks::new(e, callbacks)) else { return 0 };
    let owner = owner(handle).clone();
    stream::spawn("airplay-buffered-rx", Control::new(fd), callbacks, move |env, control, callbacks| {
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
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_packetSize(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) -> jint {
    packet::size(handle) as jint
}

/// A pooled packet holding a copy of `length` bytes of `bytes`, for a Kotlin receiver's packet that
/// native code must read (Opus decoding); 0 on bad arguments.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_packetWrap(
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
