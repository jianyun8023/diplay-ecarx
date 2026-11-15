use jni_sys::*;
use std::ptr::{null, null_mut};

unsafe fn bytes(env: *mut JNIEnv, array: jbyteArray) -> Vec<u8> {
    let f = &**env;
    let mut out = vec![0; (f.GetArrayLength.unwrap())(env, array) as usize];
    (f.GetByteArrayRegion.unwrap())(env, array, 0, out.len() as i32, out.as_mut_ptr().cast());
    out
}
unsafe fn array(env: *mut JNIEnv, value: &[u8]) -> jbyteArray {
    let f = &**env;
    let out = (f.NewByteArray.unwrap())(env, value.len() as i32);
    if !out.is_null() {
        (f.SetByteArrayRegion.unwrap())(env, out, 0, value.len() as i32, value.as_ptr().cast());
    }
    out
}
unsafe fn text(env: *mut JNIEnv, value: jstring) -> Vec<u16> {
    let f = &**env;
    if value.is_null() {
        return Vec::new();
    }
    let p = (f.GetStringChars.unwrap())(env, value, null_mut());
    if p.is_null() {
        return Vec::new();
    }
    let out =
        std::slice::from_raw_parts(p, (f.GetStringLength.unwrap())(env, value) as usize).to_vec();
    (f.ReleaseStringChars.unwrap())(env, value, p);
    out
}
unsafe fn string(env: *mut JNIEnv, value: &[u16]) -> jstring {
    ((**env).NewString.unwrap())(env, value.as_ptr(), value.len() as i32)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeEcarxMqtt_connectPacket(
    env: *mut JNIEnv,
    _: jclass,
    client: jbyteArray,
) -> jbyteArray {
    array(env, &super::connect(&bytes(env, client)))
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeEcarxMqtt_publishPacket(
    env: *mut JNIEnv,
    _: jclass,
    id: jint,
    topic: jbyteArray,
    payload: jbyteArray,
) -> jbyteArray {
    array(
        env,
        &super::publish(id, &bytes(env, topic), &bytes(env, payload)),
    )
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeEcarxMqtt_frame(
    env: *mut JNIEnv,
    _: jclass,
    kind: jint,
    body: jbyteArray,
) -> jbyteArray {
    array(env, &super::frame(kind as u8, &bytes(env, body)))
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeEcarxMqtt_mqttString(
    env: *mut JNIEnv,
    _: jclass,
    value: jbyteArray,
) -> jbyteArray {
    let mut out = Vec::new();
    super::string(&mut out, &bytes(env, value));
    array(env, &out)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeEcarxMqtt_jsonEscape(
    env: *mut JNIEnv,
    _: jclass,
    value: jstring,
) -> jstring {
    string(env, &super::escape(&text(env, value)))
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeEcarxMqtt_ack(
    env: *mut JNIEnv,
    _: jclass,
    header: jint,
    body: jbyteArray,
    packet_id: jint,
) -> jint {
    if body.is_null() {
        return -1;
    }
    let body = bytes(env, body);
    if packet_id < 0 {
        if header & 0xf0 == 0x20 && body.last() == Some(&0) {
            0
        } else {
            -1
        }
    } else if header & 0xf0 == 0x40 && body.len() >= 2 {
        u16::from_be_bytes([body[0], body[1]]) as i32
    } else {
        -1
    }
}

/// Reads the existing InputStream without transferring its lifetime or swallowing its exceptions.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeEcarxMqtt_readPacket(
    env: *mut JNIEnv,
    _: jclass,
    input: jobject,
    maximum: jint,
) -> jobjectArray {
    let f = &**env;
    let class = (f.GetObjectClass.unwrap())(env, input);
    let read = (f.GetMethodID.unwrap())(env, class, c"read".as_ptr(), c"()I".as_ptr());
    let read_bytes = (f.GetMethodID.unwrap())(env, class, c"read".as_ptr(), c"([BII)I".as_ptr());
    if read.is_null() || read_bytes.is_null() {
        return null_mut();
    }
    let header = (f.CallIntMethodA.unwrap())(env, input, read, null());
    if header < 0 || (f.ExceptionCheck.unwrap())(env) != 0 {
        return null_mut();
    }
    let (mut size, mut multiplier) = (0, 1);
    loop {
        let digit = (f.CallIntMethodA.unwrap())(env, input, read, null());
        if digit < 0 || (f.ExceptionCheck.unwrap())(env) != 0 {
            return null_mut();
        }
        if maximum < 0 {
            return null_mut();
        }
        match super::length_digit(
            &mut size,
            &mut multiplier,
            digit as u8,
            maximum.max(0) as usize,
        ) {
            None => return null_mut(),
            Some(true) => break,
            Some(false) => {}
        }
    }
    let body = (f.NewByteArray.unwrap())(env, size as i32);
    if body.is_null() {
        return null_mut();
    }
    let mut offset = 0;
    while offset < size {
        let count = (f.CallIntMethodA.unwrap())(
            env,
            input,
            read_bytes,
            [
                jvalue { l: body },
                jvalue { i: offset as i32 },
                jvalue {
                    i: (size - offset) as i32,
                },
            ]
            .as_ptr(),
        );
        if count < 0 || (f.ExceptionCheck.unwrap())(env) != 0 {
            return null_mut();
        }
        offset += count as usize;
    }
    let object = (f.FindClass.unwrap())(env, c"java/lang/Object".as_ptr());
    let integer = (f.FindClass.unwrap())(env, c"java/lang/Integer".as_ptr());
    if object.is_null() || integer.is_null() {
        return null_mut();
    }
    let init = (f.GetMethodID.unwrap())(env, integer, c"<init>".as_ptr(), c"(I)V".as_ptr());
    if init.is_null() {
        return null_mut();
    }
    let number = (f.NewObjectA.unwrap())(env, integer, init, [jvalue { i: header }].as_ptr());
    let result = (f.NewObjectArray.unwrap())(env, 2, object, null_mut());
    if result.is_null() || number.is_null() {
        return null_mut();
    }
    (f.SetObjectArrayElement.unwrap())(env, result, 0, number);
    (f.SetObjectArrayElement.unwrap())(env, result, 1, body);
    result
}

unsafe fn utf(env: *mut JNIEnv, value: &str) -> jstring {
    string(env, &value.encode_utf16().collect::<Vec<_>>())
}
unsafe fn objects(env: *mut JNIEnv, size: usize) -> jobjectArray {
    let f = &**env;
    let class = (f.FindClass.unwrap())(env, c"java/lang/Object".as_ptr());
    if class.is_null() {
        return null_mut();
    }
    (f.NewObjectArray.unwrap())(env, size as i32, class, null_mut())
}
unsafe fn token(env: *mut JNIEnv, slots: &[i64], strings: usize) -> jobjectArray {
    let f = &**env;
    let out = objects(env, strings + 1);
    let data = (f.NewLongArray.unwrap())(env, slots.len() as i32);
    if out.is_null() || data.is_null() {
        return null_mut();
    }
    (f.SetLongArrayRegion.unwrap())(env, data, 0, slots.len() as i32, slots.as_ptr());
    (f.SetObjectArrayElement.unwrap())(env, out, 0, data);
    out
}
unsafe fn slots<const N: usize>(env: *mut JNIEnv, owner: jobjectArray) -> (jlongArray, [i64; N]) {
    let f = &**env;
    let array = (f.GetObjectArrayElement.unwrap())(env, owner, 0);
    let mut values = [0; N];
    (f.GetLongArrayRegion.unwrap())(env, array, 0, N as i32, values.as_mut_ptr());
    (array, values)
}
unsafe fn store(env: *mut JNIEnv, array: jlongArray, values: &[i64]) {
    ((**env).SetLongArrayRegion.unwrap())(env, array, 0, values.len() as i32, values.as_ptr());
}
unsafe fn io_error(env: *mut JNIEnv, message: &'static str) {
    let f = &**env;
    let class = (f.FindClass.unwrap())(env, c"java/io/IOException".as_ptr());
    if !class.is_null() {
        (f.ThrowNew.unwrap())(
            env,
            class,
            std::ffi::CString::new(message).unwrap().as_ptr(),
        );
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeEcarxMqtt_projectionCreate(
    env: *mut JNIEnv,
    _: jclass,
) -> jobjectArray {
    token(env, &super::projection::Projection::new().0, 1)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeEcarxMqtt_projectionUpdate(
    env: *mut JNIEnv,
    _: jclass,
    owner: jobjectArray,
    wanted: jboolean,
) -> jint {
    let (array, values) = slots(env, owner);
    let mut state = super::projection::Projection(values);
    let result = state.update(wanted != 0);
    store(env, array, &state.0);
    result
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeEcarxMqtt_projectionPlan(
    env: *mut JNIEnv,
    _: jclass,
    owner: jobjectArray,
) -> jstring {
    let (array, values) = slots(env, owner);
    let mut state = super::projection::Projection(values);
    let result = state.plan();
    store(env, array, &state.0);
    result.map(|s| utf(env, s)).unwrap_or(null_mut())
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeEcarxMqtt_projectionCommit(
    env: *mut JNIEnv,
    _: jclass,
    owner: jobjectArray,
    published: jstring,
) {
    let (array, values) = slots(env, owner);
    let mut state = super::projection::Projection(values);
    state.commit(
        text(env, published)
            .iter()
            .copied()
            .eq(super::projection::NAVIGATION.encode_utf16()),
    );
    store(env, array, &state.0);
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeEcarxMqtt_projectionPacketId(
    env: *mut JNIEnv,
    _: jclass,
    owner: jobjectArray,
) -> jint {
    let (array, values) = slots(env, owner);
    let mut state = super::projection::Projection(values);
    let id = state.packet_id();
    store(env, array, &state.0);
    id
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeEcarxMqtt_projectionOngoing(
    env: *mut JNIEnv,
    _: jclass,
    owner: jobjectArray,
    on: jboolean,
    destination: jstring,
) -> jstring {
    let f = &**env;
    let payload = super::projection::ongoing(on != 0, &text(env, destination));
    let previous = (f.GetObjectArrayElement.unwrap())(env, owner, 1);
    if !previous.is_null() && text(env, previous) == payload {
        return null_mut();
    }
    let result = string(env, &payload);
    // Match the original attempt dedupe, including failure: ongoing commits before scheduling I/O.
    if !result.is_null() {
        (f.SetObjectArrayElement.unwrap())(env, owner, 1, result);
    }
    result
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeEcarxMqtt_projectionDescription(
    env: *mut JNIEnv,
    _: jclass,
    owner: jobjectArray,
    wanted_only: jboolean,
) -> jstring {
    let (_, values) = slots(env, owner);
    let state = super::projection::Projection(values);
    utf(
        env,
        &if wanted_only != 0 {
            state.wanted().into()
        } else {
            state.description()
        },
    )
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeEcarxMqtt_projectionPayload(
    env: *mut JNIEnv,
    _: jclass,
    published: jstring,
) -> jstring {
    utf(
        env,
        &format!(
            "{{\"statusType\":\"{}\"}}",
            String::from_utf16_lossy(&text(env, published))
        ),
    )
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeEcarxMqtt_observerCreate(
    env: *mut JNIEnv,
    _: jclass,
) -> jobjectArray {
    token(
        env,
        &[0; super::observer::SLOTS],
        super::observer::TOPICS.len(),
    )
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeEcarxMqtt_observerClear(
    env: *mut JNIEnv,
    _: jclass,
    owner: jobjectArray,
) {
    let f = &**env;
    let (array, _) = slots::<{ super::observer::SLOTS }>(env, owner);
    store(env, array, &[0; super::observer::SLOTS]);
    for i in 1..=super::observer::TOPICS.len() {
        (f.SetObjectArrayElement.unwrap())(env, owner, i as i32, null_mut());
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeEcarxMqtt_observerReport(
    env: *mut JNIEnv,
    _: jclass,
    owner: jobjectArray,
    now: jlong,
) -> jstring {
    let (_, state) = slots::<{ super::observer::SLOTS }>(env, owner);
    let mut lines = Vec::new();
    for (i, topic) in super::observer::TOPICS.iter().enumerate() {
        let previous = ((**env).GetObjectArrayElement.unwrap())(env, owner, i as i32 + 1);
        let s = &state[1 + i * 5..];
        lines.push(if previous.is_null() {
            format!("{topic} count=0")
        } else {
            format!(
                "{topic} count={} ageMs={} retained={} {}",
                s[0],
                now.wrapping_sub(s[1]),
                s[4] != 0,
                String::from_utf16_lossy(&text(env, previous))
            )
        });
    }
    utf(env, &lines.join("\n"))
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeEcarxMqtt_observerConnack(
    env: *mut JNIEnv,
    _: jclass,
    header: jint,
    body: jbyteArray,
) -> jboolean {
    (header == 0x20 && !body.is_null() && bytes(env, body) == [0, 0]) as jboolean
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeEcarxMqtt_observerSubscribe(
    env: *mut JNIEnv,
    _: jclass,
) -> jbyteArray {
    array(env, &super::observer::subscribe())
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeEcarxMqtt_observerSuback(
    env: *mut JNIEnv,
    _: jclass,
    body: jbyteArray,
) -> jobjectArray {
    let (accepted, denied) = match super::observer::suback(&bytes(env, body)) {
        Ok(result) => result,
        Err(message) => {
            io_error(env, message);
            return null_mut();
        }
    };
    let f = &**env;
    let class = (f.FindClass.unwrap())(env, c"java/lang/String".as_ptr());
    if class.is_null() {
        return null_mut();
    }
    let result = (f.NewObjectArray.unwrap())(env, 3, class, null_mut());
    if result.is_null() {
        return null_mut();
    }
    let status = format!("subscribed={accepted}/{}", super::observer::TOPICS.len());
    for (i, text) in [
        status.clone(),
        format!("mqtt {status} denied={denied}"),
        (accepted == 0).to_string(),
    ]
    .iter()
    .enumerate()
    {
        let value = utf(env, text);
        if value.is_null() {
            return null_mut();
        }
        (f.SetObjectArrayElement.unwrap())(env, result, i as i32, value);
    }
    result
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeEcarxMqtt_observerPublication(
    env: *mut JNIEnv,
    _: jclass,
    header: jint,
    body: jbyteArray,
) -> jobjectArray {
    let data = bytes(env, body);
    let (index, payload) = match super::observer::publication(header, &data) {
        Ok(Some(value)) => value,
        Ok(None) => return null_mut(),
        Err(message) => {
            io_error(env, message);
            return null_mut();
        }
    };
    let f = &**env;
    let result = objects(env, 3);
    let topic = utf(env, super::observer::TOPICS[index]);
    let payload = array(env, payload);
    let class = (f.FindClass.unwrap())(env, c"java/lang/Boolean".as_ptr());
    if result.is_null() || topic.is_null() || payload.is_null() || class.is_null() {
        return null_mut();
    }
    let init = (f.GetMethodID.unwrap())(env, class, c"<init>".as_ptr(), c"(Z)V".as_ptr());
    if init.is_null() {
        return null_mut();
    }
    let retained = (f.NewObjectA.unwrap())(
        env,
        class,
        init,
        [jvalue {
            z: (header & 1 != 0) as u8,
        }]
        .as_ptr(),
    );
    if retained.is_null() {
        return null_mut();
    }
    for (i, value) in [topic, payload, retained].into_iter().enumerate() {
        (f.SetObjectArrayElement.unwrap())(env, result, i as i32, value);
    }
    result
}

// JSONObject stays a platform parser. Rust selects fields, interprets values and keeps only summaries.
unsafe fn json_value(
    env: *mut JNIEnv,
    json: jobject,
    field: &str,
    null_is_missing: bool,
) -> Option<Vec<u16>> {
    let f = &**env;
    if json.is_null() || (f.ExceptionCheck.unwrap())(env) != 0 {
        return None;
    }
    let class = (f.GetObjectClass.unwrap())(env, json);
    let is_null = (f.GetMethodID.unwrap())(
        env,
        class,
        c"isNull".as_ptr(),
        c"(Ljava/lang/String;)Z".as_ptr(),
    );
    let get = (f.GetMethodID.unwrap())(
        env,
        class,
        c"optString".as_ptr(),
        c"(Ljava/lang/String;)Ljava/lang/String;".as_ptr(),
    );
    if is_null.is_null() || get.is_null() {
        return None;
    }
    let key = utf(env, field);
    if key.is_null() {
        return None;
    }
    let missing = null_is_missing
        && (f.CallBooleanMethodA.unwrap())(env, json, is_null, [jvalue { l: key }].as_ptr()) != 0;
    let result = if missing || (f.ExceptionCheck.unwrap())(env) != 0 {
        None
    } else {
        let value = (f.CallObjectMethodA.unwrap())(env, json, get, [jvalue { l: key }].as_ptr());
        let result = if value.is_null() {
            None
        } else {
            Some(text(env, value))
        };
        (f.DeleteLocalRef.unwrap())(env, value);
        result
    };
    (f.DeleteLocalRef.unwrap())(env, key);
    (f.DeleteLocalRef.unwrap())(env, class);
    result
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeEcarxMqtt_observerObserve(
    env: *mut JNIEnv,
    _: jclass,
    owner: jobjectArray,
    topic: jstring,
    json: jobject,
    empty: jboolean,
    retained: jboolean,
    now: jlong,
    debug: jboolean,
) -> jstring {
    let f = &**env;
    let topic = String::from_utf16_lossy(&text(env, topic));
    let Some(index) = super::observer::TOPICS.iter().position(|t| *t == topic) else {
        return null_mut();
    };
    let summary = if empty != 0 {
        "empty".into()
    } else if json.is_null() {
        "invalid-json".into()
    } else {
        let mut periodicity = -1;
        let mut lanes = 0;
        let class = (f.GetObjectClass.unwrap())(env, json);
        if index == 9 {
            let get = (f.GetMethodID.unwrap())(
                env,
                class,
                c"optInt".as_ptr(),
                c"(Ljava/lang/String;I)I".as_ptr(),
            );
            if get.is_null() {
                return null_mut();
            }
            let key = utf(env, "periodicity");
            if key.is_null() {
                return null_mut();
            }
            periodicity = (f.CallIntMethodA.unwrap())(
                env,
                json,
                get,
                [jvalue { l: key }, jvalue { i: -1 }].as_ptr(),
            );
        } else if index == 7 {
            let get = (f.GetMethodID.unwrap())(
                env,
                class,
                c"optJSONArray".as_ptr(),
                c"(Ljava/lang/String;)Lorg/json/JSONArray;".as_ptr(),
            );
            if get.is_null() {
                return null_mut();
            }
            let key = utf(env, "lanes");
            if key.is_null() {
                return null_mut();
            }
            let items =
                (f.CallObjectMethodA.unwrap())(env, json, get, [jvalue { l: key }].as_ptr());
            if !items.is_null() {
                let class = (f.GetObjectClass.unwrap())(env, items);
                let length =
                    (f.GetMethodID.unwrap())(env, class, c"length".as_ptr(), c"()I".as_ptr());
                let item = (f.GetMethodID.unwrap())(
                    env,
                    class,
                    c"optJSONObject".as_ptr(),
                    c"(I)Lorg/json/JSONObject;".as_ptr(),
                );
                if length.is_null() || item.is_null() {
                    return null_mut();
                }
                let size = (f.CallIntMethodA.unwrap())(env, items, length, null());
                for i in 0..size {
                    let lane =
                        (f.CallObjectMethodA.unwrap())(env, items, item, [jvalue { i }].as_ptr());
                    if json_value(env, lane, "icon", false)
                        .is_some_and(|v| !super::observer::blank(&v))
                    {
                        lanes += 1;
                    }
                    (f.DeleteLocalRef.unwrap())(env, lane);
                    if (f.ExceptionCheck.unwrap())(env) != 0 {
                        return null_mut();
                    }
                }
            }
        }
        if (f.ExceptionCheck.unwrap())(env) != 0 {
            return null_mut();
        }
        super::observer::summary(
            index,
            |field| json_value(env, json, field, true),
            periodicity,
            lanes,
        )
    };
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return null_mut();
    }
    let (array, mut state) = slots::<{ super::observer::SLOTS }>(env, owner);
    let old = (f.GetObjectArrayElement.unwrap())(env, owner, index as i32 + 1);
    let previous = (!old.is_null()).then(|| String::from_utf16_lossy(&text(env, old)));
    let detail = if debug != 0 && !json.is_null() && matches!(index, 5 | 10) {
        format!(
            " distance={}",
            super::observer::distance(
                &json_value(env, json, "distance", false).unwrap_or_default()
            )
        )
    } else {
        String::new()
    };
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        return null_mut();
    }
    let line = super::observer::observe(
        &mut state,
        index,
        previous.as_deref(),
        &summary,
        retained != 0,
        now,
        debug != 0,
        &detail,
    );
    let summary = utf(env, &summary);
    if summary.is_null() {
        return null_mut();
    }
    store(env, array, &state);
    (f.SetObjectArrayElement.unwrap())(env, owner, index as i32 + 1, summary);
    line.map(|s| utf(env, &s)).unwrap_or(null_mut())
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeEcarxMqtt_observerKey(
    env: *mut JNIEnv,
    _: jclass,
    topic: jstring,
    summary: jstring,
) -> jstring {
    utf(
        env,
        &super::observer::key(
            &String::from_utf16_lossy(&text(env, topic)),
            &String::from_utf16_lossy(&text(env, summary)),
        ),
    )
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeEcarxMqtt_observerRetryMillis(
    _: *mut JNIEnv,
    _: jclass,
) -> jlong {
    super::observer::RETRY_MS
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeEcarxMqtt_publishReply(
    env: *mut JNIEnv,
    _: jclass,
    header: jint,
    body: jbyteArray,
    packet_id: jint,
) -> jstring {
    let body = if body.is_null() {
        None
    } else {
        Some(bytes(env, body))
    };
    let acked = body
        .as_ref()
        .filter(|b| header & 0xf0 == 0x40 && b.len() >= 2)
        .map(|b| u16::from_be_bytes([b[0], b[1]]) as i32)
        .unwrap_or(-1);
    let result = if acked == packet_id {
        "ok".into()
    } else if body.is_none() {
        "no puback".into()
    } else {
        format!("puback={acked} wanted={packet_id}")
    };
    string(env, &result.encode_utf16().collect::<Vec<_>>())
}

/// Retry once through a fresh connection after a failed PUBACK; a failed connect stops immediately.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeEcarxMqtt_publishLoop(
    env: *mut JNIEnv,
    _: jclass,
    host: jobject,
    topic: jstring,
    payload: jstring,
) -> jstring {
    let f = &**env;
    if (f.PushLocalFrame.unwrap())(env, 16) < 0 {
        return null_mut();
    }
    let run = || -> jstring {
        let class = (f.GetObjectClass.unwrap())(env, host);
        let connect =
            (f.GetMethodID.unwrap())(env, class, c"mqttConnect".as_ptr(), c"()Z".as_ptr());
        let reason = (f.GetMethodID.unwrap())(
            env,
            class,
            c"mqttConnectReason".as_ptr(),
            c"()Ljava/lang/String;".as_ptr(),
        );
        let publish = (f.GetMethodID.unwrap())(
            env,
            class,
            c"mqttPublish".as_ptr(),
            c"(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;".as_ptr(),
        );
        let drop = (f.GetMethodID.unwrap())(env, class, c"mqttDrop".as_ptr(), c"()V".as_ptr());
        if (f.ExceptionCheck.unwrap())(env) != 0 {
            return null_mut();
        }
        let mut result = null_mut();
        for _ in 0..2 {
            let connected = (f.CallBooleanMethodA.unwrap())(env, host, connect, null());
            if (f.ExceptionCheck.unwrap())(env) != 0 {
                return null_mut();
            }
            if connected == 0 {
                return (f.CallObjectMethodA.unwrap())(env, host, reason, null());
            }
            result = (f.CallObjectMethodA.unwrap())(
                env,
                host,
                publish,
                [jvalue { l: topic }, jvalue { l: payload }].as_ptr(),
            );
            if (f.ExceptionCheck.unwrap())(env) != 0 {
                return null_mut();
            }
            if text(env, result) == [111, 107] {
                return result;
            }
            (f.CallVoidMethodA.unwrap())(env, host, drop, null());
            if (f.ExceptionCheck.unwrap())(env) != 0 {
                return null_mut();
            }
        }
        result
    };
    let result = run();
    (f.PopLocalFrame.unwrap())(env, result)
}
