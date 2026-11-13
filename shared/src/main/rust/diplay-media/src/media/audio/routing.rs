//! CarPlay metadata selects the route; Kotlin builds the Android AudioAttributes/AudioTrack.
use jni_sys::{jclass, jint, jintArray, jstring, JNIEnv};

fn channel(audio_type: &[u16], payload: i32, automotive: bool, navigation: i32) -> [i32; 3] {
    let name = |expected: &str| audio_type.iter().copied().eq(expected.encode_utf16());
    if name("telephony") { return [1, 1, 3]; }
    if name("speechrecognition") { return [2, 1, 3]; }
    if name("media") || automotive && name("compatibility") { return [0, 0, 3]; }
    if name("default") || name("alert") || name("compatibility") || payload != 102 {
        [3, 1, navigation]
    } else { [0, 0, 3] }
}

fn speaker(channel: i32, content: i32, stream: i32) -> [i32; 3] {
    if channel == 1 { [0, 1, stream] } else { [channel, content, stream] }
}

unsafe fn selection(env: *mut JNIEnv, values: [i32; 3]) -> jintArray {
    let f = &**env;
    let result = (f.NewIntArray.unwrap())(env, 3);
    if !result.is_null() { (f.SetIntArrayRegion.unwrap())(env, result, 0, 3, values.as_ptr()); }
    result
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAudioRouting_map(
    env: *mut JNIEnv, _: jclass, audio_type: jstring, payload: jint, mode: jint, navigation: jint,
) -> jintArray {
    let f = &**env;
    let length = (f.GetStringLength.unwrap())(env, audio_type);
    let mut text = vec![0; length as usize];
    (f.GetStringRegion.unwrap())(env, audio_type, 0, length, text.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 { return std::ptr::null_mut(); }
    selection(env, channel(&text, payload, mode == 1, navigation))
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeAudioRouting_speaker(
    env: *mut JNIEnv, _: jclass, channel: jint, content: jint, stream: jint,
) -> jintArray { selection(env, speaker(channel, content, stream)) }

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn known_types_override_payload_and_unknown_types_use_main_high_audio() {
        for automotive in [false, true] {
            for (name, expected) in [("telephony", [1, 1, 3]), ("speechrecognition", [2, 1, 3]),
                ("media", [0, 0, 3]), ("default", [3, 1, 14]), ("alert", [3, 1, 14])] {
                assert_eq!(channel(&name.encode_utf16().collect::<Vec<_>>(), 102, automotive, 14), expected);
            }
            assert_eq!(channel(&[], 102, automotive, 7), [0, 0, 3]);
            assert_eq!(channel(&[], 100, automotive, 7), [3, 1, 7]);
        }
        let compatibility: Vec<_> = "compatibility".encode_utf16().collect();
        assert_eq!(channel(&compatibility, 102, false, 14), [3, 1, 14]);
        assert_eq!(channel(&compatibility, 100, true, 14), [0, 0, 3]);
        assert_eq!(speaker(1, 1, 7), [0, 1, 7]);
        assert_eq!(speaker(3, 1, 14), [3, 1, 14]);
    }
}
