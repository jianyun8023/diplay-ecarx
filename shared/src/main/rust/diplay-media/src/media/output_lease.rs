//! Output-identity leases shared across sessions of one media engine.
//!
//! The platform registers every accepted audio, microphone and screen output with a lease keyed by
//! (engine epoch, kind, stream type, audioType) plus the holder session. Closing a stream releases
//! its lease; only the last release of an output identity may stop that output. This owns the stream
//! identity and output-sharing relation that used to be re-derived by scanning Kotlin session maps
//! at every cleanup site. The epoch scopes one engine's leases so a replaced engine starts empty.
use jni_sys::{jboolean, jclass, jint, jlong, jstring, JNIEnv};
use std::collections::{HashMap, HashSet};
use std::sync::{Mutex, OnceLock};

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum OutputKind {
    Audio = 0,
    Microphone = 1,
    Screen = 2,
}

impl OutputKind {
    fn from_code(code: i32) -> Option<Self> {
        match code {
            0 => Some(Self::Audio),
            1 => Some(Self::Microphone),
            2 => Some(Self::Screen),
            _ => None,
        }
    }

    fn code(self) -> i32 {
        self as i32
    }
}

#[derive(Clone, PartialEq, Eq, Hash)]
struct OutputKey {
    epoch: i64,
    kind: i32,
    stream_type: i32,
    audio_type: String,
}

#[derive(Default)]
struct Registry {
    /// Active output identity -> holder sessions that still reference it.
    outputs: HashMap<OutputKey, HashSet<i64>>,
}

fn registry() -> &'static Mutex<Registry> {
    static REGISTRY: OnceLock<Mutex<Registry>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(Registry::default()))
}

/// Add `holder`'s lease on an output identity. Acquiring is idempotent for one holder.
pub fn acquire(epoch: i64, holder: i64, kind: OutputKind, stream_type: i32, audio_type: String) {
    let key = OutputKey { epoch, kind: kind.code(), stream_type, audio_type };
    registry().lock().unwrap().outputs.entry(key).or_default().insert(holder);
}

/// Release `holder`'s lease on an output identity. Returns true when no holder remains, i.e. the
/// platform may stop the shared output. Releasing an identity that was never acquired is treated as
/// the last release so a caller that guards on the return value never leaves a stale output running.
pub fn release(epoch: i64, holder: i64, kind: OutputKind, stream_type: i32, audio_type: String) -> bool {
    let key = OutputKey { epoch, kind: kind.code(), stream_type, audio_type };
    let mut registry = registry().lock().unwrap();
    let Some(holders) = registry.outputs.get_mut(&key) else { return true };
    holders.remove(&holder);
    let empty = holders.is_empty();
    if empty {
        registry.outputs.remove(&key);
    }
    empty
}

unsafe fn read_utf16(env: *mut JNIEnv, string: jstring) -> Option<String> {
    if string.is_null() {
        return Some(String::new());
    }
    let f = &**env;
    let length = (f.GetStringLength.unwrap())(env, string);
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return None;
    }
    let mut units = vec![0u16; length.max(0) as usize];
    if length > 0 {
        (f.GetStringRegion.unwrap())(env, string, 0, length, units.as_mut_ptr());
        if (f.ExceptionCheck.unwrap())(env) != 0 {
            return None;
        }
    }
    Some(String::from_utf16_lossy(&units))
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_outputLeaseAcquire(
    env: *mut JNIEnv, _class: jclass, epoch: jlong, holder: jlong, kind: jint,
    stream_type: jint, audio_type: jstring,
) {
    let Some(kind) = OutputKind::from_code(kind) else { return };
    let Some(audio_type) = read_utf16(env, audio_type) else { return };
    acquire(epoch, holder, kind, stream_type, audio_type);
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_outputLeaseRelease(
    env: *mut JNIEnv, _class: jclass, epoch: jlong, holder: jlong, kind: jint,
    stream_type: jint, audio_type: jstring,
) -> jboolean {
    let Some(kind) = OutputKind::from_code(kind) else { return 0 };
    let Some(audio_type) = read_utf16(env, audio_type) else { return 0 };
    release(epoch, holder, kind, stream_type, audio_type) as jboolean
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn last_release_of_a_shared_output_is_the_only_stop() {
        let epoch = 1;
        acquire(epoch, 10, OutputKind::Audio, 100, "media".into());
        acquire(epoch, 11, OutputKind::Audio, 100, "media".into());
        assert!(!release(epoch, 10, OutputKind::Audio, 100, "media".into()));
        assert!(release(epoch, 11, OutputKind::Audio, 100, "media".into()));
        // The identity is gone; a later stray release still reports "may stop".
        assert!(release(epoch, 11, OutputKind::Audio, 100, "media".into()));
    }

    #[test]
    fn kinds_audio_types_and_epochs_are_independent() {
        let e = 7;
        // The same holder on several distinct output identities.
        acquire(e, 1, OutputKind::Audio, 100, "media".into());
        acquire(e, 1, OutputKind::Microphone, 100, "media".into());
        acquire(e, 1, OutputKind::Screen, 110, String::new());
        acquire(e, 1, OutputKind::Audio, 100, "telephony".into());
        // Releasing audio "media" empties only that identity.
        assert!(release(e, 1, OutputKind::Audio, 100, "media".into()));
        // Every other identity still holds holder 1: a second holder sees a non-empty set (false),
        // then holder 1 is its last release.
        for (kind, stream_type, audio_type) in [
            (OutputKind::Microphone, 100, "media"),
            (OutputKind::Screen, 110, ""),
            (OutputKind::Audio, 100, "telephony"),
        ] {
            acquire(e, 2, kind, stream_type, audio_type.into());
            assert!(!release(e, 2, kind, stream_type, audio_type.into()));
            assert!(release(e, 1, kind, stream_type, audio_type.into()));
        }
    }

    #[test]
    fn acquiring_twice_for_one_holder_does_not_double_count_and_other_epoch_is_isolated() {
        acquire(3, 5, OutputKind::Screen, 111, String::new());
        acquire(3, 5, OutputKind::Screen, 111, String::new());
        // A different engine epoch cannot see the first engine's holder.
        assert!(release(4, 5, OutputKind::Screen, 111, String::new()));
        assert!(release(3, 5, OutputKind::Screen, 111, String::new()));
    }
}
