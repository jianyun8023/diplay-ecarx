use super::{bonjour, control, info, video, Value};
use crate::protocols::bplist::jni::{read_value, write_value};
use jni_sys::{jboolean, jclass, jint, jlong, jlongArray, jobject, jvalue, JNIEnv};
use std::ptr::null_mut;

// Search Java collections lazily so unknown leaves and unvisited subtrees stay opaque.
unsafe fn corner_mask(env: *mut JNIEnv, value: jobject, depth: i32) -> jobject {
    if value.is_null() || depth > 6 {
        return null_mut();
    }
    let f = &**env;
    if (f.PushLocalFrame.unwrap())(env, 16) < 0 {
        return null_mut();
    }
    let result = (|| -> Option<jobject> {
        let class = |name: &std::ffi::CStr| {
            let class = (f.FindClass.unwrap())(env, name.as_ptr());
            (!class.is_null()).then_some(class)
        };
        let method = |class, name: &std::ffi::CStr, signature: &std::ffi::CStr| {
            let method = (f.GetMethodID.unwrap())(env, class, name.as_ptr(), signature.as_ptr());
            (!method.is_null()).then_some(method)
        };
        let map = class(c"java/util/Map")?;
        let list = class(c"java/util/List")?;
        let iterator = if (f.IsInstanceOf.unwrap())(env, value, map) != 0 {
            let get = method(map, c"get", c"(Ljava/lang/Object;)Ljava/lang/Object;")?;
            let key = (f.NewStringUTF.unwrap())(env, c"topLeftCornerMask".as_ptr());
            if key.is_null() {
                return None;
            }
            let candidate =
                (f.CallObjectMethodA.unwrap())(env, value, get, [jvalue { l: key }].as_ptr());
            if (f.ExceptionCheck.unwrap())(env) != 0 {
                return None;
            }
            let bytes = class(c"[B")?;
            if !candidate.is_null() && (f.IsInstanceOf.unwrap())(env, candidate, bytes) != 0 {
                return Some(candidate);
            }
            (f.DeleteLocalRef.unwrap())(env, candidate);
            let values = method(map, c"values", c"()Ljava/util/Collection;")?;
            let values = (f.CallObjectMethodA.unwrap())(env, value, values, std::ptr::null());
            if (f.ExceptionCheck.unwrap())(env) != 0 {
                return None;
            }
            let collection = class(c"java/util/Collection")?;
            let iterator = method(collection, c"iterator", c"()Ljava/util/Iterator;")?;
            (f.CallObjectMethodA.unwrap())(env, values, iterator, std::ptr::null())
        } else if (f.IsInstanceOf.unwrap())(env, value, list) != 0 {
            let iterator = method(list, c"iterator", c"()Ljava/util/Iterator;")?;
            (f.CallObjectMethodA.unwrap())(env, value, iterator, std::ptr::null())
        } else {
            return Some(null_mut());
        };
        if (f.ExceptionCheck.unwrap())(env) != 0 || iterator.is_null() {
            return None;
        }
        let iterator_class = class(c"java/util/Iterator")?;
        let has_next = method(iterator_class, c"hasNext", c"()Z")?;
        let next = method(iterator_class, c"next", c"()Ljava/lang/Object;")?;
        loop {
            let more = (f.CallBooleanMethodA.unwrap())(env, iterator, has_next, std::ptr::null());
            if (f.ExceptionCheck.unwrap())(env) != 0 {
                return None;
            }
            if more == 0 {
                return Some(null_mut());
            }
            let child = (f.CallObjectMethodA.unwrap())(env, iterator, next, std::ptr::null());
            if (f.ExceptionCheck.unwrap())(env) != 0 {
                return None;
            }
            let found = corner_mask(env, child, depth.wrapping_add(1));
            if (f.ExceptionCheck.unwrap())(env) != 0 {
                return None;
            }
            (f.DeleteLocalRef.unwrap())(env, child);
            if !found.is_null() {
                return Some(found);
            }
        }
    })();
    (f.PopLocalFrame.unwrap())(env, result.unwrap_or(null_mut()))
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeAirPlayProtocol_cornerMask(
    env: *mut JNIEnv,
    _class: jclass,
    value: jobject,
    depth: jint,
) -> jobject {
    corner_mask(env, value, depth)
}

unsafe fn invalid(env: *mut JNIEnv, message: &str) {
    let f = &**env;
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return;
    }
    let class = (f.FindClass.unwrap())(env, c"java/lang/IllegalArgumentException".as_ptr());
    if !class.is_null() {
        let message = std::ffi::CString::new(message).unwrap();
        (f.ThrowNew.unwrap())(env, class, message.as_ptr());
    }
}

// Android 9 BigInteger negative-bit behavior differs from desktop JDKs.
unsafe fn platform_features(env: *mut JNIEnv, legacy: i64) -> super::Result<Value> {
    let f = &**env;
    if (f.PushLocalFrame.unwrap())(env, 16) < 0 {
        return Err("AirPlay platform integer conversion failed");
    }
    let result = (|| -> Option<Value> {
        let class = (f.FindClass.unwrap())(env, c"java/math/BigInteger".as_ptr());
        if class.is_null() {
            return None;
        }
        let value_of = (f.GetStaticMethodID.unwrap())(
            env,
            class,
            c"valueOf".as_ptr(),
            c"(J)Ljava/math/BigInteger;".as_ptr(),
        );
        if value_of.is_null() {
            return None;
        }
        let set_bit = (f.GetMethodID.unwrap())(
            env,
            class,
            c"setBit".as_ptr(),
            c"(I)Ljava/math/BigInteger;".as_ptr(),
        );
        if set_bit.is_null() {
            return None;
        }
        let to_bytes =
            (f.GetMethodID.unwrap())(env, class, c"toByteArray".as_ptr(), c"()[B".as_ptr());
        if to_bytes.is_null() {
            return None;
        }
        let mut bits = (f.CallStaticObjectMethodA.unwrap())(
            env,
            class,
            value_of,
            [jvalue { j: legacy }].as_ptr(),
        );
        if (f.ExceptionCheck.unwrap())(env) != 0 || bits.is_null() {
            return None;
        }
        for bit in video::FEATURE_BITS {
            let next =
                (f.CallObjectMethodA.unwrap())(env, bits, set_bit, [jvalue { i: bit }].as_ptr());
            if (f.ExceptionCheck.unwrap())(env) != 0 || next.is_null() {
                return None;
            }
            (f.DeleteLocalRef.unwrap())(env, bits);
            bits = next;
        }
        let encoded = (f.CallObjectMethodA.unwrap())(env, bits, to_bytes, std::ptr::null());
        if (f.ExceptionCheck.unwrap())(env) != 0 || encoded.is_null() {
            return None;
        }
        let length = (f.GetArrayLength.unwrap())(env, encoded);
        let mut bytes = vec![0u8; length as usize];
        (f.GetByteArrayRegion.unwrap())(env, encoded, 0, length, bytes.as_mut_ptr() as *mut i8);
        if (f.ExceptionCheck.unwrap())(env) != 0 {
            return None;
        }
        Some(video::features_ex(&bytes))
    })();
    (f.PopLocalFrame.unwrap())(env, null_mut());
    result.ok_or("AirPlay platform integer conversion failed")
}

// Android's ICU regex classes and desktop Pattern classes intentionally differ.
unsafe fn platform_status(env: *mut JNIEnv, line: &[u16]) -> super::Result<Value> {
    let f = &**env;
    if (f.PushLocalFrame.unwrap())(env, 16) < 0 {
        return Err("AirPlay platform status matching failed");
    }
    let result = (|| -> Option<Value> {
        let pattern = (f.FindClass.unwrap())(env, c"java/util/regex/Pattern".as_ptr());
        if pattern.is_null() {
            return None;
        }
        let compile = (f.GetStaticMethodID.unwrap())(
            env,
            pattern,
            c"compile".as_ptr(),
            c"(Ljava/lang/String;)Ljava/util/regex/Pattern;".as_ptr(),
        );
        if compile.is_null() {
            return None;
        }
        let matcher = (f.GetMethodID.unwrap())(
            env,
            pattern,
            c"matcher".as_ptr(),
            c"(Ljava/lang/CharSequence;)Ljava/util/regex/Matcher;".as_ptr(),
        );
        if matcher.is_null() {
            return None;
        }
        let expression = (f.NewStringUTF.unwrap())(env, bonjour::STATUS_PATTERN.as_ptr());
        if expression.is_null() {
            return None;
        }
        let compiled = (f.CallStaticObjectMethodA.unwrap())(
            env,
            pattern,
            compile,
            [jvalue { l: expression }].as_ptr(),
        );
        if (f.ExceptionCheck.unwrap())(env) != 0 || compiled.is_null() {
            return None;
        }
        let text = (f.NewString.unwrap())(env, line.as_ptr(), line.len() as jint);
        if text.is_null() {
            return None;
        }
        let matcher =
            (f.CallObjectMethodA.unwrap())(env, compiled, matcher, [jvalue { l: text }].as_ptr());
        if (f.ExceptionCheck.unwrap())(env) != 0 || matcher.is_null() {
            return None;
        }
        let class = (f.FindClass.unwrap())(env, c"java/util/regex/Matcher".as_ptr());
        if class.is_null() {
            return None;
        }
        let find = (f.GetMethodID.unwrap())(env, class, c"find".as_ptr(), c"()Z".as_ptr());
        if find.is_null() {
            return None;
        }
        let group = (f.GetMethodID.unwrap())(
            env,
            class,
            c"group".as_ptr(),
            c"(I)Ljava/lang/String;".as_ptr(),
        );
        if group.is_null() {
            return None;
        }
        let found = (f.CallBooleanMethodA.unwrap())(env, matcher, find, std::ptr::null());
        if (f.ExceptionCheck.unwrap())(env) != 0 {
            return None;
        }
        if found == 0 {
            return Some(Value::Null);
        }
        let matched =
            (f.CallObjectMethodA.unwrap())(env, matcher, group, [jvalue { i: 1 }].as_ptr());
        if (f.ExceptionCheck.unwrap())(env) != 0 || matched.is_null() {
            return None;
        }
        let length = (f.GetStringLength.unwrap())(env, matched);
        let mut units = vec![0u16; length as usize];
        (f.GetStringRegion.unwrap())(env, matched, 0, length, units.as_mut_ptr());
        if (f.ExceptionCheck.unwrap())(env) != 0 {
            return None;
        }
        Some(Value::String(units))
    })();
    (f.PopLocalFrame.unwrap())(env, null_mut());
    result.ok_or("AirPlay platform status matching failed")
}
unsafe fn object(
    env: *mut JNIEnv,
    input: jobject,
    operation: impl FnOnce(Value) -> super::Result<Value>,
) -> jobject {
    let f = &**env;
    if (f.PushLocalFrame.unwrap())(env, 64) < 0 {
        return null_mut();
    }
    let result = (|| {
        let value = read_value(env, input)?;
        match operation(value) {
            Ok(value) => write_value(env, &value),
            Err(message) => {
                invalid(env, message);
                None
            }
        }
    })();
    (f.PopLocalFrame.unwrap())(env, result.unwrap_or(null_mut()))
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeAirPlayProtocol_info(
    env: *mut JNIEnv,
    _class: jclass,
    facts: jobject,
) -> jobject {
    object(env, facts, |facts| {
        info::build(&facts, |legacy| platform_features(env, legacy))
    })
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeAirPlayProtocol_features(
    _env: *mut JNIEnv,
    _class: jclass,
    disable_audio: jboolean,
) -> jlong {
    info::features(disable_audio != 0)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeAirPlayProtocol_bufferedEnabled(
    _env: *mut JNIEnv,
    _class: jclass,
    buffered: jboolean,
    disable_audio: jboolean,
) -> jboolean {
    info::buffered_enabled(buffered != 0, disable_audio != 0) as jboolean
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeAirPlayProtocol_viewArea(
    env: *mut JNIEnv,
    _class: jclass,
    index: jint,
    count: jint,
) -> jobject {
    let f = &**env;
    if (f.PushLocalFrame.unwrap())(env, 32) < 0 {
        return null_mut();
    }
    let result = write_value(env, &info::view_area(index, count)).unwrap_or(null_mut());
    (f.PopLocalFrame.unwrap())(env, result)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeAirPlayProtocol_video(
    env: *mut JNIEnv,
    _class: jclass,
    operation: jint,
    arguments: jobject,
) -> jobject {
    object(env, arguments, |args| {
        video::message(operation, &args, |legacy| platform_features(env, legacy))
    })
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeAirPlayProtocol_control(
    env: *mut JNIEnv,
    _class: jclass,
    operation: jint,
    arguments: jobject,
) -> jobject {
    object(env, arguments, |args| control::command(operation, &args))
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeAirPlayProtocol_bonjour(
    env: *mut JNIEnv,
    _class: jclass,
    operation: jint,
    arguments: jobject,
) -> jobject {
    object(env, arguments, |args| {
        bonjour::values(operation, &args, |line| platform_status(env, line))
    })
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeAirPlayProtocol_probeCreate(
    _env: *mut JNIEnv,
    _class: jclass,
) -> jlong {
    Box::into_raw(Box::new(bonjour::ProbeLine { bytes: Vec::new() })) as jlong
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeAirPlayProtocol_probeRetryCreate(
    env: *mut JNIEnv,
    _class: jclass,
) -> jlongArray {
    let f = &**env;
    let token = (f.NewLongArray.unwrap())(env, 1);
    if !token.is_null() {
        (f.SetLongArrayRegion.unwrap())(env, token, 0, 1, [0].as_ptr());
    }
    token
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeAirPlayProtocol_probeRetry(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    event: jint,
    closed: jboolean,
) -> jlong {
    let f = &**env;
    if token.is_null() || (f.GetArrayLength.unwrap())(env, token) != 1 {
        invalid(env, "Invalid AirPlay probe retry token");
        return 0;
    }
    let mut value = [0];
    (f.GetLongArrayRegion.unwrap())(env, token, 0, 1, value.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return 0;
    }
    match bonjour::probe_retry(&mut value[0], event, closed != 0) {
        Ok(action) => {
            (f.SetLongArrayRegion.unwrap())(env, token, 0, 1, value.as_ptr());
            action
        }
        Err(message) => {
            invalid(env, message);
            0
        }
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeAirPlayProtocol_probeDestroy(
    _env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
) {
    if handle != 0 {
        drop(Box::from_raw(handle as *mut bonjour::ProbeLine));
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeAirPlayProtocol_probeFeed(
    env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
    byte: jint,
) -> jobject {
    if handle == 0 {
        invalid(env, "AirPlay probe is closed");
        return null_mut();
    }
    let f = &**env;
    if (f.PushLocalFrame.unwrap())(env, 32) < 0 {
        return null_mut();
    }
    let result = match (&mut *(handle as *mut bonjour::ProbeLine)).feed(byte) {
        Ok(Some(value)) => write_value(env, &value).unwrap_or(null_mut()),
        Ok(None) => null_mut(),
        Err(message) => {
            let class = (f.FindClass.unwrap())(env, c"java/io/IOException".as_ptr());
            if !class.is_null() {
                (f.ThrowNew.unwrap())(
                    env,
                    class,
                    std::ffi::CString::new(message).unwrap().as_ptr(),
                );
            }
            null_mut()
        }
    };
    (f.PopLocalFrame.unwrap())(env, result)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeAirPlayProtocol_availabilityCreate(
    env: *mut JNIEnv,
    _class: jclass,
) -> jlongArray {
    let f = &**env;
    let token = (f.NewLongArray.unwrap())(env, 4);
    if !token.is_null() {
        (f.SetLongArrayRegion.unwrap())(env, token, 0, 4, [-1, 0, 0, -1].as_ptr());
    }
    token
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeAirPlayProtocol_availability(
    env: *mut JNIEnv,
    _class: jclass,
    token: jlongArray,
    event: jint,
    value: jboolean,
) -> jint {
    let f = &**env;
    if token.is_null() || (f.GetArrayLength.unwrap())(env, token) != 4 {
        invalid(env, "Invalid AirPlay availability token");
        return -1;
    }
    let mut values = [0; 4];
    (f.GetLongArrayRegion.unwrap())(env, token, 0, 4, values.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return -1;
    }
    match video::availability(&mut values, event, value != 0) {
        Ok(action) => {
            (f.SetLongArrayRegion.unwrap())(env, token, 0, 4, values.as_ptr());
            action
        }
        Err(message) => {
            invalid(env, message);
            -1
        }
    }
}
