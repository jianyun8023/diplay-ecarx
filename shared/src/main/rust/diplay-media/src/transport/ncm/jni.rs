use super::{
    platform_core::{self, ReceiveCore, ReceiveStep, Transmit},
    *,
};
use jni_sys::{
    jboolean, jbyteArray, jclass, jint, jintArray, jlong, jlongArray, jobjectArray, jstring, JNIEnv,
};
use std::ptr::null_mut;

unsafe fn invalid(env: *mut JNIEnv, message: &std::ffi::CStr) {
    let f = &**env;
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return;
    }
    let class = (f.FindClass.unwrap())(env, c"java/lang/IllegalArgumentException".as_ptr());
    if !class.is_null() {
        (f.ThrowNew.unwrap())(env, class, message.as_ptr());
    }
}
unsafe fn bad(env: *mut JNIEnv) {
    invalid(env, c"Invalid NCM core value");
}
unsafe fn bytes(env: *mut JNIEnv, value: jbyteArray) -> Option<Vec<u8>> {
    let f = &**env;
    if value.is_null() {
        bad(env);
        return None;
    }
    let length = (f.GetArrayLength.unwrap())(env, value);
    let mut data = vec![0u8; length as usize];
    (f.GetByteArrayRegion.unwrap())(env, value, 0, length, data.as_mut_ptr().cast());
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        None
    } else {
        Some(data)
    }
}
unsafe fn words(env: *mut JNIEnv, value: jlongArray) -> Option<Vec<i64>> {
    let f = &**env;
    if value.is_null() {
        bad(env);
        return None;
    }
    let length = (f.GetArrayLength.unwrap())(env, value);
    let mut data = vec![0; length as usize];
    (f.GetLongArrayRegion.unwrap())(env, value, 0, length, data.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        None
    } else {
        Some(data)
    }
}
unsafe fn prefix(env: *mut JNIEnv, value: jbyteArray, length: jint) -> Option<Vec<u8>> {
    let f = &**env;
    if value.is_null() {
        bad(env);
        return None;
    }
    if length < 0 || length > (f.GetArrayLength.unwrap())(env, value) {
        bad(env);
        return None;
    }
    let mut data = vec![0u8; length as usize];
    (f.GetByteArrayRegion.unwrap())(env, value, 0, length, data.as_mut_ptr().cast());
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        None
    } else {
        Some(data)
    }
}
unsafe fn byte_array(env: *mut JNIEnv, data: &[u8]) -> jbyteArray {
    let f = &**env;
    let value = (f.NewByteArray.unwrap())(env, data.len() as jint);
    if !value.is_null() {
        (f.SetByteArrayRegion.unwrap())(env, value, 0, data.len() as jint, data.as_ptr().cast());
    }
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        null_mut()
    } else {
        value
    }
}
unsafe fn long_array(env: *mut JNIEnv, data: &[i64]) -> jlongArray {
    let f = &**env;
    let value = (f.NewLongArray.unwrap())(env, data.len() as jint);
    if !value.is_null() {
        (f.SetLongArrayRegion.unwrap())(env, value, 0, data.len() as jint, data.as_ptr());
    }
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        null_mut()
    } else {
        value
    }
}
unsafe fn int_array(env: *mut JNIEnv, data: &[i32]) -> jintArray {
    let f = &**env;
    let value = (f.NewIntArray.unwrap())(env, data.len() as jint);
    if !value.is_null() {
        (f.SetIntArrayRegion.unwrap())(env, value, 0, data.len() as jint, data.as_ptr());
    }
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        null_mut()
    } else {
        value
    }
}
unsafe fn save(env: *mut JNIEnv, token: jlongArray, data: &[i64]) {
    ((**env).SetLongArrayRegion.unwrap())(env, token, 0, data.len() as jint, data.as_ptr());
}
unsafe fn string(env: *mut JNIEnv, text: &str) -> jstring {
    let utf16: Vec<u16> = text.encode_utf16().collect();
    ((**env).NewString.unwrap())(env, utf16.as_ptr(), utf16.len() as jint)
}
unsafe fn arrays(env: *mut JNIEnv, rows: &[&[u8]]) -> jobjectArray {
    let f = &**env;
    let class = (f.FindClass.unwrap())(env, c"[B".as_ptr());
    if class.is_null() {
        return null_mut();
    }
    let array = (f.NewObjectArray.unwrap())(env, rows.len() as jint, class, null_mut());
    if array.is_null() {
        return null_mut();
    }
    for (index, row) in rows.iter().enumerate() {
        let value = byte_array(env, row);
        if value.is_null() {
            return null_mut();
        }
        (f.SetObjectArrayElement.unwrap())(env, array, index as jint, value);
        (f.DeleteLocalRef.unwrap())(env, value);
        if (f.ExceptionCheck.unwrap())(env) != 0 {
            return null_mut();
        }
    }
    array
}
unsafe fn tx(env: *mut JNIEnv, token: jlongArray) -> Option<Transmit> {
    let state = Transmit::decode(&words(env, token)?);
    if state.is_none() {
        bad(env);
    }
    state
}
unsafe fn traffic(env: *mut JNIEnv, token: jlongArray) -> Option<Traffic> {
    let state = Traffic::decode(&words(env, token)?);
    if state.is_none() {
        bad(env);
    }
    state
}
unsafe fn rx<'a>(env: *mut JNIEnv, handle: jlong) -> Option<&'a mut ReceiveCore> {
    if handle == 0 {
        bad(env);
        None
    } else {
        Some(&mut *(handle as *mut ReceiveCore))
    }
}
unsafe fn peer(env: *mut JNIEnv, token: jlongArray) -> Option<Option<[u8; 6]>> {
    let state = platform_core::peer_decode(&words(env, token)?);
    if state.is_none() {
        bad(env);
    }
    state
}
fn view(frame: &[u8]) -> Option<Vec<i32>> {
    let (mac, payload) = ipv6_payload(frame)?;
    let mut result: Vec<i32> = mac.into_iter().map(i32::from).collect();
    result.extend([14, payload.len() as i32]);
    Some(result)
}
unsafe fn build_result(env: *mut JNIEnv, result: Result<Vec<u8>, &'static str>) -> jbyteArray {
    match result {
        Ok(data) => byte_array(env, &data),
        Err(message) => {
            let text = std::ffi::CString::new(message).unwrap();
            invalid(env, &text);
            null_mut()
        }
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeNcm_valueCreate(
    env: *mut JNIEnv,
    _class: jclass,
    kind: jint,
) -> jlongArray {
    match kind {
        0 => long_array(env, &Transmit::new().encode()),
        1 => long_array(env, &Traffic::default().encode()),
        2 => long_array(env, &[0]),
        _ => {
            bad(env);
            null_mut()
        }
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeNcm_ntbBuild(
    env: *mut JNIEnv,
    _class: jclass,
    frame: jbyteArray,
    sequence: jint,
) -> jbyteArray {
    let Some(frame) = bytes(env, frame) else {
        return null_mut();
    };
    build_result(env, platform_core::build(&frame, sequence))
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeNcm_ntbParse(
    env: *mut JNIEnv,
    _class: jclass,
    block: jbyteArray,
    offset: jint,
    length: jint,
) -> jobjectArray {
    let Some(block) = bytes(env, block) else {
        return null_mut();
    };
    if offset < 0
        || length < 0
        || offset as usize > block.len().saturating_sub(length as usize)
        || length as usize > block.len()
    {
        invalid(env, c"NTB16 range is outside the source buffer");
        return null_mut();
    }
    let frames = ntb16_parse_origin(
        &block[offset as usize..offset as usize + length as usize],
        offset != 0,
    );
    arrays(env, &frames)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeNcm_ethernetParse(
    env: *mut JNIEnv,
    _class: jclass,
    frame: jbyteArray,
) -> jobjectArray {
    let Some(frame) = bytes(env, frame) else {
        return null_mut();
    };
    let Some((source, payload)) = ipv6_payload_with_empty(&frame, true) else {
        return null_mut();
    };
    arrays(env, &[&source, &frame[..6], payload])
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeNcm_ethernetView(
    env: *mut JNIEnv,
    _class: jclass,
    frame: jbyteArray,
) -> jintArray {
    let Some(frame) = bytes(env, frame) else {
        return null_mut();
    };
    view(&frame)
        .map(|row| int_array(env, &row))
        .unwrap_or(null_mut())
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeNcm_ethernetBuild(
    env: *mut JNIEnv,
    _class: jclass,
    source: jbyteArray,
    destination: jbyteArray,
    payload: jbyteArray,
) -> jbyteArray {
    let Some(source) = bytes(env, source) else {
        return null_mut();
    };
    let Ok(source) = <[u8; 6]>::try_from(source.as_slice()) else {
        invalid(env, c"sourceMac must be 6 bytes");
        return null_mut();
    };
    let Some(destination) = bytes(env, destination) else {
        return null_mut();
    };
    let Ok(destination) = <[u8; 6]>::try_from(destination.as_slice()) else {
        invalid(env, c"destinationMac must be 6 bytes");
        return null_mut();
    };
    let Some(payload) = bytes(env, payload) else {
        return null_mut();
    };
    if payload.is_empty() {
        invalid(env, c"ipv6 payload must not be empty");
        return null_mut();
    }
    byte_array(env, &ethernet_frame(&source, &destination, &payload))
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeNcm_ethernetMulticast(
    env: *mut JNIEnv,
    _class: jclass,
    ipv6: jbyteArray,
) -> jbyteArray {
    let Some(ipv6) = bytes(env, ipv6) else {
        return null_mut();
    };
    multicast_mac(&ipv6)
        .map(|mac| byte_array(env, &mac))
        .unwrap_or(null_mut())
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeNcm_ethernetFixNa(
    env: *mut JNIEnv,
    _class: jclass,
    ipv6: jbyteArray,
    host: jbyteArray,
) -> jbyteArray {
    let Some(host) = bytes(env, host) else {
        return null_mut();
    };
    let Ok(host) = <[u8; 6]>::try_from(host.as_slice()) else {
        invalid(env, c"hostMac must be 6 bytes");
        return null_mut();
    };
    let Some(ipv6) = bytes(env, ipv6) else {
        return null_mut();
    };
    fix_neighbor_advertisement(&ipv6, &host)
        .map(|data| byte_array(env, &data))
        .unwrap_or(null_mut())
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeNcm_trafficHeader(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    frame: jbyteArray,
) -> jint {
    let Some(mut state) = traffic(env, token) else {
        return -1;
    };
    let Some(frame) = bytes(env, frame) else {
        return -1;
    };
    let port = state.receive_header(&frame).map(i32::from).unwrap_or(-1);
    save(env, token, &state.encode());
    port
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeNcm_trafficTime(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    port: jint,
    now: jlong,
) {
    let Some(mut state) = traffic(env, token) else {
        return;
    };
    let Ok(port) = u16::try_from(port) else {
        bad(env);
        return;
    };
    state.record_time(port, now);
    save(env, token, &state.encode());
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeNcm_trafficSent(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
) {
    let Some(mut state) = traffic(env, token) else {
        return;
    };
    state.sent();
    save(env, token, &state.encode());
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeNcm_trafficSummary(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    port: jint,
) -> jstring {
    let Some(state) = traffic(env, token) else {
        return null_mut();
    };
    string(env, &state.summary(u16::try_from(port).ok()))
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeNcm_trafficAttempt(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    port: jint,
    since: jlong,
) -> jboolean {
    let Some(state) = traffic(env, token) else {
        return 0;
    };
    u16::try_from(port).is_ok_and(|port| state.attempt_since(port, since)) as jboolean
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeNcm_txSequence(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
) -> jint {
    let Some(mut state) = tx(env, token) else {
        return 0;
    };
    let sequence = state.next_sequence();
    save(env, token, &state.encode());
    sequence as jint
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeNcm_txTimeout(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    requested: jint,
) -> jint {
    tx(env, token)
        .map(|state| state.timeout(requested))
        .unwrap_or(0)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeNcm_txBegin(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    transferred: jint,
) -> jlongArray {
    let Some(mut state) = tx(env, token) else {
        return null_mut();
    };
    let result = state.begin(transferred);
    save(env, token, &state.encode());
    if ((**env).ExceptionCheck.unwrap())(env) != 0 {
        return null_mut();
    }
    long_array(env, &result)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeNcm_txOpened(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    now: jlong,
) {
    let Some(mut state) = tx(env, token) else {
        return;
    };
    state.opened(now);
    save(env, token, &state.encode());
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeNcm_txOpenedAt(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
) -> jlongArray {
    let Some(state) = tx(env, token) else {
        return null_mut();
    };
    long_array(env, &state.opened_at().into_iter().collect::<Vec<_>>())
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeNcm_txFinish(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    transferred: jint,
    expected: jint,
) -> jint {
    let Some(mut state) = tx(env, token) else {
        return -1;
    };
    let result = state.finish(transferred, expected);
    save(env, token, &state.encode());
    result
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeNcm_receiveCreate(
    env: *mut JNIEnv,
    _class: jclass,
    packet_size: jint,
) -> jlong {
    if packet_size <= 0 {
        bad(env);
        return 0;
    }
    Box::into_raw(Box::new(ReceiveCore::new(packet_size as usize))) as jlong
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeNcm_receiveAppend(
    env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
    chunk: jbyteArray,
    length: jint,
) {
    let Some(state) = rx(env, handle) else {
        return;
    };
    let Some(chunk) = prefix(env, chunk, length) else {
        return;
    };
    state.append(&chunk);
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeNcm_receiveStep(
    env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
    has_time: jboolean,
    now: jlong,
    details: jobjectArray,
) -> jint {
    let Some(state) = rx(env, handle) else {
        return 0;
    };
    let f = &**env;
    if details.is_null() || (f.GetArrayLength.unwrap())(env, details) != 1 {
        bad(env);
        return 0;
    }
    let (code, detail) = match state.step((has_time != 0).then_some(now)) {
        ReceiveStep::Idle => (0, None),
        ReceiveStep::NeedTime => (1, None),
        ReceiveStep::Report(line) => (2, Some(line)),
        ReceiveStep::Failed(error) => (-1, Some(error)),
    };
    if let Some(text) = detail {
        let text = string(env, &text);
        if text.is_null() {
            return 0;
        }
        (f.SetObjectArrayElement.unwrap())(env, details, 0, text);
    }
    code
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeNcm_receivePoll(
    env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
) -> jbyteArray {
    let Some(state) = rx(env, handle) else {
        return null_mut();
    };
    state
        .poll()
        .map(|frame| byte_array(env, &frame))
        .unwrap_or(null_mut())
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeNcm_receiveClose(
    _env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
) {
    if handle != 0 {
        drop(Box::from_raw(handle as *mut ReceiveCore));
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeNcm_routeInbound(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    frame: jbyteArray,
) -> jintArray {
    if peer(env, token).is_none() {
        return null_mut();
    }
    let Some(frame) = bytes(env, frame) else {
        return null_mut();
    };
    let Some((mac, _)) = ipv6_payload(&frame) else {
        return null_mut();
    };
    save(env, token, &[platform_core::peer_encode(mac)]);
    if ((**env).ExceptionCheck.unwrap())(env) != 0 {
        return null_mut();
    }
    int_array(env, &view(&frame).unwrap())
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeNcm_routeDestination(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    ipv6: jbyteArray,
    details: jlongArray,
) -> jbyteArray {
    let Some(peer) = peer(env, token) else {
        return null_mut();
    };
    let Some(ipv6) = bytes(env, ipv6) else {
        return null_mut();
    };
    let Some(output) = words(env, details) else {
        return null_mut();
    };
    if output.len() != 1 {
        bad(env);
        return null_mut();
    }
    let group = multicast_mac(&ipv6);
    save(env, details, &[group.is_some() as i64]);
    if ((**env).ExceptionCheck.unwrap())(env) != 0 {
        return null_mut();
    }
    group
        .or(peer)
        .map(|mac| byte_array(env, &mac))
        .unwrap_or(null_mut())
}
