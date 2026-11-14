use super::{bonjour, control, info, setup, video, Value};
use crate::protocols::bplist::jni::{read_value, replace_map_value, write_value};
use jni_sys::{jboolean, jbyteArray, jclass, jint, jlong, jlongArray, jobject, jstring, jvalue, JNIEnv};
use std::ffi::{CStr, CString};
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
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayProtocol_cornerMask(
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
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayProtocol_info(
    env: *mut JNIEnv,
    _class: jclass,
    facts: jobject,
) -> jobject {
    object(env, facts, |facts| {
        info::build(&facts, |legacy| platform_features(env, legacy))
    })
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayProtocol_features(
    _env: *mut JNIEnv,
    _class: jclass,
    disable_audio: jboolean,
) -> jlong {
    info::features(disable_audio != 0)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayProtocol_bufferedEnabled(
    _env: *mut JNIEnv,
    _class: jclass,
    buffered: jboolean,
    disable_audio: jboolean,
) -> jboolean {
    info::buffered_enabled(buffered != 0, disable_audio != 0) as jboolean
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayProtocol_viewArea(
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
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayProtocol_video(
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
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayProtocol_control(
    env: *mut JNIEnv,
    _class: jclass,
    operation: jint,
    arguments: jobject,
) -> jobject {
    object(env, arguments, |args| control::command(operation, &args))
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayProtocol_bonjour(
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
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayProtocol_probeCreate(
    _env: *mut JNIEnv,
    _class: jclass,
) -> jlong {
    Box::into_raw(Box::new(bonjour::ProbeLine { bytes: Vec::new() })) as jlong
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayProtocol_probeRetryCreate(
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
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayProtocol_probeRetry(
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
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayProtocol_probeDestroy(
    _env: *mut JNIEnv,
    _class: jclass,
    handle: jlong,
) {
    if handle != 0 {
        drop(Box::from_raw(handle as *mut bonjour::ProbeLine));
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayProtocol_probeFeed(
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
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayProtocol_availabilityCreate(
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
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayProtocol_availability(
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

// The SETUP boundary reads the platform stream map lazily, so every scalar class keeps the
// unsigned semantics the old Kotlin helpers gave it; the plist bridge is not used here.

unsafe fn string(env: *mut JNIEnv, value: jstring) -> Option<String> {
    if value.is_null() {
        return None;
    }
    let f = &**env;
    let size = (f.GetStringLength.unwrap())(env, value);
    let mut units = vec![0u16; size as usize];
    (f.GetStringRegion.unwrap())(env, value, 0, size, units.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return None;
    }
    Some(String::from_utf16_lossy(&units))
}

/// One stream field by name; None means the read itself failed, a null object means absent.
unsafe fn map_get(env: *mut JNIEnv, map: jobject, key: &str) -> Option<jobject> {
    let f = &**env;
    let class = (f.GetObjectClass.unwrap())(env, map);
    let get = (f.GetMethodID.unwrap())(env, class, c"get".as_ptr(),
        c"(Ljava/lang/Object;)Ljava/lang/Object;".as_ptr());
    if get.is_null() {
        return None;
    }
    let name = CString::new(key).ok()?;
    let key = (f.NewStringUTF.unwrap())(env, name.as_ptr());
    if key.is_null() {
        return None;
    }
    let value = (f.CallObjectMethodA.unwrap())(env, map, get, [jvalue { l: key }].as_ptr());
    (f.DeleteLocalRef.unwrap())(env, key);
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return None;
    }
    Some(value)
}

/// Kotlin's `(value as? Number)?.toLong()`: absent or non-Number values stay missing.
unsafe fn number_long(env: *mut JNIEnv, value: jobject) -> Option<i64> {
    let f = &**env;
    let number = (f.FindClass.unwrap())(env, c"java/lang/Number".as_ptr());
    if value.is_null() || number.is_null() || (f.IsInstanceOf.unwrap())(env, value, number) == 0 {
        return None;
    }
    let method = (f.GetMethodID.unwrap())(env, number, c"longValue".as_ptr(), c"()J".as_ptr());
    if method.is_null() {
        return None;
    }
    let result = (f.CallLongMethodA.unwrap())(env, value, method, std::ptr::null());
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return None;
    }
    Some(result)
}

/// Kotlin's `.toInt()` on a Number: Number#intValue (Long truncation, Double saturation).
unsafe fn number_int(env: *mut JNIEnv, value: jobject) -> Option<i32> {
    let f = &**env;
    let number = (f.FindClass.unwrap())(env, c"java/lang/Number".as_ptr());
    if value.is_null() || number.is_null() || (f.IsInstanceOf.unwrap())(env, value, number) == 0 {
        return None;
    }
    let method = (f.GetMethodID.unwrap())(env, number, c"intValue".as_ptr(), c"()I".as_ptr());
    if method.is_null() {
        return None;
    }
    let result = (f.CallIntMethodA.unwrap())(env, value, method, std::ptr::null());
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return None;
    }
    Some(result)
}

/// Kotlin's `is Boolean -> input; is Number -> input.toInt() != 0; else -> port fallback`.
unsafe fn input_flag(env: *mut JNIEnv, value: jobject) -> Option<bool> {
    let f = &**env;
    if value.is_null() {
        return None;
    }
    let boolean = (f.FindClass.unwrap())(env, c"java/lang/Boolean".as_ptr());
    if !boolean.is_null() && (f.IsInstanceOf.unwrap())(env, value, boolean) != 0 {
        let method = (f.GetMethodID.unwrap())(env, boolean, c"booleanValue".as_ptr(), c"()Z".as_ptr());
        if method.is_null() {
            return None;
        }
        let result = (f.CallBooleanMethodA.unwrap())(env, value, method, std::ptr::null());
        return if (f.ExceptionCheck.unwrap())(env) != 0 { None } else { Some(result != 0) };
    }
    number_int(env, value).map(|value| value != 0)
}

/// The old unsignedPlistDecimal semantics: each scalar class keeps its unsigned decimal form,
/// a negative BigInteger and any non-number are missing.
unsafe fn unsigned_decimal(env: *mut JNIEnv, value: jobject) -> Option<String> {
    let f = &**env;
    if value.is_null() {
        return None;
    }
    let class = |name: &CStr| {
        let class = (f.FindClass.unwrap())(env, name.as_ptr());
        (!class.is_null()).then_some(class)
    };
    let is = |value: jobject, class: jclass| (f.IsInstanceOf.unwrap())(env, value, class) != 0;
    if class(c"java/math/BigInteger").is_some_and(|class| is(value, class)) {
        let big = (f.FindClass.unwrap())(env, c"java/math/BigInteger".as_ptr());
        let signum = (f.GetMethodID.unwrap())(env, big, c"signum".as_ptr(), c"()I".as_ptr());
        let to_string = (f.GetMethodID.unwrap())(env, big, c"toString".as_ptr(),
            c"()Ljava/lang/String;".as_ptr());
        if signum.is_null() || to_string.is_null() {
            return None;
        }
        let sign = (f.CallIntMethodA.unwrap())(env, value, signum, std::ptr::null());
        if (f.ExceptionCheck.unwrap())(env) != 0 {
            return None;
        }
        if sign < 0 {
            return None;
        }
        let text = (f.CallObjectMethodA.unwrap())(env, value, to_string, std::ptr::null());
        if (f.ExceptionCheck.unwrap())(env) != 0 {
            return None;
        }
        return string(env, text as jstring);
    }
    if class(c"java/lang/Long").is_some_and(|class| is(value, class)) {
        return number_long(env, value).map(|value| (value as u64).to_string());
    }
    if class(c"java/lang/Integer").is_some_and(|class| is(value, class)) {
        return number_long(env, value).map(|value| (value as i32 as u32).to_string());
    }
    if class(c"java/lang/Short").is_some_and(|class| is(value, class)) {
        return number_int(env, value).map(|value| (value as u16).to_string());
    }
    if class(c"java/lang/Byte").is_some_and(|class| is(value, class)) {
        return number_int(env, value).map(|value| (value as u8).to_string());
    }
    // Any other Number falls through to its long value, like the old helper's else branch.
    number_long(env, value).map(|value| (value as u64).to_string())
}

/// The reply slot for one connection scalar under the old unsignedPlistInteger semantics.
unsafe fn connection_slot(env: *mut JNIEnv, value: jobject) -> Option<setup::ConnectionSlot> {
    let f = &**env;
    let long = (f.FindClass.unwrap())(env, c"java/lang/Long".as_ptr());
    if !long.is_null() && (f.IsInstanceOf.unwrap())(env, value, long) != 0 {
        return number_long(env, value).map(setup::long_connection);
    }
    let integer = (f.FindClass.unwrap())(env, c"java/lang/Integer".as_ptr());
    if !integer.is_null() && (f.IsInstanceOf.unwrap())(env, value, integer) != 0 {
        return number_long(env, value).map(|value| setup::int_connection(value as i32));
    }
    // A BigInteger or any other object crossed back unchanged.
    Some(setup::ConnectionSlot::Original)
}

enum ExternalConnection {
    /// The unsigned decimal the old platform built as BigInteger(toUnsignedString(value)).
    BigInteger(u64),
    /// The original scalar object crosses back unchanged.
    Object(jobject),
}

/// The connection slot and its external replacement, if the dict needs one.
unsafe fn connection_field(
    env: *mut JNIEnv,
    raw: jobject,
    zero_when_absent: bool,
) -> Option<(Option<Value>, Option<ExternalConnection>)> {
    if raw.is_null() {
        return Some((zero_when_absent.then(|| Value::Int(0)), None));
    }
    Some(match connection_slot(env, raw)? {
        setup::ConnectionSlot::Inline(value) => (Some(value), None),
        setup::ConnectionSlot::UnsignedDecimal(decimal) => (Some(Value::Null), Some(ExternalConnection::BigInteger(decimal))),
        setup::ConnectionSlot::Original => (Some(Value::Null), Some(ExternalConnection::Object(raw))),
    })
}

unsafe fn big_integer(env: *mut JNIEnv, decimal: &str) -> Option<jobject> {
    let f = &**env;
    let class = (f.FindClass.unwrap())(env, c"java/math/BigInteger".as_ptr());
    if class.is_null() {
        return None;
    }
    let ctor = (f.GetMethodID.unwrap())(env, class, c"<init>".as_ptr(), c"(Ljava/lang/String;)V".as_ptr());
    if ctor.is_null() {
        return None;
    }
    let text = (f.NewStringUTF.unwrap())(env, CString::new(decimal).ok()?.as_ptr());
    if text.is_null() {
        return None;
    }
    let object = (f.NewObjectA.unwrap())(env, class, ctor, [jvalue { l: text }].as_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 || object.is_null() {
        return None;
    }
    Some(object)
}

unsafe fn secret(env: *mut JNIEnv, shared: jbyteArray) -> Option<Vec<u8>> {
    if shared.is_null() {
        return None;
    }
    let f = &**env;
    let size = (f.GetArrayLength.unwrap())(env, shared) as usize;
    let mut bytes = vec![0u8; size];
    if size != 0 {
        (f.GetByteArrayRegion.unwrap())(env, shared, 0, size as jint, bytes.as_mut_ptr() as *mut i8);
    }
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return None;
    }
    Some(bytes)
}

/// Interprets one audio SETUP request: the DataStream keys, the reply's raw fields and the
/// microphone plan. Null aborts the SETUP before anything else runs.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayProtocol_audioSetup(
    env: *mut JNIEnv,
    _class: jclass,
    stream_type: jint,
    audio_type: jstring,
    stream: jobject,
    shared_secret: jbyteArray,
    microphone_enabled: jboolean,
) -> jobject {
    let f = &**env;
    if (f.PushLocalFrame.unwrap())(env, 64) < 0 {
        return null_mut();
    }
    let result = (|| -> Option<jobject> {
        let audio_type = string(env, audio_type)?;
        let format_bits_value = map_get(env, stream, "audioFormat")
            .and_then(|value| number_long(env, value))
            .unwrap_or(0);
        let data_port = map_get(env, stream, "dataPort").and_then(|value| number_int(env, value));
        let input = map_get(env, stream, "input").and_then(|value| input_flag(env, value));
        let frames_per_packet = map_get(env, stream, "framesPerPacket")
            .and_then(|value| number_int(env, value))
            .unwrap_or(0);
        let latency_ms = map_get(env, stream, "audioLatencyMs")
            .and_then(|value| number_int(env, value))
            .unwrap_or(0);
        let connection_decimal = map_get(env, stream, "streamConnectionID")
            .and_then(|value| unsigned_decimal(env, value));
        let secret = secret(env, shared_secret);

        let derive = |label: &str| {
            secret.as_ref().zip(connection_decimal.as_ref())
                .map(|(secret, decimal)| setup::data_stream_key(secret, decimal, label).to_vec())
        };
        let plan = setup::microphone_plan(
            &audio_type,
            stream_type,
            microphone_enabled != 0,
            format_bits_value,
            frames_per_packet,
            data_port,
            input,
        );
        let mic = plan.map(|plan| {
            super::dict([
                ("port", Value::Int32(plan.port)),
                ("frameMillis", Value::Int32(plan.frame_millis)),
                ("bitrate", plan.bitrate.map(Value::Int32).unwrap_or(Value::Null)),
                ("opusClockRate", Value::Int32(plan.opus_clock_rate)),
                ("waitDownlink", Value::Bool(plan.wait_downlink)),
                ("inputKey", derive(setup::INPUT_KEY_LABEL).map(Value::Data).unwrap_or(Value::Null)),
            ])
        }).unwrap_or(Value::Null);
        let plan = super::dict([
            ("outputKey", derive(setup::OUTPUT_KEY_LABEL).map(Value::Data).unwrap_or(Value::Null)),
            ("formatBits", Value::Int(format_bits_value as u64)),
            ("micPort", Value::Int32(data_port.unwrap_or(0))),
            ("latencyMs", Value::Int32(latency_ms)),
            ("mic", mic),
        ]);
        write_value(env, &plan)
    })();
    (f.PopLocalFrame.unwrap())(env, result.unwrap_or(null_mut()))
}

/// Interprets one data-stream SETUP request: the control type, the seed decimal and the
/// connection decimal the setup log and reply need.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayProtocol_dataStreamSetup(
    env: *mut JNIEnv,
    _class: jclass,
    stream: jobject,
) -> jobject {
    let f = &**env;
    if (f.PushLocalFrame.unwrap())(env, 32) < 0 {
        return null_mut();
    }
    let result = (|| -> Option<jobject> {
        let control_type = map_get(env, stream, "controlType")
            .and_then(|value| number_int(env, value))
            .unwrap_or(0);
        let seed = map_get(env, stream, "seed").and_then(|value| unsigned_decimal(env, value));
        let connection = map_get(env, stream, "streamConnectionID")
            .and_then(|value| unsigned_decimal(env, value));
        let text = |value: Option<String>| value
            .map(|value| Value::String(value.encode_utf16().collect()))
            .unwrap_or(Value::Null);
        let parsed = super::dict([
            ("controlType", Value::Int32(control_type)),
            ("seed", text(seed)),
            ("connectionIdDecimal", text(connection)),
        ]);
        write_value(env, &parsed)
    })();
    (f.PopLocalFrame.unwrap())(env, result.unwrap_or(null_mut()))
}

/// Derives the 32-byte DataStream key the platform builds its sockets with: the seed decimal
/// and label name cross the boundary, the salt assembly stays with the Rust owner.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayProtocol_dataStreamKey(
    env: *mut JNIEnv,
    _class: jclass,
    shared_secret: jbyteArray,
    seed: jstring,
    label: jstring,
) -> jbyteArray {
    let f = &**env;
    if (f.PushLocalFrame.unwrap())(env, 16) < 0 {
        return null_mut();
    }
    let result = (|| -> Option<jbyteArray> {
        let secret = secret(env, shared_secret)?;
        let seed = string(env, seed)?;
        let label = string(env, label)?;
        let key = setup::data_stream_key(&secret, &seed, &label);
        let array = (f.NewByteArray.unwrap())(env, key.len() as jint);
        if !array.is_null() {
            (f.SetByteArrayRegion.unwrap())(env, array, 0, key.len() as jint, key.as_ptr() as *mut i8);
        }
        if (f.ExceptionCheck.unwrap())(env) != 0 {
            return None;
        }
        Some(array)
    })();
    (f.PopLocalFrame.unwrap())(env, result.unwrap_or(null_mut()))
}

/// Builds one SETUP reply dict in the protocol's field order. The connection value keeps the
/// old unsigned representation: signed scalars stay Long/Integer, a negative scalar becomes
/// its unsigned decimal BigInteger, and any other object crosses back unchanged.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeAirPlayProtocol_setupReply(
    env: *mut JNIEnv,
    _class: jclass,
    operation: jint,
    stream_type: jint,
    stream_id: jlong,
    data_port: jint,
    control_port: jint,
    connection: jobject,
) -> jobject {
    let f = &**env;
    if (f.PushLocalFrame.unwrap())(env, 32) < 0 {
        return null_mut();
    }
    let result = (|| -> Option<jobject> {
        let (slot, external) = connection_field(env, connection, operation == setup::REPLY_AUDIO)?;
        let value = match operation {
            setup::REPLY_AUDIO => setup::audio_reply(stream_type, data_port, control_port,
                slot.unwrap_or(Value::Int(0))),
            setup::REPLY_REMOTE_CONTROL => setup::data_reply(stream_type, stream_id, None, slot),
            setup::REPLY_DATA => setup::data_reply(stream_type, stream_id, Some(data_port), slot),
            _ => return None,
        };
        let map = write_value(env, &value)?;
        match external {
            Some(ExternalConnection::BigInteger(decimal)) => {
                let object = big_integer(env, &setup::unsigned_decimal_string(decimal))?;
                replace_map_value(env, map, "streamConnectionID", object);
            }
            Some(ExternalConnection::Object(object)) => {
                replace_map_value(env, map, "streamConnectionID", object);
            }
            None => {}
        }
        if (f.ExceptionCheck.unwrap())(env) != 0 {
            return None;
        }
        Some(map)
    })();
    (f.PopLocalFrame.unwrap())(env, result.unwrap_or(null_mut()))
}
