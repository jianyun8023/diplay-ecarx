//! One CarPlay screen stream (Kotlin's `ScreenStream`): a 128-byte header, then a body that is a
//! clear VideoConfig or a sealed VideoFrame. Frames are opened and turned into Annex B in their
//! pooled buffer, then sent directly to native decoder queues (or to Kotlin's packet fallback).

use crate::media::video::annexb;
use crate::protocols::crypto::{counter_nonce, StreamKey, TAG_LEN};
use crate::platform::jvm::Env;
use crate::media::packet::Pool;
use crate::media::stats::{clock, ReceiveStats, SystemClock, SESSION_REPORT_INTERVAL_NS};
use crate::platform::stream::{self, recv_exact, Callbacks, Control};
use jni_sys::{jbyteArray, jclass, jint, jlong, jobject, JNIEnv};

const HEADER_LEN: usize = 128;
const OPCODE_OFFSET: usize = 4;
const OP_VIDEO_FRAME: u8 = 0;
const OP_VIDEO_CONFIG: u8 = 1;
const MAX_BODY: usize = 8 * 1024 * 1024;

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_startVideo(
    env: *mut JNIEnv, _class: jclass, fd: jint, key: jbyteArray, callbacks: jobject, route: jlong,
) -> jlong {
    let e = Env(env);
    let mut key_bytes = [0u8; 32];
    if fd < 0 || e.array_len(key) != 32 || !e.read_array(key, 0, &mut key_bytes) { return 0; }
    let (Some(stream_key), Some(callbacks)) = (StreamKey::new(&key_bytes), Callbacks::new(e, callbacks)) else { return 0 };
    let control = Control::new(fd);
    #[cfg(target_os = "android")]
    let route = crate::media::video::vpipe::retain_route(route);
    #[cfg(not(target_os = "android"))]
    let _ = route;
    stream::spawn("airplay-screen", control, callbacks, move |env, control, callbacks| {
        receive(env, control, callbacks, stream_key, #[cfg(target_os = "android")] route)
    })
}

fn receive(env: Env, control: &Control, callbacks: &Callbacks, key: StreamKey,
    #[cfg(target_os = "android")] route: Option<std::sync::Arc<crate::media::video::vpipe::Route>>,
) {
    let Some(fd) = control.socket() else { return };
    let pool = Pool::new();
    let mut stats = ReceiveStats::new("video".into(), SESSION_REPORT_INTERVAL_NS, SystemClock);
    let extra = |pool: &Pool| format!(" impl=rust pooled={}", pool.outstanding());
    let mut header = [0u8; HEADER_LEN];
    let mut counter: u64 = 0;
    let mut first_frame = true;
    let failure: Option<String> = loop {
        if control.closed() { break None; }
        stats.reading();
        match recv_exact(fd, header.as_mut_ptr(), HEADER_LEN) {
            Ok(true) => {}
            Ok(false) => break None,
            Err(error) => break Some(format!("IOException: {error}")),
        }
        let body_size = u32::from_le_bytes(header[0..4].try_into().unwrap()) as usize;
        if body_size > MAX_BODY { break None; }
        let mut packet = pool.take(body_size.max(1));
        match recv_exact(fd, packet.spare(), body_size) {
            Ok(true) => unsafe { packet.set_len(body_size) },
            Ok(false) => { packet.recycle(); break None; }
            Err(error) => { packet.recycle(); break Some(format!("IOException: {error}")); }
        }
        let arrival = clock(libc::CLOCK_MONOTONIC);
        stats.received(HEADER_LEN + body_size, None, None);
        match header[OPCODE_OFFSET] {
            OP_VIDEO_FRAME if body_size >= TAG_LEN => {
                let started = stats.now();
                let opened = key.open(counter_nonce(counter), &header, packet.bytes_mut());
                stats.decrypted(stats.now() - started, body_size);
                let Some(plain) = opened else {
                    packet.recycle();
                    break Some("AEADBadTagException: video frame did not open".into());
                };
                counter += 1;
                packet.truncate(plain);
                let mut flags = 0;
                if annexb::to_annex_b_in_place(packet.bytes_mut()) {
                    flags = annexb::FLAG_ANNEX_B | annexb::random_access_flags(packet.bytes());
                }
                if first_frame {
                    first_frame = false;
                    callbacks.receiving(env);
                    let head: String = packet.bytes().iter().take(16).map(|b| format!("{b:02x}")).collect();
                    callbacks.log(env, 0, &format!(
                        "video first decrypted frame sealed={body_size} plain={plain} head={head} chacha=ring impl=rust"));
                }
                let size = packet.len();
                let handle = packet.into_handle();
                let sender = annexb::sender_nanos(&header);
                #[cfg(target_os = "android")]
                let delivered = route.as_ref().is_some_and(|route| unsafe { route.deliver(handle, size, flags, sender, arrival) });
                #[cfg(not(target_os = "android"))]
                let delivered = false;
                if !delivered { callbacks.packet(env, handle, size, flags, sender, arrival); }
            }
            OP_VIDEO_CONFIG => {
                callbacks.config(env, packet.bytes());
                packet.recycle();
            }
            _ => packet.recycle(),
        }
        if let Some(line) = stats.processed(&|| extra(&pool)) { callbacks.report(env, &line); }
    };
    if let Some(line) = stats.flush(true, &|| extra(&pool)) { callbacks.report(env, &line); }
    if !control.closed() { callbacks.ended(env, failure.as_deref()); }
}
