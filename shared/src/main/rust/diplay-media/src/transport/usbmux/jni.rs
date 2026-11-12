//! Java owns USB effects and monitors. No native borrow spans an application callback or USB I/O.
use super::{
    framer::{Framer, Read},
    parse_tcp, segment,
    tcp::{Tcp, FROZEN_HEADER, NOTIFY, REMOVE, RESET},
    u16_at, u32_at, Host,
};
use jni_sys::{
    jarray, jboolean, jbyteArray, jclass, jint, jintArray, jlong, jobject, jobjectArray, JNIEnv,
};
use std::{
    ffi::{CStr, CString},
    ptr::null_mut,
};

unsafe fn throw(env: *mut JNIEnv, class: &CStr, message: &str) {
    let f = &**env;
    let class = (f.FindClass.unwrap())(env, class.as_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 || class.is_null() {
        return;
    }
    // These protocol/validation messages are controlled ASCII, not peer strings.
    let message = CString::new(message).unwrap();
    (f.ThrowNew.unwrap())(env, class, message.as_ptr());
}
unsafe fn length(env: *mut JNIEnv, array: jarray) -> Option<usize> {
    let f = &**env;
    if array.is_null() {
        throw(env, c"java/lang/NullPointerException", "null USBMUX array");
        return None;
    }
    let count = (f.GetArrayLength.unwrap())(env, array);
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        None
    } else {
        Some(count as usize)
    }
}
unsafe fn region(env: *mut JNIEnv, array: jbyteArray, offset: usize, out: &mut [u8]) -> bool {
    let f = &**env;
    if array.is_null() {
        throw(env, c"java/lang/NullPointerException", "null USBMUX array");
        return false;
    }
    (f.GetByteArrayRegion.unwrap())(
        env,
        array,
        offset as jint,
        out.len() as jint,
        out.as_mut_ptr() as *mut i8,
    );
    (f.ExceptionCheck.unwrap())(env) == 0
}
unsafe fn read(env: *mut JNIEnv, array: jbyteArray) -> Option<Vec<u8>> {
    let mut value = vec![0; length(env, array)?];
    region(env, array, 0, &mut value).then_some(value)
}
unsafe fn bytes(env: *mut JNIEnv, value: &[u8]) -> jbyteArray {
    let f = &**env;
    let array = (f.NewByteArray.unwrap())(env, value.len() as jint);
    if (f.ExceptionCheck.unwrap())(env) != 0 || array.is_null() {
        return null_mut();
    }
    (f.SetByteArrayRegion.unwrap())(
        env,
        array,
        0,
        value.len() as jint,
        value.as_ptr() as *const i8,
    );
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        null_mut()
    } else {
        array
    }
}
unsafe fn result(
    env: *mut JNIEnv,
    meta: &[i32],
    payload: Option<&[u8]>,
    text: Option<&str>,
) -> jobjectArray {
    let f = &**env;
    let class = (f.FindClass.unwrap())(env, c"java/lang/Object".as_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 || class.is_null() {
        return null_mut();
    }
    let out = (f.NewObjectArray.unwrap())(env, 3, class, null_mut());
    if (f.ExceptionCheck.unwrap())(env) != 0 || out.is_null() {
        return null_mut();
    }
    let ints = (f.NewIntArray.unwrap())(env, meta.len() as jint);
    if (f.ExceptionCheck.unwrap())(env) != 0 || ints.is_null() {
        return null_mut();
    }
    (f.SetIntArrayRegion.unwrap())(env, ints, 0, meta.len() as jint, meta.as_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return null_mut();
    }
    let payload = match payload {
        Some(value) => {
            let value = bytes(env, value);
            if value.is_null() {
                return null_mut();
            }
            value
        }
        None => null_mut(),
    };
    let text = match text {
        Some(text) => {
            let text = CString::new(text).unwrap();
            let value = (f.NewStringUTF.unwrap())(env, text.as_ptr());
            if (f.ExceptionCheck.unwrap())(env) != 0 || value.is_null() {
                return null_mut();
            }
            value
        }
        None => null_mut(),
    };
    for (index, value) in [ints as jobject, payload as jobject, text as jobject]
        .iter()
        .enumerate()
    {
        (f.SetObjectArrayElement.unwrap())(env, out, index as jint, *value);
        if (f.ExceptionCheck.unwrap())(env) != 0 {
            return null_mut();
        }
    }
    out
}
unsafe fn frozen(env: *mut JNIEnv, array: jbyteArray) -> Option<[u8; FROZEN_HEADER]> {
    let mut header = [0; FROZEN_HEADER];
    region(env, array, 0, &mut header).then_some(header)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeUsbMux_framerCreate(
    _env: *mut JNIEnv,
    _class: jclass,
) -> jlong {
    Box::into_raw(Box::new(Framer::new())) as jlong
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeUsbMux_framerAppend(
    env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
    array: jbyteArray,
) {
    if let Some(value) = read(env, array) {
        (&mut *(handle as *mut Framer)).append(&value);
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeUsbMux_framerBuffered(
    _env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
) -> jint {
    (&*(handle as *const Framer)).bytes.len() as jint
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeUsbMux_framerRead(
    env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
) -> jobjectArray {
    let next = (&mut *(handle as *mut Framer)).take();
    match next {
        Read::More => null_mut(),
        Read::Report(text) => result(env, &[1, 0, 0, 0, 0], None, Some(&text)),
        Read::Rejected(len, text) => result(env, &[3, len, 0, 0, 0], None, Some(&text)),
        Read::Frame(frame) => result(
            env,
            &[
                2,
                frame.protocol,
                frame.length,
                frame.word,
                frame.sequence as i32,
            ],
            Some(&frame.payload),
            None,
        ),
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeUsbMux_framerDestroy(
    _env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
) {
    if handle != 0 {
        drop(Box::from_raw(handle as *mut Framer));
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeUsbMux_hostCreate(
    _env: *mut JNIEnv,
    _class: jclass,
) -> jlong {
    Box::into_raw(Box::new(Host::new())) as jlong
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeUsbMux_version(
    env: *mut JNIEnv,
    _class: jclass,
) -> jbyteArray {
    bytes(env, &Host::version())
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeUsbMux_handshake(
    _env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
    protocol: jint,
    len: jint,
    word: jint,
) -> jint {
    (&mut *(handle as *mut Host)).handshake(protocol, len, word)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeUsbMux_muxFrame(
    env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
    payload: jbyteArray,
) -> jbyteArray {
    let frame = if payload.is_null() {
        (&*(handle as *const Host)).frame(2, &[7])
    } else {
        let Some(payload) = read(env, payload) else {
            return null_mut();
        };
        (&*(handle as *const Host)).frame(6, &payload)
    };
    bytes(env, &frame)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeUsbMux_muxSent(
    _env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
) {
    let host = &mut *(handle as *mut Host);
    host.sequence = host.sequence.wrapping_add(1);
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeUsbMux_muxReceived(
    _env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
    sequence: jint,
) {
    (&mut *(handle as *mut Host)).acknowledgement = sequence as u16;
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeUsbMux_sourcePort(
    env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
    active: jintArray,
) -> jint {
    let Some(count) = length(env, active) else {
        return 0;
    };
    let mut values = vec![0; count];
    let f = &**env;
    (f.GetIntArrayRegion.unwrap())(env, active, 0, count as jint, values.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return 0;
    }
    (&mut *(handle as *mut Host)).port(&values).unwrap_or(0) as jint
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeUsbMux_hostDestroy(
    _env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
) {
    if handle != 0 {
        drop(Box::from_raw(handle as *mut Host));
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeUsbMux_encodeTcp(
    env: *mut JNIEnv,
    _class: jclass,
    source: jint,
    dest: jint,
    seq: jint,
    ack: jint,
    flags: jint,
    payload: jbyteArray,
) -> jbyteArray {
    let Some(payload) = read(env, payload) else {
        return null_mut();
    };
    bytes(
        env,
        &segment(
            source as u16,
            dest as u16,
            seq as u32,
            ack as u32,
            flags as u8,
            &payload,
        ),
    )
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeUsbMux_parseTcp(
    env: *mut JNIEnv,
    _class: jclass,
    protocol: jint,
    frame: jbyteArray,
) -> jobjectArray {
    if protocol != 6 {
        return null_mut();
    }
    let Some(frame) = read(env, frame) else {
        return null_mut();
    };
    match parse_tcp(&frame) {
        Ok((meta, payload)) => result(env, &meta, Some(payload), None),
        Err(message) => {
            throw(
                env,
                c"com/shilapi/xcertplay/transport/IphoneUsbException$Protocol",
                message,
            );
            null_mut()
        }
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeUsbMux_tcpCreate(
    _env: *mut JNIEnv,
    _class: jclass,
    source: jint,
    dest: jint,
) -> jlong {
    Box::into_raw(Box::new(Tcp::new(source as u16, dest as u16))) as jlong
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeUsbMux_tcpStatus(
    env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
    retired: jbyteArray,
) -> jint {
    if handle != 0 {
        return (&*(handle as *const Tcp)).status();
    }
    let Some(header) = frozen(env, retired) else {
        return 0;
    };
    let Some(len) = length(env, retired) else {
        return 0;
    };
    (header[12] as i32)
        | 2
        | (i32::from((u32_at(&header, 16) as usize) < len) << 2)
        | ((header[13] as i32) << 8)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeUsbMux_tcpPrepare(
    env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
    array: jbyteArray,
    offset: jint,
) -> jobjectArray {
    let Some(len) = length(env, array) else {
        return null_mut();
    };
    let Some(remaining) = len.checked_sub(offset as usize) else {
        throw(
            env,
            c"java/lang/IllegalArgumentException",
            "Invalid USBMUX send offset",
        );
        return null_mut();
    };
    let count = remaining.min(16384);
    let mut payload = vec![0; count];
    if !region(env, array, offset as usize, &mut payload) {
        return null_mut();
    }
    let tcp = &*(handle as *const Tcp);
    let meta = [
        count as i32,
        tcp.source as i32,
        tcp.destination as i32,
        tcp.sequence as i32,
        tcp.ack as i32,
    ];
    let frame = tcp.data(&payload);
    result(env, &meta, Some(&frame), None)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeUsbMux_tcpSent(
    _env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
    count: jint,
) {
    let tcp = &mut *(handle as *mut Tcp);
    tcp.sequence = tcp.sequence.wrapping_add(count as u32);
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeUsbMux_tcpControl(
    env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
    retired: jbyteArray,
    command: jint,
) -> jbyteArray {
    let frame = if handle != 0 {
        (&*(handle as *const Tcp)).control(command)
    } else {
        let Some(header) = frozen(env, retired) else {
            return null_mut();
        };
        super::control(
            u16_at(&header, 0),
            u16_at(&header, 2),
            u32_at(&header, 4),
            u32_at(&header, 8),
            command,
        )
    };
    bytes(env, &frame)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeUsbMux_tcpPacket(
    env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
    retired: jbyteArray,
    flags: jint,
    sequence: jint,
    payload: jbyteArray,
) -> jint {
    if handle == 0 {
        if flags & 4 == 0 {
            return 0;
        }
        let value: i8 = 1;
        let f = &**env;
        (f.SetByteArrayRegion.unwrap())(env, retired, 13, 1, &value);
        if (f.ExceptionCheck.unwrap())(env) != 0 {
            return 0;
        }
        return RESET | NOTIFY | REMOVE;
    }
    let Some(payload) = read(env, payload) else {
        return 0;
    };
    (&mut *(handle as *mut Tcp)).receive(flags as u8, sequence as u32, payload)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeUsbMux_tcpAckSent(
    _env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
) -> jint {
    if handle == 0 {
        0
    } else {
        (&mut *(handle as *mut Tcp)).ack_sent()
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeUsbMux_tcpRecv(
    env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
    retired: jbyteArray,
    max: jint,
) -> jbyteArray {
    if handle != 0 {
        let value = (&mut *(handle as *mut Tcp)).recv(max as usize);
        return value.map_or(null_mut(), |value| bytes(env, &value));
    }
    let Some(header) = frozen(env, retired) else {
        return null_mut();
    };
    let Some(total) = length(env, retired) else {
        return null_mut();
    };
    let cursor = u32_at(&header, 16) as usize;
    let consumed = u32_at(&header, 20) as usize;
    if cursor >= total {
        return bytes(env, &[]);
    }
    let mut word = [0; 4];
    if !region(env, retired, cursor, &mut word) {
        return null_mut();
    }
    let packet = u32::from_be_bytes(word) as usize;
    if cursor + 4 > total || packet > total - cursor - 4 || consumed > packet {
        throw(
            env,
            c"java/lang/IllegalArgumentException",
            "Invalid terminal USBMUX queue",
        );
        return null_mut();
    }
    let count = (packet - consumed).min(max as usize);
    let mut payload = vec![0; count];
    if !region(env, retired, cursor + 4 + consumed, &mut payload) {
        return null_mut();
    }
    let (cursor, consumed) = if consumed + count == packet {
        (cursor + 4 + packet, 0)
    } else {
        (cursor, consumed + count)
    };
    let mut next = [0; 8];
    next[..4].copy_from_slice(&(cursor as u32).to_be_bytes());
    next[4..].copy_from_slice(&(consumed as u32).to_be_bytes());
    let f = &**env;
    (f.SetByteArrayRegion.unwrap())(env, retired, 16, 8, next.as_ptr() as *const i8);
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return null_mut();
    }
    bytes(env, &payload)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeUsbMux_tcpClosePlan(
    _env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
    abort: jboolean,
) -> jint {
    (&mut *(handle as *mut Tcp)).close_plan(abort != 0)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeUsbMux_tcpHostClose(
    _env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
    failed: jboolean,
) -> jboolean {
    (&mut *(handle as *mut Tcp)).host_close(failed != 0) as jboolean
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeUsbMux_tcpFreeze(
    env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
) -> jbyteArray {
    let tcp = Box::from_raw(handle as *mut Tcp);
    let value = tcp.freeze();
    drop(tcp);
    bytes(env, &value)
}
