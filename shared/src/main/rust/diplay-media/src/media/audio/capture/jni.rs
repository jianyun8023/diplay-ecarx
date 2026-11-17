use super::{echo, stats, Capture, Speex, Uplink};
use crate::platform::jvm::Env;
use jni_sys::{
    jboolean, jbyteArray, jclass, jint, jlong, jlongArray, jobject, jstring, JNIEnv, JNI_ABORT,
};
use std::ptr::null_mut;

unsafe fn state<'a>(handle: jlong) -> &'a mut Capture {
    &mut *(handle as *mut Capture)
}

unsafe fn load_stats(env: *mut JNIEnv, owner: jlongArray) -> Option<stats::Stats> {
    let mut values = [0; stats::SLOTS];
    ((**env).GetLongArrayRegion.unwrap())(env, owner, 0, stats::SLOTS as i32, values.as_mut_ptr());
    (((**env).ExceptionCheck.unwrap())(env) == 0).then_some(stats::Stats(values))
}

unsafe fn save_stats(env: *mut JNIEnv, owner: jlongArray, stats: &stats::Stats) {
    ((**env).SetLongArrayRegion.unwrap())(env, owner, 0, stats::SLOTS as i32, stats.0.as_ptr());
}

unsafe fn longs(env: *mut JNIEnv, values: &[i64]) -> jlongArray {
    let result = ((**env).NewLongArray.unwrap())(env, values.len() as i32);
    if !result.is_null() {
        ((**env).SetLongArrayRegion.unwrap())(env, result, 0, values.len() as i32, values.as_ptr());
    }
    result
}

/// Takes a source snapshot at each frame boundary. Retain succeeds before the old lease is retired.
unsafe fn refresh(env: *mut JNIEnv, capture: &mut Capture, source: jobject) -> bool {
    let f = &**env;
    if capture.echo.is_some() && (f.IsSameObject.unwrap())(env, source, capture.source) != 0 {
        return true;
    }
    let mut next = None;
    let mut identity = null_mut();
    if !source.is_null() {
        let class = (f.GetObjectClass.unwrap())(env, source);
        let retain = (f.GetMethodID.unwrap())(
            env,
            class,
            c"retainForNativeCapture".as_ptr(),
            c"()J".as_ptr(),
        );
        if retain.is_null() {
            (f.DeleteLocalRef.unwrap())(env, class);
            return false;
        }
        let handle = (f.CallLongMethodA.unwrap())(env, source, retain, std::ptr::null());
        (f.DeleteLocalRef.unwrap())(env, class);
        if (f.ExceptionCheck.unwrap())(env) != 0 {
            return false;
        }
        if handle != 0 {
            next = Some(*Box::from_raw(handle as *mut echo::SharedReference));
            identity = (f.NewGlobalRef.unwrap())(env, source);
            if identity.is_null() {
                return false;
            }
        }
    }
    Env(env).delete_global(capture.source);
    capture.source = identity;
    capture.echo = next;
    true
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeMicrophone_create(
    env: *mut JNIEnv,
    _: jclass,
    key: jbyteArray,
    payload_type: jint,
    address: jbyteArray,
    scope: jint,
    port: jint,
    bitrate: jint,
    samples_per_packet: jint,
    frame_bytes: jint,
    rate: jint,
    channels: jint,
    seed_nonce: jlong,
) -> jlong {
    let e = Env(env);
    if frame_bytes <= 0
        || frame_bytes % 2 != 0
        || rate <= 0
        || channels <= 0
        || e.array_len(key) != 32
    {
        return 0;
    }
    let mut key_bytes = [0; 32];
    let mut address_bytes = vec![0; e.array_len(address)];
    if !e.read_array(key, 0, &mut key_bytes) || !e.read_array(address, 0, &mut address_bytes) {
        return 0;
    }
    let Some(sender) = Uplink::new(
        &key_bytes,
        payload_type,
        &address_bytes,
        scope,
        port,
        bitrate,
        samples_per_packet,
        seed_nonce as u64,
    ) else {
        return 0;
    };
    Box::into_raw(Box::new(Capture {
        sender,
        frame: vec![0; frame_bytes as usize],
        filled: 0,
        rate,
        channels,
        clock: [0; 3],
        speex: None,
        reference: vec![0; frame_bytes as usize / 2],
        echo: None,
        source: null_mut(),
    })) as jlong
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeMicrophone_enableEcho(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    source: jobject,
) -> jboolean {
    if handle == 0 {
        return 0;
    }
    let capture = state(handle);
    if !refresh(env, capture, source) || capture.channels != 1 {
        return 0;
    }
    let compatible = capture
        .echo
        .as_ref()
        .is_some_and(|echo| echo.lock().unwrap().rate == capture.rate as i64);
    if compatible {
        capture.speex = Speex::new(capture.reference.len(), capture.rate);
    }
    capture.speex.is_some() as jboolean
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeMicrophone_disableEcho(
    _: *mut JNIEnv,
    _: jclass,
    handle: jlong,
) {
    if handle != 0 {
        state(handle).speex = None;
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeMicrophone_feed(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    input: jbyteArray,
    count: jint,
    now: jlong,
    stamped: jboolean,
    timestamp: jlong,
    frame_position: jlong,
    host: jobject,
    stats_owner: jlongArray,
) -> jint {
    let f = &**env;
    if handle == 0 || count <= 0 || count > (f.GetArrayLength.unwrap())(env, input) {
        return -1;
    }
    let Some(mut stats) = load_stats(env, stats_owner) else {
        return -1;
    };
    let capture = state(handle);
    echo::capture_read(
        &mut capture.clock,
        capture.rate,
        count / 2,
        now,
        stamped != 0,
        timestamp,
        frame_position,
    );
    let class = (f.GetObjectClass.unwrap())(env, host);
    let running = (f.GetMethodID.unwrap())(env, class, c"captureRunning".as_ptr(), c"()Z".as_ptr());
    let source = (f.GetMethodID.unwrap())(
        env,
        class,
        c"captureEchoSource".as_ptr(),
        c"()Lcom/shihab/diplay/media/EchoReference;".as_ptr(),
    );
    let restore = (f.GetMethodID.unwrap())(
        env,
        class,
        c"restorePlatformEchoCancellation".as_ptr(),
        c"()V".as_ptr(),
    );
    let system = (f.FindClass.unwrap())(env, c"java/lang/System".as_ptr());
    let nano_time =
        (f.GetStaticMethodID.unwrap())(env, system, c"nanoTime".as_ptr(), c"()J".as_ptr());
    if running.is_null() || source.is_null() || restore.is_null() || nano_time.is_null() {
        return -1;
    }
    let bytes = (f.GetByteArrayElements.unwrap())(env, input, null_mut());
    if bytes.is_null() {
        return -1;
    }
    let input_bytes = std::slice::from_raw_parts(bytes.cast::<u8>(), count as usize);
    let mut offset = 0;
    let mut result = 0;
    while offset < input_bytes.len() {
        if (f.CallBooleanMethodA.unwrap())(env, host, running, std::ptr::null()) == 0
            || (f.ExceptionCheck.unwrap())(env) != 0
        {
            break;
        }
        offset += capture.fill(&input_bytes[offset..]);
        if capture.filled < capture.frame.len() {
            continue;
        }
        if capture.speex.is_some() {
            let current = (f.CallObjectMethodA.unwrap())(env, host, source, std::ptr::null());
            if (f.ExceptionCheck.unwrap())(env) != 0 {
                break;
            }
            let refreshed = refresh(env, capture, current);
            Env(env).delete_local(current);
            if !refreshed {
                break;
            }
            let end = echo::capture_end(&capture.clock, capture.rate, offset as i32 / 2);
            if !capture.process_echo(end) {
                capture.speex = None;
                (f.CallVoidMethodA.unwrap())(env, host, restore, std::ptr::null());
                if (f.ExceptionCheck.unwrap())(env) != 0 {
                    break;
                }
            }
        }
        stats.level(&capture.frame);
        let sent = capture.sender.send(&capture.frame);
        if sent >= 0 {
            stats.event(2, sent as i64, (sent == 0) as i64, 0);
            for _ in 0..sent {
                let now =
                    (f.CallStaticLongMethodA.unwrap())(env, system, nano_time, std::ptr::null());
                stats.event(3, 0, 0, now);
            }
        } else if sent == -1 {
            stats.event(2, 0, 1, 0);
        } else {
            stats.event(2, 1, 0, 0);
            stats.event(4, 0, 0, 0);
            result = sent;
        }
        capture.filled = 0;
        if result < 0 {
            break;
        }
    }
    (f.ReleaseByteArrayElements.unwrap())(env, input, bytes, JNI_ABORT);
    // Preserve callback failures while still committing statistics for preceding completed frames.
    let failure = (f.ExceptionOccurred.unwrap())(env);
    if !failure.is_null() {
        (f.ExceptionClear.unwrap())(env);
    }
    save_stats(env, stats_owner, &stats);
    if !failure.is_null() {
        (f.Throw.unwrap())(env, failure);
        (f.DeleteLocalRef.unwrap())(env, failure);
    }
    (f.DeleteLocalRef.unwrap())(env, class);
    (f.DeleteLocalRef.unwrap())(env, system);
    result
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeMicrophone_close(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
) {
    if handle != 0 {
        let capture = Box::from_raw(handle as *mut Capture);
        Env(env).delete_global(capture.source);
        drop(capture);
    }
}

/// One microphone id's committed effective sender configuration, so a newer configuration for the
/// same id is replaced instead of silently reusing the earlier sender. The platform supplies the
/// identity facts; this owner decides reuse vs replace and records when a new sender actually ran.
pub struct UplinkConfigHandoff {
    effective: Option<String>,
    key_fingerprint: Option<String>,
}

/// 0 reuse (effective config identical), 2 replace while keeping the ChaCha nonce (same key, other
/// settings changed), 1 replace with a fresh nonce (a different key).
fn config_decision(
    effective: &Option<String>,
    committed_key: &Option<String>,
    new: &str,
    key: &str,
) -> jint {
    if effective.as_deref() == Some(new) {
        0
    } else if committed_key.as_deref() == Some(key) {
        2
    } else {
        1
    }
}

unsafe fn read_utf16(env: *mut JNIEnv, s: jstring) -> Option<String> {
    if s.is_null() {
        return Some(String::new());
    }
    let f = &**env;
    let length = (f.GetStringLength.unwrap())(env, s);
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return None;
    }
    let mut units = vec![0u16; length.max(0) as usize];
    if length > 0 {
        (f.GetStringRegion.unwrap())(env, s, 0, length, units.as_mut_ptr());
        if (f.ExceptionCheck.unwrap())(env) != 0 {
            return None;
        }
    }
    Some(String::from_utf16_lossy(&units))
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeMicrophone_uplinkConfigCreate(
    _env: *mut JNIEnv,
    _: jclass,
) -> jlong {
    Box::into_raw(Box::new(UplinkConfigHandoff {
        effective: None,
        key_fingerprint: None,
    })) as jlong
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeMicrophone_uplinkConfigDecide(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    fingerprint: jstring,
    key_fingerprint: jstring,
) -> jint {
    if handle == 0 {
        return 1;
    }
    let (Some(fp), Some(kfp)) = (
        read_utf16(env, fingerprint),
        read_utf16(env, key_fingerprint),
    ) else {
        return 1;
    };
    let handoff = &*(handle as *const UplinkConfigHandoff);
    config_decision(&handoff.effective, &handoff.key_fingerprint, &fp, &kfp)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeMicrophone_uplinkConfigCommit(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    fingerprint: jstring,
    key_fingerprint: jstring,
) {
    if handle == 0 {
        return;
    }
    let (Some(fp), Some(kfp)) = (
        read_utf16(env, fingerprint),
        read_utf16(env, key_fingerprint),
    ) else {
        return;
    };
    let handoff = &mut *(handle as *mut UplinkConfigHandoff);
    handoff.effective = Some(fp);
    handoff.key_fingerprint = Some(kfp);
}

/// The current ChaCha counter of a running sender, snapshotted before it is replaced.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeMicrophone_uplinkNonce(
    _env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
) -> jlong {
    if handle == 0 {
        return 0;
    }
    (*(handle as *const Capture)).sender.nonce() as jlong
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeMicrophone_uplinkConfigDestroy(
    _env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
) {
    if handle != 0 {
        drop(Box::from_raw(handle as *mut UplinkConfigHandoff));
    }
}

#[cfg(test)]
mod uplink_cfg_tests {
    use super::config_decision;

    #[test]
    fn reuse_only_for_the_committed_effective_configuration() {
        // Nothing committed, or a different key: replace with a fresh nonce.
        assert_eq!(config_decision(&None, &None, "a", "K"), 1);
        assert_eq!(
            config_decision(&Some("a".into()), &Some("K".into()), "b", "K2"),
            1
        );
        // Same effective configuration: reuse the sender.
        assert_eq!(
            config_decision(&Some("a".into()), &Some("K".into()), "a", "K"),
            0
        );
        // Different configuration but the same key: replace while preserving the ChaCha nonce.
        assert_eq!(
            config_decision(&Some("a".into()), &Some("K".into()), "b", "K"),
            2
        );
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeCaptureStats_create(
    env: *mut JNIEnv,
    _: jclass,
    now: jlong,
) -> jlongArray {
    longs(env, &stats::Stats::new(now).0)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeCaptureStats_event(
    env: *mut JNIEnv,
    _: jclass,
    owner: jlongArray,
    event: jint,
    value: jlong,
    extra: jlong,
    now: jlong,
) {
    if let Some(mut stats) = load_stats(env, owner) {
        stats.event(event, value, extra, now);
        save_stats(env, owner, &stats);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeCaptureStats_level(
    env: *mut JNIEnv,
    _: jclass,
    owner: jlongArray,
    pcm: jbyteArray,
) {
    let Some(mut stats) = load_stats(env, owner) else {
        return;
    };
    let f = &**env;
    let length = (f.GetArrayLength.unwrap())(env, pcm) as usize;
    let bytes = (f.GetByteArrayElements.unwrap())(env, pcm, null_mut());
    if bytes.is_null() {
        return;
    }
    stats.level(std::slice::from_raw_parts(bytes.cast::<u8>(), length));
    (f.ReleaseByteArrayElements.unwrap())(env, pcm, bytes, JNI_ABORT);
    save_stats(env, owner, &stats);
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeCaptureStats_snapshot(
    env: *mut JNIEnv,
    _: jclass,
    owner: jlongArray,
    now: jlong,
    ended: jboolean,
) -> jlongArray {
    let Some(mut stats) = load_stats(env, owner) else {
        return null_mut();
    };
    let Some(snapshot) = stats.snapshot(now, ended != 0) else {
        return null_mut();
    };
    let result = longs(env, &snapshot);
    if !result.is_null() {
        save_stats(env, owner, &stats);
    }
    result
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_media_NativeCaptureStats_peakDbfs(
    _: *mut JNIEnv,
    _: jclass,
    peak: jint,
) -> jlong {
    stats::peak_dbfs(peak as i64)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, Mutex};

    struct Source {
        reference: Option<echo::SharedReference>,
        retains: usize,
    }
    impl Source {
        fn new(value: i16, rate: i32) -> Box<Self> {
            let mut reference = echo::EchoReference::new(rate, 32_000);
            let pcm: Vec<u8> = (0..32_000).flat_map(|_| value.to_le_bytes()).collect();
            reference.append(&pcm, 1, Some(16_000), 0);
            Box::new(Self {
                reference: Some(Arc::new(Mutex::new(reference))),
                retains: 0,
            })
        }
        fn object(&mut self) -> jobject {
            (self as *mut Self).cast()
        }
    }

    #[test]
    fn source_identity_retains_closed_tail_retries_closed_candidates_and_releases_old_rings() {
        unsafe extern "system" fn class(_: *mut JNIEnv, _: jobject) -> jclass {
            1usize as jclass
        }
        unsafe extern "system" fn method(
            _: *mut JNIEnv,
            _: jclass,
            _: *const std::ffi::c_char,
            _: *const std::ffi::c_char,
        ) -> jni_sys::jmethodID {
            1usize as jni_sys::jmethodID
        }
        unsafe extern "system" fn retain(
            _: *mut JNIEnv,
            source: jobject,
            _: jni_sys::jmethodID,
            _: *const jni_sys::jvalue,
        ) -> jlong {
            let source = &mut *source.cast::<Source>();
            source.retains += 1;
            source
                .reference
                .as_ref()
                .map(|r| Box::into_raw(Box::new(r.clone())) as jlong)
                .unwrap_or(0)
        }
        unsafe extern "system" fn same(_: *mut JNIEnv, a: jobject, b: jobject) -> jboolean {
            (a == b) as jboolean
        }
        unsafe extern "system" fn global(_: *mut JNIEnv, a: jobject) -> jobject {
            a
        }
        unsafe extern "system" fn delete(_: *mut JNIEnv, _: jobject) {}
        unsafe extern "system" fn ok(_: *mut JNIEnv) -> jboolean {
            0
        }
        let mut functions: jni_sys::JNINativeInterface_ = unsafe { std::mem::zeroed() };
        functions.GetObjectClass = Some(class);
        functions.GetMethodID = Some(method);
        functions.CallLongMethodA = Some(retain);
        functions.IsSameObject = Some(same);
        functions.NewGlobalRef = Some(global);
        functions.DeleteGlobalRef = Some(delete);
        functions.DeleteLocalRef = Some(delete);
        functions.ExceptionCheck = Some(ok);
        let mut env: JNIEnv = &functions;
        let mut capture = super::super::tests::capture(16_000, 320);
        let mut first = Source::new(111, 16_000);
        let first_weak = Arc::downgrade(first.reference.as_ref().unwrap());
        unsafe {
            assert!(refresh(&mut env, &mut capture, first.object()));
        }
        assert!(capture.process_echo(-30_000_000));
        assert_eq!(capture.reference, vec![111; 320]);
        first.reference = None;
        unsafe {
            assert!(refresh(&mut env, &mut capture, first.object()));
        }
        assert_eq!(first.retains, 1);
        assert!(capture.process_echo(-30_000_000));
        assert_eq!(capture.reference, vec![111; 320]);
        assert!(first_weak.upgrade().is_some());

        let mut second = Source::new(222, 16_000);
        unsafe {
            assert!(refresh(&mut env, &mut capture, second.object()));
        }
        assert!(first_weak.upgrade().is_none());
        assert!(capture.process_echo(-30_000_000));
        assert_eq!(capture.reference, vec![222; 320]);
        unsafe {
            assert!(refresh(&mut env, &mut capture, null_mut()));
        }
        assert_eq!(Arc::strong_count(second.reference.as_ref().unwrap()), 1);
        assert!(capture.process_echo(-30_000_000));
        assert_eq!(capture.reference, vec![0; 320]);

        let mut closed = Source::new(333, 16_000);
        closed.reference = None; // producer closed between source lookup and native retain
        for _ in 0..2 {
            unsafe {
                assert!(refresh(&mut env, &mut capture, closed.object()));
            }
        }
        assert_eq!(closed.retains, 2);
        assert!(capture.echo.is_none() && capture.source.is_null());
        assert!(capture.process_echo(-30_000_000));
        assert_eq!(capture.reference, vec![0; 320]);

        let mut recovered = Source::new(444, 16_000);
        unsafe {
            assert!(refresh(&mut env, &mut capture, recovered.object()));
        }
        assert!(capture.process_echo(-30_000_000));
        assert_eq!(capture.reference, vec![444; 320]);
        let mut incompatible = Source::new(555, 8_000);
        unsafe {
            assert!(refresh(&mut env, &mut capture, incompatible.object()));
        }
        assert_eq!(Arc::strong_count(recovered.reference.as_ref().unwrap()), 1);
        assert!(!capture.process_echo(-30_000_000));
        assert_eq!(capture.reference, vec![0; 320]);
        drop(capture);
        assert_eq!(
            Arc::strong_count(incompatible.reference.as_ref().unwrap()),
            1
        );
    }
}
