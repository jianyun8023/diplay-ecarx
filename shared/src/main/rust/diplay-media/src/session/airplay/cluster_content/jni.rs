use super::State;
use jni_sys::{jboolean, jclass, jint, jlongArray, jobject, jobjectArray, jstring, jvalue, JNIEnv};
use std::ptr::null_mut;

unsafe fn invalid(env: *mut JNIEnv) {
    let f = &**env;
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return;
    }
    let class = (f.FindClass.unwrap())(env, c"java/lang/IllegalArgumentException".as_ptr());
    if !class.is_null() {
        (f.ThrowNew.unwrap())(env, class, c"Invalid cluster content value".as_ptr());
        (f.DeleteLocalRef.unwrap())(env, class);
    }
}

unsafe fn read(env: *mut JNIEnv, token: jlongArray) -> Option<State> {
    let f = &**env;
    if token.is_null() {
        invalid(env);
        return None;
    }
    let size = (f.GetArrayLength.unwrap())(env, token);
    let mut words = vec![0; size as usize];
    (f.GetLongArrayRegion.unwrap())(env, token, 0, size, words.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return None;
    }
    let state = State::from_token(&words);
    if state.is_none() {
        invalid(env);
    }
    state
}

unsafe fn string(env: *mut JNIEnv, value: jstring) -> Option<Vec<u16>> {
    if value.is_null() {
        return None;
    }
    let f = &**env;
    let size = (f.GetStringLength.unwrap())(env, value);
    let mut units = vec![0; size as usize];
    (f.GetStringRegion.unwrap())(env, value, 0, size, units.as_mut_ptr());
    Some(units)
}

unsafe fn token(env: *mut JNIEnv, state: &State) -> jlongArray {
    let words = state.token();
    let f = &**env;
    let array = (f.NewLongArray.unwrap())(env, words.len() as jint);
    if !array.is_null() {
        (f.SetLongArrayRegion.unwrap())(env, array, 0, words.len() as jint, words.as_ptr());
    }
    array
}

unsafe fn boxed(env: *mut JNIEnv, value: i32, boolean: bool) -> jobject {
    let f = &**env;
    let (name, signature, argument) = if boolean {
        (
            c"java/lang/Boolean",
            c"(Z)Ljava/lang/Boolean;",
            jvalue { z: value as u8 },
        )
    } else {
        (
            c"java/lang/Integer",
            c"(I)Ljava/lang/Integer;",
            jvalue { i: value },
        )
    };
    let class = (f.FindClass.unwrap())(env, name.as_ptr());
    if class.is_null() {
        return null_mut();
    }
    let method =
        (f.GetStaticMethodID.unwrap())(env, class, c"valueOf".as_ptr(), signature.as_ptr());
    let object = if method.is_null() {
        null_mut()
    } else {
        (f.CallStaticObjectMethodA.unwrap())(env, class, method, &argument)
    };
    (f.DeleteLocalRef.unwrap())(env, class);
    object
}

unsafe fn array(env: *mut JNIEnv, values: &[jobject]) -> jobjectArray {
    let f = &**env;
    let class = (f.FindClass.unwrap())(env, c"java/lang/Object".as_ptr());
    if class.is_null() {
        return null_mut();
    }
    let result = (f.NewObjectArray.unwrap())(env, values.len() as jint, class, null_mut());
    (f.DeleteLocalRef.unwrap())(env, class);
    for (index, &value) in values.iter().enumerate() {
        if !result.is_null() {
            (f.SetObjectArrayElement.unwrap())(env, result, index as jint, value);
        }
        if !value.is_null() {
            (f.DeleteLocalRef.unwrap())(env, value);
        }
    }
    result
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeClusterContent_create(
    env: *mut JNIEnv,
    _class: jclass,
    initial: jstring,
) -> jlongArray {
    let state = State::new(string(env, initial));
    if ((**env).ExceptionCheck.unwrap())(env) != 0 {
        return null_mut();
    }
    token(env, &state)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeClusterContent_change(
    env: *mut JNIEnv,
    _class: jclass,
    value: jlongArray,
    event: jint,
    stream: jint,
    url: jstring,
    flags: jint,
    expected: jint,
) -> jobjectArray {
    if !(1..=12).contains(&event)
        || !(0..32).contains(&flags)
        || ([3, 4, 10].contains(&event) && url.is_null())
    {
        invalid(env);
        return null_mut();
    }
    let Some(state) = read(env, value) else {
        return null_mut();
    };
    let url = string(env, url);
    if ((**env).ExceptionCheck.unwrap())(env) != 0 {
        return null_mut();
    }
    let change = state.change(event, stream, url, flags, expected);
    let send_url = change.send_url.as_ref().map_or(null_mut(), |url| {
        ((**env).NewString.unwrap())(env, url.as_ptr(), url.len() as jint)
    });
    array(
        env,
        &[
            token(env, &change.state),
            boxed(
                env,
                change.state.content.as_ref().map_or(0, |c| c.stream),
                false,
            ),
            boxed(env, change.accepted as i32, true),
            send_url,
            boxed(env, change.need_key_frame as i32, true),
            boxed(env, change.rotate_route_token as i32, true),
        ],
    )
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeClusterContent_route(
    env: *mut JNIEnv,
    _class: jclass,
    value: jlongArray,
    closed: jboolean,
    stream: jint,
) -> jobjectArray {
    let Some(state) = read(env, value) else {
        return null_mut();
    };
    let Some(content) = state.content else {
        return null_mut();
    };
    if closed != 0 || content.stream != stream {
        return null_mut();
    }
    let displayed = content.displayed.as_ref().map_or(null_mut(), |url| {
        ((**env).NewString.unwrap())(env, url.as_ptr(), url.len() as jint)
    });
    array(env, &[boxed(env, content.stream, false), displayed])
}
