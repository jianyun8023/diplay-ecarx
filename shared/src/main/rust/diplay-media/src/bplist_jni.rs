//! Java value/charset boundary for the Rust plist owner. Pending JVM exceptions propagate.
use crate::bplist::{self, Value};
use jni_sys::{jbyteArray, jclass, jint, jmethodID, jobject, jstring, jvalue, JNIEnv};
use std::{ffi::CStr, ptr::null_mut};

unsafe fn class(env: *mut JNIEnv, name: &CStr) -> Option<jclass> {
    let result = ((**env).FindClass.unwrap())(env, name.as_ptr());
    (!result.is_null()).then_some(result)
}

unsafe fn method(
    env: *mut JNIEnv,
    class: jclass,
    name: &CStr,
    signature: &CStr,
) -> Option<jmethodID> {
    let result = ((**env).GetMethodID.unwrap())(env, class, name.as_ptr(), signature.as_ptr());
    (!result.is_null()).then_some(result)
}

unsafe fn binding(
    env: *mut JNIEnv,
    name: &CStr,
    member: &CStr,
    signature: &CStr,
) -> Option<(jclass, jmethodID)> {
    let class = class(env, name)?;
    Some((class, method(env, class, member, signature)?))
}

unsafe fn text(env: *mut JNIEnv, units: &[u16]) -> Option<jstring> {
    let result = ((**env).NewString.unwrap())(env, units.as_ptr(), units.len() as jint);
    (!result.is_null()).then_some(result)
}

unsafe fn read_text(env: *mut JNIEnv, value: jstring) -> Option<Vec<u16>> {
    let f = &**env;
    let data = (f.GetStringChars.unwrap())(env, value, null_mut());
    if data.is_null() {
        return None;
    }
    let units = std::slice::from_raw_parts(data, (f.GetStringLength.unwrap())(env, value) as usize)
        .to_vec();
    (f.ReleaseStringChars.unwrap())(env, value, data);
    Some(units)
}

unsafe fn bytes(env: *mut JNIEnv, bytes: &[u8]) -> Option<jbyteArray> {
    let f = &**env;
    let result = (f.NewByteArray.unwrap())(env, bytes.len() as jint);
    if result.is_null() {
        return None;
    }
    (f.SetByteArrayRegion.unwrap())(
        env,
        result,
        0,
        bytes.len() as jint,
        bytes.as_ptr() as *const i8,
    );
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        None
    } else {
        Some(result)
    }
}

unsafe fn read_bytes(env: *mut JNIEnv, array: jbyteArray) -> Option<Vec<u8>> {
    let f = &**env;
    let mut bytes = vec![0; (f.GetArrayLength.unwrap())(env, array) as usize];
    (f.GetByteArrayRegion.unwrap())(
        env,
        array,
        0,
        bytes.len() as jint,
        bytes.as_mut_ptr() as *mut i8,
    );
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        None
    } else {
        Some(bytes)
    }
}

unsafe fn throw_text(env: *mut JNIEnv, message: &[u16]) {
    if ((**env).ExceptionCheck.unwrap())(env) != 0 {
        return;
    }
    let result = (|| -> Option<()> {
        let (class, init) = binding(
            env,
            c"java/lang/IllegalArgumentException",
            c"<init>",
            c"(Ljava/lang/String;)V",
        )?;
        let message = text(env, message)?;
        let error =
            ((**env).NewObjectA.unwrap())(env, class, init, [jvalue { l: message }].as_ptr());
        if error.is_null() {
            return None;
        }
        ((**env).Throw.unwrap())(env, error);
        Some(())
    })();
    let _ = result;
}

unsafe fn throw(env: *mut JNIEnv, message: &str) {
    throw_text(env, &message.encode_utf16().collect::<Vec<_>>());
}

fn malformed(units: &[u16]) -> bool {
    char::decode_utf16(units.iter().copied()).any(|unit| unit.is_err())
}

struct Input {
    boolean: (jclass, jmethodID),
    big: (jclass, jmethodID),
    bit_length: jmethodID,
    integers: [jclass; 4],
    reals: [jclass; 2],
    long_value: jmethodID,
    double_value: jmethodID,
    string: (jclass, jmethodID),
    string_value_of: jmethodID,
    charset: jstring,
    data: jclass,
    list: jclass,
    iterator: jmethodID,
    has_next: jmethodID,
    next: jmethodID,
    map: (jclass, jmethodID),
    to_array: jmethodID,
    key: jmethodID,
    value: jmethodID,
}

impl Input {
    unsafe fn new(env: *mut JNIEnv) -> Option<Self> {
        let boolean = binding(env, c"java/lang/Boolean", c"booleanValue", c"()Z")?;
        let big = binding(env, c"java/math/BigInteger", c"signum", c"()I")?;
        let bit_length = method(env, big.0, c"bitLength", c"()I")?;
        let number = class(env, c"java/lang/Number")?;
        let long_value = method(env, number, c"longValue", c"()J")?;
        let double_value = method(env, number, c"doubleValue", c"()D")?;
        let string = binding(
            env,
            c"java/lang/String",
            c"getBytes",
            c"(Ljava/lang/String;)[B",
        )?;
        let string_value_of = ((**env).GetStaticMethodID.unwrap())(
            env,
            string.0,
            c"valueOf".as_ptr(),
            c"(Ljava/lang/Object;)Ljava/lang/String;".as_ptr(),
        );
        if string_value_of.is_null() {
            return None;
        }
        let list = class(env, c"java/util/List")?;
        let iterator = method(env, list, c"iterator", c"()Ljava/util/Iterator;")?;
        let iterator_class = class(env, c"java/util/Iterator")?;
        let map = binding(env, c"java/util/Map", c"entrySet", c"()Ljava/util/Set;")?;
        let set = class(env, c"java/util/Set")?;
        let entry = class(env, c"java/util/Map$Entry")?;
        Some(Self {
            boolean,
            big,
            bit_length,
            integers: [
                class(env, c"java/lang/Byte")?,
                class(env, c"java/lang/Short")?,
                class(env, c"java/lang/Integer")?,
                class(env, c"java/lang/Long")?,
            ],
            reals: [
                class(env, c"java/lang/Float")?,
                class(env, c"java/lang/Double")?,
            ],
            long_value,
            double_value,
            string,
            string_value_of,
            charset: text(env, &"UTF-16BE".encode_utf16().collect::<Vec<_>>())?,
            data: class(env, c"[B")?,
            list,
            iterator,
            has_next: method(env, iterator_class, c"hasNext", c"()Z")?,
            next: method(env, iterator_class, c"next", c"()Ljava/lang/Object;")?,
            map,
            to_array: method(env, set, c"toArray", c"()[Ljava/lang/Object;")?,
            key: method(env, entry, c"getKey", c"()Ljava/lang/Object;")?,
            value: method(env, entry, c"getValue", c"()Ljava/lang/Object;")?,
        })
    }

    unsafe fn string_units(&self, env: *mut JNIEnv, string: jstring) -> Option<Vec<u16>> {
        let units = read_text(env, string)?;
        if !malformed(&units) {
            return Some(units);
        }
        // Charset replacement is a platform boundary, not Rust's Unicode scalar conversion.
        let f = &**env;
        let encoded = (f.CallObjectMethodA.unwrap())(
            env,
            string,
            self.string.1,
            [jvalue { l: self.charset }].as_ptr(),
        );
        if encoded.is_null() {
            return None;
        }
        let encoded_bytes = read_bytes(env, encoded)?;
        (f.DeleteLocalRef.unwrap())(env, encoded);
        Some(
            encoded_bytes
                .chunks_exact(2)
                .map(|unit| u16::from_be_bytes([unit[0], unit[1]]))
                .collect(),
        )
    }

    unsafe fn read(&self, env: *mut JNIEnv, object: jobject, depth: usize) -> Option<Value> {
        let f = &**env;
        if depth > bplist::MAX_ENCODE_DEPTH {
            throw(env, bplist::ENCODE_DEPTH_ERROR);
            return None;
        }
        if (f.PushLocalFrame.unwrap())(env, 16) < 0 {
            return None;
        }
        let result = (|| -> Option<Value> {
            if !object.is_null() {
                if (f.IsInstanceOf.unwrap())(env, object, self.boolean.0) != 0 {
                    return Some(Value::Bool(
                        (f.CallBooleanMethodA.unwrap())(
                            env,
                            object,
                            self.boolean.1,
                            std::ptr::null(),
                        ) != 0,
                    ));
                }
                if (f.IsInstanceOf.unwrap())(env, object, self.big.0) != 0 {
                    let sign =
                        (f.CallIntMethodA.unwrap())(env, object, self.big.1, std::ptr::null());
                    if (f.ExceptionCheck.unwrap())(env) != 0 {
                        return None;
                    }
                    if sign < 0 {
                        throw(env, "bplist: negative integers are not supported");
                        return None;
                    }
                    let bits =
                        (f.CallIntMethodA.unwrap())(env, object, self.bit_length, std::ptr::null());
                    if (f.ExceptionCheck.unwrap())(env) != 0 {
                        return None;
                    }
                    if bits > 64 {
                        throw(env, "bplist: integer exceeds 64 bits");
                        return None;
                    }
                    let value = (f.CallLongMethodA.unwrap())(
                        env,
                        object,
                        self.long_value,
                        std::ptr::null(),
                    );
                    return Some(Value::Int(value as u64));
                }
                if self
                    .integers
                    .iter()
                    .any(|class| (f.IsInstanceOf.unwrap())(env, object, *class) != 0)
                {
                    let value = (f.CallLongMethodA.unwrap())(
                        env,
                        object,
                        self.long_value,
                        std::ptr::null(),
                    );
                    return Some(if value < 0 {
                        Value::Double(value as f64)
                    } else {
                        Value::Int(value as u64)
                    });
                }
                if self
                    .reals
                    .iter()
                    .any(|class| (f.IsInstanceOf.unwrap())(env, object, *class) != 0)
                {
                    return Some(Value::Double((f.CallDoubleMethodA.unwrap())(
                        env,
                        object,
                        self.double_value,
                        std::ptr::null(),
                    )));
                }
                if (f.IsInstanceOf.unwrap())(env, object, self.string.0) != 0 {
                    return Some(Value::String(self.string_units(env, object)?));
                }
                if (f.IsInstanceOf.unwrap())(env, object, self.data) != 0 {
                    return Some(Value::Data(read_bytes(env, object)?));
                }
                if (f.IsInstanceOf.unwrap())(env, object, self.list) != 0 {
                    let iterator = (f.CallObjectMethodA.unwrap())(
                        env,
                        object,
                        self.iterator,
                        std::ptr::null(),
                    );
                    if iterator.is_null() {
                        return None;
                    }
                    let mut values = Vec::new();
                    loop {
                        let has_next = (f.CallBooleanMethodA.unwrap())(
                            env,
                            iterator,
                            self.has_next,
                            std::ptr::null(),
                        );
                        if (f.ExceptionCheck.unwrap())(env) != 0 {
                            return None;
                        }
                        if has_next == 0 {
                            break;
                        }
                        let child = (f.CallObjectMethodA.unwrap())(
                            env,
                            iterator,
                            self.next,
                            std::ptr::null(),
                        );
                        if (f.ExceptionCheck.unwrap())(env) != 0 {
                            return None;
                        }
                        values.push(self.read(env, child, depth + 1)?);
                        (f.DeleteLocalRef.unwrap())(env, child);
                    }
                    return Some(Value::Array(values));
                }
                if (f.IsInstanceOf.unwrap())(env, object, self.map.0) != 0 {
                    let set =
                        (f.CallObjectMethodA.unwrap())(env, object, self.map.1, std::ptr::null());
                    if set.is_null() {
                        return None;
                    }
                    let entries =
                        (f.CallObjectMethodA.unwrap())(env, set, self.to_array, std::ptr::null());
                    if entries.is_null() {
                        return None;
                    }
                    let count = (f.GetArrayLength.unwrap())(env, entries);
                    let mut keys = Vec::with_capacity(count as usize);
                    // Kotlin snapshots entries, serializes every key, then visits every value.
                    for index in 0..count {
                        let entry = (f.GetObjectArrayElement.unwrap())(env, entries, index);
                        if entry.is_null() {
                            return None;
                        }
                        let key =
                            (f.CallObjectMethodA.unwrap())(env, entry, self.key, std::ptr::null());
                        if (f.ExceptionCheck.unwrap())(env) != 0 {
                            return None;
                        }
                        let string = (f.CallStaticObjectMethodA.unwrap())(
                            env,
                            self.string.0,
                            self.string_value_of,
                            [jvalue { l: key }].as_ptr(),
                        );
                        if string.is_null() {
                            return None;
                        }
                        if depth + 1 > bplist::MAX_ENCODE_DEPTH {
                            throw(env, bplist::ENCODE_DEPTH_ERROR);
                            return None;
                        }
                        keys.push(self.string_units(env, string)?);
                        (f.DeleteLocalRef.unwrap())(env, key);
                        (f.DeleteLocalRef.unwrap())(env, string);
                        (f.DeleteLocalRef.unwrap())(env, entry);
                    }
                    let mut values = Vec::with_capacity(count as usize);
                    for (index, key) in keys.into_iter().enumerate() {
                        let entry = (f.GetObjectArrayElement.unwrap())(env, entries, index as jint);
                        if entry.is_null() {
                            return None;
                        }
                        let value = (f.CallObjectMethodA.unwrap())(
                            env,
                            entry,
                            self.value,
                            std::ptr::null(),
                        );
                        if (f.ExceptionCheck.unwrap())(env) != 0 {
                            return None;
                        }
                        values.push((key, self.read(env, value, depth + 1)?));
                        (f.DeleteLocalRef.unwrap())(env, value);
                        (f.DeleteLocalRef.unwrap())(env, entry);
                    }
                    return Some(Value::Dict(values));
                }
            }
            let string = (f.CallStaticObjectMethodA.unwrap())(
                env,
                self.string.0,
                self.string_value_of,
                [jvalue { l: object }].as_ptr(),
            );
            if (f.ExceptionCheck.unwrap())(env) != 0 {
                return None;
            }
            let mut message: Vec<u16> = "bplist: unsupported value ".encode_utf16().collect();
            message.extend(if string.is_null() {
                "null".encode_utf16().collect()
            } else {
                read_text(env, string)?
            });
            throw_text(env, &message);
            None
        })();
        (f.PopLocalFrame.unwrap())(env, null_mut());
        if (f.ExceptionCheck.unwrap())(env) != 0 {
            None
        } else {
            result
        }
    }
}

struct Output {
    boolean: (jclass, jmethodID),
    long: (jclass, jmethodID),
    float: (jclass, jmethodID),
    double: (jclass, jmethodID),
    date: (jclass, jmethodID),
    string: (jclass, jmethodID),
    charset: jstring,
    list: (jclass, jmethodID),
    add: jmethodID,
    map: (jclass, jmethodID),
    put: jmethodID,
}

impl Output {
    unsafe fn new(env: *mut JNIEnv) -> Option<Self> {
        let list = binding(env, c"java/util/ArrayList", c"<init>", c"()V")?;
        let map = binding(env, c"java/util/LinkedHashMap", c"<init>", c"()V")?;
        Some(Self {
            boolean: binding(env, c"java/lang/Boolean", c"<init>", c"(Z)V")?,
            long: binding(env, c"java/lang/Long", c"<init>", c"(J)V")?,
            float: binding(env, c"java/lang/Float", c"<init>", c"(F)V")?,
            double: binding(env, c"java/lang/Double", c"<init>", c"(D)V")?,
            date: binding(env, c"java/util/Date", c"<init>", c"(J)V")?,
            string: binding(
                env,
                c"java/lang/String",
                c"<init>",
                c"([BLjava/lang/String;)V",
            )?,
            charset: text(env, &"UTF-16BE".encode_utf16().collect::<Vec<_>>())?,
            add: method(env, list.0, c"add", c"(Ljava/lang/Object;)Z")?,
            list,
            put: method(
                env,
                map.0,
                c"put",
                c"(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
            )?,
            map,
        })
    }

    unsafe fn string(&self, env: *mut JNIEnv, units: &[u16]) -> Option<jstring> {
        if !malformed(units) {
            return text(env, units);
        }
        // Android and desktop JDKs can consume different malformed UTF-16 sequences.
        let data: Vec<u8> = units.iter().flat_map(|unit| unit.to_be_bytes()).collect();
        let data = bytes(env, &data)?;
        let result = ((**env).NewObjectA.unwrap())(
            env,
            self.string.0,
            self.string.1,
            [jvalue { l: data }, jvalue { l: self.charset }].as_ptr(),
        );
        ((**env).DeleteLocalRef.unwrap())(env, data);
        (!result.is_null()).then_some(result)
    }

    unsafe fn write(&self, env: *mut JNIEnv, value: &Value) -> Option<jobject> {
        let f = &**env;
        if (f.PushLocalFrame.unwrap())(env, 16) < 0 {
            return None;
        }
        let result = (|| -> Option<jobject> {
            let (binding, arg) = match value {
                Value::Null => return Some(null_mut()),
                Value::Bool(value) => (
                    self.boolean,
                    jvalue {
                        z: u8::from(*value),
                    },
                ),
                Value::Int(value) => (self.long, jvalue { j: *value as i64 }),
                Value::Float(value) => (self.float, jvalue { f: *value }),
                Value::Double(value) => (self.double, jvalue { d: *value }),
                Value::Date(value) => (self.date, jvalue { j: *value }),
                Value::String(value) => return self.string(env, value),
                Value::Data(value) => return bytes(env, value),
                Value::Array(values) => {
                    let list =
                        (f.NewObjectA.unwrap())(env, self.list.0, self.list.1, std::ptr::null());
                    if list.is_null() {
                        return None;
                    }
                    for value in values {
                        let child = self.write(env, value)?;
                        (f.CallBooleanMethodA.unwrap())(
                            env,
                            list,
                            self.add,
                            [jvalue { l: child }].as_ptr(),
                        );
                        (f.DeleteLocalRef.unwrap())(env, child);
                        if (f.ExceptionCheck.unwrap())(env) != 0 {
                            return None;
                        }
                    }
                    return Some(list);
                }
                Value::Dict(values) => {
                    let map =
                        (f.NewObjectA.unwrap())(env, self.map.0, self.map.1, std::ptr::null());
                    if map.is_null() {
                        return None;
                    }
                    for (key, value) in values {
                        let key = self.string(env, key)?;
                        let child = self.write(env, value)?;
                        let old = (f.CallObjectMethodA.unwrap())(
                            env,
                            map,
                            self.put,
                            [jvalue { l: key }, jvalue { l: child }].as_ptr(),
                        );
                        (f.DeleteLocalRef.unwrap())(env, key);
                        (f.DeleteLocalRef.unwrap())(env, child);
                        (f.DeleteLocalRef.unwrap())(env, old);
                        if (f.ExceptionCheck.unwrap())(env) != 0 {
                            return None;
                        }
                    }
                    return Some(map);
                }
            };
            let object = (f.NewObjectA.unwrap())(env, binding.0, binding.1, [arg].as_ptr());
            (!object.is_null()).then_some(object)
        })();
        let object = (f.PopLocalFrame.unwrap())(env, result.unwrap_or(null_mut()));
        result.map(|_| object)
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeBplist_encode(
    env: *mut JNIEnv,
    _class: jclass,
    root: jobject,
) -> jbyteArray {
    let f = &**env;
    if (f.PushLocalFrame.unwrap())(env, 64) < 0 {
        return null_mut();
    }
    let result = (|| -> Option<jbyteArray> {
        let value = Input::new(env)?.read(env, root, 0)?;
        match bplist::encode(&value) {
            Ok(encoded) => bytes(env, &encoded),
            Err(error) => {
                throw(env, &error);
                None
            }
        }
    })();
    (f.PopLocalFrame.unwrap())(env, result.unwrap_or(null_mut()))
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeBplist_decode(
    env: *mut JNIEnv,
    _class: jclass,
    input: jbyteArray,
) -> jobject {
    let f = &**env;
    if (f.PushLocalFrame.unwrap())(env, 32) < 0 {
        return null_mut();
    }
    let result = (|| -> Option<jobject> {
        if input.is_null() {
            throw(env, "bplist: input is null");
            return None;
        }
        let encoded = read_bytes(env, input)?;
        let value = match bplist::decode(&encoded) {
            Ok(value) => value,
            Err(error) => {
                throw(env, &error);
                return None;
            }
        };
        Output::new(env)?.write(env, &value)
    })();
    (f.PopLocalFrame.unwrap())(env, result.unwrap_or(null_mut()))
}
