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

// ---- Audio focus policy ----
//
// Kotlin's AudioFocusCoordinator keeps the platform objects (the active outputs, the
// AudioFocusRequest, the listener) and reports channel activity plus the platform's grant facts
// here; the core owns the channel priority, the request intent and the per-channel target gains.
// One six-slot value token, like `prompt_focus`: 0..2 media/phone/assistant output counts, 3 the
// channel of the request Kotlin still holds (0 none), 4 the reported grant, 5 the media gain level
// (0 full, 1 duck 0.2, 2 mute). Navigation never enters: its prompts run through `prompt_focus`
// with their own platform request, and unknown channel codes are ignored. The executor reports
// every fact — a request result or a listener gain — and the core never infers success.

// Events: 1 acquire, 2 release (channel 1 media, 2 phone, 3 assistant), 3 a ducking loss, 4 a
// transient loss, 5 a gain, 6/7 the requestAudioFocus result (granted/failed), 8 close.
fn focus_primary(state: &[i64; 6]) -> i32 {
    if state[1] > 0 { 2 } else if state[2] > 0 { 3 } else if state[0] > 0 { 1 } else { 0 }
}

// 1 gain, 2 gain transient, 3 gain transient may duck; Kotlin maps them to AudioManager constants.
fn focus_gain_intent(channel: i32) -> i32 {
    match channel { 1 => 1, 2 => 2, 3 => 3, _ => 0 }
}

// A rejected request silences every channel; an ordinary duck lowers only media.
fn focus_media_target(state: &[i64; 6]) -> i32 { if state[4] != 0 { state[5] as i32 } else { 2 } }
fn focus_other_target(state: &[i64; 6]) -> i32 { if state[4] != 0 { 0 } else { 2 } }

// Decision: 0 abandon the held request, 1 make a new one, 2 its channel, 3 its gain intent,
// 4 media gain, 5 other-channel gain (full/duck/mute codes), 6 apply scope (0 nothing, 1 the new
// output only, 2 all outputs). A new request's gains arrive with the reported result, never with
// the plan.
fn focus_step(state: &mut [i64; 6], event: i32, channel: i32, enabled: bool, mute_transient: bool) -> [i32; 7] {
    let mut decision = [0; 7];
    match event {
        1 | 2 => {
            if !enabled || !(1..=3).contains(&channel) { return decision; }
            let slot = (channel - 1) as usize;
            if event == 1 { state[slot] += 1 } else { state[slot] = (state[slot] - 1).max(0) }
            let primary = focus_primary(state);
            if primary == 0 {
                decision[0] = (state[3] != 0) as i32;
                state[3] = 0;
                state[4] = 1;
                state[5] = 0;
            } else if state[3] == primary as i64 && state[4] != 0 {
                // The held request already covers the primary channel; a new output only joins it.
                if event == 1 {
                    decision[4] = focus_media_target(state);
                    decision[5] = focus_other_target(state);
                    decision[6] = 1;
                }
            } else {
                decision[0] = (state[3] != 0) as i32;
                decision[1] = 1;
                decision[2] = primary;
                decision[3] = focus_gain_intent(primary);
            }
        }
        3 | 4 | 5 => {
            let before = (focus_media_target(state), focus_other_target(state));
            match event {
                3 => state[5] = 1,
                4 => if mute_transient { state[5] = 2 },
                _ => { state[4] = 1; state[5] = 0; }
            }
            let after = (focus_media_target(state), focus_other_target(state));
            if before != after {
                decision[4] = after.0;
                decision[5] = after.1;
                decision[6] = 2;
            }
        }
        6 | 7 => {
            if !(1..=3).contains(&channel) { return decision; }
            state[3] = channel as i64;
            state[4] = if event == 6 { 1 } else { 0 };
            state[5] = 0;
            decision[4] = focus_media_target(state);
            decision[5] = focus_other_target(state);
            decision[6] = 2;
        }
        8 => {
            state[0] = 0;
            state[1] = 0;
            state[2] = 0;
            if state[3] != 0 {
                state[3] = 0;
                state[4] = 1;
                state[5] = 0;
                decision[0] = 1;
            }
        }
        _ => {}
    }
    decision
}

/// CarPlay music holds granted focus now, so the car's audio controller already plays Android.
fn focus_holds_media(state: &[i64; 6]) -> bool {
    state[3] == 1 && state[4] != 0
}

// 1 requests focus; release decisions return milliseconds. The tails cover buffered playout,
// while 30/60 s bound missing unduck/silence events observed on head units.
fn prompt_focus(state: &mut [i64; 3], event: i32, enabled: bool, held: bool) -> i32 {
    match event {
        0 if state[2] == 0 && enabled => { state[0] = 1; 1 }
        1 => { state[0] = 0; if state[2] == 0 && held && state[1] == 0 { 1_000 } else { 0 } }
        2 | 3 => {
            state[1] = (state[1] as i32).wrapping_add(if event == 2 { 1 } else { -1 }).max(0) as i64;
            if state[2] != 0 || !enabled { 0 }
            else if event == 2 { 1 }
            else if state[1] == 0 && state[0] == 0 && held { 400 }
            else { 0 }
        }
        4 if state[2] == 0 => { state[2] = 1; 1 }
        5 => if state[1] > 0 { 60_000 } else { 30_000 },
        6 => 60_000,
        _ => 0,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn prompt_focus_tracks_duck_and_overlapping_audio_until_silence_or_close() {
        let mut state = [0; 3];
        assert_eq!(prompt_focus(&mut state, 0, false, false), 0);
        assert_eq!(prompt_focus(&mut state, 0, true, false), 1);
        assert_eq!(prompt_focus(&mut state, 5, true, false), 30_000);
        assert_eq!(prompt_focus(&mut state, 2, true, false), 1);
        assert_eq!(prompt_focus(&mut state, 2, true, true), 1);
        assert_eq!(prompt_focus(&mut state, 5, true, true), 60_000);
        assert_eq!(prompt_focus(&mut state, 6, true, true), 60_000);
        assert_eq!(prompt_focus(&mut state, 1, true, true), 0);
        assert_eq!(prompt_focus(&mut state, 3, true, true), 0);
        assert_eq!(prompt_focus(&mut state, 3, true, true), 400);
        assert_eq!(prompt_focus(&mut state, 1, true, true), 1_000);
        assert_eq!(prompt_focus(&mut state, 1, true, false), 0);
        assert_eq!(prompt_focus(&mut state, 4, true, true), 1);
        assert_eq!(prompt_focus(&mut state, 4, true, true), 0);
        assert_eq!(prompt_focus(&mut state, 2, true, false), 0);
        assert_eq!(prompt_focus(&mut state, 0, true, false), 0);
    }

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

    // Decision slots: [abandon, new request, channel, gain intent, media gain, other gain, scope].
    // Channels 1 media, 2 phone, 3 assistant; gains 0 full, 1 duck 0.2, 2 mute; scope 0 nothing,
    // 1 the new output, 2 all outputs.
    #[test]
    fn focus_priority_asks_phone_above_assistant_above_media_with_their_gain_intents() {
        let mut state = [0; 6];
        // Media alone asks with AUDIOFOCUS_GAIN.
        assert_eq!(focus_step(&mut state, 1, 1, true, true), [0, 1, 1, 1, 0, 0, 0]);
        assert_eq!(focus_step(&mut state, 6, 1, true, true), [0, 0, 0, 0, 0, 0, 2]);
        // Phone outranks media: abandon the held request and ask with GAIN_TRANSIENT.
        assert_eq!(focus_step(&mut state, 1, 2, true, true), [1, 1, 2, 2, 0, 0, 0]);
        assert_eq!(focus_step(&mut state, 6, 2, true, true), [0, 0, 0, 0, 0, 0, 2]);
        // Assistant does not outrank phone: it only joins the held request.
        assert_eq!(focus_step(&mut state, 1, 3, true, true), [0, 0, 0, 0, 0, 0, 1]);
        assert_eq!(focus_step(&mut state, 2, 3, true, true), [0, 0, 0, 0, 0, 0, 0]);
        // Without phone, assistant outranks media with GAIN_TRANSIENT_MAY_DUCK.
        assert_eq!(focus_step(&mut state, 2, 2, true, true), [1, 1, 1, 1, 0, 0, 0]);
        assert_eq!(focus_step(&mut state, 6, 1, true, true), [0, 0, 0, 0, 0, 0, 2]);
        assert_eq!(focus_step(&mut state, 1, 3, true, true), [1, 1, 3, 3, 0, 0, 0]);
        assert_eq!(focus_step(&mut state, 6, 3, true, true), [0, 0, 0, 0, 0, 0, 2]);
        // Releasing the assistant restores media with AUDIOFOCUS_GAIN, abandoning the held one.
        assert_eq!(focus_step(&mut state, 2, 3, true, true), [1, 1, 1, 1, 0, 0, 0]);
        assert_eq!(focus_step(&mut state, 6, 1, true, true), [0, 0, 0, 0, 0, 0, 2]);
        // An output joining a channel the held request already covers asks for nothing.
        assert_eq!(focus_step(&mut state, 1, 1, true, true), [0, 0, 0, 0, 0, 0, 1]);
    }

    #[test]
    fn focus_gain_targets_duck_only_media_and_mute_everything_when_denied() {
        let mut state = [0; 6];
        focus_step(&mut state, 1, 1, true, true);
        focus_step(&mut state, 6, 1, true, true);
        // A ducking loss lowers only the media target.
        assert_eq!(focus_step(&mut state, 3, 0, true, true), [0, 0, 0, 0, 1, 0, 2]);
        // A transient loss with the switch on mutes media only; GAIN restores full everywhere.
        assert_eq!(focus_step(&mut state, 4, 0, true, true), [0, 0, 0, 0, 2, 0, 2]);
        assert_eq!(focus_step(&mut state, 5, 0, true, true), [0, 0, 0, 0, 0, 0, 2]);
        // With the switch off a transient loss leaves the targets alone.
        assert_eq!(focus_step(&mut state, 4, 0, true, false), [0, 0, 0, 0, 0, 0, 0]);
        // A repeated gain that restores nothing applies nothing.
        assert_eq!(focus_step(&mut state, 5, 0, true, true), [0, 0, 0, 0, 0, 0, 0]);
        // A rejected request silences every channel until a gain is reported; a new activity
        // on the same channel abandons the denied request and asks again.
        let mut denied = [0; 6];
        focus_step(&mut denied, 1, 1, true, true);
        assert_eq!(focus_step(&mut denied, 7, 1, true, true), [0, 0, 0, 0, 2, 2, 2]);
        assert_eq!(focus_step(&mut denied, 1, 1, true, true), [1, 1, 1, 1, 0, 0, 0]);
        focus_step(&mut denied, 7, 1, true, true);
        assert_eq!(focus_step(&mut denied, 5, 0, true, true), [0, 0, 0, 0, 0, 0, 2]);
    }

    #[test]
    fn focus_release_and_close_abandon_the_held_request() {
        let mut state = [0; 6];
        // Closing before any activity leaves nothing to abandon.
        assert_eq!(focus_step(&mut state, 8, 0, true, true), [0, 0, 0, 0, 0, 0, 0]);
        focus_step(&mut state, 1, 1, true, true);
        focus_step(&mut state, 6, 1, true, true);
        // The last release abandons the held request and resets the reported facts.
        assert_eq!(focus_step(&mut state, 2, 1, true, true), [1, 0, 0, 0, 0, 0, 0]);
        assert!(!focus_holds_media(&state));
        // Close with a request still held abandons it.
        focus_step(&mut state, 1, 1, true, true);
        focus_step(&mut state, 6, 1, true, true);
        assert_eq!(focus_step(&mut state, 8, 0, true, true), [1, 0, 0, 0, 0, 0, 0]);
        assert!(!focus_holds_media(&state));
    }

    #[test]
    fn focus_records_only_the_reported_grant_facts() {
        let mut state = [0; 6];
        focus_step(&mut state, 1, 1, true, true);
        // The plan for a new request carries no gains; the result does.
        assert!(!focus_holds_media(&state));
        focus_step(&mut state, 6, 1, true, true);
        assert!(focus_holds_media(&state));
        // A failed result keeps the request held but not granted; a reported gain restores it.
        focus_step(&mut state, 7, 1, true, true);
        assert!(!focus_holds_media(&state));
        focus_step(&mut state, 5, 0, true, true);
        assert!(focus_holds_media(&state));
        // Phone holding the request never counts as media focus.
        let mut phone = [0; 6];
        focus_step(&mut phone, 1, 2, true, true);
        focus_step(&mut phone, 6, 2, true, true);
        assert!(!focus_holds_media(&phone));
    }

    #[test]
    fn focus_never_lets_navigation_or_unknown_channels_enter() {
        let mut state = [0; 6];
        // Navigation (0) and unknown codes are ignored for activity, release and results.
        assert_eq!(focus_step(&mut state, 1, 0, true, true), [0, 0, 0, 0, 0, 0, 0]);
        assert_eq!(focus_step(&mut state, 1, 4, true, true), [0, 0, 0, 0, 0, 0, 0]);
        assert_eq!(focus_step(&mut state, 2, 0, true, true), [0, 0, 0, 0, 0, 0, 0]);
        assert_eq!(focus_step(&mut state, 6, 0, true, true), [0, 0, 0, 0, 0, 0, 0]);
        assert_eq!(state, [0; 6]);
        // With the setting off nothing is requested at all.
        assert_eq!(focus_step(&mut state, 1, 1, false, true), [0, 0, 0, 0, 0, 0, 0]);
        assert_eq!(state, [0; 6]);
    }
}

// ---- JNI boundary: `com.shihab.diplay.media.NativeAudioScheduler` ----

use jni_sys::{jboolean, jclass, jint, jintArray, jlong, jlongArray, JNIEnv};

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAudioScheduler_promptFocus(
    env: *mut JNIEnv, _: jclass, owner: jlongArray, event: jint, enabled: jboolean, held: jboolean,
) -> jint {
    let f = &**env;
    let mut state = [0; 3];
    (f.GetLongArrayRegion.unwrap())(env, owner, 0, 3, state.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 { return 0; }
    let result = prompt_focus(&mut state, event, enabled != 0, held != 0);
    (f.SetLongArrayRegion.unwrap())(env, owner, 0, 3, state.as_ptr());
    result
}

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

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAudioScheduler_focusStep(
    env: *mut JNIEnv, _: jclass, state: jlongArray, event: jint, channel: jint,
    enabled: jboolean, mute_transient: jboolean,
) -> jintArray {
    let f = &**env;
    let mut token = [0; 6];
    (f.GetLongArrayRegion.unwrap())(env, state, 0, 6, token.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 { return std::ptr::null_mut(); }
    let decision = focus_step(&mut token, event, channel, enabled != 0, mute_transient != 0);
    (f.SetLongArrayRegion.unwrap())(env, state, 0, 6, token.as_ptr());
    let result = (f.NewIntArray.unwrap())(env, 7);
    if !result.is_null() { (f.SetIntArrayRegion.unwrap())(env, result, 0, 7, decision.as_ptr()); }
    result
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAudioScheduler_mediaFocusHeld(
    env: *mut JNIEnv, _: jclass, state: jlongArray,
) -> jboolean {
    let f = &**env;
    let mut token = [0; 6];
    (f.GetLongArrayRegion.unwrap())(env, state, 0, 6, token.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 { return 0; }
    focus_holds_media(&token) as jboolean
}
