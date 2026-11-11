//! Buffered music (Kotlin's `BufferedAudioStream`) keeps its TCP reader, pacing and backpressure in
//! Kotlin; each length-prefixed frame it reads is opened here into a pooled packet instead of three
//! heap arrays.

use crate::crypto::{StreamKey, TAG_LEN};
use crate::jvm::Env;
use crate::packet::{self, shared_pool};
use jni_sys::{jbyteArray, jclass, jint, jlong, JNIEnv};

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
    let wire = packet.bytes_mut();
    // Exactly a 12-byte RTP v2 header: extensions, padding and CSRCs cannot reach the renderer.
    if wire[0] != 0x80 { packet.recycle(); return 0; }
    let mut nonce = [0u8; 12];
    nonce[4..].copy_from_slice(&wire[length - NONCE_LEN..]);
    let mut aad = [0u8; 8];
    aad.copy_from_slice(&wire[4..RTP_HEADER_LEN]);
    match stream_key.open(nonce, &aad, &mut wire[RTP_HEADER_LEN..length - NONCE_LEN]) {
        Some(plain) => { packet.truncate(RTP_HEADER_LEN + plain); packet.into_handle() }
        None => { packet.recycle(); 0 }
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_packetSize(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) -> jint {
    packet::size(handle) as jint
}
