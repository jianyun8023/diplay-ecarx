use super::xml::{self, Fault, Lexer, Token};
use super::{dict, remaining, string, text, timeout, Text, Value};
use jni_sys::{
    jarray, jboolean, jbyteArray, jclass, jint, jlong, jlongArray, jobject, jobjectArray, jstring,
    jvalue, JNIEnv,
};
use std::{ffi::CStr, ptr::null_mut};
type R<T> = Result<T, Fault>;
struct Env(*mut JNIEnv);
impl Env {
    unsafe fn ok(&self) -> R<()> {
        if ((**self.0).ExceptionCheck.unwrap())(self.0) == 0 {
            Ok(())
        } else {
            Err(Fault::Pending)
        }
    }
    unsafe fn frame<T>(&self, f: impl FnOnce() -> R<T>) -> R<T> {
        ((**self.0).PushLocalFrame.unwrap())(self.0, 16);
        self.ok()?;
        let result = f();
        ((**self.0).PopLocalFrame.unwrap())(self.0, null_mut());
        self.ok()?;
        result
    }
    unsafe fn len(&self, a: jarray) -> R<usize> {
        let n = ((**self.0).GetArrayLength.unwrap())(self.0, a);
        self.ok()?;
        Ok(n as usize)
    }
    unsafe fn item(&self, a: jobjectArray, i: usize) -> R<jobject> {
        let o = ((**self.0).GetObjectArrayElement.unwrap())(self.0, a, i as i32);
        self.ok()?;
        Ok(o)
    }
    unsafe fn set(&self, a: jobjectArray, i: usize, v: jobject) -> R<()> {
        ((**self.0).SetObjectArrayElement.unwrap())(self.0, a, i as i32, v);
        self.ok()
    }
    unsafe fn array(&self, n: usize) -> R<jobjectArray> {
        let c = ((**self.0).FindClass.unwrap())(self.0, c"java/lang/Object".as_ptr());
        self.ok()?;
        let a = ((**self.0).NewObjectArray.unwrap())(self.0, n as i32, c, null_mut());
        self.ok()?;
        ((**self.0).DeleteLocalRef.unwrap())(self.0, c);
        Ok(a)
    }
    unsafe fn longs(&self, a: jlongArray) -> R<Vec<i64>> {
        let mut v = vec![0; self.len(a)?];
        ((**self.0).GetLongArrayRegion.unwrap())(self.0, a, 0, v.len() as i32, v.as_mut_ptr());
        self.ok()?;
        Ok(v)
    }
    unsafe fn put_longs(&self, a: jlongArray, v: &[i64]) -> R<()> {
        ((**self.0).SetLongArrayRegion.unwrap())(self.0, a, 0, v.len() as i32, v.as_ptr());
        self.ok()
    }
    unsafe fn long_array(&self, v: &[i64]) -> R<jlongArray> {
        let a = ((**self.0).NewLongArray.unwrap())(self.0, v.len() as i32);
        self.ok()?;
        self.put_longs(a, v)?;
        Ok(a)
    }
    unsafe fn ints(&self, a: jarray) -> R<Vec<i32>> {
        let mut v = vec![0; self.len(a)?];
        ((**self.0).GetIntArrayRegion.unwrap())(self.0, a, 0, v.len() as i32, v.as_mut_ptr());
        self.ok()?;
        Ok(v)
    }
    unsafe fn int_array(&self, v: &[i32]) -> R<jobject> {
        let a = ((**self.0).NewIntArray.unwrap())(self.0, v.len() as i32);
        self.ok()?;
        ((**self.0).SetIntArrayRegion.unwrap())(self.0, a, 0, v.len() as i32, v.as_ptr());
        self.ok()?;
        Ok(a)
    }
    unsafe fn bytes(&self, a: jbyteArray) -> R<Vec<u8>> {
        let mut v = vec![0; self.len(a)?];
        ((**self.0).GetByteArrayRegion.unwrap())(
            self.0,
            a,
            0,
            v.len() as i32,
            v.as_mut_ptr() as *mut i8,
        );
        self.ok()?;
        Ok(v)
    }
    unsafe fn byte_array(&self, v: &[u8]) -> R<jbyteArray> {
        let a = ((**self.0).NewByteArray.unwrap())(self.0, v.len() as i32);
        self.ok()?;
        ((**self.0).SetByteArrayRegion.unwrap())(
            self.0,
            a,
            0,
            v.len() as i32,
            v.as_ptr() as *const i8,
        );
        self.ok()?;
        Ok(a)
    }
    unsafe fn text(&self, a: jstring) -> R<Text> {
        if a.is_null() {
            return Ok(Vec::new());
        };
        let n = ((**self.0).GetStringLength.unwrap())(self.0, a);
        self.ok()?;
        let mut v = vec![0; n as usize];
        ((**self.0).GetStringRegion.unwrap())(self.0, a, 0, n, v.as_mut_ptr());
        self.ok()?;
        Ok(v)
    }
    unsafe fn string(&self, v: &[u16]) -> R<jstring> {
        let o = ((**self.0).NewString.unwrap())(self.0, v.as_ptr(), v.len() as i32);
        self.ok()?;
        Ok(o)
    }
    unsafe fn method(&self, o: jobject, name: &CStr, sig: &CStr) -> R<jni_sys::jmethodID> {
        let c = ((**self.0).GetObjectClass.unwrap())(self.0, o);
        self.ok()?;
        let m = ((**self.0).GetMethodID.unwrap())(self.0, c, name.as_ptr(), sig.as_ptr());
        self.ok()?;
        ((**self.0).DeleteLocalRef.unwrap())(self.0, c);
        Ok(m)
    }
    unsafe fn call(&self, o: jobject, name: &CStr, sig: &CStr, args: &[jvalue]) -> R<jobject> {
        let m = self.method(o, name, sig)?;
        let v = ((**self.0).CallObjectMethodA.unwrap())(self.0, o, m, args.as_ptr());
        self.ok()?;
        Ok(v)
    }
    unsafe fn throw(&self, class: &CStr, msg: &CStr) -> R<()> {
        let c = ((**self.0).FindClass.unwrap())(self.0, class.as_ptr());
        self.ok()?;
        ((**self.0).ThrowNew.unwrap())(self.0, c, msg.as_ptr());
        Err(Fault::Pending)
    }
    unsafe fn tree(&self, v: &Value) -> R<jobjectArray> {
        let a = self.array(2)?;
        let kind = match v {
            Value::Dict(_) => 0,
            Value::Text(_) => 1,
            Value::Int(_) => 2,
            Value::Bool(_) => 3,
            Value::Data(_) => 4,
        };
        self.set(
            a,
            0,
            self.int_array(&[kind, if matches!(v, Value::Bool(true)) { 1 } else { 0 }])?,
        )?;
        let child = match v {
            Value::Dict(items) => {
                let children = self.array(items.len() * 2)?;
                for (i, (k, v)) in items.iter().enumerate() {
                    self.frame(|| {
                        self.set(children, i * 2, self.string(k)?)?;
                        let child = self.tree(v)?;
                        self.set(children, i * 2 + 1, child)
                    })?;
                }
                children
            }
            Value::Text(t) => self.string(t)?,
            Value::Int(n) => self.long_array(&[*n])?,
            Value::Bool(_) => null_mut(),
            Value::Data(b) => self.byte_array(b)?,
        };
        self.set(a, 1, child)?;
        if !child.is_null() {
            ((**self.0).DeleteLocalRef.unwrap())(self.0, child);
        }
        Ok(a)
    }
    unsafe fn value(&self, a: jobjectArray) -> R<Value> {
        self.frame(|| {
            let meta = self.ints(self.item(a, 0)?)?;
            let v = self.item(a, 1)?;
            Ok(match meta[0] {
                0 => {
                    let mut items = Vec::new();
                    for i in (0..self.len(v)?).step_by(2) {
                        items.push(self.frame(|| {
                            Ok((
                                self.text(self.item(v, i)?)?,
                                self.value(self.item(v, i + 1)?)?,
                            ))
                        })?);
                    }
                    Value::Dict(items)
                }
                1 => Value::Text(self.text(v)?),
                2 => Value::Int(self.longs(v)?[0]),
                3 => Value::Bool(meta[1] != 0),
                4 => Value::Data(self.bytes(v)?),
                _ => unreachable!("private Lockdown value tag"),
            })
        })
    }
    unsafe fn codec(&self, result: R<jobject>) -> R<jobjectArray> {
        if matches!(result, Err(Fault::Pending)) {
            return Err(Fault::Pending);
        }
        let a = self.array(2)?;
        match result {
            Ok(v) => self.set(a, 0, v)?,
            Err(Fault::Protocol(t)) => self.set(a, 1, self.string(&t)?)?,
            Err(e) => return Err(e),
        };
        Ok(a)
    }
}
unsafe fn export(env: *mut JNIEnv, f: impl FnOnce(&Env) -> R<jobject>) -> jobject {
    let e = Env(env);
    ((**env).PushLocalFrame.unwrap())(env, 32);
    if e.ok().is_err() {
        return null_mut();
    }
    let result = f(&e);
    let value = match result {
        Ok(v) => v,
        Err(_) => null_mut(),
    };
    ((**env).PopLocalFrame.unwrap())(env, value)
}
struct Tokens<'a>(&'a Env, jobject);
impl Lexer for Tokens<'_> {
    fn next(&mut self, tag: bool) -> R<Token> {
        unsafe {
            self.0.frame(|| {
                let a = self.0.call(
                    self.1,
                    c"next",
                    c"(I)[Ljava/lang/Object;",
                    &[jvalue { i: tag as i32 }],
                )?;
                let meta = self.0.ints(self.0.item(a, 0)?)?;
                let t = self.0.item(a, 2)?;
                Ok(Token {
                    event: meta[0],
                    blank: meta[1] != 0,
                    name: self.0.text(self.0.item(a, 1)?)?,
                    value: if t.is_null() {
                        None
                    } else {
                        Some(self.0.text(t)?)
                    },
                })
            })
        }
    }
    fn integer(&mut self, t: &[u16]) -> R<Option<i64>> {
        unsafe {
            self.0.frame(|| {
                let s = self.0.string(t)?;
                let a = self.0.call(
                    self.1,
                    c"integer",
                    c"(Ljava/lang/String;)[J",
                    &[jvalue { l: s }],
                )?;
                Ok(self.0.longs(a)?.first().copied())
            })
        }
    }
    fn data(&mut self, t: &[u16]) -> R<Text> {
        unsafe {
            self.0.frame(|| {
                let s = self.0.string(t)?;
                let a = self.0.call(
                    self.1,
                    c"data",
                    c"(Ljava/lang/String;)Ljava/lang/String;",
                    &[jvalue { l: s }],
                )?;
                self.0.text(a)
            })
        }
    }
}
unsafe fn input(e: &Env, source: jobject, depth: usize) -> R<Value> {
    if depth > 64 {
        return Err(Fault::Protocol(text("Lockdown plist nesting exceeds 64")));
    }
    e.frame(|| {
        let token = e.call(source, c"next", c"()[Ljava/lang/Object;", &[])?;
        let meta = e.ints(e.item(token, 0)?)?;
        Ok(match meta[0] {
            0 => {
                if meta[1] > 1024 {
                    return Err(Fault::Protocol(text(
                        "Lockdown plist dictionary exceeds 1024 entries",
                    )));
                }
                let mut items = Vec::new();
                loop {
                    let key = e.frame(|| {
                        let a = e.call(source, c"next", c"()[Ljava/lang/Object;", &[])?;
                        let kind = e.ints(e.item(a, 0)?)?[0];
                        if kind == 2 {
                            Ok(None)
                        } else {
                            Ok(Some(e.text(e.item(a, 1)?)?))
                        }
                    })?;
                    match key {
                        Some(k) => items.push((k, input(e, source, depth + 1)?)),
                        None => break,
                    }
                }
                Value::Dict(items)
            }
            3 => Value::Text(e.text(e.item(token, 1)?)?),
            4 => Value::Int(e.longs(e.item(token, 3)?)?[0]),
            5 => Value::Bool(meta[1] != 0),
            6 => Value::Data(e.bytes(e.item(token, 2)?)?),
            _ => unreachable!("private Lockdown visitor event"),
        })
    })
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeLockdown_encode(
    env: *mut JNIEnv,
    _: jclass,
    source: jobject,
) -> jobjectArray {
    export(env, |e| {
        let result = input(e, source, 0)
            .and_then(|v| xml::encode(&v))
            .and_then(|v| e.string(&v));
        e.codec(result)
    })
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeLockdown_encodeTree(
    env: *mut JNIEnv,
    _: jclass,
    tree: jobjectArray,
) -> jobjectArray {
    export(env, |e| {
        let result = e
            .value(tree)
            .and_then(|v| xml::encode(&v))
            .and_then(|v| e.string(&v));
        e.codec(result)
    })
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeLockdown_decode(
    env: *mut JNIEnv,
    _: jclass,
    source: jobject,
) -> jobjectArray {
    export(env, |e| {
        let result = xml::parse(&mut Tokens(e, source)).and_then(|v| e.tree(&v));
        e.codec(result)
    })
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeLockdown_channelCreate(
    env: *mut JNIEnv,
    _: jclass,
    max: jint,
    default_timeout: jlong,
) -> jlongArray {
    export(env, |e| {
        if !(1..=4 * 1024 * 1024).contains(&max) {
            e.throw(
                c"java/lang/IllegalArgumentException",
                c"maximumMessageBytes must be between 1 and 4194304",
            )?;
        }
        if timeout(default_timeout).is_err() {
            e.throw(
                c"java/lang/IllegalArgumentException",
                c"defaultTimeoutMillis must be between 1 and 300000",
            )?;
        }
        e.long_array(&[max as i64, 0])
    })
}
// Serialized by the facade's state monitor. No native allocation can outlive close/detach.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeLockdown_channelState(
    env: *mut JNIEnv,
    _: jclass,
    state: jlongArray,
    operation: jint,
) -> jlong {
    let e = Env(env);
    let result: R<i64> = (|| {
        let mut s = e.longs(state)?;
        let old = s[1];
        match operation {
            0 => return Ok(if old == 0 { s[0] } else { 0 }),
            1 => {
                if old == 0 {
                    s[1] = 1;
                    e.put_longs(state, &s)?;
                }
                Ok((old == 0) as i64)
            }
            2 => {
                if old == 0 {
                    s[1] = 2;
                    e.put_longs(state, &s)?;
                }
                Ok(old)
            }
            _ => unreachable!(),
        }
    })();
    result.unwrap_or(0)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeLockdown_validateTimeout(
    env: *mut JNIEnv,
    _: jclass,
    value: jlong,
) {
    let e = Env(env);
    if timeout(value).is_err() {
        let _ = e.throw(
            c"java/lang/IllegalArgumentException",
            c"timeoutMillis must be between 1 and 300000",
        );
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeLockdown_frame(
    env: *mut JNIEnv,
    _: jclass,
    max: jint,
    bytes: jbyteArray,
) -> jobjectArray {
    export(env, |e| {
        let bytes = e.bytes(bytes)?;
        let result = if bytes.is_empty() || bytes.len() > max as usize {
            Err(Fault::Protocol(text(&format!(
                "Lockdown plist message length {} is outside 1..{max}",
                bytes.len()
            ))))
        } else {
            let mut frame = (bytes.len() as u32).to_be_bytes().to_vec();
            frame.extend(bytes);
            e.byte_array(&frame)
        };
        e.codec(result)
    })
}

impl Env {
    unsafe fn action(
        &self,
        kind: i32,
        body: Option<&[u16]>,
        meta: &[i64],
        payload: Option<&[u8]>,
        extra: Option<&[u16]>,
    ) -> R<jobjectArray> {
        let out = self.array(5)?;
        self.set(out, 0, self.int_array(&[kind])?)?;
        self.set(out, 2, self.long_array(meta)?)?;
        if let Some(t) = body {
            self.set(out, 1, self.string(t)?)?;
        }
        if let Some(b) = payload {
            self.set(out, 3, self.byte_array(b)?)?;
        }
        if let Some(t) = extra {
            self.set(out, 4, self.string(t)?)?;
        }
        Ok(out)
    }
    unsafe fn optional_value(&self, state: jobjectArray, index: usize) -> R<Option<Value>> {
        self.frame(|| {
            let v = self.item(state, index)?;
            if v.is_null() {
                Ok(None)
            } else {
                self.value(v).map(Some)
            }
        })
    }
    unsafe fn save_value(&self, state: jobjectArray, index: usize, v: Option<&Value>) -> R<()> {
        self.frame(|| {
            let v = match v {
                Some(v) => self.tree(v)?,
                None => null_mut(),
            };
            self.set(state, index, v)
        })
    }
    unsafe fn request_action(&self, value: &Value, budget: i64, human: bool) -> R<jobjectArray> {
        // Retain the original order: cancellation/open checks precede XML encoding.
        let out = self.action(3, None, &[budget, human as i64], None, None)?;
        self.set(out, 1, self.tree(value)?)?;
        Ok(out)
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeLockdown_pairCreate(
    env: *mut JNIEnv,
    _: jclass,
    label: jstring,
    host: jstring,
    buid: jstring,
    budget: jlong,
    now: jlong,
) -> jobjectArray {
    export(env, |e| {
        if !(1..=300_000).contains(&budget) {
            e.throw(
                c"java/lang/IllegalArgumentException",
                c"totalTimeoutMillis must be between 1 and 300000",
            )?;
        }
        let out = e.array(8)?;
        e.set(
            out,
            0,
            e.long_array(&[0, now.wrapping_add(budget * 1_000_000), 0, 0, 0])?,
        )?;
        for (i, v) in [label, host, buid].into_iter().enumerate() {
            e.set(out, i + 1, v)?;
        }
        Ok(out)
    })
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeLockdown_pairWait(
    env: *mut JNIEnv,
    _: jclass,
    state: jobjectArray,
    cancelled: jboolean,
    now: jlong,
) -> jint {
    if cancelled != 0 {
        return 1;
    }
    let e = Env(env);
    let result = e.frame(|| {
        let words = e.longs(e.item(state, 0)?)?;
        Ok(if remaining(words[1], now).is_some() {
            0
        } else {
            2
        })
    });
    result.unwrap_or(0)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeLockdown_pairStep(
    env: *mut JNIEnv,
    _: jclass,
    state: jobjectArray,
    event: jint,
    value: jobjectArray,
    cancelled: jboolean,
    now: jlong,
) -> jobjectArray {
    export(env, |e| {
        use super::flow::{Effect, Pair};
        let words = e.item(state, 0)?;
        let words_value = e.longs(words)?;
        let mut p = Pair {
            words: words_value.try_into().unwrap(),
            config: [
                e.text(e.item(state, 1)?)?,
                e.text(e.item(state, 2)?)?,
                e.text(e.item(state, 3)?)?,
            ],
            key: None,
            wifi: None,
            record: e.optional_value(state, 6)?,
            response: e.optional_value(state, 7)?,
        };
        let key = e.item(state, 4)?;
        if !key.is_null() {
            p.key = Some(e.bytes(key)?)
        }
        let wifi = e.item(state, 5)?;
        if !wifi.is_null() {
            p.wifi = Some(e.text(wifi)?)
        }
        if event == 3 {
            p.response = Some(e.value(value)?)
        }
        if event == 4 {
            p.record = Some(e.frame(|| {
                Ok(dict(vec![
                    (
                        "DeviceCertificate",
                        Value::Data(e.bytes(e.item(value, 0)?)?),
                    ),
                    ("HostCertificate", Value::Data(e.bytes(e.item(value, 1)?)?)),
                    ("HostID", Value::Text(e.text(e.item(value, 2)?)?)),
                    ("RootCertificate", Value::Data(e.bytes(e.item(value, 3)?)?)),
                    ("SystemBUID", Value::Text(e.text(e.item(value, 4)?)?)),
                ]))
            })?)
        }
        let effect = p.step(event, cancelled != 0, now);
        e.put_longs(words, &p.words)?;
        e.frame(|| {
            let key = match &p.key {
                Some(k) => e.byte_array(k)?,
                None => null_mut(),
            };
            e.set(state, 4, key)
        })?;
        e.frame(|| {
            let wifi = match &p.wifi {
                Some(t) => e.string(t)?,
                None => null_mut(),
            };
            e.set(state, 5, wifi)
        })?;
        e.save_value(state, 6, p.record.as_ref())?;
        e.save_value(state, 7, p.response.as_ref())?;
        match effect {
            Effect::Check => e.action(1, None, &[], None, None),
            Effect::Connect(t) => e.action(2, None, &[t], None, None),
            Effect::Request(v, t, h) => e.request_action(&v, t, h),
            Effect::Generate(k, w) => e.action(4, None, &[], Some(&k), Some(&w)),
            Effect::LogSet => e.action(5, None, &[], None, None),
            Effect::LogPrepared => e.action(6, None, &[], None, None),
            Effect::LogAttempt(n) => e.action(7, None, &[n], None, None),
            Effect::Trust => e.action(8, None, &[], None, None),
            Effect::Sleep(n) => e.action(9, None, &[n], None, None),
            Effect::Done(b) => e.action(10, None, &[], b.as_deref(), None),
            Effect::Error(n, t) => e.action(11, Some(&t), &[n], None, None),
        }
    })
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeLockdown_serviceCreate(
    env: *mut JNIEnv,
    _: jclass,
    label: jstring,
    host: jstring,
    buid: jstring,
    service: jstring,
) -> jobjectArray {
    export(env, |e| {
        let a = e.array(5)?;
        e.set(a, 0, e.long_array(&[0])?)?;
        for (i, v) in [label, host, buid, service].into_iter().enumerate() {
            e.set(a, i + 1, v)?;
        }
        Ok(a)
    })
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeLockdown_serviceStep(
    env: *mut JNIEnv,
    _: jclass,
    state: jobjectArray,
    response: jobjectArray,
) -> jobjectArray {
    export(env, |e| {
        let a = e.item(state, 0)?;
        let mut words = e.longs(a)?;
        let stage = words[0];
        let effect = match stage {
            0 => e.action(2, None, &[62078, 5000], None, None),
            1 => e.request_action(
                &dict(vec![
                    ("Label", Value::Text(e.text(e.item(state, 1)?)?)),
                    ("Request", string("StartSession")),
                    ("HostID", Value::Text(e.text(e.item(state, 2)?)?)),
                    ("SystemBUID", Value::Text(e.text(e.item(state, 3)?)?)),
                ]),
                5000,
                false,
            ),
            2 => match super::flow::service_response(&e.value(response)?, true) {
                Ok(_) => e.action(4, None, &[5000], None, None),
                Err(t) => e.action(11, Some(&t), &[], None, None),
            },
            3 => e.request_action(
                &dict(vec![
                    ("Request", string("StartService")),
                    ("Service", Value::Text(e.text(e.item(state, 4)?)?)),
                ]),
                5000,
                false,
            ),
            4 => match super::flow::service_response(&e.value(response)?, false) {
                Ok((port, ssl)) => e.action(5, None, &[port, 5000, ssl as i64], None, None),
                Err(t) => e.action(11, Some(&t), &[], None, None),
            },
            5 => e.action(10, None, &[], None, None),
            _ => unreachable!("private service stage"),
        }?;
        words[0] += 1;
        e.put_longs(a, &words)?;
        Ok(effect)
    })
}
unsafe fn now(e: &Env, io: jobject) -> R<i64> {
    let m = e.method(io, c"now", c"()J")?;
    let n = ((**e.0).CallLongMethodA.unwrap())(e.0, io, m, std::ptr::null());
    e.ok()?;
    Ok(n)
}
unsafe fn fully(
    e: &Env,
    io: jobject,
    n: usize,
    deadline: i64,
    human: bool,
) -> Result<Vec<u8>, (i32, Text)> {
    let pending = |_: Fault| (0, Vec::new());
    let mut out = vec![0; n];
    let mut offset = 0;
    while offset < n {
        let step = e
            .frame(|| {
                let m = e.method(io, c"verify", c"()V")?;
                ((**e.0).CallVoidMethodA.unwrap())(e.0, io, m, std::ptr::null());
                e.ok()?;
                let left = remaining(deadline, now(e, io)?);
                if left.is_none() {
                    return Ok(None);
                }
                let wait = if human {
                    left.unwrap().min(100)
                } else {
                    left.unwrap()
                };
                let a = e.call(
                    io,
                    c"recv",
                    c"(IJ)[B",
                    &[
                        jvalue {
                            i: (n - offset) as i32,
                        },
                        jvalue { j: wait },
                    ],
                )?;
                if a.is_null() {
                    Ok(Some(None))
                } else {
                    Ok(Some(Some(e.bytes(a)?)))
                }
            })
            .map_err(pending)?;
        let bytes = match step {
            None => {
                return Err((
                    if human { 2 } else { 1 },
                    text("Timed out while reading Lockdown plist frame"),
                ))
            }
            Some(None) => {
                if human {
                    continue;
                } else {
                    return Err((1, text("Timed out while reading Lockdown plist frame")));
                }
            }
            Some(Some(b)) => b,
        };
        if bytes.is_empty() {
            return Err((
                3,
                text("USBMUX TCP stream ended inside a Lockdown plist frame"),
            ));
        }
        if bytes.len() > n - offset {
            let _ = e.throw(
                c"java/lang/ArrayIndexOutOfBoundsException",
                c"Lockdown stream returned more bytes than requested",
            );
            return Err((0, Vec::new()));
        }
        out[offset..offset + bytes.len()].copy_from_slice(&bytes);
        offset += bytes.len();
    }
    Ok(out)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_transport_NativeLockdown_read(
    env: *mut JNIEnv,
    _: jclass,
    max: jint,
    io: jobject,
    budget: jlong,
    human: jboolean,
) -> jobjectArray {
    export(env, |e| {
        let deadline = now(e, io)?.wrapping_add(budget * 1_000_000);
        let read = (|| {
            let header = fully(e, io, 4, deadline, human != 0)?;
            let n = u32::from_be_bytes(header.try_into().unwrap()) as usize;
            if n == 0 || n > max as usize {
                return Err((
                    3,
                    text(&format!(
                        "Invalid Lockdown plist message length {n} (maximum {max})"
                    )),
                ));
            }
            fully(e, io, n, deadline, human != 0)
        })();
        e.ok()?;
        let a = e.array(3)?;
        match read {
            Ok(b) => {
                e.set(a, 0, e.int_array(&[0])?)?;
                e.set(a, 1, e.byte_array(&b)?)?;
            }
            Err((0, _)) => return Err(Fault::Pending),
            Err((kind, t)) => {
                e.set(a, 0, e.int_array(&[kind])?)?;
                e.set(a, 2, e.string(&t)?)?;
            }
        }
        Ok(a)
    })
}
