//! One audio renderer's native state (Kotlin's `NativePcm`): its Opus decoder, call high-pass and the
//! PCM steps of [crate::pcm]. Every call comes from that renderer's worker thread, into its direct
//! PCM buffer, so no PCM crosses the Java heap.
//!
//! Opus comes from the `libopus.so` the app already ships for the microphone encoder, opened with
//! dlopen: a phone without it still gets every other part of this library.

use crate::jvm::Env;
use crate::packet;
use crate::pcm::{self, VoiceFilter, DEFAULT_CUTOFF_HZ};
use jni_sys::{jboolean, jclass, jint, jlong, jobject, JNIEnv};
use std::ffi::{c_void, CStr};
use std::os::raw::c_int;
use std::sync::OnceLock;

type Create = unsafe extern "C" fn(i32, c_int, *mut c_int) -> *mut c_void;
type Decode = unsafe extern "C" fn(*mut c_void, *const u8, i32, *mut i16, c_int, c_int) -> c_int;
type Destroy = unsafe extern "C" fn(*mut c_void);

struct Opus { create: Create, decode: Decode, destroy: Destroy }

fn opus() -> Option<&'static Opus> {
    static OPUS: OnceLock<Option<Opus>> = OnceLock::new();
    OPUS.get_or_init(|| unsafe {
        let library = libc::dlopen(c"libopus.so".as_ptr(), libc::RTLD_NOW);
        if library.is_null() { return None; }
        let symbol = |name: &CStr| libc::dlsym(library, name.as_ptr());
        let (create, decode, destroy) =
            (symbol(c"opus_decoder_create"), symbol(c"opus_decode"), symbol(c"opus_decoder_destroy"));
        if create.is_null() || decode.is_null() || destroy.is_null() { return None; }
        Some(Opus {
            create: std::mem::transmute::<*mut c_void, Create>(create),
            decode: std::mem::transmute::<*mut c_void, Decode>(decode),
            destroy: std::mem::transmute::<*mut c_void, Destroy>(destroy),
        })
    }).as_ref()
}

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
    opus: *mut c_void,
    pre_skip_left: usize,
    decoded: Vec<i16>,
}

impl Drop for Renderer {
    fn drop(&mut self) {
        if !self.opus.is_null() {
            if let Some(library) = opus() { unsafe { (library.destroy)(self.opus) } }
        }
    }
}

unsafe fn renderer<'a>(handle: jlong) -> &'a mut Renderer { &mut *(handle as *mut Renderer) }

fn direct(env: *mut JNIEnv, buffer: jobject) -> Option<&'static mut [u8]> {
    Env(env).direct_buffer(buffer).map(|(address, capacity)| unsafe { std::slice::from_raw_parts_mut(address, capacity) })
}

/// 0 when Opus was asked for and cannot be decoded here; the renderer then keeps MediaCodec.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_pcmCreate(
    _env: *mut JNIEnv, _class: jclass, sample_rate: jint, channels: jint, voice_filter: jboolean, opus_decoder: jboolean,
) -> jlong {
    if sample_rate <= 0 || !(1..=2).contains(&channels) { return 0; }
    let mut state = Box::new(Renderer {
        sample_rate,
        channels: channels as usize,
        filter: (voice_filter != 0).then(|| VoiceFilter::new(sample_rate, channels as usize, DEFAULT_CUTOFF_HZ)),
        opus: std::ptr::null_mut(),
        pre_skip_left: 0,
        decoded: Vec::new(),
    });
    if opus_decoder != 0 {
        let Some(library) = opus() else { return 0 };
        let mut error: c_int = 0;
        state.opus = (library.create)(sample_rate, channels, &mut error);
        if state.opus.is_null() || error != 0 { return 0; }
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
/// written, or a negative libopus error (-100 for bad arguments or a buffer that is not direct).
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_pcmDecodeOpus(
    env: *mut JNIEnv, _class: jclass, handle: jlong, packet_handle: jlong, offset: jint, length: jint, out: jobject,
) -> jint {
    if handle == 0 || packet_handle == 0 || offset < 0 || length <= 0 { return -100; }
    let state = renderer(handle);
    let (Some(library), Some(out)) = (opus(), direct(env, out)) else { return -100 };
    if state.opus.is_null() { return -100; }
    let source = packet::bytes(packet_handle);
    let (offset, length) = (offset as usize, length as usize);
    if offset > source.len() || length > source.len() - offset { return -100; }
    let frames = (library.decode)(
        state.opus, source.as_ptr().add(offset), length as i32,
        state.decoded.as_mut_ptr(), (state.decoded.len() / state.channels) as c_int, 0,
    );
    if frames < 0 { return frames; }
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
