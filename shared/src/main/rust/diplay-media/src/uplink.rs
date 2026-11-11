//! The microphone uplink's send path (Kotlin's `MicrophoneUplink.sendFrame`): one captured frame in,
//! one sealed RTP datagram out. Opus frames are encoded with opus-pure at the settings the libopus
//! bridge used (VoIP, complexity 5, voice signal); PCM goes in CarPlay's big-endian wire order. The
//! packet layout is `MicrophonePacketizer`'s: a clear 12-byte RTP header whose timestamp and SSRC are
//! the associated data, the ChaCha20-Poly1305 ciphertext and tag, then the 8-byte little-endian nonce.
//!
//! Capture stays with Kotlin's AudioRecord: the head unit's microphone policy, the source choice and the
//! platform's noise and echo effects all hang off its session.

use crate::crypto::TAG_LEN;
use crate::jvm::Env;
use jni_sys::{jbyteArray, jclass, jint, jlong, JNIEnv};
use opus_pure::{Application, OpusEncoder, Signal, MAX_PACKET_BYTES};
use ring::aead::{Aad, LessSafeKey, Nonce, UnboundKey, CHACHA20_POLY1305};

const RTP_HEADER_LEN: usize = 12;
const NONCE_LEN: usize = 8;
const OPUS_RATE: i32 = 48_000;
const OPUS_FRAME_SAMPLES: usize = 960;

fn voice_encoder(bitrate: i32) -> Option<OpusEncoder> {
    let mut encoder = OpusEncoder::new(OPUS_RATE, 1, Application::Voip).ok()?;
    encoder.bitrate_bps = bitrate;
    encoder.complexity = 5;
    encoder.signal_type = Some(Signal::Voice);
    Some(encoder)
}

pub struct Uplink {
    fd: i32,
    address: libc::sockaddr_storage,
    address_len: libc::socklen_t,
    key: LessSafeKey,
    payload_type: u8,
    samples_per_packet: u32,
    encoder: Option<OpusEncoder>,
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
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_uplinkCreate(
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
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_uplinkSend(
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
    match up.encoder.as_mut() {
        Some(encoder) => {
            up.pcm.clear();
            up.pcm.extend(bytes.chunks_exact(2).map(|s| i16::from_le_bytes([s[0], s[1]])));
            if up.pcm.len() < OPUS_FRAME_SAMPLES { return -1; }
            let mut out = [0u8; MAX_PACKET_BYTES];
            match encoder.encode_s16(&up.pcm, OPUS_FRAME_SAMPLES, &mut out) {
                Ok(0) => return 0,
                Ok(size) => up.packet.extend_from_slice(&out[..size]),
                Err(_) => {
                    // As the libopus bridge did: start the next frame from a clean state.
                    let _ = encoder.reset_state();
                    return -1;
                }
            }
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
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_uplinkClose(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) {
    if handle == 0 { return; }
    let up = Box::from_raw(handle as *mut Uplink);
    libc::close(up.fd);
}

/// A standalone 48 kHz mono VoIP encoder (Kotlin's `NativeOpusEncoder`); 0 when the bitrate is refused.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_opusEncoderCreate(
    _env: *mut JNIEnv, _class: jclass, bitrate: jint,
) -> jlong {
    voice_encoder(bitrate).map(|encoder| Box::into_raw(Box::new(encoder)) as jlong).unwrap_or(0)
}

/// Encodes 960 samples of 16-bit little-endian PCM from `pcm` into `output`; the packet length, or
/// a negative value on failure (the encoder is then reset).
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_opusEncode(
    env: *mut JNIEnv, _class: jclass, handle: jlong, pcm: jbyteArray, output: jbyteArray,
) -> jint {
    let e = Env(env);
    let encoder = &mut *(handle as *mut OpusEncoder);
    if e.array_len(pcm) < OPUS_FRAME_SAMPLES * 2 || e.array_len(output) < MAX_PACKET_BYTES { return -1; }
    let mut bytes = vec![0u8; OPUS_FRAME_SAMPLES * 2];
    if !e.read_array(pcm, 0, &mut bytes) { return -1; }
    let samples: Vec<i16> = bytes.chunks_exact(2).map(|s| i16::from_le_bytes([s[0], s[1]])).collect();
    let mut out = [0u8; MAX_PACKET_BYTES];
    match encoder.encode_s16(&samples, OPUS_FRAME_SAMPLES, &mut out) {
        Ok(size) => if e.write_array(output, 0, &out[..size]) { size as jint } else { -1 },
        Err(_) => { let _ = encoder.reset_state(); -1 }
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_opusEncoderDestroy(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) {
    if handle != 0 { drop(Box::from_raw(handle as *mut OpusEncoder)); }
}

#[cfg(test)]
mod tests {
    use super::*;
    use opus_pure::OpusDecoder;

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
    fn a_voice_frame_round_trips_through_opus_pure() {
        let mut encoder = voice_encoder(64_000).unwrap();
        let mut decoder = OpusDecoder::new(OPUS_RATE, 1).unwrap();
        let mut out = [0u8; MAX_PACKET_BYTES];
        let mut pcm = vec![0i16; OPUS_FRAME_SAMPLES];
        let mut energy_in = 0f64;
        let mut energy_out = 0f64;
        for frame in 0..50 {
            let input: Vec<i16> = (0..OPUS_FRAME_SAMPLES)
                .map(|n| (((frame * OPUS_FRAME_SAMPLES + n) as f64 * 2.0 * std::f64::consts::PI * 300.0 / 48_000.0).sin() * 8000.0) as i16)
                .collect();
            let size = encoder.encode_s16(&input, OPUS_FRAME_SAMPLES, &mut out).unwrap();
            assert!(size > 0 && size <= MAX_PACKET_BYTES);
            let decoded = decoder.decode_s16(&out[..size], OPUS_FRAME_SAMPLES, &mut pcm).unwrap();
            assert_eq!(decoded, OPUS_FRAME_SAMPLES);
            if frame >= 10 {
                energy_in += input.iter().map(|&s| (s as f64).powi(2)).sum::<f64>();
                energy_out += pcm.iter().map(|&s| (s as f64).powi(2)).sum::<f64>();
            }
        }
        let ratio = energy_out / energy_in;
        assert!((0.7..1.3).contains(&ratio), "{ratio}");
    }
}
