use super::{frame, prepare};
use jni_sys::{jclass, jdouble, jdoubleArray, jint, jobjectArray, jstring, JNIEnv};
use std::ptr::null_mut;

unsafe fn invalid(env: *mut JNIEnv) {
    let f = &**env;
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return;
    }
    let class = (f.FindClass.unwrap())(env, c"java/lang/IllegalArgumentException".as_ptr());
    if !class.is_null() {
        (f.ThrowNew.unwrap())(env, class, c"Invalid formatted NMEA fields".as_ptr());
        (f.DeleteLocalRef.unwrap())(env, class);
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeNmeaLocation_prepare(
    env: *mut JNIEnv,
    _class: jclass,
    lat: jdouble,
    lon: jdouble,
    altitude: jdouble,
    bearing: jdouble,
    speed: jdouble,
    accuracy: jdouble,
    presence: jint,
) -> jdoubleArray {
    let values = prepare(lat, lon, altitude, bearing, speed, accuracy, presence);
    let f = &**env;
    let array = (f.NewDoubleArray.unwrap())(env, values.len() as jint);
    if array.is_null() {
        return null_mut();
    }
    (f.SetDoubleArrayRegion.unwrap())(env, array, 0, values.len() as jint, values.as_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        null_mut()
    } else {
        array
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeNmeaLocation_frame(
    env: *mut JNIEnv,
    _class: jclass,
    array: jobjectArray,
    flags: jint,
) -> jstring {
    let f = &**env;
    if array.is_null() || (f.GetArrayLength.unwrap())(env, array) != 10 || !(0..8).contains(&flags)
    {
        invalid(env);
        return null_mut();
    }
    let mut parts = Vec::with_capacity(10);
    for index in 0..10 {
        let value = (f.GetObjectArrayElement.unwrap())(env, array, index) as jstring;
        if (f.ExceptionCheck.unwrap())(env) != 0 {
            return null_mut();
        }
        if value.is_null() {
            invalid(env);
            return null_mut();
        }
        let length = (f.GetStringLength.unwrap())(env, value);
        let mut chars = vec![0u16; length as usize];
        (f.GetStringRegion.unwrap())(env, value, 0, length, chars.as_mut_ptr());
        (f.DeleteLocalRef.unwrap())(env, value);
        if (f.ExceptionCheck.unwrap())(env) != 0 {
            return null_mut();
        }
        // This boundary accepts only strings produced by Locale.US Formatter in its facade.
        parts.push(String::from_utf16_lossy(&chars));
    }
    let Some(sentence) = frame(&parts, flags) else {
        invalid(env);
        return null_mut();
    };
    let chars: Vec<u16> = sentence.encode_utf16().collect();
    (f.NewString.unwrap())(env, chars.as_ptr(), chars.len() as jint)
}
