//! Synchronous link adapter. The Kotlin facade serializes every call against destruction.
use crate::protocols::iap2_body::{byte_array, read_bytes, throw};
use super::{Config, Engine, Event, Synchronization};
use jni_sys::{jboolean, jbyteArray, jclass, jint, jintArray, jlong, jobject, jvalue, JNIEnv};
use std::ptr::null_mut;

unsafe fn ints(env: *mut JNIEnv, array: jintArray) -> Option<Vec<i32>> {
    let f = &**env;
    if array.is_null() { throw(env, "java/lang/NullPointerException", "values"); return None; }
    let count = (f.GetArrayLength.unwrap())(env, array);
    let mut values = vec![0; count as usize];
    if count != 0 { (f.GetIntArrayRegion.unwrap())(env, array, 0, count, values.as_mut_ptr()); }
    if (f.ExceptionCheck.unwrap())(env) != 0 { None } else { Some(values) }
}

fn sync_values(sync: &Synchronization) -> Vec<i32> {
    let mut values = vec![sync.outgoing as i32, sync.length as i32, sync.retransmission_ms as i32,
        sync.acknowledgement_ms as i32, sync.retransmissions as i32, sync.acknowledgements as i32];
    values.extend(sync.sessions.iter().flatten().map(|v| *v as i32));
    values
}

unsafe fn int_array(env: *mut JNIEnv, values: &[i32]) -> jintArray {
    let f = &**env;
    let array = (f.NewIntArray.unwrap())(env, values.len() as jint);
    if !array.is_null() && !values.is_empty() { (f.SetIntArrayRegion.unwrap())(env, array, 0, values.len() as jint, values.as_ptr()); }
    array
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeIap2Link_create(env: *mut JNIEnv, _: jclass, values: jintArray) -> jlong {
    let Some(v) = ints(env, values) else { return 0; };
    if v.len() != 9 { throw(env, "java/lang/IllegalArgumentException", "iAP2 link configuration needs 9 values"); return 0; }
    // Values are supplied by the validated Iap2LinkConfig constructor.
    Box::into_raw(Box::new(Engine::new(Config {
        outgoing: v[0] as u8, outgoing_delta: v[1] as u8, acknowledgement_ms: v[2] as u16,
        zero_acknowledgements: v[3] != 0, control_version: v[4] as u8, queued_packets: v[5] as usize,
        out_of_order_packets: v[6] as usize, output_bytes: v[7] as usize, events: v[8] as usize,
    }))) as jlong
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeIap2Link_destroy(_: *mut JNIEnv, _: jclass, handle: jlong) {
    if handle != 0 { drop(Box::from_raw(handle as *mut Engine)); }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeIap2Link_state(_: *mut JNIEnv, _: jclass, handle: jlong) -> jint {
    (*(handle as *const Engine)).state as jint
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeIap2Link_writable(_: *mut JNIEnv, _: jclass, handle: jlong) -> jboolean {
    (*(handle as *const Engine)).writable as jboolean
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeIap2Link_synchronization(env: *mut JNIEnv, _: jclass, handle: jlong) -> jintArray {
    int_array(env, &sync_values(&(*(handle as *const Engine)).peer))
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeIap2Link_takeOutput(env: *mut JNIEnv, _: jclass, handle: jlong) -> jbyteArray {
    byte_array(env, &std::mem::take(&mut (*(handle as *mut Engine)).output))
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeIap2Link_pollEvent(env: *mut JNIEnv, _: jclass, handle: jlong) -> jbyteArray {
    let Some(event) = (*(handle as *mut Engine)).events.pop_front() else { return null_mut(); };
    let (mut prefix, payload) = match event {
        Event::Control(bytes) => (vec![0], bytes), Event::Session(id, bytes) => (vec![1, id], bytes),
        Event::Writable(value) => (vec![2, value as u8], Vec::new()),
        Event::Dead(None) => (vec![3], Vec::new()), Event::Dead(Some(reason)) => (vec![4], reason.into_bytes()),
    };
    prefix.extend(payload);
    byte_array(env, &prefix)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeIap2Link_nextDeadlineMillis(env: *mut JNIEnv, _: jclass, handle: jlong) -> jobject {
    let Some(deadline) = (*(handle as *const Engine)).next_deadline() else { return null_mut(); };
    let f = &**env;
    let class = (f.FindClass.unwrap())(env, c"java/lang/Long".as_ptr());
    if class.is_null() { return null_mut(); }
    let constructor = (f.GetMethodID.unwrap())(env, class, c"<init>".as_ptr(), c"(J)V".as_ptr());
    let result = if constructor.is_null() { null_mut() } else {
        (f.NewObjectA.unwrap())(env, class, constructor, [jvalue { j: deadline }].as_ptr())
    };
    (f.DeleteLocalRef.unwrap())(env, class);
    result
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeIap2Link_start(_: *mut JNIEnv, _: jclass, handle: jlong, wired: jboolean, now: jlong) {
    (*(handle as *mut Engine)).start(wired != 0, now);
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeIap2Link_feed(env: *mut JNIEnv, _: jclass, handle: jlong, bytes: jbyteArray, now: jlong) {
    if let Some(bytes) = read_bytes(env, bytes) { (*(handle as *mut Engine)).feed(&bytes, now); }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeIap2Link_feedEof(_: *mut JNIEnv, _: jclass, handle: jlong) {
    (*(handle as *mut Engine)).die(None);
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeIap2Link_advanceTime(_: *mut JNIEnv, _: jclass, handle: jlong, now: jlong) {
    (*(handle as *mut Engine)).advance(now);
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeIap2Link_sendSession(env: *mut JNIEnv, _: jclass, handle: jlong, session: jint, bytes: jbyteArray, now: jlong) {
    let Some(bytes) = read_bytes(env, bytes) else { return; };
    if let Err(message) = (*(handle as *mut Engine)).send_session(session, &bytes, now) { throw(env, "java/lang/IllegalArgumentException", &message); }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeIap2Link_encodeSynchronization(env: *mut JNIEnv, _: jclass, values: jintArray) -> jbyteArray {
    let Some(v) = ints(env, values) else { return null_mut(); };
    if v.len() < 6 || (v.len() - 6) % 3 != 0 { throw(env, "java/lang/IllegalArgumentException", "invalid iAP2 synchronization values"); return null_mut(); }
    let sync = Synchronization {
        outgoing: v[0] as u8, length: v[1] as u16, retransmission_ms: v[2] as u16,
        acknowledgement_ms: v[3] as u16, retransmissions: v[4] as u8, acknowledgements: v[5] as u8,
        sessions: v[6..].chunks_exact(3).map(|v| [v[0] as u8, v[1] as u8, v[2] as u8]).collect(),
    };
    byte_array(env, &sync.encode())
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeIap2Link_decodeSynchronization(env: *mut JNIEnv, _: jclass, bytes: jbyteArray) -> jintArray {
    let Some(bytes) = read_bytes(env, bytes) else { return null_mut(); };
    Synchronization::decode(&bytes).map_or(null_mut(), |sync| int_array(env, &sync_values(&sync)))
}
