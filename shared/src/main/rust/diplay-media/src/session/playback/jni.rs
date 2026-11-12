use super::{Navigation, NowPlaying};
use jni_sys::{
    jboolean, jbyteArray, jclass, jint, jlong, jlongArray, jobject, jobjectArray, jvalue, JNIEnv,
};
use std::ptr::null_mut;

unsafe fn invalid(env: *mut JNIEnv) {
    let f = &**env;
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return;
    }
    let class = (f.FindClass.unwrap())(env, c"java/lang/IllegalArgumentException".as_ptr());
    if !class.is_null() {
        (f.ThrowNew.unwrap())(env, class, c"Invalid playback state value".as_ptr());
    }
}
unsafe fn array(env: *mut JNIEnv, words: &[i64]) -> jlongArray {
    let f = &**env;
    let result = (f.NewLongArray.unwrap())(env, words.len() as jint);
    if !result.is_null() {
        (f.SetLongArrayRegion.unwrap())(env, result, 0, words.len() as jint, words.as_ptr());
    }
    result
}
unsafe fn read(env: *mut JNIEnv, owner: jobjectArray) -> Option<Vec<i64>> {
    let f = &**env;
    if owner.is_null() || (f.GetArrayLength.unwrap())(env, owner) != 1 {
        invalid(env);
        return None;
    }
    let token = (f.GetObjectArrayElement.unwrap())(env, owner, 0);
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return None;
    }
    if token.is_null() {
        invalid(env);
        return None;
    }
    let length = (f.GetArrayLength.unwrap())(env, token);
    let mut words = vec![0; length as usize];
    (f.GetLongArrayRegion.unwrap())(env, token, 0, length, words.as_mut_ptr());
    (f.DeleteLocalRef.unwrap())(env, token);
    ((f.ExceptionCheck.unwrap())(env) == 0).then_some(words)
}
unsafe fn save(env: *mut JNIEnv, owner: jobjectArray, words: &[i64]) {
    let token = array(env, words);
    if !token.is_null() {
        ((**env).SetObjectArrayElement.unwrap())(env, owner, 0, token);
        ((**env).DeleteLocalRef.unwrap())(env, token);
    }
}
unsafe fn now(env: *mut JNIEnv, owner: jobjectArray) -> Option<NowPlaying> {
    let state = NowPlaying::decode(&read(env, owner)?);
    if state.is_none() {
        invalid(env);
    }
    state
}
unsafe fn navigation(env: *mut JNIEnv, owner: jobjectArray) -> Option<Navigation> {
    let state = Navigation::decode(&read(env, owner)?);
    if state.is_none() {
        invalid(env);
    }
    state
}
unsafe fn snapshot(env: *mut JNIEnv, state: &NowPlaying) -> jobject {
    let f = &**env;
    if (f.PushLocalFrame.unwrap())(env, 20) < 0 {
        return null_mut();
    }
    let result = (|| -> Option<jobject> {
        let class = (f.FindClass.unwrap())(env, c"com/shilapi/xcertplay/media/CarPlayNowPlaying".as_ptr());
        if class.is_null() { return None; }
        let init = (f.GetMethodID.unwrap())(env, class, c"<init>".as_ptr(),
            c"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Integer;Ljava/lang/String;Ljava/lang/Long;Ljava/lang/Long;Z)V".as_ptr());
        if init.is_null() { return None; }
        let mut strings = [null_mut(); 4];
        for (index, value) in state.text.iter().enumerate() {
            if let Some(value) = value {
                strings[index] = (f.NewString.unwrap())(env, value.as_ptr(), value.len() as jint);
                if strings[index].is_null() { return None; }
            }
        }
        let mut numbers = [null_mut(); 3];
        for (index, value) in state.numbers.iter().enumerate() {
            if let Some(value) = value {
                let class_name = if index == 0 { c"java/lang/Integer" } else { c"java/lang/Long" };
                let signature = if index == 0 { c"(I)Ljava/lang/Integer;" } else { c"(J)Ljava/lang/Long;" };
                let class = (f.FindClass.unwrap())(env, class_name.as_ptr());
                if class.is_null() { return None; }
                let method = (f.GetStaticMethodID.unwrap())(env, class, c"valueOf".as_ptr(), signature.as_ptr());
                if method.is_null() { return None; }
                let arg = if index == 0 { jvalue { i: *value as jint } } else { jvalue { j: *value } };
                numbers[index] = (f.CallStaticObjectMethodA.unwrap())(env, class, method, [arg].as_ptr());
                if (f.ExceptionCheck.unwrap())(env) != 0 { return None; }
                if numbers[index].is_null() { return None; }
            }
        }
        let args = [jvalue { l: strings[0] }, jvalue { l: strings[1] }, jvalue { l: strings[2] }, jvalue { l: numbers[0] },
            jvalue { l: strings[3] }, jvalue { l: numbers[1] }, jvalue { l: numbers[2] }, jvalue { z: state.playing as jboolean }];
        let result = (f.NewObjectA.unwrap())(env, class, init, args.as_ptr());
        if (f.ExceptionCheck.unwrap())(env) != 0 { return None; }
        Some(result)
    })().unwrap_or(null_mut());
    (f.PopLocalFrame.unwrap())(env, result)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativePlaybackCore_nowCreate(
    env: *mut JNIEnv,
    _: jclass,
) -> jlongArray {
    array(env, &NowPlaying::default().encode())
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativePlaybackCore_nowSnapshot(
    env: *mut JNIEnv,
    _: jclass,
    owner: jobjectArray,
) -> jobject {
    now(env, owner).map_or(null_mut(), |state| snapshot(env, &state))
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativePlaybackCore_nowAccept(
    env: *mut JNIEnv,
    class: jclass,
    owner: jobjectArray,
    id: jint,
    payload: jbyteArray,
) -> jobject {
    let Some(mut state) = now(env, owner) else {
        return null_mut();
    };
    let f = &**env;
    let length = (f.GetArrayLength.unwrap())(env, payload);
    let mut bytes = vec![0u8; length as usize];
    (f.GetByteArrayRegion.unwrap())(env, payload, 0, length, bytes.as_mut_ptr() as *mut i8);
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return null_mut();
    }
    let decode = (f.GetStaticMethodID.unwrap())(
        env,
        class,
        c"decodeText".as_ptr(),
        c"([B)Ljava/lang/String;".as_ptr(),
    );
    if decode.is_null() {
        return null_mut();
    }
    let changed = state.accept(id, &bytes, |bytes| {
        if (f.ExceptionCheck.unwrap())(env) != 0 {
            return None;
        }
        let array = (f.NewByteArray.unwrap())(env, bytes.len() as jint);
        if array.is_null() {
            return None;
        }
        (f.SetByteArrayRegion.unwrap())(
            env,
            array,
            0,
            bytes.len() as jint,
            bytes.as_ptr() as *const i8,
        );
        if (f.ExceptionCheck.unwrap())(env) != 0 {
            (f.DeleteLocalRef.unwrap())(env, array);
            return None;
        }
        let value = (f.CallStaticObjectMethodA.unwrap())(
            env,
            class,
            decode,
            [jvalue { l: array }].as_ptr(),
        );
        (f.DeleteLocalRef.unwrap())(env, array);
        if (f.ExceptionCheck.unwrap())(env) != 0 {
            return None;
        }
        if value.is_null() {
            return None;
        }
        let length = (f.GetStringLength.unwrap())(env, value);
        let mut units = vec![0; length as usize];
        (f.GetStringRegion.unwrap())(env, value, 0, length, units.as_mut_ptr());
        (f.DeleteLocalRef.unwrap())(env, value);
        Some(units)
    });
    if (f.ExceptionCheck.unwrap())(env) != 0 || !changed {
        return null_mut();
    }
    // Construct the public immutable snapshot before committing the retained value.
    let result = snapshot(env, &state);
    if !result.is_null() {
        save(env, owner, &state.encode());
    }
    result
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativePlaybackCore_nowClear(
    env: *mut JNIEnv,
    _: jclass,
    owner: jobjectArray,
    all: jboolean,
) -> jobject {
    let Some(mut state) = now(env, owner) else {
        return null_mut();
    };
    if !state.clear(all != 0) {
        return null_mut();
    }
    let result = snapshot(env, &state);
    if !result.is_null() {
        save(env, owner, &state.encode());
    }
    result
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativePlaybackCore_navigationCreate(
    env: *mut JNIEnv,
    _: jclass,
) -> jlongArray {
    array(
        env,
        &Navigation {
            next: 1,
            entries: Vec::new(),
        }
        .encode(),
    )
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativePlaybackCore_navigationEvent(
    env: *mut JNIEnv,
    _: jclass,
    owner: jobjectArray,
    event: jint,
    id: jlong,
    stream: jint,
    has_stream: jboolean,
    now: jlong,
    buffered: jlong,
    tail: jlong,
    priority: jboolean,
) -> jlong {
    let Some(mut state) = navigation(env, owner) else {
        return 0;
    };
    let result = match event {
        0 => state.open(),
        1 => {
            state.played(
                id,
                (has_stream != 0).then_some(stream),
                now,
                buffered,
                tail,
                priority != 0,
            );
            0
        }
        2 => {
            state.close(id);
            0
        }
        3 => return state.contains(id) as jlong,
        _ => {
            invalid(env);
            return 0;
        }
    };
    save(env, owner, &state.encode());
    result
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativePlaybackCore_navigationSnapshot(
    env: *mut JNIEnv,
    _: jclass,
    owner: jobjectArray,
    now: jlong,
) -> jlongArray {
    let Some(mut state) = navigation(env, owner) else {
        return null_mut();
    };
    let result = array(env, &state.snapshot(now));
    if !result.is_null() {
        save(env, owner, &state.encode());
    }
    result
}
