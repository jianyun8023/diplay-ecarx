//! The audio renderer's platform-output buffer accounting (Kotlin's old `AudioBufferProgress`):
//! it tracks the unsigned AudioTrack/AAudio head across wrap without flushing queued sound, and
//! decides the music rebuffer. Kotlin still performs the writes and owns the platform output.

/// The device may grant a smaller track than requested; the start level must stay inside it.
pub fn start_bytes_for(planned_start_bytes: i32, actual_capacity_bytes: i32, write_chunk_bytes: i32) -> i32 {
    if actual_capacity_bytes <= 0 {
        return planned_start_bytes;
    }
    planned_start_bytes
        .min(actual_capacity_bytes - write_chunk_bytes)
        .max(write_chunk_bytes)
}

pub struct AudioBufferProgress {
    frame_bytes: i64,
    written_bytes: i64,
    played_frames: i64,
    last_head: i64,
}

impl AudioBufferProgress {
    pub fn new(frame_bytes: i32) -> AudioBufferProgress {
        AudioBufferProgress { frame_bytes: frame_bytes.max(1) as i64, written_bytes: 0, played_frames: 0, last_head: 0 }
    }

    /// A freshly built output starts from zero without releasing the native owner.
    pub fn reset(&mut self) {
        self.written_bytes = 0;
        self.played_frames = 0;
        self.last_head = 0;
    }

    pub fn written(&mut self, bytes: i32) {
        self.written_bytes += bytes.max(0) as i64;
    }

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
}

// ---- JNI boundary: `com.shilapi.xcertplay.media.NativeAudioScheduler` ----

use jni_sys::{jboolean, jclass, jint, jlong, JNIEnv};

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeAudioScheduler_create(
    _env: *mut JNIEnv, _class: jclass, frame_bytes: jint,
) -> jlong {
    Box::into_raw(Box::new(AudioBufferProgress::new(frame_bytes))) as jlong
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeAudioScheduler_destroy(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) {
    if handle != 0 { drop(Box::from_raw(handle as *mut AudioBufferProgress)); }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeAudioScheduler_reset(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) {
    if handle != 0 { (&mut *(handle as *mut AudioBufferProgress)).reset(); }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeAudioScheduler_written(
    _env: *mut JNIEnv, _class: jclass, handle: jlong, bytes: jint,
) {
    if handle != 0 { (&mut *(handle as *mut AudioBufferProgress)).written(bytes); }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeAudioScheduler_queuedBytes(
    _env: *mut JNIEnv, _class: jclass, handle: jlong, raw_head: jint,
) -> jlong {
    if handle == 0 { 0 } else { (&mut *(handle as *mut AudioBufferProgress)).queued_bytes(raw_head) }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeAudioScheduler_shouldRebuffer(
    _env: *mut JNIEnv, _class: jclass, handle: jlong, is_media: jboolean, playing: jboolean,
    underrun_since_start: jboolean, compressed_queue_empty: jboolean, raw_head: jint, floor_bytes: jlong,
) -> jboolean {
    if handle == 0 { return 0; }
    (&mut *(handle as *mut AudioBufferProgress)).should_rebuffer(
        is_media != 0, playing != 0, underrun_since_start != 0, compressed_queue_empty != 0, raw_head, floor_bytes,
    ) as jboolean
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeAudioScheduler_startBytesFor(
    _env: *mut JNIEnv, _class: jclass, planned_start_bytes: jint, actual_capacity_bytes: jint, write_chunk_bytes: jint,
) -> jint {
    start_bytes_for(planned_start_bytes, actual_capacity_bytes, write_chunk_bytes)
}
