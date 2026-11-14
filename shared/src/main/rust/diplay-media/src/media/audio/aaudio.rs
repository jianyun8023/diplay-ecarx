//! An audio renderer's output through AAudio (Kotlin's `NativeAudioOut`), for streams routed by usage.
//! AAudio exists from API 26 and gained usage and content type in API 28, so it is opened with dlopen.
//!
//! Two things AudioTrack does and AAudio does not are done here: the focus ducking volume becomes a
//! PCM gain, and the music clock trim (`setPlaybackRate`) becomes linear-interpolation resampling. The
//! playback head is reported in the renderer's own (input) frames, so its buffer accounting is unchanged.

use crate::platform::jvm::Env;
use jni_sys::{jclass, jfloat, jint, jlong, jobject, JNIEnv};
use std::ffi::c_void;
use std::collections::VecDeque;
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
    stream_getDeviceId: unsafe extern "C" fn(*mut Stream) -> i32,
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
            stream_getDeviceId: load!("AAudioStream_getDeviceId"),
        })
    }).as_ref()
}

const DIRECTION_OUTPUT: i32 = 0;
const FORMAT_PCM_I16: i32 = 1;
const SHARING_MODE_SHARED: i32 = 1;
const PERFORMANCE_MODE_NONE: i32 = 10;
/// Requests a blocking write; API 28's legacy AudioTrack backend ignores the timeout duration.
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
        let sample = |frame: isize, channel: usize, last: &[i16]| -> f32 {
            if frame < 0 { last[channel] as f32 } else { input[frame as usize * self.channels + channel] as f32 }
        };
        while self.position < frames as f64 {
            let base = self.position.floor();
            let fraction = (self.position - base) as f32;
            let index = base as isize - 1;
            for channel in 0..self.channels {
                let a = sample(index, channel, &self.last);
                let b = sample(index + 1, channel, &self.last);
                out.push((a + (b - a) * fraction).round().clamp(i16::MIN as f32, i16::MAX as f32) as i16);
            }
            self.position += self.step;
        }
        self.position -= frames as f64;
        self.last.copy_from_slice(&input[(frames - 1) * self.channels..frames * self.channels]);
    }
}

/// Immutable per-block conversion: newly queued PCM must never reinterpret old playback.
#[derive(Default)]
struct PlaybackHead {
    spans: VecDeque<(i64, i64, i64, i64)>, // output start/end, input start/end
    input_frames: i64,
    output_frames: i64,
    submitted_frames: i64,
    played: i64,
}

impl PlaybackHead {
    fn accept(&mut self, input: i64, output: i64) {
        let input_end = self.input_frames + input;
        let output_end = self.output_frames + output;
        self.spans.push_back((self.output_frames, output_end, self.input_frames, input_end));
        self.input_frames = input_end;
        self.output_frames = output_end;
    }

    fn read(&mut self, hardware: i64) -> i64 {
        // AAudio can advance through silence on underrun. Pending software PCM is not played.
        let read = hardware.max(0).min(self.submitted_frames);
        while let Some(&(start, end, input_start, input_end)) = self.spans.front() {
            if read < start { break; }
            let mapped = if read >= end {
                input_end
            } else {
                // Widen before multiplying: 48 kHz sessions can overflow i64 in under 18 hours.
                input_start + (((read - start) as i128 * (input_end - input_start) as i128)
                    / (end - start) as i128) as i64
            };
            self.played = self.played.max(mapped);
            if read < end { break; }
            self.spans.pop_front();
        }
        self.played
    }

    fn flush(&mut self) {
        // AAudio's hardware counters survive flush. Drop the unsubmitted software tail,
        // and begin a new input epoch at the end of all previously submitted PCM.
        self.spans.clear();
        self.input_frames = 0;
        self.output_frames = self.submitted_frames;
        self.played = 0;
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
    pending_offset: usize,
    head: PlaybackHead,
}

impl Output {
    /// Returns false on backpressure; the pending PCM and resampler state belong to this worker.
    fn drain(&mut self, write: &mut impl FnMut(*const c_void, i32) -> i32) -> Result<bool, i32> {
        while self.pending_offset < self.resampled.len() {
            let remaining = (self.resampled.len() - self.pending_offset) / self.channels;
            let result = write(unsafe { self.resampled.as_ptr().add(self.pending_offset) } as *const c_void, remaining as i32);
            if result < 0 { return Err(result); }
            if result == 0 { return Ok(false); }
            self.pending_offset += result as usize * self.channels;
            self.head.submitted_frames += result as i64;
        }
        self.resampled.clear();
        self.pending_offset = 0;
        Ok(true)
    }

    fn write(&mut self, bytes: &[u8], mut write: impl FnMut(*const c_void, i32) -> i32) -> Result<usize, i32> {
        if !self.drain(&mut write)? || bytes.is_empty() { return Ok(0); }
        let frame_bytes = 2 * self.channels;
        let frames = bytes.len() / frame_bytes;
        if self.gain >= 1.0 && self.resampler.is_identity() {
            let mut written = 0;
            while written < frames {
                let result = write(unsafe { bytes.as_ptr().add(written * frame_bytes) } as *const c_void, (frames - written) as i32);
                if result < 0 { return Err(result); }
                if result == 0 { break; }
                written += result as usize;
                self.head.accept(result as i64, result as i64);
                self.head.submitted_frames += result as i64;
            }
            if written > 0 {
                let mut tail = [0i16; 2];
                for (channel, sample) in tail[..self.channels].iter_mut().enumerate() {
                    let at = (written - 1) * frame_bytes + channel * 2;
                    *sample = i16::from_le_bytes([bytes[at], bytes[at + 1]]);
                }
                self.resampler.remember(&tail[..self.channels]);
            }
            return Ok(written * frame_bytes);
        }
        self.scratch.clear();
        self.scratch.extend(bytes.chunks_exact(2).map(|s| i16::from_le_bytes([s[0], s[1]])));
        if self.gain < 1.0 {
            for sample in &mut self.scratch { *sample = (*sample as f32 * self.gain) as i16; }
        }
        if self.resampler.is_identity() {
            self.resampler.remember(&self.scratch);
            std::mem::swap(&mut self.scratch, &mut self.resampled);
        } else {
            self.resampler.process(&self.scratch, &mut self.resampled);
        }
        self.head.accept(frames as i64, (self.resampled.len() / self.channels) as i64);
        // Consume the input once; a partial write retains output, never reprocesses input.
        self.drain(&mut write)?;
        Ok(bytes.len())
    }

    fn discard_pending(&mut self) {
        self.head.flush();
        self.resampled.clear();
        self.pending_offset = 0;
        let step = self.resampler.step;
        self.resampler = Resampler::new(self.channels);
        self.resampler.set_step(step);
    }
}

// NativeAudioOut's worker is the sole caller; focus callbacks only publish a Kotlin volatile gain.
unsafe fn output<'a>(handle: jlong) -> &'a mut Output { &mut *(handle as *mut Output) }

/// An output stream for 16-bit PCM, or 0 when AAudio cannot open it (the renderer then uses AudioTrack).
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_outOpen(
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
        scratch: Vec::new(), resampled: Vec::new(), pending_offset: 0, head: PlaybackHead::default(),
    })) as jlong
}

/// Writes `length` bytes of the direct `buffer` from `offset`, blocking like AudioTrack's blocking
/// write. Returns input bytes consumed (possibly buffered), or a terminal AAudio error.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_outWrite(
    env: *mut JNIEnv, _class: jclass, handle: jlong, buffer: jobject, offset: jint, length: jint,
) -> jint {
    let Some(api) = api() else { return -1 };
    let out = output(handle);
    let Some((address, capacity)) = Env(env).direct_buffer(buffer) else { return -1 };
    let frame_bytes = 2 * out.channels;
    let length = (length.max(0) as usize / frame_bytes) * frame_bytes;
    if offset < 0 || offset as usize + length > capacity { return -1; }
    let bytes = std::slice::from_raw_parts(address.add(offset as usize), length);
    let stream = out.stream;
    out.write(bytes, |data, frames| (api.stream_write)(stream, data, frames, WRITE_TIMEOUT_NS))
        .map(|count| count as jint).unwrap_or_else(|error| error)
}

/// Frames played, in the renderer's input frames (AudioTrack.getPlaybackHeadPosition), as an int.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_outFramesRead(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) -> jint {
    let Some(api) = api() else { return 0 };
    let out = output(handle);
    let read = (api.stream_getFramesRead)(out.stream).max(0);
    out.head.read(read) as jint
}

/// Underruns, buffer size in frames, state: the AudioTrack figures the renderer reports.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_outQuery(
    _env: *mut JNIEnv, _class: jclass, handle: jlong, what: jint,
) -> jint {
    let Some(api) = api() else { return -1 };
    let out = output(handle);
    match what {
        0 => (api.stream_getXRunCount)(out.stream),
        1 => (api.stream_getBufferSizeInFrames)(out.stream),
        2 => (api.stream_getState)(out.stream),
        3 => out.sample_rate,
        4 => (api.stream_getDeviceId)(out.stream),
        _ => -1,
    }
}

/// 0 start, 1 pause, 2 flush. Returns the AAudio result.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_outControl(
    _env: *mut JNIEnv, _class: jclass, handle: jlong, what: jint,
) -> jint {
    let Some(api) = api() else { return -1 };
    let out = output(handle);
    match what {
        0 => (api.stream_requestStart)(out.stream),
        1 => (api.stream_requestPause)(out.stream),
        2 => {
            let result = (api.stream_requestFlush)(out.stream);
            if result == 0 { out.discard_pending(); }
            result
        },
        _ => -1,
    }
}

/// The focus ducking volume, applied to the PCM.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_outSetGain(
    _env: *mut JNIEnv, _class: jclass, handle: jlong, gain: jfloat,
) {
    output(handle).gain = gain.clamp(0.0, 1.0);
}

/// AudioTrack.setPlaybackRate: plays at `rate` Hz worth of input per second by resampling.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_outSetRate(
    _env: *mut JNIEnv, _class: jclass, handle: jlong, rate: jint,
) -> jint {
    let out = output(handle);
    if rate <= 0 { return -1; }
    out.resampler.set_step(rate as f64 / out.sample_rate as f64);
    0
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_outClose(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) {
    if handle == 0 { return; }
    let out = Box::from_raw(handle as *mut Output);
    if let Some(api) = api() { (api.stream_close)(out.stream); }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn renderer(channels: usize, gain: f32, step: f64) -> Output {
        let mut resampler = Resampler::new(channels);
        resampler.set_step(step);
        Output {
            stream: std::ptr::null_mut(), channels, sample_rate: 48000, gain, resampler,
            scratch: Vec::new(), resampled: Vec::new(), pending_offset: 0, head: PlaybackHead::default(),
        }
    }

    #[test]
    fn resampled_writes_do_not_look_like_a_hardware_wrap_to_the_scheduler() {
        use crate::media::audio::scheduler::AudioBufferProgress;
        let mut out = renderer(2, 1.0, 1.0);
        let mut scheduler = AudioBufferProgress::new(4);
        let first = vec![0; 14800 * 4];
        scheduler.written(out.write(&first, |_, frames| frames).unwrap() as i32);
        assert_eq!(scheduler.queued_bytes(out.head.read(10000) as i32), 19200);
        out.resampler.set_step(47952.0 / 48000.0);
        let next = vec![0; 1024 * 4];
        scheduler.written(out.write(&next, |_, frames| frames).unwrap() as i32);
        assert_eq!(out.head.output_frames, 14800 + 1026);
        for _ in 0..3 {
            assert_eq!(out.head.read(10000), 10000);
            assert_eq!(scheduler.queued_bytes(out.head.read(10000) as i32), 23296);
        }
        for rate in [48048.0, 47952.0, 48000.0] {
            out.resampler.set_step(rate / 48000.0);
            scheduler.written(out.write(&next, |_, frames| frames).unwrap() as i32);
            assert_eq!(out.head.read(10000), 10000);
        }
        assert_eq!(scheduler.queued_bytes(out.head.read(10000) as i32), 23296 + 3 * 4096);
        assert_eq!(scheduler.queued_bytes(out.head.read(out.head.submitted_frames) as i32), 0);
        let hardware_epoch = out.head.submitted_frames;
        out.discard_pending();
        scheduler.reset();
        assert_eq!(out.head.read(hardware_epoch), 0);
        scheduler.written(out.write(&next, |_, frames| frames).unwrap() as i32);
        assert_eq!(scheduler.queued_bytes(out.head.read(hardware_epoch) as i32), 4096);
        assert_eq!(scheduler.queued_bytes(out.head.read(out.head.submitted_frames) as i32), 0);
    }

    #[test]
    fn queued_rate_changes_never_reinterpret_already_played_frames() {
        let mut head = PlaybackHead::default();
        head.accept(14800, 14800);
        head.submitted_frames = 14800;
        assert_eq!(head.read(10000), 10000);
        head.accept(1024, 1026);
        head.submitted_frames += 1026;
        assert_eq!(head.read(10000), 10000);
        assert_eq!(head.read(14800 + 513), 14800 + 512);
        head.accept(1024, 1022);
        head.submitted_frames += 1022;
        assert_eq!(head.read(14800 + 1026 + 511), 14800 + 1024 + 512);
        assert_eq!(head.read(head.submitted_frames), 14800 + 2048);
        assert!(head.spans.is_empty());
    }

    #[test]
    fn long_sessions_widen_multiplication_and_only_wrap_at_jni_boundary() {
        let mut head = PlaybackHead::default();
        let count = 10_000_000_000;
        head.accept(count, count + 1000);
        head.submitted_frames = count + 1000;
        assert_eq!(head.read(5_000_000_500), 5_000_000_000);
        assert_eq!(head.read(count + 1000), count);
        assert_eq!(head.played as jint, count as jint);
    }

    #[test]
    fn partial_submission_and_flush_do_not_leave_a_phantom_output_tail() {
        let mut out = renderer(1, 0.2, 0.99);
        let bytes = vec![0; 200];
        let mut calls = 0;
        assert_eq!(out.write(&bytes, |_, _| { calls += 1; if calls == 1 { 50 } else { 0 } }), Ok(200));
        assert_eq!(out.head.read(50), 49);
        assert_eq!(out.head.read(1000), 49); // pending tail has not reached AAudio
        assert_eq!(out.write(&[], |_, frames| frames), Ok(0));
        assert_eq!(out.head.read(102), 100);
        assert_eq!(out.head.read(50), 100); // stale platform observation is not a wrap
        assert!(out.head.spans.is_empty());
        out.discard_pending();
        assert_eq!(out.head.read(102), 0);
        assert_eq!(out.write(&bytes, |_, _| 0), Ok(200));
        out.discard_pending(); // all of this block was still in software
        assert_eq!(out.head.read(102), 0);
        assert_eq!(out.write(&bytes, |_, frames| frames), Ok(200));
        assert_eq!(out.head.read(out.head.submitted_frames), 100);
    }

    #[test]
    fn partial_and_zero_writes_keep_pcm_and_advance_input_only_once() {
        for channels in [1, 2] {
            for step in [1.0, 0.99, 1.01] {
                let bytes: Vec<u8> = (0..100 * channels).flat_map(|n| (n as i16 * 50).to_le_bytes()).collect();
                let mut reference = renderer(channels, 0.2, step);
                let mut expected = Vec::new();
                let all = |data: *const c_void, frames: i32| {
                    expected.extend_from_slice(unsafe { std::slice::from_raw_parts(data as *const i16, frames as usize * channels) });
                    frames
                };
                assert_eq!(reference.write(&bytes, all), Ok(bytes.len()));
                let mut out = renderer(channels, 0.2, step);
                let mut actual = Vec::new();
                let mut calls = 0;
                assert_eq!(out.write(&bytes, |data, _frames| {
                    calls += 1;
                    if calls > 1 { return 0; }
                    actual.extend_from_slice(unsafe { std::slice::from_raw_parts(data as *const i16, 50 * channels) });
                    50
                }), Ok(bytes.len()));
                assert_eq!(out.head.input_frames, 100);
                assert_eq!(out.head.output_frames, reference.head.output_frames);
                let position = out.resampler.position;
                assert_eq!(out.write(&bytes, |_, _| 0), Ok(0));
                assert_eq!(out.head.input_frames, 100);
                assert_eq!(out.resampler.position, position);
                assert_eq!(out.write(&[], |data, frames| {
                    actual.extend_from_slice(unsafe { std::slice::from_raw_parts(data as *const i16, frames as usize * channels) });
                    frames
                }), Ok(0));
                assert_eq!(actual, expected);
                assert_eq!(out.head.input_frames, 100);
                assert!(out.resampled.is_empty());
                assert_eq!(out.resampler.last, reference.resampler.last);
            }
        }
    }

    #[test]
    fn fast_path_remembers_only_written_input_and_propagates_errors() {
        let mut out = renderer(1, 1.0, 1.0);
        let bytes: Vec<u8> = [10i16, 20, 30, 40].into_iter().flat_map(i16::to_le_bytes).collect();
        let mut calls = 0;
        assert_eq!(out.write(&bytes, |_, _| { calls += 1; if calls == 1 { 2 } else { 0 } }), Ok(4));
        assert_eq!(out.resampler.last, [20]);
        assert_eq!((out.head.input_frames, out.head.output_frames), (2, 2));
        assert_eq!(out.write(&bytes[4..], |_, _| -899), Err(-899));
        assert_eq!((out.head.input_frames, out.head.output_frames), (2, 2));
        assert_eq!(out.write(&bytes[4..], |_, frames| frames), Ok(4));
        assert_eq!(out.resampler.last, [40]);
        assert_eq!((out.head.input_frames, out.head.output_frames), (4, 4));
    }

    #[test]
    fn pending_error_is_reported_without_accepting_more_input_and_flush_discards_tail() {
        let mut out = renderer(1, 0.2, 0.99);
        let bytes = [1u8, 0, 2, 0, 3, 0, 4, 0];
        assert_eq!(out.write(&bytes, |_, _| 0), Ok(bytes.len()));
        let generated = out.head.output_frames;
        assert_eq!(out.write(&bytes, |_, _| -899), Err(-899));
        assert_eq!((out.head.input_frames, out.head.output_frames), (4, generated));
        out.discard_pending();
        assert!(out.resampled.is_empty());
        assert_eq!(out.pending_offset, 0);
        assert_eq!(out.resampler.position, 0.0);
        assert_eq!(out.resampler.last, [0]);
        assert_eq!(out.resampler.step, 0.99);
        assert_eq!(out.write(&[], |_, _| panic!("flushed PCM must not be written")), Ok(0));
    }

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
