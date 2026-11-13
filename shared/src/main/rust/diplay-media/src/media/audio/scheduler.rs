//! The audio renderer's platform-output buffer accounting (Kotlin's old `AudioBufferProgress`):
//! it tracks the unsigned AudioTrack/AAudio head across wrap without flushing queued sound, and
//! decides the music rebuffer. Kotlin still performs the writes and owns the platform output.

/// The device may grant a smaller track than requested; the start level must stay inside it.
pub fn start_bytes_for(planned_start_bytes: i32, actual_capacity_bytes: i32, write_chunk_bytes: i32) -> i32 {
    if actual_capacity_bytes <= 0 {
        return planned_start_bytes;
    }
    planned_start_bytes
        .min(actual_capacity_bytes.wrapping_sub(write_chunk_bytes))
        .max(write_chunk_bytes)
}

pub struct AudioBufferProgress {
    frame_bytes: i64,
    written_bytes: i64,
    played_frames: i64,
    last_head: i64,
    playing: bool,
    prebuffer_bytes: i32,
    start_bytes: i32,
    last_write_ns: i64,
}

impl AudioBufferProgress {
    pub fn new(frame_bytes: i32) -> AudioBufferProgress {
        AudioBufferProgress { frame_bytes: frame_bytes as i64, written_bytes: 0, played_frames: 0, last_head: 0,
            playing: false, prebuffer_bytes: 0, start_bytes: 0, last_write_ns: 0 }
    }

    /// A freshly built output starts from zero without releasing the native owner.
    pub fn reset(&mut self) {
        self.written_bytes = 0;
        self.played_frames = 0;
        self.last_head = 0;
        self.playing = false;
        self.prebuffer_bytes = 0;
    }

    pub fn written(&mut self, bytes: i32) {
        self.written_bytes += bytes as i64;
    }

    pub fn configure(&mut self, planned: i32, capacity: i32, chunk: i32) {
        self.start_bytes = start_bytes_for(planned, capacity, chunk);
    }

    pub fn output_state(&self, field: i32) -> i64 {
        match field { 0 => self.playing as i64, 1 => self.prebuffer_bytes as i64,
            2 => self.start_bytes as i64, 3 => self.last_write_ns, _ => 0 }
    }

    pub fn write_limit(&self, remaining: i32, chunk: i32) -> i32 {
        if self.playing { remaining } else { remaining.min(chunk) }
    }

    pub fn completed_write(&mut self, bytes: i32, now: i64) -> bool {
        self.written(bytes);
        self.last_write_ns = now;
        if self.playing { return false; }
        self.prebuffer_bytes = self.prebuffer_bytes.wrapping_add(bytes);
        self.prebuffer_bytes >= self.start_bytes
    }

    /// Applied only after the platform play/pause action succeeds.
    pub fn playback(&mut self, playing: bool) { self.playing = playing; }

    pub fn retain_after_pause(&mut self, head: i32, now: i64) {
        self.prebuffer_bytes = self.queued_bytes(head).min(self.start_bytes as i64) as i32;
        self.last_write_ns = now;
    }

    pub fn needs_rebuffer(&mut self, media: bool, underrun: bool, queue_empty: bool, head: i32) -> bool {
        self.should_rebuffer(media, self.playing, underrun, queue_empty, head, self.start_bytes as i64 / 2)
    }

    pub fn waiting_for_tail(&self, queue_empty: bool) -> bool {
        !self.playing && self.prebuffer_bytes > 0 && queue_empty
    }

    pub fn tail_ready(&self, now: i64, wait: i64) -> bool { now.wrapping_sub(self.last_write_ns) >= wait }

    /// Bytes written but not yet played. `raw_head` is the platform's unsigned 32-bit position.
    pub fn queued_bytes(&mut self, raw_head: i32) -> i64 {
        let head = raw_head as u32 as i64;
        self.played_frames += (head - self.last_head) & 0xffff_ffff;
        self.last_head = head;
        (self.written_bytes - self.played_frames * self.frame_bytes).max(0)
    }

    pub fn should_rebuffer(
        &mut self,
        is_media: bool,
        playing: bool,
        underrun_since_start: bool,
        compressed_queue_empty: bool,
        raw_head: i32,
        floor_bytes: i64,
    ) -> bool {
        is_media
            && playing
            && underrun_since_start
            && compressed_queue_empty
            && self.queued_bytes(raw_head) <= floor_bytes
    }
}

pub fn sanitize_delay(millis: i32, mode: i32) -> i32 {
    let accepted = if mode == 1 { matches!(millis, 300 | 500 | 1000) }
        else { matches!(millis, 50 | 100 | 150 | 200 | 300 | 400 | 500 | 600 | 700 | 800 | 900 | 1000) };
    if accepted { millis } else { 300 }
}

pub fn buffer_plan(media: bool, rate: i32, channels: i32, minimum: i32, millis: i32) -> [i32; 2] {
    let low_capacity = minimum.wrapping_mul(4).max(16384);
    let low_start = minimum.max(4096);
    if !media { return [low_capacity, low_start]; }
    let bytes_per_second = rate as i64 * channels.clamp(1, 2) as i64 * 2;
    let millis = sanitize_delay(millis, 0) as i64;
    let start = (bytes_per_second * millis / 1000) as i32;
    let capacity = (bytes_per_second * (millis + 200) / 1000) as i32;
    [capacity.max(low_capacity), start.max(low_start)]
}

pub fn write_disposition(count: i32, native: bool) -> i32 {
    match (count.cmp(&0), native) {
        (std::cmp::Ordering::Less, true) => 4, (std::cmp::Ordering::Less, false) => 1,
        (std::cmp::Ordering::Equal, true) => 2, (std::cmp::Ordering::Equal, false) => 3, _ => 0,
    }
}

pub fn navigation_route(follows: bool, configured: i32, ducking: bool) -> i32 {
    if !follows { configured } else if ducking { 0 } else { 3 }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn queued_bytes_follows_the_unsigned_head_forward_without_flushing() {
        let mut progress = AudioBufferProgress::new(4);
        progress.written(4000);
        assert_eq!(progress.queued_bytes(100), 4000 - 100 * 4);
        assert_eq!(progress.queued_bytes(200), 4000 - 200 * 4);
        // A head that jumps forward (wrap or reset) cannot report negative queued sound.
        assert_eq!(progress.queued_bytes(-4), 0);
    }

    #[test]
    fn rebuffer_needs_media_playing_underrun_empty_queue_and_the_floor() {
        let mut progress = AudioBufferProgress::new(4);
        progress.written(1000);
        assert!(!progress.should_rebuffer(true, false, true, true, 0, 0));
        assert!(!progress.should_rebuffer(false, true, true, true, 0, 0));
        assert!(progress.should_rebuffer(true, true, true, true, 0, 1000));
        assert!(!progress.should_rebuffer(true, true, false, true, 0, 1000));
        assert!(!progress.should_rebuffer(true, true, true, false, 0, 1000));
    }

    #[test]
    fn wrap_and_reset_rebase_the_playing_head() {
        let mut progress = AudioBufferProgress::new(1);
        for _ in 0..3 {
            progress.written(2_000_000_000);
        }
        // Head near 0xffff_ff00, then wrapped forward to 256: 512 frames played across the wrap.
        let near_top = progress.queued_bytes(-256);
        let wrapped = progress.queued_bytes(256);
        assert_eq!(wrapped, near_top - 512);
        progress.reset();
        assert_eq!(progress.queued_bytes(256), 0);
        assert!(!progress.should_rebuffer(true, true, true, true, 256, -1));
    }

    #[test]
    fn start_level_is_kept_below_the_granted_capacity() {
        assert_eq!(start_bytes_for(8192, 0, 2048), 8192);
        assert_eq!(start_bytes_for(8192, 4096, 2048), 2048);
        assert_eq!(start_bytes_for(4096, 6144, 2048), 4096);
        assert_eq!(start_bytes_for(1024, 2048, 2048), 2048);
    }

    #[test]
    fn output_schedule_commits_platform_actions_and_keeps_paused_pcm_for_tail_playback() {
        let mut owner = AudioBufferProgress::new(4);
        owner.configure(8192, 4096, 2048);
        assert_eq!(owner.write_limit(9000, 2048), 2048);
        assert!(!owner.completed_write(1024, 10));
        assert!(owner.completed_write(1024, 20));
        assert_eq!(owner.output_state(0), 0);
        owner.playback(true);
        assert_eq!(owner.write_limit(9000, 2048), 9000);
        assert!(!owner.waiting_for_tail(true));
        owner.playback(false);
        owner.retain_after_pause(100, 30);
        assert_eq!(owner.output_state(1), 1648);
        assert!(owner.waiting_for_tail(true));
        assert!(!owner.tail_ready(500_000_029, 500_000_000));
        assert!(owner.tail_ready(500_000_030, 500_000_000));
        owner.reset();
        assert!(!owner.waiting_for_tail(true));
        assert_eq!(owner.output_state(2), 2048);
    }

    #[test]
    fn buffer_calibration_and_write_actions_keep_existing_platform_choices() {
        assert_eq!(buffer_plan(true, 48000, 2, 1024, 100), [57600, 19200]);
        assert_eq!(buffer_plan(false, 48000, 2, 1024, 100), [16384, 4096]);
        assert_eq!(sanitize_delay(100, 1), 300);
        assert_eq!(sanitize_delay(100, 2), 100);
        assert_eq!(write_disposition(0, true), 2);
        assert_eq!(write_disposition(-6, true), 4);
        assert_eq!(write_disposition(0, false), 3);
        assert_eq!(navigation_route(false, 10, true), 10);
        assert_eq!(navigation_route(true, 10, true), 0);
        assert_eq!(navigation_route(true, 10, false), 3);
    }
}

// ---- JNI boundary: `com.shihab.diplay.media.NativeAudioScheduler` ----

use jni_sys::{jboolean, jclass, jint, jintArray, jlong, JNIEnv};

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAudioScheduler_create(
    _env: *mut JNIEnv, _class: jclass, frame_bytes: jint,
) -> jlong {
    Box::into_raw(Box::new(AudioBufferProgress::new(frame_bytes))) as jlong
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAudioScheduler_destroy(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) {
    if handle != 0 { drop(Box::from_raw(handle as *mut AudioBufferProgress)); }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAudioScheduler_reset(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) {
    if handle != 0 { (&mut *(handle as *mut AudioBufferProgress)).reset(); }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAudioScheduler_written(
    _env: *mut JNIEnv, _class: jclass, handle: jlong, bytes: jint,
) {
    if handle != 0 { (&mut *(handle as *mut AudioBufferProgress)).written(bytes); }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAudioScheduler_queuedBytes(
    _env: *mut JNIEnv, _class: jclass, handle: jlong, raw_head: jint,
) -> jlong {
    if handle == 0 { 0 } else { (&mut *(handle as *mut AudioBufferProgress)).queued_bytes(raw_head) }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAudioScheduler_shouldRebuffer(
    _env: *mut JNIEnv, _class: jclass, handle: jlong, is_media: jboolean, playing: jboolean,
    underrun_since_start: jboolean, compressed_queue_empty: jboolean, raw_head: jint, floor_bytes: jlong,
) -> jboolean {
    if handle == 0 { return 0; }
    (&mut *(handle as *mut AudioBufferProgress)).should_rebuffer(
        is_media != 0, playing != 0, underrun_since_start != 0, compressed_queue_empty != 0, raw_head, floor_bytes,
    ) as jboolean
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAudioScheduler_startBytesFor(
    _env: *mut JNIEnv, _class: jclass, planned_start_bytes: jint, actual_capacity_bytes: jint, write_chunk_bytes: jint,
) -> jint {
    start_bytes_for(planned_start_bytes, actual_capacity_bytes, write_chunk_bytes)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAudioScheduler_configure(
    _: *mut JNIEnv, _: jclass, handle: jlong, planned: jint, capacity: jint, chunk: jint,
) {
    if handle != 0 { (&mut *(handle as *mut AudioBufferProgress)).configure(planned, capacity, chunk); }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAudioScheduler_outputState(
    _: *mut JNIEnv, _: jclass, handle: jlong, field: jint,
) -> jlong {
    if handle == 0 { 0 } else { (&*(handle as *const AudioBufferProgress)).output_state(field) }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAudioScheduler_writeLimit(
    _: *mut JNIEnv, _: jclass, handle: jlong, remaining: jint, chunk: jint,
) -> jint {
    if handle == 0 { 0 } else { (&*(handle as *const AudioBufferProgress)).write_limit(remaining, chunk) }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAudioScheduler_completedWrite(
    _: *mut JNIEnv, _: jclass, handle: jlong, bytes: jint, now: jlong,
) -> jboolean {
    if handle == 0 { 0 } else { (&mut *(handle as *mut AudioBufferProgress)).completed_write(bytes, now) as jboolean }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAudioScheduler_playback(
    _: *mut JNIEnv, _: jclass, handle: jlong, playing: jboolean,
) {
    if handle != 0 { (&mut *(handle as *mut AudioBufferProgress)).playback(playing != 0); }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAudioScheduler_retainAfterPause(
    _: *mut JNIEnv, _: jclass, handle: jlong, head: jint, now: jlong,
) {
    if handle != 0 { (&mut *(handle as *mut AudioBufferProgress)).retain_after_pause(head, now); }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAudioScheduler_needsRebuffer(
    _: *mut JNIEnv, _: jclass, handle: jlong, media: jboolean, underrun: jboolean, empty: jboolean, head: jint,
) -> jboolean {
    if handle == 0 { 0 } else { (&mut *(handle as *mut AudioBufferProgress)).needs_rebuffer(media != 0, underrun != 0, empty != 0, head) as jboolean }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAudioScheduler_waitingForTail(
    _: *mut JNIEnv, _: jclass, handle: jlong, empty: jboolean,
) -> jboolean {
    if handle == 0 { 0 } else { (&*(handle as *const AudioBufferProgress)).waiting_for_tail(empty != 0) as jboolean }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAudioScheduler_tailReady(
    _: *mut JNIEnv, _: jclass, handle: jlong, now: jlong, wait: jlong,
) -> jboolean {
    if handle == 0 { 0 } else { (&*(handle as *const AudioBufferProgress)).tail_ready(now, wait) as jboolean }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAudioScheduler_writeDisposition(
    _: *mut JNIEnv, _: jclass, count: jint, native: jboolean,
) -> jint { write_disposition(count, native != 0) }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAudioScheduler_sanitizeDelay(
    _: *mut JNIEnv, _: jclass, millis: jint, mode: jint,
) -> jint { sanitize_delay(millis, mode) }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAudioScheduler_bufferPlan(
    env: *mut JNIEnv, _: jclass, media: jboolean, rate: jint, channels: jint, minimum: jint, millis: jint,
) -> jintArray {
    let f = &**env;
    let result = (f.NewIntArray.unwrap())(env, 2);
    if !result.is_null() { (f.SetIntArrayRegion.unwrap())(env, result, 0, 2, buffer_plan(media != 0, rate, channels, minimum, millis).as_ptr()); }
    result
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAudioScheduler_navigationRoute(
    _: *mut JNIEnv, _: jclass, follows: jboolean, configured: jint, ducking: jboolean,
) -> jint { navigation_route(follows != 0, configured, ducking != 0) }
