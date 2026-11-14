//! The microphone uplink's send path (Kotlin's `MicrophoneUplink.sendFrame`): one captured frame in,
//! one sealed RTP datagram out. Opus frames are encoded with the statically linked C libopus at the settings the
//! former JNI bridge used (VoIP, complexity 5, voice signal); PCM goes in CarPlay's big-endian wire order. The
//! packet layout is `MicrophonePacketizer`'s: a clear 12-byte RTP header whose timestamp and SSRC are
//! the associated data, the ChaCha20-Poly1305 ciphertext and tag, then the 8-byte little-endian nonce.
//!
//! Capture stays with Kotlin's AudioRecord: the head unit's microphone policy, the source choice and the
//! platform's noise and echo effects all hang off its session.

use crate::protocols::crypto::TAG_LEN;
use crate::platform::jvm::Env;
use jni_sys::{jbyteArray, jclass, jint, jlong, jlongArray, JNIEnv};
use libopus_sys::{
    opus_encode, opus_encoder_create, opus_encoder_ctl, opus_encoder_destroy, OpusEncoder,
    OPUS_APPLICATION_VOIP, OPUS_OK, OPUS_SET_BITRATE_REQUEST, OPUS_SET_COMPLEXITY_REQUEST,
    OPUS_SET_SIGNAL_REQUEST, OPUS_SIGNAL_VOICE,
};

/// `OPUS_RESET_STATE` (opus_defines.h); libopus_sys 0.3 does not re-export it.
const OPUS_RESET_STATE_REQUEST: i32 = 4028;
use ring::aead::{Aad, LessSafeKey, Nonce, UnboundKey, CHACHA20_POLY1305};

const RTP_HEADER_LEN: usize = 12;
const NONCE_LEN: usize = 8;
const OPUS_RATE: i32 = 48_000;
const OPUS_FRAME_SAMPLES: usize = 960;
// CarPlay sends one 20 ms frame; libopus's packet fits this budget.
const OPUS_PACKET_BYTES: usize = 1275;

// Capture recovery counts successful error restarts, but counts every no-audio attempt.
fn capture_recovery(state: &mut [i64; 3], event: i32, now: i64, timeout_ms: i64) -> i32 {
    match event {
        0 => { state[2] = now; 0 }
        1 => if state[0] < 3 { state[0] as i32 + 1 } else { 0 },
        2 => { state[0] += 1; state[0] as i32 }
        3 if state[1] < 3 && now.wrapping_sub(state[2]) >= timeout_ms.wrapping_mul(1_000_000) => {
            state[1] += 1;
            state[2] = now;
            state[1] as i32
        }
        _ => 0,
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAudioScheduler_captureRecovery(
    env: *mut JNIEnv, _: jclass, owner: jlongArray, event: jint, now: jlong, timeout_ms: jlong,
) -> jint {
    let f = &**env;
    let mut state = [0; 3];
    (f.GetLongArrayRegion.unwrap())(env, owner, 0, 3, state.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 { return 0; }
    let result = capture_recovery(&mut state, event, now, timeout_ms);
    (f.SetLongArrayRegion.unwrap())(env, owner, 0, 3, state.as_ptr());
    result
}

/// A 48 kHz mono VoIP encoder backed by the statically linked C libopus (same settings the
/// former JNI bridge used: VoIP, complexity 5, voice signal). `None` when libopus refuses.
fn voice_encoder(bitrate: i32) -> Option<*mut OpusEncoder> {
    if bitrate <= 0 { return None; }
    unsafe {
        let mut error = 0;
        let encoder = opus_encoder_create(OPUS_RATE, 1, OPUS_APPLICATION_VOIP as i32, &mut error);
        if encoder.is_null() || error != OPUS_OK as i32 { return None; }
        let ok = opus_encoder_ctl(encoder, OPUS_SET_BITRATE_REQUEST as i32, bitrate) == OPUS_OK as i32
            && opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY_REQUEST as i32, 5) == OPUS_OK as i32
            && opus_encoder_ctl(encoder, OPUS_SET_SIGNAL_REQUEST as i32, OPUS_SIGNAL_VOICE as i32) == OPUS_OK as i32;
        if !ok { opus_encoder_destroy(encoder); return None; }
        Some(encoder)
    }
}

pub struct Uplink {
    fd: i32,
    address: libc::sockaddr_storage,
    address_len: libc::socklen_t,
    key: LessSafeKey,
    payload_type: u8,
    samples_per_packet: u32,
    encoder: Option<*mut OpusEncoder>,
    sequence: u16,
    timestamp: u32,
    nonce: u64,
    pcm: Vec<i16>,
    packet: Vec<u8>,
}

/// Seals `packet` (12 header bytes to fill, then the body) in place into the wire layout.
pub fn seal(key: &LessSafeKey, payload_type: u8, sequence: u16, timestamp: u32, counter: u64, packet: &mut Vec<u8>) -> bool {
    packet[0] = 0x80;
    packet[1] = payload_type;
    packet[2..4].copy_from_slice(&sequence.to_be_bytes());
    packet[4..8].copy_from_slice(&timestamp.to_be_bytes());
    packet[8..12].fill(0);
    let mut aad = [0u8; 8];
    aad.copy_from_slice(&packet[4..RTP_HEADER_LEN]);
    let mut nonce = [0u8; 12];
    nonce[4..].copy_from_slice(&counter.to_le_bytes());
    let Ok(tag) = key.seal_in_place_separate_tag(Nonce::assume_unique_for_key(nonce), Aad::from(aad), &mut packet[RTP_HEADER_LEN..]) else {
        return false;
    };
    packet.extend_from_slice(tag.as_ref());
    packet.extend_from_slice(&counter.to_le_bytes());
    debug_assert!(packet.len() >= RTP_HEADER_LEN + TAG_LEN + NONCE_LEN);
    true
}

unsafe fn uplink<'a>(handle: jlong) -> &'a mut Uplink { &mut *(handle as *mut Uplink) }

/// A sender on `fd` (a duplicate the uplink owns) to `address` (4 or 16 bytes) and `port`. With
/// `opus_bitrate` > 0 frames are encoded as 20 ms 48 kHz mono Opus. 0 when an argument is wrong.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_uplinkCreate(
    env: *mut JNIEnv, _class: jclass, fd: jint, key: jbyteArray, payload_type: jint, address: jbyteArray,
    scope_id: jint, port: jint, opus_bitrate: jint, rtp_samples_per_packet: jint,
) -> jlong {
    let e = Env(env);
    let mut key_bytes = [0u8; 32];
    if fd < 0 || e.array_len(key) != 32 || !e.read_array(key, 0, &mut key_bytes) { return 0; }
    let Ok(unbound) = UnboundKey::new(&CHACHA20_POLY1305, &key_bytes) else { return 0 };
    let mut storage: libc::sockaddr_storage = std::mem::zeroed();
    let length = match e.array_len(address) {
        4 => {
            let mut raw = [0u8; 4];
            e.read_array(address, 0, &mut raw);
            let v4 = &mut *(&mut storage as *mut _ as *mut libc::sockaddr_in);
            v4.sin_family = libc::AF_INET as _;
            v4.sin_port = (port as u16).to_be();
            v4.sin_addr.s_addr = u32::from_ne_bytes(raw);
            std::mem::size_of::<libc::sockaddr_in>()
        }
        16 => {
            let mut raw = [0u8; 16];
            e.read_array(address, 0, &mut raw);
            let v6 = &mut *(&mut storage as *mut _ as *mut libc::sockaddr_in6);
            v6.sin6_family = libc::AF_INET6 as _;
            v6.sin6_port = (port as u16).to_be();
            v6.sin6_addr.s6_addr = raw;
            v6.sin6_scope_id = scope_id as u32;
            std::mem::size_of::<libc::sockaddr_in6>()
        }
        _ => return 0,
    };
    let encoder = if opus_bitrate > 0 {
        match voice_encoder(opus_bitrate) { Some(encoder) => Some(encoder), None => return 0 }
    } else { None };
    Box::into_raw(Box::new(Uplink {
        fd, address: storage, address_len: length as libc::socklen_t, key: LessSafeKey::new(unbound),
        payload_type: (payload_type & 0x7f) as u8, samples_per_packet: rtp_samples_per_packet as u32, encoder,
        sequence: 0, timestamp: 0, nonce: 0, pcm: Vec::new(), packet: Vec::new(),
    })) as jlong
}

/// Sends one captured frame (`length` bytes of 16-bit little-endian PCM). Returns the packets sent
/// (0 when the encoder produced nothing), -1 when encoding failed, or -errno when the send failed.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_uplinkSend(
    env: *mut JNIEnv, _class: jclass, handle: jlong, frame: jbyteArray, length: jint,
) -> jint {
    let e = Env(env);
    let up = uplink(handle);
    let length = length.max(0) as usize & !1;
    if length > e.array_len(frame) { return -1; }
    let mut bytes = vec![0u8; length];
    if !e.read_array(frame, 0, &mut bytes) { return -1; }
    up.packet.clear();
    up.packet.resize(RTP_HEADER_LEN, 0);
    match up.encoder {
        Some(encoder) => {
            up.pcm.clear();
            up.pcm.extend(bytes.chunks_exact(2).map(|s| i16::from_le_bytes([s[0], s[1]])));
            if up.pcm.len() < OPUS_FRAME_SAMPLES { return -1; }
            let mut out = [0u8; OPUS_PACKET_BYTES];
            let size = unsafe { opus_encode(encoder, up.pcm.as_ptr(), OPUS_FRAME_SAMPLES as i32,
                out.as_mut_ptr(), OPUS_PACKET_BYTES as i32) };
            if size == 0 { return 0; }
            if size < 0 {
                // As the libopus bridge did: start the next frame from a clean state.
                unsafe { opus_encoder_ctl(encoder, OPUS_RESET_STATE_REQUEST) };
                return -1;
            }
            up.packet.extend_from_slice(&out[..size as usize]);
        }
        None => up.packet.extend(bytes.chunks_exact(2).flat_map(|s| [s[1], s[0]])),
    }
    if !seal(&up.key, up.payload_type, up.sequence, up.timestamp, up.nonce, &mut up.packet) { return -1; }
    up.sequence = up.sequence.wrapping_add(1);
    up.timestamp = up.timestamp.wrapping_add(up.samples_per_packet);
    up.nonce += 1;
    let sent = libc::sendto(up.fd, up.packet.as_ptr() as *const _, up.packet.len(), 0,
        &up.address as *const _ as *const libc::sockaddr, up.address_len);
    if sent < 0 { -std::io::Error::last_os_error().raw_os_error().unwrap_or(1) } else { 1 }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_uplinkClose(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) {
    if handle == 0 { return; }
    let up = Box::from_raw(handle as *mut Uplink);
    if let Some(encoder) = up.encoder { unsafe { opus_encoder_destroy(encoder) }; }
    libc::close(up.fd);
}

/// A standalone 48 kHz mono VoIP encoder (Kotlin's `NativeOpusEncoder`); 0 when the bitrate is refused.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_opusEncoderCreate(
    _env: *mut JNIEnv, _class: jclass, bitrate: jint,
) -> jlong {
    voice_encoder(bitrate).map(|encoder| encoder as jlong).unwrap_or(0)
}

/// Encodes 960 samples of 16-bit little-endian PCM from `pcm` into `output`; the packet length, or
/// a negative value on failure (the encoder is then reset).
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_opusEncode(
    env: *mut JNIEnv, _class: jclass, handle: jlong, pcm: jbyteArray, output: jbyteArray,
) -> jint {
    let e = Env(env);
    if handle == 0 { return -1; }
    let encoder = handle as *mut OpusEncoder;
    if e.array_len(pcm) != OPUS_FRAME_SAMPLES * 2 || e.array_len(output) < OPUS_PACKET_BYTES { return -1; }
    let mut bytes = vec![0u8; OPUS_FRAME_SAMPLES * 2];
    if !e.read_array(pcm, 0, &mut bytes) { return -1; }
    let samples: Vec<i16> = bytes.chunks_exact(2).map(|s| i16::from_le_bytes([s[0], s[1]])).collect();
    let mut out = [0u8; OPUS_PACKET_BYTES];
    let size = unsafe { opus_encode(encoder, samples.as_ptr(), OPUS_FRAME_SAMPLES as i32,
        out.as_mut_ptr(), OPUS_PACKET_BYTES as i32) };
    if size < 0 {
        unsafe { opus_encoder_ctl(encoder, OPUS_RESET_STATE_REQUEST) };
        return -1;
    }
    if e.write_array(output, 0, &out[..size as usize]) { size as jint } else { -1 }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_opusEncoderDestroy(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) {
    if handle != 0 { unsafe { opus_encoder_destroy(handle as *mut OpusEncoder) }; }
}

#[cfg(test)]
mod tests {
    use super::*;
    use libopus_sys::{opus_decode, opus_decoder_create, opus_decoder_destroy};

    #[test]
    fn capture_recovery_preserves_independent_budgets_and_commit_order() {
        let mut state = [0, 0, 100];
        assert_eq!(capture_recovery(&mut state, 1, 0, 0), 1);
        assert_eq!(capture_recovery(&mut state, 1, 0, 0), 1); // failed restart is not committed
        for attempt in 1..=3 {
            assert_eq!(capture_recovery(&mut state, 1, 0, 0), attempt);
            assert_eq!(capture_recovery(&mut state, 2, 0, 0), attempt);
        }
        assert_eq!(capture_recovery(&mut state, 1, 0, 0), 0);
        assert_eq!(capture_recovery(&mut state, 3, 1_000_099, 1), 0);
        for attempt in 1..=3 {
            assert_eq!(capture_recovery(&mut state, 3, 100 + attempt as i64 * 1_000_000, 1), attempt);
        }
        assert_eq!(capture_recovery(&mut state, 3, 4_000_100, 1), 0);
        let mut state = [0, 0, 0];
        capture_recovery(&mut state, 0, 900_000, 1);
        assert_eq!(capture_recovery(&mut state, 3, 1_000_000, 1), 0);
        assert_eq!(capture_recovery(&mut state, 3, 1_900_000, 1), 1);
    }

    /// Decode one packet with the same static libopus the encoder links against.
    unsafe fn decode_opus(packet: &[u8], pcm: &mut [i16]) -> i32 {
        unsafe {
            let mut error = 0;
            let decoder = opus_decoder_create(OPUS_RATE, 1, &mut error);
            if decoder.is_null() { return -1; }
            let frames = opus_decode(decoder, packet.as_ptr(), packet.len() as i32,
                pcm.as_mut_ptr(), pcm.len() as i32, 0);
            opus_decoder_destroy(decoder);
            frames
        }
    }

    #[test]
    fn the_jni_encoder_uses_the_kotlin_twenty_ms_frame_and_packet_contract() {
        // Exercise the real JNI entry point with the same arrays NativeOpusEncoder allocates.
        unsafe extern "system" fn array_len(_env: *mut JNIEnv, array: jni_sys::jarray) -> jint {
            (*(array as *const Vec<u8>)).len() as jint
        }
        unsafe extern "system" fn read_array(_env: *mut JNIEnv, array: jbyteArray, offset: jint, length: jint, out: *mut i8) {
            let bytes = &*(array as *const Vec<u8>);
            std::ptr::copy_nonoverlapping(bytes.as_ptr().add(offset as usize), out as *mut u8, length as usize);
        }
        unsafe extern "system" fn write_array(_env: *mut JNIEnv, array: jbyteArray, offset: jint, length: jint, input: *const i8) {
            let bytes = &mut *(array as *mut Vec<u8>);
            std::ptr::copy_nonoverlapping(input as *const u8, bytes.as_mut_ptr().add(offset as usize), length as usize);
        }
        unsafe extern "system" fn exception_check(_env: *mut JNIEnv) -> jni_sys::jboolean { 0 }

        unsafe {
            let mut functions: jni_sys::JNINativeInterface_ = std::mem::zeroed();
            functions.GetArrayLength = Some(array_len);
            functions.GetByteArrayRegion = Some(read_array);
            functions.SetByteArrayRegion = Some(write_array);
            functions.ExceptionCheck = Some(exception_check);
            let mut env = &functions as JNIEnv;
            let class = std::ptr::null_mut();
            for bitrate in [24_000, 48_000, 64_000, 96_000] {
                let handle = Java_com_shihab_diplay_airplay_NativeMedia_opusEncoderCreate(&mut env, class, bitrate);
                assert_ne!(handle, 0);
                let mut pcm: Vec<u8> = (0..960).flat_map(|n| {
                    let sample = ((n as f64 * 2.0 * std::f64::consts::PI * 300.0 / 48_000.0).sin() * 8000.0) as i16;
                    sample.to_le_bytes()
                }).collect();
                let mut output = vec![0u8; 1275];
                let input = &mut pcm as *mut Vec<u8> as jbyteArray;
                let out = &mut output as *mut Vec<u8> as jbyteArray;
                let first = Java_com_shihab_diplay_airplay_NativeMedia_opusEncode(&mut env, class, handle, input, out);
                let first_packet = output[..first.max(0) as usize].to_vec();
                let invalid: Vec<_> = [0, 1918, 1922, 3840].into_iter().map(|length| {
                    let mut bad = vec![0u8; length];
                    Java_com_shihab_diplay_airplay_NativeMedia_opusEncode(&mut env, class, handle,
                        &mut bad as *mut Vec<u8> as jbyteArray, out)
                }).collect();
                let second = Java_com_shihab_diplay_airplay_NativeMedia_opusEncode(&mut env, class, handle, input, out);
                Java_com_shihab_diplay_airplay_NativeMedia_opusEncoderDestroy(&mut env, class, handle);
                assert!((1..=1275).contains(&first), "20 ms / {bitrate} bps with Kotlin's 1275-byte output returned {first}");
                assert_eq!(invalid, vec![-1; 4]);
                assert!((1..=1275).contains(&second));
                let mut decoded = [0i16; 960];
                assert_eq!(decode_opus(&first_packet, &mut decoded), 960);
                assert_eq!(decode_opus(&output[..second as usize], &mut decoded), 960);
                assert!(decoded.iter().any(|&sample| sample != 0));
            }
            for bitrate in [0, -1] {
                let handle = Java_com_shihab_diplay_airplay_NativeMedia_opusEncoderCreate(&mut env, class, bitrate);
                Java_com_shihab_diplay_airplay_NativeMedia_opusEncoderDestroy(&mut env, class, handle);
                assert_eq!(handle, 0);
            }
        }
    }

    /// The same packet as Kotlin's `MicrophonePacketizerGoldenTest`, so both sealers send one layout.
    #[test]
    fn a_sealed_pcm_packet_matches_the_golden_bytes() {
        let key_bytes: Vec<u8> = (0..32).collect();
        let key = LessSafeKey::new(UnboundKey::new(&CHACHA20_POLY1305, &key_bytes).unwrap());
        // Little-endian PCM 0x0201, 0x0403 goes out big-endian.
        let mut packet = vec![0u8; RTP_HEADER_LEN];
        packet.extend([1u8, 2, 3, 4].chunks_exact(2).flat_map(|s| [s[1], s[0]]));
        assert!(seal(&key, 100, 7, 960, 3, &mut packet));
        let hex: String = packet.iter().map(|b| format!("{b:02x}")).collect();
        assert_eq!(hex, GOLDEN);
    }

    const GOLDEN: &str = "80640007000003c00000000055727079e7668ab0538bf19d08f4688b9121d2050300000000000000";

    #[test]
    fn a_voice_frame_round_trips_through_libopus() {
        let encoder = voice_encoder(64_000).unwrap();
        let mut out = [0u8; OPUS_PACKET_BYTES];
        let mut pcm = vec![0i16; OPUS_FRAME_SAMPLES];
        let mut energy_in = 0f64;
        let mut energy_out = 0f64;
        let mut error = 0;
        let decoder = unsafe { opus_decoder_create(OPUS_RATE, 1, &mut error) };
        assert!(!decoder.is_null());
        for frame in 0..50 {
            let input: Vec<i16> = (0..OPUS_FRAME_SAMPLES)
                .map(|n| (((frame * OPUS_FRAME_SAMPLES + n) as f64 * 2.0 * std::f64::consts::PI * 300.0 / 48_000.0).sin() * 8000.0) as i16)
                .collect();
            let size = unsafe { opus_encode(encoder, input.as_ptr(), OPUS_FRAME_SAMPLES as i32,
                out.as_mut_ptr(), OPUS_PACKET_BYTES as i32) };
            assert!(size > 0 && size as usize <= OPUS_PACKET_BYTES);
            let decoded = unsafe { opus_decode(decoder, out.as_ptr(), size,
                pcm.as_mut_ptr(), OPUS_FRAME_SAMPLES as i32, 0) };
            assert_eq!(decoded, OPUS_FRAME_SAMPLES as i32);
            if frame >= 10 {
                energy_in += input.iter().map(|&s| (s as f64).powi(2)).sum::<f64>();
                energy_out += pcm.iter().map(|&s| (s as f64).powi(2)).sum::<f64>();
            }
        }
        unsafe { opus_encoder_destroy(encoder); opus_decoder_destroy(decoder) };
        let ratio = energy_out / energy_in;
        assert!((0.7..1.3).contains(&ratio), "{ratio}");
    }
}
