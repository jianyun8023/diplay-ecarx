//! One screen stream's decode loop (driven by Kotlin's `VideoDecoder`): frames from the receiver go
//! into an NDK MediaCodec running in async mode, and decoded pictures are released to the surface,
//! paced as `VideoDecoder` paces them. Nothing polls the codec.
//!
//! The codec's callbacks run on the same looper as the codec itself, so a codec call made from a
//! callback waits on its own thread forever. Callbacks and the receiver therefore only queue work;
//! one feeder thread per pipe makes every input and output call, outside the lock.
//!
//! Kotlin keeps the decisions: which decoder and format to try, where the codec renders, when to
//! rebuild it. This side runs the frames: the reference chain (no frame before a keyframe), the input
//! backlog, pacing, and the counters. It asks Kotlin for a decoder, a keyframe or a rebuild through
//! events, which a per-pipe thread delivers, so MediaCodec's own callback thread never calls Java.

use crate::annexb::{FLAG_ANNEX_B, FLAG_RANDOM_ACCESS_H264, FLAG_RANDOM_ACCESS_H265};
use crate::jvm::{Attached, Env};
use crate::ndk::*;
use crate::pacing::{is_obsolete, BacklogRecovery, FramePacer, PacingDelay};
use crate::packet;
use crate::stats::{clock, VideoInputStats};
use jni_sys::{jboolean, jbyteArray, jclass, jint, jintArray, jlong, jlongArray, jmethodID, jobject, jobjectArray, jstring, jvalue, JNIEnv};
use std::collections::VecDeque;
use std::ffi::{c_char, c_void, CStr, CString};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Condvar, Mutex};

/// Events for Kotlin (`NativeVideoCallbacks.onEvent`).
const EVENT_REQUEST_KEYFRAME: i32 = 1;
const EVENT_REPORT: i32 = 2;
const EVENT_NEED_DECODER: i32 = 3;
const EVENT_RELEASE_DECODER: i32 = 4;
const EVENT_CODEC_ERROR: i32 = 5;
const EVENT_INPUT_STALLED: i32 = 6;
const EVENT_FIRST_FRAME: i32 = 7;
const EVENT_LOG: i32 = 8;

const MAX_FRAMES: usize = 60;
const MAX_BYTES: usize = 8 * 1024 * 1024;
const SLOTS: usize = 64;
const PAUSE_HELD_FRAMES: usize = 3;
const STILL_GAP_NS: i64 = 500_000_000;
const SENDER_PAUSE_NS: i64 = 120_000_000;
const MAX_PACING_AHEAD_NS: i64 = 1_000_000_000;
const INPUT_STALL_NS: i64 = 500_000_000;
const KEYFRAME_INTERVAL_NS: i64 = 1_000_000_000;
const DECODE_SAMPLES: usize = 1024;

struct Frame { handle: jlong, size: usize, flags: i32, received_ns: i64, local_ns: i64 }

/// What the feeder does with the codec, decided under the lock and carried out outside it.
enum Action {
    Feed { index: usize, handle: jlong, size: usize, presentation_us: i64 },
    Render { index: usize, at_ns: Option<i64> },
    Drop { index: usize },
}

#[derive(Clone, Copy, Default)]
struct Slot { presentation_us: i64, queued_ns: i64, paced: bool, before_pause: bool }

#[derive(Default)]
struct Stats {
    rendered: i64,
    recoveries: i64,
    late: i64,
    pacing_delay_ns: i64,
    held_samples: i64,
    held_max_ns: i64,
    decode_micros: Vec<i32>,
}

struct Inner {
    codec: *mut AMediaCodec,
    window: *mut ANativeWindow,
    hevc: bool,
    /// Kotlin has a configuration and a surface: frames are kept for a decoder.
    accepting: bool,
    /// Nothing is fed: recovery asked Kotlin to release this codec, or a new configuration is pending
    /// (`pipeHold`). A retired codec also shows nothing more.
    retired: bool,
    shown: bool,
    need_decoder_sent: bool,
    needs_keyframe: bool,
    frames: VecDeque<Frame>,
    frame_bytes: usize,
    inputs: VecDeque<i32>,
    outputs: VecDeque<(i32, AMediaCodecBufferInfo)>,
    /// The feeder is using [codec] outside the lock; a release waits for it.
    busy: bool,
    destroyed: bool,
    waiting_since_ns: i64,
    slots: [Slot; SLOTS],
    slot_next: usize,
    recent_paced: [Option<usize>; PAUSE_HELD_FRAMES],
    last_queued_ns: i64,
    resumed_at_ns: i64,
    last_queued_local_ns: i64,
    pacer: Option<FramePacer>,
    delay: Option<PacingDelay>,
    backlog: BacklogRecovery,
    frames_since_configure: u32,
    last_keyframe_request_ns: i64,
    first_input_logged: bool,
    first_frame_sent: bool,
    report_vendor_format: bool,
    requested_size: (i32, i32),
    stats: Stats,
    input_stats: VideoInputStats,
    track_touches: bool,
}

// The codec and window pointers are only used under the lock or by the control thread that owns them.
unsafe impl Send for Inner {}

pub struct Pipe {
    inner: Mutex<Inner>,
    /// Wakes the feeder (work queued) and a release waiting for the feeder.
    work: Condvar,
    events: Mutex<(VecDeque<(i32, Option<String>)>, bool)>,
    wake: Condvar,
    live_received: AtomicI64,
    live_rendered: AtomicI64,
    live_decode_ns: AtomicI64,
    live_decode_samples: AtomicI64,
}

impl Pipe {
    fn post(&self, kind: i32, detail: Option<String>) {
        let mut events = self.events.lock().unwrap();
        events.0.push_back((kind, detail));
        self.wake.notify_one();
    }

    fn post_all(&self, list: Vec<(i32, Option<String>)>) {
        if list.is_empty() { return; }
        let mut events = self.events.lock().unwrap();
        events.0.extend(list);
        self.wake.notify_one();
    }
}

type Events = Vec<(i32, Option<String>)>;

impl Inner {
    fn request_keyframe(&mut self, now: i64, events: &mut Events) {
        if self.last_keyframe_request_ns != 0 && now - self.last_keyframe_request_ns < KEYFRAME_INTERVAL_NS { return; }
        self.last_keyframe_request_ns = now;
        events.push((EVENT_REQUEST_KEYFRAME, None));
    }

    fn drop_frames(&mut self) {
        for frame in self.frames.drain(..) { unsafe { packet::release(frame.handle) }; }
        self.frame_bytes = 0;
        self.waiting_since_ns = 0;
    }

    /// `VideoDecoder.recover`: drop the chain and wait for a keyframe; the decoder is rebuilt unless it
    /// is unused and [keep_unused].
    fn recover(&mut self, reason: &str, keep_unused: bool, now: i64, events: &mut Events) {
        let keep = keep_unused && !self.codec.is_null() && self.frames_since_configure == 0;
        self.stats.recoveries += 1;
        events.push((EVENT_LOG, Some(format!("Video recovery: {reason}; waiting for keyframe"))));
        events.push((EVENT_REPORT, Some(format!("recovery: {reason}; waiting for keyframe{}",
            if keep { "; decoder kept, no frame queued yet" } else { "" }))));
        if !keep && !self.codec.is_null() && !self.retired {
            self.retired = true;
            events.push((EVENT_RELEASE_DECODER, None));
        }
        self.drop_frames();
        self.needs_keyframe = true;
        self.backlog.reset();
        self.request_keyframe(now, events);
    }

    fn random_access(&self, frame: &Frame) -> bool {
        frame.flags & (if self.hevc { FLAG_RANDOM_ACCESS_H265 } else { FLAG_RANDOM_ACCESS_H264 }) != 0
    }

    /// Pairs queued frames with free input buffers: the reference chain and backlog checks run here,
    /// the copies in the feeder.
    fn plan_feeds(&mut self, now: i64, events: &mut Events, actions: &mut Vec<Action>) {
        while !self.codec.is_null() && !self.retired && !self.inputs.is_empty() && !self.frames.is_empty() {
            let front = &self.frames[0];
            let pending = self.frames.len() - 1;
            let newest = (pending > 0).then(|| self.frames.back().unwrap().received_ns);
            if self.needs_keyframe && !self.random_access(front) {
                let frame = self.frames.pop_front().unwrap();
                self.frame_bytes -= frame.size;
                unsafe { packet::release(frame.handle) };
                self.request_keyframe(now, events);
                continue;
            }
            if !self.needs_keyframe && self.backlog.observe(now, front.received_ns, pending, newest) {
                self.recover("video backlog kept growing or exceeded hard limit", false, now, events);
                return;
            }
            if self.needs_keyframe && is_obsolete(now, front.received_ns, pending, newest) {
                self.drop_frames();
                self.backlog.reset();
                self.request_keyframe(now, events);
                return;
            }
            let frame = self.frames.pop_front().unwrap();
            self.frame_bytes -= frame.size;
            let index = self.inputs.pop_front().unwrap() as usize;
            self.waiting_since_ns = if self.frames.is_empty() { 0 } else { now };
            let presentation_us = if frame.local_ns > 0 { frame.local_ns / 1000 } else { now / 1000 };
            let slot = self.slot_next;
            if self.last_queued_ns != 0 && now - self.last_queued_ns > STILL_GAP_NS { self.resumed_at_ns = now; }
            self.last_queued_ns = now;
            self.slots[slot] = Slot { presentation_us, queued_ns: now, paced: frame.local_ns > 0, before_pause: false };
            if frame.local_ns > 0 {
                if self.last_queued_local_ns != 0 && frame.local_ns - self.last_queued_local_ns > SENDER_PAUSE_NS {
                    for held in self.recent_paced.iter().flatten() { self.slots[*held].before_pause = true; }
                }
                self.last_queued_local_ns = frame.local_ns;
                self.recent_paced.rotate_right(1);
                self.recent_paced[0] = Some(slot);
            }
            self.slot_next = (slot + 1) % SLOTS;
            self.frames_since_configure += 1;
            self.needs_keyframe = false;
            if !self.first_input_logged {
                self.first_input_logged = true;
                events.push((EVENT_LOG, Some(format!("video decoder first input annexB={} packet=NativePacket decoder=native", frame.size))));
            }
            actions.push(Action::Feed { index, handle: frame.handle, size: frame.size, presentation_us });
        }
        if !self.codec.is_null() && !self.retired && self.inputs.is_empty() && !self.frames.is_empty() {
            if self.waiting_since_ns == 0 {
                self.waiting_since_ns = now;
            } else if now - self.waiting_since_ns >= INPUT_STALL_NS {
                self.waiting_since_ns = 0;
                events.push((EVENT_INPUT_STALLED, None));
                self.recover("video decoder input stalled", false, now, events);
            }
        }
    }

    /// Decides how each decoded picture is released: paced, at once, or not shown (retired codec).
    fn plan_outputs(&mut self, pipe: &Pipe, now: i64, events: &mut Events, actions: &mut Vec<Action>) {
        while let Some((index, info)) = self.outputs.pop_front() {
            let index = index as usize;
            if self.retired { actions.push(Action::Drop { index }); continue; }
            let shown = self.shown;
            let slot = self.slots.iter().position(|s| s.presentation_us == info.presentation_time_us && s.queued_ns != 0);
            let entry = slot.map(|s| self.slots[s]).unwrap_or_default();
            let held_over_gap = entry.queued_ns > 0 && entry.queued_ns < self.resumed_at_ns;
            if entry.queued_ns > 0 {
                let latency = now - entry.queued_ns;
                if held_over_gap {
                    self.stats.held_samples += 1;
                    self.stats.held_max_ns = self.stats.held_max_ns.max(latency);
                } else {
                    if self.stats.decode_micros.len() < DECODE_SAMPLES {
                        self.stats.decode_micros.push((latency / 1000).clamp(0, i32::MAX as i64) as i32);
                    }
                    pipe.live_decode_ns.fetch_add(latency, Ordering::Relaxed);
                    pipe.live_decode_samples.fetch_add(1, Ordering::Relaxed);
                }
            }
            let paced = shown && self.delay.is_some() && entry.paced;
            let local_ns = info.presentation_time_us * 1000;
            let pause_held = paced && entry.before_pause;
            let mut target = 0;
            if paced {
                let delay = self.delay.as_mut().unwrap();
                if !held_over_gap && !pause_held { delay.on_frame(now - local_ns); }
                let delay_ns = delay.nanos;
                self.stats.pacing_delay_ns = delay_ns;
                target = local_ns + delay_ns;
            }
            if (1..=MAX_PACING_AHEAD_NS).contains(&(target - now)) {
                actions.push(Action::Render { index, at_ns: Some(target) });
            } else {
                if shown && self.delay.is_some() && !held_over_gap && !pause_held { self.stats.late += 1; }
                actions.push(Action::Render { index, at_ns: None });
            }
            if shown {
                self.stats.rendered += 1;
                pipe.live_rendered.fetch_add(1, Ordering::Relaxed);
                if !self.first_frame_sent {
                    self.first_frame_sent = true;
                    events.push((EVENT_FIRST_FRAME, Some(format!("bytes={}", info.size))));
                }
            }
        }
    }
}

/// The feeder: waits for queued work, plans it under the lock, and makes the codec calls outside it.
fn run_feeder(pipe: Arc<Pipe>) {
    let mut guard = pipe.inner.lock().unwrap();
    loop {
        if guard.destroyed { break; }
        let now = clock(libc::CLOCK_MONOTONIC);
        let mut events = Vec::new();
        let mut actions = Vec::new();
        guard.plan_outputs(&pipe, now, &mut events, &mut actions);
        guard.plan_feeds(now, &mut events, &mut actions);
        if actions.is_empty() {
            if !events.is_empty() { drop(guard); pipe.post_all(events); guard = pipe.inner.lock().unwrap(); continue; }
            // Woken by queued work; the timeout only notices an input stall when no frame arrives.
            guard = pipe.work.wait_timeout(guard, std::time::Duration::from_millis(100)).unwrap().0;
            continue;
        }
        let codec = guard.codec;
        guard.busy = true;
        drop(guard);
        let mut oversized = false;
        for action in actions {
            unsafe {
                match action {
                    Action::Feed { index, handle, size, presentation_us } => {
                        let mut capacity = 0usize;
                        let input = AMediaCodec_getInputBuffer(codec, index, &mut capacity);
                        if input.is_null() || size > capacity {
                            AMediaCodec_queueInputBuffer(codec, index, 0, 0, 0, 0);
                            oversized = true;
                        } else {
                            std::ptr::copy_nonoverlapping(packet::bytes(handle).as_ptr(), input, size);
                            AMediaCodec_queueInputBuffer(codec, index, 0, size, presentation_us as u64, 0);
                        }
                        packet::release(handle);
                    }
                    Action::Render { index, at_ns: Some(at) } => { AMediaCodec_releaseOutputBufferAtTime(codec, index, at); }
                    Action::Render { index, at_ns: None } => { AMediaCodec_releaseOutputBuffer(codec, index, true); }
                    Action::Drop { index } => { AMediaCodec_releaseOutputBuffer(codec, index, false); }
                }
            }
        }
        guard = pipe.inner.lock().unwrap();
        guard.busy = false;
        pipe.work.notify_all();
        if oversized && guard.codec == codec {
            guard.recover("video frame exceeded codec input capacity", false, clock(libc::CLOCK_MONOTONIC), &mut events);
        }
        if !events.is_empty() { drop(guard); pipe.post_all(events); guard = pipe.inner.lock().unwrap(); }
    }
    drop(guard);
}

unsafe fn pipe<'a>(handle: jlong) -> &'a Arc<Pipe> { &*(handle as *const Arc<Pipe>) }

/// None uses Kotlin's packet callback; an empty list disconnects a replaced/closed receiver.
pub struct Route(Mutex<Option<Vec<Arc<Pipe>>>>);

impl Route {
    /// Returns false without consuming the packet when Kotlin must handle it.
    pub unsafe fn deliver(&self, frame: jlong, size: usize, flags: i32, sender: i64, arrival: i64) -> bool {
        let targets = self.0.lock().unwrap();
        let Some(targets) = targets.as_ref() else { return false };
        for target in targets {
            packet::retain(frame);
            submit(target, frame, size, flags, sender, arrival);
        }
        packet::release(frame);
        true
    }
}

pub unsafe fn retain_route(handle: jlong) -> Option<Arc<Route>> {
    (handle != 0).then(|| (&*(handle as *const Arc<Route>)).clone())
}

#[no_mangle]
pub extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_videoRouteCreate(
    _env: *mut JNIEnv, _class: jclass,
) -> jlong {
    Box::into_raw(Box::new(Arc::new(Route(Mutex::new(None))))) as jlong
}

/// The caller transfers separately retained references, never the worker's original pipe handles.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_videoRouteSetTargets(
    env: *mut JNIEnv, _class: jclass, handle: jlong, targets: jlongArray,
) {
    let count = ((**env).GetArrayLength.unwrap())(env, targets) as usize;
    let mut handles = vec![0; count];
    ((**env).GetLongArrayRegion.unwrap())(env, targets, 0, count as i32, handles.as_mut_ptr());
    let fallback = handles.contains(&0);
    let pipes: Vec<_> = handles.into_iter().filter(|h| *h != 0)
        .map(|h| *Box::from_raw(h as *mut Arc<Pipe>)).collect();
    *(&*(handle as *const Arc<Route>)).0.lock().unwrap() = if fallback { None } else { Some(pipes) };
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_videoRouteDestroy(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) {
    let route = Box::from_raw(handle as *mut Arc<Route>);
    *route.0.lock().unwrap() = Some(Vec::new());
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_pipeRetain(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) -> jlong { Box::into_raw(Box::new(pipe(handle).clone())) as jlong }

static PENDING_TOUCH_NS: AtomicI64 = AtomicI64::new(0);

#[no_mangle]
pub extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_videoTouchSent(
    _env: *mut JNIEnv, _class: jclass, sent_at: jlong,
) { let _ = PENDING_TOUCH_NS.compare_exchange(0, sent_at, Ordering::Relaxed, Ordering::Relaxed); }

#[no_mangle]
pub extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_videoTouchFrame(
    _env: *mut JNIEnv, _class: jclass, now: jlong,
) -> jlong { touch_latency(now).unwrap_or(-1) }

fn touch_latency(now: i64) -> Option<i64> {
    let sent = PENDING_TOUCH_NS.swap(0, Ordering::Relaxed);
    (sent != 0).then_some(now - sent)
}

unsafe extern "C" fn on_input(codec: *mut AMediaCodec, user: *mut c_void, index: i32) {
    let pipe = &*(user as *const Pipe);
    let mut inner = pipe.inner.lock().unwrap();
    if inner.codec != codec { return; }
    inner.inputs.push_back(index);
    pipe.work.notify_all();
}

unsafe extern "C" fn on_output(codec: *mut AMediaCodec, user: *mut c_void, index: i32, info: *mut AMediaCodecBufferInfo) {
    let pipe = &*(user as *const Pipe);
    let mut inner = pipe.inner.lock().unwrap();
    if inner.codec != codec { return; }
    inner.outputs.push_back((index, *info));
    pipe.work.notify_all();
}

fn format_int(format: *mut AMediaFormat, key: &CStr) -> String {
    let mut value = 0i32;
    if unsafe { AMediaFormat_getInt32(format, key.as_ptr(), &mut value) } { value.to_string() } else { "null".into() }
}

unsafe extern "C" fn on_format(codec: *mut AMediaCodec, user: *mut c_void, format: *mut AMediaFormat) {
    let pipe = &*(user as *const Pipe);
    let (vendor, (width, height)) = {
        let inner = pipe.inner.lock().unwrap();
        if inner.codec != codec { return; }
        (inner.report_vendor_format, inner.requested_size)
    };
    let f = |key: &CStr| format_int(format, key);
    let mut events = Vec::new();
    if vendor {
        events.push((EVENT_REPORT, Some(format!(
            "QTI driver-reported output format vendor.qti-ext-dec-low-latency.enable={} \
             vendor.qti-ext-dec-picture-order.enable={}; effectiveness=unverified (reported values may be stale; \
             unreported is not unsupported)",
            f(c"vendor.qti-ext-dec-low-latency.enable").replace("null", "unreported"),
            f(c"vendor.qti-ext-dec-picture-order.enable").replace("null", "unreported")))));
    }
    events.push((EVENT_REPORT, Some(format!(
        "output format requested={width}x{height} coded={}x{} crop={},{},{},{} stride={} slice={} color={}/{}/{}",
        f(KEY_WIDTH), f(KEY_HEIGHT), f(c"crop-left"), f(c"crop-top"), f(c"crop-right"), f(c"crop-bottom"),
        f(c"stride"), f(c"slice-height"), f(c"color-standard"), f(c"color-range"), f(c"color-transfer")))));
    pipe.post_all(events);
}

unsafe extern "C" fn on_error(codec: *mut AMediaCodec, user: *mut c_void, error: media_status_t, action: i32, detail: *const c_char) {
    let pipe = &*(user as *const Pipe);
    {
        let mut inner = pipe.inner.lock().unwrap();
        if inner.codec != codec || inner.retired { return; }
        inner.retired = true;
    }
    let text = if detail.is_null() { String::new() } else { CStr::from_ptr(detail).to_string_lossy().into_owned() };
    pipe.post(EVENT_CODEC_ERROR, Some(format!("error={error} action={action} {text}")));
}

/// Delivers events to Kotlin in order on an attached thread, until the pipe is destroyed.
fn run_events(pipe: Arc<Pipe>, callbacks: jobject, on_event: jmethodID) {
    let Some(attached) = Attached::current("carplay-video-events") else { return };
    let env = attached.env;
    loop {
        let next = {
            let mut events = pipe.events.lock().unwrap();
            loop {
                if let Some(event) = events.0.pop_front() { break Some(event); }
                if events.1 { break None; }
                events = pipe.wake.wait(events).unwrap();
            }
        };
        let Some((kind, detail)) = next else { break };
        let text = detail.map(|d| env.string(&d)).unwrap_or(std::ptr::null_mut());
        env.call_void(callbacks, on_event, &[jvalue { i: kind }, jvalue { l: text }]);
        env.delete_local(text);
    }
    env.delete_global(callbacks);
}

/// A pipe for one screen stream; `pacing_delay_nanos` > 0 paces frames by the iPhone's frame times.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_pipeCreate(
    env: *mut JNIEnv, _class: jclass, callbacks: jobject, pacing_delay_nanos: jlong, track_touches: jboolean,
) -> jlong {
    let e = Env(env);
    if set_async_notify_callback().is_none() { return 0; }
    let class = e.object_class(callbacks);
    let on_event = e.method(class, "onEvent", "(ILjava/lang/String;)V");
    e.delete_local(class);
    if on_event.is_null() { return 0; }
    let pipe = Arc::new(Pipe {
        inner: Mutex::new(Inner {
            // Held until Kotlin has a configuration: frames before it have nothing to decode with.
            codec: std::ptr::null_mut(), window: std::ptr::null_mut(), hevc: false, accepting: false, retired: true,
            shown: true, need_decoder_sent: false, needs_keyframe: true, frames: VecDeque::new(), frame_bytes: 0,
            inputs: VecDeque::new(), outputs: VecDeque::new(), busy: false, destroyed: false, waiting_since_ns: 0, slots: [Slot::default(); SLOTS], slot_next: 0,
            recent_paced: [None; PAUSE_HELD_FRAMES], last_queued_ns: 0, resumed_at_ns: 0, last_queued_local_ns: 0,
            pacer: (pacing_delay_nanos > 0).then(FramePacer::default),
            delay: (pacing_delay_nanos > 0).then(|| PacingDelay::new(pacing_delay_nanos)),
            backlog: BacklogRecovery::default(), frames_since_configure: 0, last_keyframe_request_ns: 0,
            first_input_logged: false, first_frame_sent: false, report_vendor_format: false, requested_size: (0, 0),
            stats: Stats::default(),
            input_stats: VideoInputStats::default(), track_touches: track_touches != 0,
        }),
        work: Condvar::new(),
        events: Mutex::new((VecDeque::new(), false)),
        wake: Condvar::new(),
        live_received: AtomicI64::new(0),
        live_rendered: AtomicI64::new(0),
        live_decode_ns: AtomicI64::new(0),
        live_decode_samples: AtomicI64::new(0),
    });
    let global = e.new_global(callbacks) as usize;
    let thread_pipe = pipe.clone();
    let mid = on_event as usize;
    if std::thread::Builder::new().name("carplay-video-events".into())
        .spawn(move || run_events(thread_pipe, global as jobject, mid as jmethodID)).is_err() {
        e.delete_global(global as jobject);
        return 0;
    }
    let feeder = pipe.clone();
    if std::thread::Builder::new().name("carplay-video-feed".into()).spawn(move || run_feeder(feeder)).is_err() {
        pipe.events.lock().unwrap().1 = true;
        pipe.wake.notify_one();
        return 0;
    }
    Box::into_raw(Box::new(pipe)) as jlong
}

fn status_text(phase: &str, status: media_status_t) -> String { format!("{phase} failed status={status}") }

/// Creates (by `name`, or the platform's decoder for `mime`), configures and starts a decoder that
/// renders to `surface`. 0 on success, or -1 create / -2 configure / -3 start / -4 async, with the
/// detail in `detail_out[0]`. Integer keys come from Kotlin's format policy.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_pipeConfigure(
    env: *mut JNIEnv, _class: jclass, handle: jlong, name: jstring, mime: jstring, width: jint, height: jint,
    keys: jobjectArray, values: jintArray, csd0: jbyteArray, csd1: jbyteArray, surface: jobject, hevc: jboolean,
    report_vendor_format: jboolean, detail_out: jobjectArray,
) -> jint {
    let e = Env(env);
    let f = &**env;
    let pipe = pipe(handle);
    let string = |s: jstring| -> Option<CString> {
        if s.is_null() { return None; }
        let chars = (f.GetStringUTFChars.unwrap())(env, s, std::ptr::null_mut());
        let owned = CStr::from_ptr(chars).to_owned();
        (f.ReleaseStringUTFChars.unwrap())(env, s, chars);
        Some(owned)
    };
    let set_detail = |text: String| {
        let value = e.string(&text);
        (f.SetObjectArrayElement.unwrap())(env, detail_out, 0, value);
        e.delete_local(value);
    };
    let Some(mime_c) = string(mime) else { return -1 };
    let codec = match string(name) {
        Some(n) => AMediaCodec_createCodecByName(n.as_ptr()),
        None => AMediaCodec_createDecoderByType(mime_c.as_ptr()),
    };
    if codec.is_null() { set_detail("create returned null".into()); return -1; }
    let mut name_ptr: *mut c_char = std::ptr::null_mut();
    if AMediaCodec_getName(codec, &mut name_ptr) == AMEDIA_OK && !name_ptr.is_null() {
        set_detail(CStr::from_ptr(name_ptr).to_string_lossy().into_owned());
        AMediaCodec_releaseName(codec, name_ptr);
    }
    let format = AMediaFormat_new();
    AMediaFormat_setString(format, KEY_MIME.as_ptr(), mime_c.as_ptr());
    AMediaFormat_setInt32(format, KEY_WIDTH.as_ptr(), width);
    AMediaFormat_setInt32(format, KEY_HEIGHT.as_ptr(), height);
    let count = if keys.is_null() { 0 } else { (f.GetArrayLength.unwrap())(env, keys) };
    let mut ints = vec![0i32; count as usize];
    if count > 0 { (f.GetIntArrayRegion.unwrap())(env, values, 0, count, ints.as_mut_ptr()); }
    for (i, value) in ints.iter().enumerate() {
        let key = (f.GetObjectArrayElement.unwrap())(env, keys, i as i32);
        if let Some(k) = string(key) { AMediaFormat_setInt32(format, k.as_ptr(), *value); }
        e.delete_local(key);
    }
    for (key, data) in [(c"csd-0", csd0), (c"csd-1", csd1)] {
        if data.is_null() { continue; }
        let mut bytes = vec![0u8; e.array_len(data)];
        e.read_array(data, 0, &mut bytes);
        AMediaFormat_setBuffer(format, key.as_ptr(), bytes.as_ptr() as *const c_void, bytes.len());
    }
    let window = ANativeWindow_fromSurface(env, surface);
    let user = Arc::as_ptr(pipe) as *mut c_void;
    let callback = AMediaCodecOnAsyncNotifyCallback { on_input, on_output, on_format, on_error };
    let async_status = set_async_notify_callback().map(|set| set(codec, callback, user)).unwrap_or(-1);
    let fail = |code: jint, text: String| -> jint {
        AMediaCodec_delete(codec);
        AMediaFormat_delete(format);
        if !window.is_null() { ANativeWindow_release(window); }
        set_detail(text);
        code
    };
    if async_status != AMEDIA_OK { return fail(-4, status_text("async callback", async_status)); }
    if window.is_null() { return fail(-2, "surface has no window".into()); }
    {
        // Callbacks may come as soon as the codec starts; they find it under the lock.
        let mut inner = pipe.inner.lock().unwrap();
        inner.codec = codec;
        inner.hevc = hevc != 0;
        inner.retired = false;
        inner.inputs.clear();
        inner.outputs.clear();
        inner.frames_since_configure = 0;
        inner.first_input_logged = false;
        inner.first_frame_sent = false;
        inner.need_decoder_sent = false;
        inner.report_vendor_format = report_vendor_format != 0;
        inner.requested_size = (width, height);
        inner.slots = [Slot::default(); SLOTS];
        inner.backlog.decoder_started(clock(libc::CLOCK_MONOTONIC));
    }
    let configured = AMediaCodec_configure(codec, format, window, std::ptr::null_mut(), 0);
    AMediaFormat_delete(format);
    let started = if configured == AMEDIA_OK { AMediaCodec_start(codec) } else { configured };
    if started != AMEDIA_OK {
        pipe.inner.lock().unwrap().codec = std::ptr::null_mut();
        AMediaCodec_delete(codec);
        ANativeWindow_release(window);
        set_detail(status_text(if configured != AMEDIA_OK { "configure" } else { "start" }, started));
        return if configured != AMEDIA_OK { -2 } else { -3 };
    }
    let previous = std::mem::replace(&mut pipe.inner.lock().unwrap().window, window);
    if !previous.is_null() { ANativeWindow_release(previous); }
    0
}

/// Stops and frees the decoder, if any. The pipe keeps its frames' policy (a keyframe is needed next).
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_pipeRelease(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) {
    let pipe = pipe(handle);
    let codec = {
        let mut inner = pipe.inner.lock().unwrap();
        let codec = std::mem::replace(&mut inner.codec, std::ptr::null_mut());
        let window = std::mem::replace(&mut inner.window, std::ptr::null_mut());
        // The feeder may be mid-call on this codec; it rechecks the codec once it holds the lock again.
        while inner.busy { inner = pipe.work.wait(inner).unwrap(); }
        inner.inputs.clear();
        inner.outputs.clear();
        inner.retired = false;
        inner.needs_keyframe = true;
        inner.backlog.reset();
        // A keyframe that arrived while the old decoder was retiring asks for the next one.
        inner.need_decoder_sent = inner.accepting && !inner.frames.is_empty();
        let ask = inner.need_decoder_sent;
        (codec, window, ask)
    };
    let (codec, window, ask) = (codec.0, codec.1, codec.2);
    if ask { pipe.post(EVENT_NEED_DECODER, None); }
    if !codec.is_null() {
        AMediaCodec_stop(codec);
        AMediaCodec_delete(codec);
    }
    if !window.is_null() { ANativeWindow_release(window); }
}

/// Moves the running decoder to `surface`; `shown` false for the parking consumer. 0 on success.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_pipeSetSurface(
    env: *mut JNIEnv, _class: jclass, handle: jlong, surface: jobject, shown: jboolean,
) -> jint {
    let pipe = pipe(handle);
    let codec = pipe.inner.lock().unwrap().codec;
    if codec.is_null() {
        pipe.inner.lock().unwrap().shown = shown != 0;
        return 0;
    }
    let window = ANativeWindow_fromSurface(env, surface);
    if window.is_null() { return -1; }
    let status = AMediaCodec_setOutputSurface(codec, window);
    if status != AMEDIA_OK { ANativeWindow_release(window); return status; }
    let previous = {
        let mut inner = pipe.inner.lock().unwrap();
        inner.shown = shown != 0;
        std::mem::replace(&mut inner.window, window)
    };
    if !previous.is_null() { ANativeWindow_release(previous); }
    0
}

/// Whether frames are kept for a decoder (Kotlin has a configuration and a surface); a change resets
/// the reference chain, as a new configuration or surface does in `VideoDecoder`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_pipeSetAccepting(
    _env: *mut JNIEnv, _class: jclass, handle: jlong, accepting: jboolean, reset_chain: jboolean,
) {
    let mut inner = pipe(handle).inner.lock().unwrap();
    inner.accepting = accepting != 0;
    if reset_chain != 0 || !inner.accepting {
        inner.drop_frames();
        inner.needs_keyframe = true;
    }
}

/// `hold` true when a new configuration arrives (from the receive thread, before the next frame):
/// drops the queued chain and feeds nothing until Kotlin has acted on it. False lets frames through
/// again: the configuration was unchanged, or no decoder could be made and the next keyframe retries.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_pipeHold(
    _env: *mut JNIEnv, _class: jclass, handle: jlong, hold: jboolean,
) {
    let pipe = pipe(handle);
    let mut events = Vec::new();
    {
        let mut inner = pipe.inner.lock().unwrap();
        if hold != 0 {
            inner.retired = true;
            inner.drop_frames();
            inner.needs_keyframe = true;
        } else if inner.retired {
            inner.retired = false;
            inner.need_decoder_sent = false;
            if inner.codec.is_null() && inner.accepting && !inner.frames.is_empty() {
                inner.need_decoder_sent = true;
                events.push((EVENT_NEED_DECODER, None));
            }
            pipe.work.notify_all();
        }
    }
    pipe.post_all(events);
}

/// Takes ownership of a frame from the receiver.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_pipeSubmit(
    _env: *mut JNIEnv, _class: jclass, handle: jlong, frame: jlong, size: jint, flags: jint,
    sender_nanos: jlong, arrival_nanos: jlong,
) {
    if size < 0 || size as usize > packet::size(frame) { packet::release(frame); return; }
    submit(pipe(handle), frame, size as usize, flags, sender_nanos, arrival_nanos);
}

unsafe fn submit(pipe: &Pipe, frame: jlong, size: usize, flags: i32, sender_nanos: i64, arrival_nanos: i64) {
    let now = clock(libc::CLOCK_MONOTONIC);
    let mut events = Vec::new();
    {
        let mut inner = pipe.inner.lock().unwrap();
        if inner.destroyed { packet::release(frame); return; }
        pipe.live_received.fetch_add(1, Ordering::Relaxed);
        let touch = if inner.track_touches { touch_latency(now) } else { None };
        inner.input_stats.received(size, now, touch);
        if !inner.accepting {
            packet::release(frame);
        } else if flags & FLAG_ANNEX_B == 0 {
            packet::release(frame);
            inner.recover("invalid video access unit", false, now, &mut events);
        } else if inner.needs_keyframe && inner.frames.is_empty() &&
            flags & (if inner.hevc { FLAG_RANDOM_ACCESS_H265 } else { FLAG_RANDOM_ACCESS_H264 }) == 0 {
            // VideoReferenceChain: nothing after a lost reference until a keyframe; no decoder for it either.
            packet::release(frame);
            inner.request_keyframe(now, &mut events);
        } else {
            let local_ns = match inner.pacer.as_mut() {
                Some(pacer) if sender_nanos > 0 && arrival_nanos > 0 => pacer.local_time(sender_nanos, arrival_nanos),
                _ => 0,
            };
            if inner.frames.len() >= MAX_FRAMES || inner.frame_bytes + size > MAX_BYTES {
                // VideoDecodeQueue's overflow: the chain is gone; an unused decoder survives it.
                inner.drop_frames();
                inner.recover("video queue overflow", true, now, &mut events);
            }
            if size > MAX_BYTES {
                packet::release(frame);
            } else {
                inner.frames.push_back(Frame { handle: frame, size, flags, received_ns: now, local_ns });
                inner.frame_bytes += size;
                if inner.codec.is_null() && !inner.retired && !inner.need_decoder_sent {
                    inner.need_decoder_sent = true;
                    events.push((EVENT_NEED_DECODER, None));
                }
                pipe.work.notify_all();
            }
        }
    }
    pipe.post_all(events);
}

/// Live counters for the FPS overlay: received, rendered, decode ns, decode samples.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_pipeLive(
    env: *mut JNIEnv, _class: jclass, handle: jlong, out: jlongArray,
) {
    let pipe = pipe(handle);
    let values = [
        pipe.live_received.load(Ordering::Relaxed), pipe.live_rendered.load(Ordering::Relaxed),
        pipe.live_decode_ns.load(Ordering::Relaxed), pipe.live_decode_samples.load(Ordering::Relaxed),
    ];
    ((**env).SetLongArrayRegion.unwrap())(env, out, 0, 4, values.as_ptr());
}

/// Moves the five-second stats into Kotlin's `VideoStats`: `out` gets rendered, recoveries, late,
/// pacing delay, held samples, held max; `decode` the decode times in microseconds. Returns their count.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_pipeTakeStats(
    env: *mut JNIEnv, _class: jclass, handle: jlong, out: jlongArray, decode: jintArray,
) -> jint {
    let (stats, input) = {
        let mut inner = pipe(handle).inner.lock().unwrap();
        (std::mem::take(&mut inner.stats), inner.input_stats.take())
    };
    let mut values = vec![stats.rendered, stats.recoveries, stats.late, stats.pacing_delay_ns, stats.held_samples, stats.held_max_ns];
    values.extend(input);
    ((**env).SetLongArrayRegion.unwrap())(env, out, 0, values.len() as i32, values.as_ptr());
    let count = stats.decode_micros.len().min(((**env).GetArrayLength.unwrap())(env, decode) as usize);
    ((**env).SetIntArrayRegion.unwrap())(env, decode, 0, count as i32, stats.decode_micros.as_ptr());
    count as jint
}

/// Releases the decoder and every queued frame, and ends the event thread; the handle is gone.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_pipeDestroy(
    env: *mut JNIEnv, class: jclass, handle: jlong,
) {
    if handle == 0 { return; }
    Java_com_shilapi_xcertplay_airplay_NativeMedia_pipeRelease(env, class, handle);
    let pipe = Box::from_raw(handle as *mut Arc<Pipe>);
    {
        let mut inner = pipe.inner.lock().unwrap();
        inner.drop_frames();
        inner.destroyed = true;
        pipe.work.notify_all();
    }
    let mut events = pipe.events.lock().unwrap();
    events.1 = true;
    pipe.wake.notify_one();
}
