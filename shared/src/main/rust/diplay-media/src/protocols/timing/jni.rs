//! NtpClock serializes this single opaque value under its existing clock monitor.
use super::{action, TimingClock, PACKET_BYTES, WORDS};
use jni_sys::{jboolean, jbyteArray, jclass, jint, jlong, jlongArray, JNIEnv};
use std::ptr::null_mut;

unsafe fn invalid(env: *mut JNIEnv) {
    let f = &**env;
    if (f.ExceptionCheck.unwrap())(env) != 0 { return; }
    let class = (f.FindClass.unwrap())(env, c"java/lang/IllegalArgumentException".as_ptr());
    if !class.is_null() { (f.ThrowNew.unwrap())(env, class, c"Invalid NTP timing token".as_ptr()); }
}

unsafe fn clock(env: *mut JNIEnv, token: jlongArray) -> Option<TimingClock> {
    let f = &**env;
    if token.is_null() || (f.GetArrayLength.unwrap())(env, token) != WORDS as jint { invalid(env); return None; }
    let mut words = [0; WORDS];
    (f.GetLongArrayRegion.unwrap())(env, token, 0, WORDS as jint, words.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 { return None; }
    let result = TimingClock::from_token(words);
    if result.is_none() { invalid(env); }
    result
}

unsafe fn save(env: *mut JNIEnv, token: jlongArray, clock: &TimingClock) {
    ((**env).SetLongArrayRegion.unwrap())(env, token, 0, WORDS as jint, clock.token().as_ptr());
}

unsafe fn packet(env: *mut JNIEnv, message: jbyteArray) -> Option<[u8; PACKET_BYTES]> {
    let f = &**env;
    if message.is_null() || (f.GetArrayLength.unwrap())(env, message) < PACKET_BYTES as jint { return None; }
    let mut data = [0u8; PACKET_BYTES];
    (f.GetByteArrayRegion.unwrap())(env, message, 0, PACKET_BYTES as jint, data.as_mut_ptr() as *mut i8);
    if (f.ExceptionCheck.unwrap())(env) != 0 { None } else { Some(data) }
}

unsafe fn bytes(env: *mut JNIEnv, value: &[u8]) -> jbyteArray {
    let f = &**env;
    let array = (f.NewByteArray.unwrap())(env, value.len() as jint);
    if !array.is_null() { (f.SetByteArrayRegion.unwrap())(env, array, 0, value.len() as jint, value.as_ptr() as *const i8); }
    array
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeNtpTiming_create(
    env: *mut JNIEnv, _class: jclass, mono: jlong, wall: jlong,
) -> jlongArray {
    let f = &**env;
    let token = (f.NewLongArray.unwrap())(env, WORDS as jint);
    if !token.is_null() { save(env, token, &TimingClock::new(mono, wall)); }
    token
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeNtpTiming_synced(
    env: *mut JNIEnv, _class: jclass, token: jlongArray, mono: jlong,
) -> jbyteArray {
    let Some(clock) = clock(env, token) else { return null_mut(); };
    bytes(env, &clock.synced_ntp(mono).to_be_bytes())
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeNtpTiming_request(
    env: *mut JNIEnv, _class: jclass, token: jlongArray, mono: jlong,
) -> jbyteArray {
    let Some(mut clock) = clock(env, token) else { return null_mut(); };
    let request = clock.request(mono);
    save(env, token, &clock);
    if ((**env).ExceptionCheck.unwrap())(env) != 0 { return null_mut(); }
    bytes(env, &request)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeNtpTiming_action(
    env: *mut JNIEnv, _class: jclass, message: jbyteArray, from_peer: jboolean,
) -> jint {
    packet(env, message).map(|message| action(&message, from_peer != 0)).unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeNtpTiming_reply(
    env: *mut JNIEnv, _class: jclass, token: jlongArray, message: jbyteArray, receive_ns: jlong, send_ns: jlong,
) -> jbyteArray {
    let Some(clock) = clock(env, token) else { return null_mut(); };
    let Some(message) = packet(env, message) else { return null_mut(); };
    bytes(env, &clock.reply(&message, receive_ns, send_ns))
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeNtpTiming_sample(
    env: *mut JNIEnv, _class: jclass, token: jlongArray, message: jbyteArray, mono: jlong,
) {
    let Some(mut clock) = clock(env, token) else { return; };
    let Some(message) = packet(env, message) else { return; };
    clock.sample(&message, mono);
    save(env, token, &clock);
}
