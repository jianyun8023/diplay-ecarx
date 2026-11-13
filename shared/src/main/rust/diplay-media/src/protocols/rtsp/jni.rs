//! RTSP's Android DTO boundary. Parser errors remain ProtocolException; JVM exceptions propagate.
use super as rtsp;
use jni_sys::{jbyteArray, jclass, jint, jmethodID, jobject, jobjectArray, jstring, jvalue, JNIEnv, JNI_ABORT};
use std::ptr::null_mut;

struct Bytes { env: *mut JNIEnv, array: jbyteArray, data: *mut i8, len: usize }

impl Bytes {
    unsafe fn new(env: *mut JNIEnv, array: jbyteArray) -> Option<Self> {
        if array.is_null() { return None; }
        let data = ((**env).GetByteArrayElements.unwrap())(env, array, null_mut());
        if data.is_null() { return None; }
        Some(Self { env, array, data, len: ((**env).GetArrayLength.unwrap())(env, array) as usize })
    }
    fn bytes(&self) -> &[u8] { unsafe { std::slice::from_raw_parts(self.data as *const u8, self.len) } }
}

impl Drop for Bytes {
    fn drop(&mut self) { unsafe { ((**self.env).ReleaseByteArrayElements.unwrap())(self.env, self.array, self.data, JNI_ABORT); } }
}

unsafe fn text(env: *mut JNIEnv, value: &str) -> Option<jstring> {
    let utf16: Vec<u16> = value.encode_utf16().collect();
    let result = ((**env).NewString.unwrap())(env, utf16.as_ptr(), utf16.len() as jint);
    (!result.is_null()).then_some(result)
}

unsafe fn read_text(env: *mut JNIEnv, value: jstring) -> Option<String> {
    if value.is_null() { return None; }
    let data = ((**env).GetStringChars.unwrap())(env, value, null_mut());
    if data.is_null() { return None; }
    let len = ((**env).GetStringLength.unwrap())(env, value) as usize;
    let result = String::from_utf16_lossy(std::slice::from_raw_parts(data, len));
    ((**env).ReleaseStringChars.unwrap())(env, value, data);
    Some(result)
}

unsafe fn bytes(env: *mut JNIEnv, value: &[u8]) -> Option<jbyteArray> {
    let result = ((**env).NewByteArray.unwrap())(env, value.len() as jint);
    if result.is_null() { return None; }
    ((**env).SetByteArrayRegion.unwrap())(env, result, 0, value.len() as jint, value.as_ptr() as *const i8);
    if ((**env).ExceptionCheck.unwrap())(env) != 0 { None } else { Some(result) }
}

unsafe fn constructor(env: *mut JNIEnv, name: &[u8], signature: &[u8]) -> Option<(jclass, jmethodID)> {
    let class = ((**env).FindClass.unwrap())(env, name.as_ptr() as *const _);
    if class.is_null() { return None; }
    let init = ((**env).GetMethodID.unwrap())(env, class, c"<init>".as_ptr(), signature.as_ptr() as *const _);
    (!init.is_null()).then_some((class, init))
}

unsafe fn throw(env: *mut JNIEnv, class: &[u8], message: &str) {
    let class = ((**env).FindClass.unwrap())(env, class.as_ptr() as *const _);
    if class.is_null() { return; }
    let mut message = message.as_bytes().to_vec();
    message.push(0);
    ((**env).ThrowNew.unwrap())(env, class, message.as_ptr() as *const _);
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeRtsp_parse(
    env: *mut JNIEnv, _class: jclass, buffer: jbyteArray,
) -> jobject {
    if buffer.is_null() { throw(env, b"java/lang/IllegalArgumentException\0", "RTSP buffer is null"); return null_mut(); }
    let Some(input) = Bytes::new(env, buffer) else { return null_mut() };
    let parsed = match rtsp::parse(input.bytes()) {
        Ok(parsed) => parsed,
        Err(error) => { throw(env, b"java/net/ProtocolException\0", &error); return null_mut(); }
    };
    let f = &**env;
    if (f.PushLocalFrame.unwrap())(env, 32) < 0 { return null_mut(); }
    let result = (|| -> Option<jobject> {
        let (list_class, list_init) = constructor(env, b"java/util/ArrayList\0", b"()V\0")?;
        let add = (f.GetMethodID.unwrap())(env, list_class, c"add".as_ptr(), c"(Ljava/lang/Object;)Z".as_ptr());
        if add.is_null() { return None; }
        let list = (f.NewObjectA.unwrap())(env, list_class, list_init, std::ptr::null());
        if list.is_null() { return None; }
        let (map_class, map_init) = constructor(env, b"java/util/HashMap\0", b"()V\0")?;
        let put = (f.GetMethodID.unwrap())(env, map_class, c"put".as_ptr(), c"(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;".as_ptr());
        if put.is_null() { return None; }
        let (request_class, request_init) = constructor(env, b"com/shihab/diplay/airplay/RtspMessage$Request\0",
            b"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/util/Map;[B)V\0")?;
        for request in parsed.messages {
            if (f.PushLocalFrame.unwrap())(env, 16) < 0 { return None; }
            let result = (|| -> Option<jobject> {
                let map = (f.NewObjectA.unwrap())(env, map_class, map_init, std::ptr::null());
                if map.is_null() { return None; }
                for (key, value) in request.headers {
                    let key = text(env, &key)?;
                    let value = text(env, &value)?;
                    let old = (f.CallObjectMethodA.unwrap())(env, map, put, [jvalue { l: key }, jvalue { l: value }].as_ptr());
                    (f.DeleteLocalRef.unwrap())(env, key); (f.DeleteLocalRef.unwrap())(env, value);
                    if !old.is_null() { (f.DeleteLocalRef.unwrap())(env, old); }
                    if (f.ExceptionCheck.unwrap())(env) != 0 { return None; }
                }
                let args = [jvalue { l: text(env, &request.method)? }, jvalue { l: text(env, &request.path)? },
                    jvalue { l: text(env, &request.protocol)? }, jvalue { l: map }, jvalue { l: bytes(env, request.body)? }];
                let result = (f.NewObjectA.unwrap())(env, request_class, request_init, args.as_ptr());
                (!result.is_null()).then_some(result)
            })();
            let request = (f.PopLocalFrame.unwrap())(env, result.unwrap_or(null_mut()));
            if request.is_null() { return None; }
            (f.CallBooleanMethodA.unwrap())(env, list, add, [jvalue { l: request }].as_ptr());
            (f.DeleteLocalRef.unwrap())(env, request);
            if (f.ExceptionCheck.unwrap())(env) != 0 { return None; }
        }
        let (class, init) = constructor(env, b"com/shihab/diplay/airplay/RtspMessage$Parsed\0", b"(Ljava/util/List;[B)V\0")?;
        let result = (f.NewObjectA.unwrap())(env, class, init, [jvalue { l: list }, jvalue { l: bytes(env, parsed.rest)? }].as_ptr());
        (!result.is_null()).then_some(result)
    })();
    (f.PopLocalFrame.unwrap())(env, result.unwrap_or(null_mut()))
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeRtsp_response(
    env: *mut JNIEnv, _class: jclass, protocol: jstring, status: jint, reason: jstring, cseq: jstring,
    headers: jobjectArray, body: jbyteArray,
) -> jbyteArray {
    if protocol.is_null() || headers.is_null() || body.is_null() {
        throw(env, b"java/lang/IllegalArgumentException\0", "RTSP response argument is null"); return null_mut();
    }
    let f = &**env;
    let count = (f.GetArrayLength.unwrap())(env, headers);
    if count % 2 != 0 { throw(env, b"java/lang/IllegalArgumentException\0", "RTSP headers must be pairs"); return null_mut(); }
    let result = (|| -> Option<jbyteArray> {
        let protocol = read_text(env, protocol)?;
        let reason = if reason.is_null() { None } else { Some(read_text(env, reason)?) };
        let cseq = if cseq.is_null() { None } else { Some(read_text(env, cseq)?) };
        let mut pairs = Vec::new();
        for index in (0..count).step_by(2) {
            let key = (f.GetObjectArrayElement.unwrap())(env, headers, index);
            let value = (f.GetObjectArrayElement.unwrap())(env, headers, index + 1);
            if key.is_null() || value.is_null() {
                if (f.ExceptionCheck.unwrap())(env) == 0 { throw(env, b"java/lang/IllegalArgumentException\0", "RTSP header is null"); }
                return None;
            }
            let pair = (read_text(env, key)?, read_text(env, value)?);
            (f.DeleteLocalRef.unwrap())(env, key); (f.DeleteLocalRef.unwrap())(env, value);
            pairs.push(pair);
        }
        let body = Bytes::new(env, body)?;
        bytes(env, &rtsp::response(&protocol, status, reason.as_deref(), cseq.as_deref(), pairs, body.bytes()))
    })();
    result.unwrap_or(null_mut())
}
