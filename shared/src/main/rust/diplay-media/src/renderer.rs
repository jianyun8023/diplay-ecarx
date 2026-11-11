//! One audio renderer's native state (Kotlin's `NativePcm`): its Opus decoder, call high-pass and the
//! PCM steps of [crate::pcm]. Every call comes from that renderer's worker thread, into its direct
//! PCM buffer, so no PCM crosses the Java heap.
//!
//! Opus is decoded by opus-pure, a pure-Rust port of libopus whose 16-bit API matches libopus 1.6.1
//! sample for sample.

use crate::jvm::Env;
use crate::packet;
use crate::pcm::{self, VoiceFilter, DEFAULT_CUTOFF_HZ};
use jni_sys::{jboolean, jclass, jint, jlong, jobject, JNIEnv};
use opus_pure::OpusDecoder;

/// Samples the Android Opus decoder drops at the start (OpusHead pre-skip, 6.5 ms at 48 kHz).
const OPUS_PRE_SKIP: usize = 312;
/// Flags for [process]: report sound above the peak, apply the attenuation, fade the block in.
const DETECT_AUDIBLE: jint = 1;
const ATTENUATE: jint = 2;
const FADE_IN: jint = 4;

pub struct Renderer {
    sample_rate: i32,
    channels: usize,
    filter: Option<VoiceFilter>,
    opus: Option<OpusDecoder>,
    pre_skip_left: usize,
    decoded: Vec<i16>,
}

unsafe fn renderer<'a>(handle: jlong) -> &'a mut Renderer { &mut *(handle as *mut Renderer) }

fn direct(env: *mut JNIEnv, buffer: jobject) -> Option<&'static mut [u8]> {
    Env(env).direct_buffer(buffer).map(|(address, capacity)| unsafe { std::slice::from_raw_parts_mut(address, capacity) })
}

/// 0 on bad arguments (or an Opus rate the codec does not take); the renderer then keeps MediaCodec.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_pcmCreate(
    _env: *mut JNIEnv, _class: jclass, sample_rate: jint, channels: jint, voice_filter: jboolean, opus_decoder: jboolean,
) -> jlong {
    if sample_rate <= 0 || !(1..=2).contains(&channels) { return 0; }
    let mut state = Box::new(Renderer {
        sample_rate,
        channels: channels as usize,
        filter: (voice_filter != 0).then(|| VoiceFilter::new(sample_rate, channels as usize, DEFAULT_CUTOFF_HZ)),
        opus: None,
        pre_skip_left: 0,
        decoded: Vec::new(),
    });
    if opus_decoder != 0 {
        let Ok(decoder) = OpusDecoder::new(sample_rate, channels as usize) else { return 0 };
        state.opus = Some(decoder);
        state.pre_skip_left = OPUS_PRE_SKIP * sample_rate as usize / 48_000;
        // 120 ms, the longest Opus packet.
        state.decoded = vec![0; sample_rate as usize * 120 / 1000 * channels as usize];
    }
    Box::into_raw(state) as jlong
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_pcmDestroy(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) {
    if handle != 0 { drop(Box::from_raw(handle as *mut Renderer)); }
}

/// Decodes `length` bytes at `offset` of a packet into `out` (from its start). Returns the PCM bytes
/// written, or -1 when the packet does not decode (-100 for bad arguments or a buffer that is not direct).
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_pcmDecodeOpus(
    env: *mut JNIEnv, _class: jclass, handle: jlong, packet_handle: jlong, offset: jint, length: jint, out: jobject,
) -> jint {
    if handle == 0 || packet_handle == 0 || offset < 0 || length <= 0 { return -100; }
    let state = renderer(handle);
    let Some(out) = direct(env, out) else { return -100 };
    let source = packet::bytes(packet_handle);
    let (offset, length) = (offset as usize, length as usize);
    if offset > source.len() || length > source.len() - offset { return -100; }
    let capacity = state.decoded.len() / state.channels;
    let Some(decoder) = state.opus.as_mut() else { return -100 };
    let frames = match decoder.decode_s16(&source[offset..offset + length], capacity, &mut state.decoded) {
        Ok(frames) => frames as i32,
        Err(_) => return -1,
    };
    let skip = state.pre_skip_left.min(frames as usize);
    state.pre_skip_left -= skip;
    let decoded = &state.decoded[skip * state.channels..frames as usize * state.channels];
    let bytes = decoded.len() * 2;
    if bytes > out.len() { return -100; }
    for (dst, sample) in out.chunks_exact_mut(2).zip(decoded) { dst.copy_from_slice(&sample.to_le_bytes()); }
    bytes as jint
}

/// Wired LPCM: copies `length` big-endian bytes at `offset` of a packet into `out` as little-endian.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_pcmCopySwapped(
    env: *mut JNIEnv, _class: jclass, packet_handle: jlong, offset: jint, length: jint, out: jobject,
) -> jint {
    if packet_handle == 0 || offset < 0 || length < 0 { return -1; }
    let Some(out) = direct(env, out) else { return -1 };
    let source = packet::bytes(packet_handle);
    let (offset, length) = (offset as usize, length as usize);
    if offset > source.len() || length > source.len() - offset || length > out.len() { return -1; }
    pcm::copy_swapped(&source[offset..offset + length], out) as jint
}

/// Runs the renderer's PCM steps in place on the first `length` bytes of `buffer`, in Kotlin's order:
/// call high-pass, sound detection, attenuation, fade-in. Returns 1 when [DETECT_AUDIBLE] found sound,
/// 0 when not, -1 on bad arguments.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_pcmProcess(
    env: *mut JNIEnv, _class: jclass, handle: jlong, buffer: jobject, length: jint, flags: jint,
    attenuation_db: jint, peak: jint,
) -> jint {
    if handle == 0 || length < 0 { return -1; }
    let state = renderer(handle);
    let Some(buffer) = direct(env, buffer) else { return -1 };
    if length as usize > buffer.len() { return -1; }
    let data = &mut buffer[..length as usize];
    if let Some(filter) = state.filter.as_mut() { filter.process(data); }
    let audible = flags & DETECT_AUDIBLE != 0 && pcm::is_audible(data, peak);
    if flags & ATTENUATE != 0 { pcm::attenuate(data, attenuation_db); }
    if flags & FADE_IN != 0 { pcm::fade_in(data, state.sample_rate); }
    audible as jint
}
