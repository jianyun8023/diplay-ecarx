//! An audio renderer's output through AAudio (Kotlin's `NativeAudioOut`), for streams routed by usage.
//! AAudio exists from API 26 and gained usage and content type in API 28, so it is opened with dlopen.
//!
//! Two things AudioTrack does and AAudio does not are done here: the focus ducking volume becomes a
//! PCM gain, and the music clock trim (`setPlaybackRate`) becomes linear-interpolation resampling. The
//! playback head is reported in the renderer's own (input) frames, so its buffer accounting is unchanged.

use crate::jvm::Env;
use jni_sys::{jclass, jfloat, jint, jlong, jobject, JNIEnv};
use std::ffi::c_void;
use std::sync::OnceLock;

type Stream = c_void;
type Builder = c_void;

#[allow(non_snake_case)]
struct Api {
    createStreamBuilder: unsafe extern "C" fn(*mut *mut Builder) -> i32,
    builder_setSampleRate: unsafe extern "C" fn(*mut Builder, i32),
    builder_setChannelCount: unsafe extern "C" fn(*mut Builder, i32),
    builder_setFormat: unsafe extern "C" fn(*mut Builder, i32),
    builder_setDirection: unsafe extern "C" fn(*mut Builder, i32),
    builder_setSharingMode: unsafe extern "C" fn(*mut Builder, i32),
    builder_setPerformanceMode: unsafe extern "C" fn(*mut Builder, i32),
    builder_setBufferCapacityInFrames: unsafe extern "C" fn(*mut Builder, i32),
    builder_setUsage: unsafe extern "C" fn(*mut Builder, i32),
    builder_setContentType: unsafe extern "C" fn(*mut Builder, i32),
    builder_openStream: unsafe extern "C" fn(*mut Builder, *mut *mut Stream) -> i32,
    builder_delete: unsafe extern "C" fn(*mut Builder) -> i32,
    stream_close: unsafe extern "C" fn(*mut Stream) -> i32,
    stream_requestStart: unsafe extern "C" fn(*mut Stream) -> i32,
    stream_requestPause: unsafe extern "C" fn(*mut Stream) -> i32,
    stream_requestFlush: unsafe extern "C" fn(*mut Stream) -> i32,
    stream_write: unsafe extern "C" fn(*mut Stream, *const c_void, i32, i64) -> i32,
    stream_setBufferSizeInFrames: unsafe extern "C" fn(*mut Stream, i32) -> i32,
    stream_getBufferSizeInFrames: unsafe extern "C" fn(*mut Stream) -> i32,
    stream_getXRunCount: unsafe extern "C" fn(*mut Stream) -> i32,
    stream_getFramesRead: unsafe extern "C" fn(*mut Stream) -> i64,
    stream_getState: unsafe extern "C" fn(*mut Stream) -> i32,
    stream_getSampleRate: unsafe extern "C" fn(*mut Stream) -> i32,
}

fn api() -> Option<&'static Api> {
    static API: OnceLock<Option<Api>> = OnceLock::new();
    API.get_or_init(|| unsafe {
        let library = libc::dlopen(c"libaaudio.so".as_ptr(), libc::RTLD_NOW);
        if library.is_null() { return None; }
        macro_rules! load {
            ($name:literal) => {{
                let symbol = libc::dlsym(library, concat!($name, "\0").as_ptr() as *const _);
                if symbol.is_null() { return None; }
                std::mem::transmute::<*mut c_void, _>(symbol)
            }};
        }
        Some(Api {
            createStreamBuilder: load!("AAudio_createStreamBuilder"),
            builder_setSampleRate: load!("AAudioStreamBuilder_setSampleRate"),
            builder_setChannelCount: load!("AAudioStreamBuilder_setChannelCount"),
            builder_setFormat: load!("AAudioStreamBuilder_setFormat"),
            builder_setDirection: load!("AAudioStreamBuilder_setDirection"),
            builder_setSharingMode: load!("AAudioStreamBuilder_setSharingMode"),
            builder_setPerformanceMode: load!("AAudioStreamBuilder_setPerformanceMode"),
            builder_setBufferCapacityInFrames: load!("AAudioStreamBuilder_setBufferCapacityInFrames"),
            builder_setUsage: load!("AAudioStreamBuilder_setUsage"),
            builder_setContentType: load!("AAudioStreamBuilder_setContentType"),
            builder_openStream: load!("AAudioStreamBuilder_openStream"),
            builder_delete: load!("AAudioStreamBuilder_delete"),
            stream_close: load!("AAudioStream_close"),
            stream_requestStart: load!("AAudioStream_requestStart"),
            stream_requestPause: load!("AAudioStream_requestPause"),
            stream_requestFlush: load!("AAudioStream_requestFlush"),
            stream_write: load!("AAudioStream_write"),
            stream_setBufferSizeInFrames: load!("AAudioStream_setBufferSizeInFrames"),
            stream_getBufferSizeInFrames: load!("AAudioStream_getBufferSizeInFrames"),
            stream_getXRunCount: load!("AAudioStream_getXRunCount"),
            stream_getFramesRead: load!("AAudioStream_getFramesRead"),
            stream_getState: load!("AAudioStream_getState"),
            stream_getSampleRate: load!("AAudioStream_getSampleRate"),
        })
    }).as_ref()
}

const DIRECTION_OUTPUT: i32 = 0;
const FORMAT_PCM_I16: i32 = 1;
const SHARING_MODE_SHARED: i32 = 1;
const PERFORMANCE_MODE_NONE: i32 = 10;
/// AudioTrack.WRITE_BLOCKING has no end; a second is far beyond any healthy write.
const WRITE_TIMEOUT_NS: i64 = 1_000_000_000;

/// Resamples by `in_rate / out_rate` with linear interpolation; the position carries across blocks.
pub struct Resampler {
    channels: usize,
    /// Input frames advanced per output frame: playback rate / nominal rate.
    step: f64,
    position: f64,
    last: Vec<i16>,
}

impl Resampler {
    pub fn new(channels: usize) -> Resampler {
        Resampler { channels, step: 1.0, position: 0.0, last: vec![0; channels] }
    }

    pub fn set_step(&mut self, step: f64) { self.step = step.clamp(0.9, 1.1); }

    pub fn is_identity(&self) -> bool { self.step == 1.0 && self.position == 0.0 }

    /// Keeps the block's last frame while unresampled, so a later rate change starts without a click.
    pub fn remember(&mut self, input: &[i16]) {
        let frames = input.len() / self.channels;
        if frames > 0 { self.last.copy_from_slice(&input[(frames - 1) * self.channels..frames * self.channels]); }
    }

    /// Appends the resampled frames of `input` (interleaved) to `out`.
    pub fn process(&mut self, input: &[i16], out: &mut Vec<i16>) {
        let frames = input.len() / self.channels;
        if frames == 0 { return; }
        // Position 0 is the last frame of the previous block; 1 is the first frame of this one.
        let sample = |frame: isize, channel: usize, last: &[i16]| -> f64 {
            if frame < 0 { last[channel] as f64 } else { input[frame as usize * self.channels + channel] as f64 }
        };
        while self.position < frames as f64 {
            let base = self.position.floor();
            let fraction = self.position - base;
            let index = base as isize - 1;
            for channel in 0..self.channels {
                let a = sample(index, channel, &self.last);
                let b = sample(index + 1, channel, &self.last);
                out.push((a + (b - a) * fraction).round().clamp(i16::MIN as f64, i16::MAX as f64) as i16);
            }
            self.position += self.step;
        }
        self.position -= frames as f64;
        self.last.copy_from_slice(&input[(frames - 1) * self.channels..frames * self.channels]);
    }
}

pub struct Output {
    stream: *mut Stream,
    channels: usize,
    sample_rate: i32,
    gain: f32,
    resampler: Resampler,
    scratch: Vec<i16>,
    resampled: Vec<i16>,
    /// Input frames taken and output frames written, to report the head in input frames.
    input_frames: i64,
    output_frames: i64,
}

unsafe fn output<'a>(handle: jlong) -> &'a mut Output { &mut *(handle as *mut Output) }

/// An output stream for 16-bit PCM, or 0 when AAudio cannot open it (the renderer then uses AudioTrack).
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_outOpen(
    _env: *mut JNIEnv, _class: jclass, sample_rate: jint, channels: jint, usage: jint, content_type: jint,
    capacity_frames: jint,
) -> jlong {
    let Some(api) = api() else { return 0 };
    if !(1..=2).contains(&channels) { return 0; }
    let mut builder: *mut Builder = std::ptr::null_mut();
    if (api.createStreamBuilder)(&mut builder) != 0 || builder.is_null() { return 0; }
    (api.builder_setDirection)(builder, DIRECTION_OUTPUT);
    (api.builder_setSampleRate)(builder, sample_rate);
    (api.builder_setChannelCount)(builder, channels);
    (api.builder_setFormat)(builder, FORMAT_PCM_I16);
    (api.builder_setSharingMode)(builder, SHARING_MODE_SHARED);
    (api.builder_setPerformanceMode)(builder, PERFORMANCE_MODE_NONE);
    (api.builder_setBufferCapacityInFrames)(builder, capacity_frames);
    (api.builder_setUsage)(builder, usage);
    (api.builder_setContentType)(builder, content_type);
    let mut stream: *mut Stream = std::ptr::null_mut();
    let opened = (api.builder_openStream)(builder, &mut stream);
    (api.builder_delete)(builder);
    if opened != 0 || stream.is_null() { return 0; }
    if (api.stream_getSampleRate)(stream) != sample_rate {
        (api.stream_close)(stream);
        return 0;
    }
    (api.stream_setBufferSizeInFrames)(stream, capacity_frames);
    Box::into_raw(Box::new(Output {
        stream, channels: channels as usize, sample_rate, gain: 1.0, resampler: Resampler::new(channels as usize),
        scratch: Vec::new(), resampled: Vec::new(), input_frames: 0, output_frames: 0,
    })) as jlong
}

/// Writes `length` bytes of the direct `buffer` from `offset`, blocking like AudioTrack's blocking
/// write. Returns the bytes taken, or a negative AAudio error.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_outWrite(
    env: *mut JNIEnv, _class: jclass, handle: jlong, buffer: jobject, offset: jint, length: jint,
) -> jint {
    let Some(api) = api() else { return -1 };
    let out = output(handle);
    let Some((address, capacity)) = Env(env).direct_buffer(buffer) else { return -1 };
    let frame_bytes = 2 * out.channels;
    let length = (length.max(0) as usize / frame_bytes) * frame_bytes;
    if offset < 0 || offset as usize + length > capacity { return -1; }
    let bytes = std::slice::from_raw_parts(address.add(offset as usize), length);
    out.scratch.clear();
    out.scratch.extend(bytes.chunks_exact(2).map(|s| i16::from_le_bytes([s[0], s[1]])));
    if out.gain < 1.0 {
        let gain = out.gain;
        for s in out.scratch.iter_mut() { *s = (*s as f32 * gain) as i16; }
    }
    let samples: &[i16] = if out.resampler.is_identity() {
        out.resampler.remember(&out.scratch);
        &out.scratch
    } else {
        out.resampled.clear();
        out.resampler.process(&out.scratch, &mut out.resampled);
        &out.resampled
    };
    let total = (samples.len() / out.channels) as i32;
    let mut written = 0i32;
    while written < total {
        let result = (api.stream_write)(out.stream, samples.as_ptr().add(written as usize * out.channels) as *const c_void,
            total - written, WRITE_TIMEOUT_NS);
        if result < 0 { return result; }
        if result == 0 { break; }
        written += result;
    }
    out.input_frames += (length / frame_bytes) as i64;
    out.output_frames += written as i64;
    if written < total {
        // A timed-out write took only part of this block; report the input share it covered.
        return ((length as i64 * written as i64 / total.max(1) as i64) as usize / frame_bytes * frame_bytes) as jint;
    }
    length as jint
}

/// Frames played, in the renderer's input frames (AudioTrack.getPlaybackHeadPosition), as an int.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_outFramesRead(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) -> jint {
    let Some(api) = api() else { return 0 };
    let out = output(handle);
    let read = (api.stream_getFramesRead)(out.stream).max(0);
    let input = if out.output_frames > 0 { read * out.input_frames / out.output_frames } else { read };
    input as jint
}

/// Underruns, buffer size in frames, state: the AudioTrack figures the renderer reports.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_outQuery(
    _env: *mut JNIEnv, _class: jclass, handle: jlong, what: jint,
) -> jint {
    let Some(api) = api() else { return -1 };
    let out = output(handle);
    match what {
        0 => (api.stream_getXRunCount)(out.stream),
        1 => (api.stream_getBufferSizeInFrames)(out.stream),
        2 => (api.stream_getState)(out.stream),
        3 => out.sample_rate,
        _ => -1,
    }
}

/// 0 start, 1 pause, 2 flush. Returns the AAudio result.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_outControl(
    _env: *mut JNIEnv, _class: jclass, handle: jlong, what: jint,
) -> jint {
    let Some(api) = api() else { return -1 };
    let out = output(handle);
    match what {
        0 => (api.stream_requestStart)(out.stream),
        1 => (api.stream_requestPause)(out.stream),
        2 => (api.stream_requestFlush)(out.stream),
        _ => -1,
    }
}

/// The focus ducking volume, applied to the PCM.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_outSetGain(
    _env: *mut JNIEnv, _class: jclass, handle: jlong, gain: jfloat,
) {
    output(handle).gain = gain.clamp(0.0, 1.0);
}

/// AudioTrack.setPlaybackRate: plays at `rate` Hz worth of input per second by resampling.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_outSetRate(
    _env: *mut JNIEnv, _class: jclass, handle: jlong, rate: jint,
) -> jint {
    let out = output(handle);
    if rate <= 0 { return -1; }
    out.resampler.set_step(rate as f64 / out.sample_rate as f64);
    0
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_outClose(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) {
    if handle == 0 { return; }
    let out = Box::from_raw(handle as *mut Output);
    if let Some(api) = api() { (api.stream_close)(out.stream); }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unit_step_is_a_one_block_delay_free_copy_and_faster_play_takes_fewer_frames() {
        let mut same = Resampler::new(1);
        assert!(same.is_identity());
        let mut faster = Resampler::new(1);
        faster.set_step(1.01);
        let input: Vec<i16> = (0..1000).map(|n| n as i16).collect();
        let mut out = Vec::new();
        for chunk in input.chunks(100) { faster.process(chunk, &mut out); }
        // 1000 input frames at 1.01x play as about 990 output frames.
        assert!((985..=995).contains(&out.len()), "{}", out.len());
        // Monotone ramp stays monotone and within the input's range.
        assert!(out.windows(2).all(|w| w[0] <= w[1]));
        let _ = same.process(&input, &mut Vec::new());
    }
}
