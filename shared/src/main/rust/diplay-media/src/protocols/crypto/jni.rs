//! Array-only JNI bridge. Java exceptions remain pending; no logging helper clears them.
use super::{
    controller_public_key, ecdsa_der_to_raw, ecdsa_raw_to_der, random32, x25519_pair,
    x25519_shared, EcdsaError, StreamKey, TAG_LEN,
};
use jni_sys::{jarray, jboolean, jbyteArray, jclass, jint, jobjectArray, JNIEnv};
use ring::{
    digest, hkdf,
    signature::{self, Ed25519KeyPair, KeyPair},
};
use std::{ffi::CStr, ptr::null_mut};

unsafe fn throw(env: *mut JNIEnv, class: &CStr, message: &CStr) {
    let f = &**env;
    let class = (f.FindClass.unwrap())(env, class.as_ptr());
    if (f.ExceptionCheck.unwrap())(env) == 0 && !class.is_null() {
        (f.ThrowNew.unwrap())(env, class, message.as_ptr());
    }
}
unsafe fn length(env: *mut JNIEnv, array: jarray) -> Option<usize> {
    if array.is_null() {
        throw(env, c"java/lang/NullPointerException", c"null crypto array");
        return None;
    }
    let f = &**env;
    let len = (f.GetArrayLength.unwrap())(env, array);
    if (f.ExceptionCheck.unwrap())(env) != 0 {
        None
    } else {
        Some(len as usize)
    }
}
unsafe fn region(env: *mut JNIEnv, array: jbyteArray, offset: jint, out: &mut [u8]) -> bool {
    if array.is_null() {
        throw(env, c"java/lang/NullPointerException", c"null crypto array");
        return false;
    }
    let f = &**env;
    (f.GetByteArrayRegion.unwrap())(
        env,
        array,
        offset,
        out.len() as jint,
        out.as_mut_ptr() as *mut i8,
    );
    (f.ExceptionCheck.unwrap())(env) == 0
}
unsafe fn read(env: *mut JNIEnv, array: jbyteArray) -> Option<Vec<u8>> {
    let mut out = vec![0; length(env, array)?];
    region(env, array, 0, &mut out).then_some(out)
}
unsafe fn prefix(env: *mut JNIEnv, array: jbyteArray) -> Option<[u8; 32]> {
    if length(env, array)? < 32 {
        throw(
            env,
            c"java/lang/ArrayIndexOutOfBoundsException",
            c"Expected at least 32 key bytes",
        );
        return None;
    }
    let mut out = [0; 32];
    region(env, array, 0, &mut out).then_some(out)
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
unsafe fn pair(env: *mut JNIEnv, seed: &[u8; 32], public: &[u8]) -> jobjectArray {
    let f = &**env;
    let class = (f.FindClass.unwrap())(env, c"[B".as_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 || class.is_null() {
        return null_mut();
    }
    let array = (f.NewObjectArray.unwrap())(env, 2, class, null_mut());
    if (f.ExceptionCheck.unwrap())(env) != 0 || array.is_null() {
        return null_mut();
    }
    for (index, value) in [seed.as_slice(), public].iter().enumerate() {
        let value = bytes(env, value);
        if value.is_null() {
            return null_mut();
        }
        (f.SetObjectArrayElement.unwrap())(env, array, index as jint, value);
        if (f.ExceptionCheck.unwrap())(env) != 0 {
            return null_mut();
        }
        (f.DeleteLocalRef.unwrap())(env, value);
    }
    array
}
unsafe fn random(env: *mut JNIEnv) -> Option<[u8; 32]> {
    let Some(seed) = random32() else {
        throw(
            env,
            c"java/lang/IllegalStateException",
            c"OS random source failed",
        );
        return None;
    };
    Some(seed)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeCrypto_x25519Generate(
    env: *mut JNIEnv,
    _class: jclass,
) -> jobjectArray {
    let Some(seed) = random(env) else {
        return null_mut();
    };
    // BC's generated encoded private key is clamped already; imported keys clamp at multiplication.
    let(private,public)=x25519_pair(seed);
    pair(env, &private, &public)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeCrypto_x25519Shared(
    env: *mut JNIEnv,
    _class: jclass,
    private: jbyteArray,
    peer: jbyteArray,
) -> jbyteArray {
    let Some(private) = prefix(env, private) else {
        return null_mut();
    };
    let Some(peer) = prefix(env, peer) else {
        return null_mut();
    };
    let Some(shared) = x25519_shared(private,peer) else {
        throw(
            env,
            c"java/lang/IllegalStateException",
            c"X25519 agreement failed",
        );
        return null_mut();
    };
    bytes(env, &shared)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeCrypto_ed25519Generate(
    env: *mut JNIEnv,
    _class: jclass,
) -> jobjectArray {
    let Some(seed) = random(env) else {
        return null_mut();
    };
    let key = Ed25519KeyPair::from_seed_unchecked(&seed).unwrap();
    pair(env, &seed, key.public_key().as_ref())
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeCrypto_ed25519Sign(
    env: *mut JNIEnv,
    _class: jclass,
    seed: jbyteArray,
    data: jbyteArray,
) -> jbyteArray {
    let Some(seed) = prefix(env, seed) else {
        return null_mut();
    };
    let Some(data) = read(env, data) else {
        return null_mut();
    };
    bytes(
        env,
        Ed25519KeyPair::from_seed_unchecked(&seed)
            .unwrap()
            .sign(&data)
            .as_ref(),
    )
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeCrypto_ed25519Verify(
    env: *mut JNIEnv,
    _class: jclass,
    public: jbyteArray,
    data: jbyteArray,
    sig: jbyteArray,
) -> jboolean {
    let Some(len) = length(env, public) else {
        return 0;
    };
    if len < 32 {
        return 0;
    }
    let mut public_bytes = [0; 32];
    if !region(env, public, 0, &mut public_bytes) {
        return 0;
    }
    let Some(public) = controller_public_key(&public_bytes) else {
        return 0;
    };
    let Some(data) = read(env, data) else {
        return 0;
    };
    let Some(sig) = read(env, sig) else { return 0 };
    signature::UnparsedPublicKey::new(&signature::ED25519, public)
        .verify(&data, &sig)
        .is_ok() as jboolean
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeCrypto_hkdfSha512(
    env: *mut JNIEnv,
    _class: jclass,
    input: jbyteArray,
    salt: jbyteArray,
    info: jbyteArray,
    len: jint,
) -> jbyteArray {
    if len < 0 {
        throw(
            env,
            c"java/lang/NegativeArraySizeException",
            c"Negative HKDF length",
        );
        return null_mut();
    }
    if len > 255 * 64 {
        throw(
            env,
            c"java/lang/IllegalArgumentException",
            c"HKDF may only be used for 255 * HashLen bytes of output",
        );
        return null_mut();
    }
    let Some(input) = read(env, input) else {
        return null_mut();
    };
    let Some(salt) = read(env, salt) else {
        return null_mut();
    };
    let Some(info) = read(env, info) else {
        return null_mut();
    };
    struct Size(usize);
    impl hkdf::KeyType for Size {
        fn len(&self) -> usize {
            self.0
        }
    }
    let key = hkdf::Salt::new(hkdf::HKDF_SHA512, &salt).extract(&input);
    let infos = [info.as_slice()];
    let derived = key.expand(&infos, Size(len as usize)).unwrap();
    let mut out = vec![0; len as usize];
    derived.fill(&mut out).unwrap();
    bytes(env, &out)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeCrypto_sha512(
    env: *mut JNIEnv,
    _class: jclass,
    parts: jobjectArray,
) -> jbyteArray {
    let f = &**env;
    let Some(count) = length(env, parts) else {
        return null_mut();
    };
    let mut hash = digest::Context::new(&digest::SHA512);
    for index in 0..count {
        let part = (f.GetObjectArrayElement.unwrap())(env, parts, index as jint);
        if (f.ExceptionCheck.unwrap())(env) != 0 {
            return null_mut();
        }
        let value = read(env, part);
        (f.DeleteLocalRef.unwrap())(env, part);
        let Some(value) = value else {
            return null_mut();
        };
        hash.update(&value);
    }
    bytes(env, hash.finish().as_ref())
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeCrypto_chachaSeal(
    env: *mut JNIEnv,
    _class: jclass,
    key: jbyteArray,
    nonce: jbyteArray,
    plain: jbyteArray,
    aad: jbyteArray,
) -> jbyteArray {
    let Some(key) = read(env, key) else {
        return null_mut();
    };
    let Some(nonce) = read(env, nonce) else {
        return null_mut();
    };
    let Some(key) = StreamKey::new(&key) else {
        throw(
            env,
            c"java/lang/IllegalArgumentException",
            c"ChaCha20 requires a 256 bit key",
        );
        return null_mut();
    };
    let Ok(nonce) = nonce.as_slice().try_into() else {
        throw(
            env,
            c"java/lang/IllegalArgumentException",
            c"ChaCha20Poly1305 nonce must be 96 bits",
        );
        return null_mut();
    };
    let Some(plain) = read(env, plain) else {
        return null_mut();
    };
    let Some(aad) = read(env, aad) else {
        return null_mut();
    };
    bytes(env, &key.seal(nonce, &aad, &plain).unwrap())
}

/// Existing hot-path ABI: Kotlin validates ranges, key/nonce sizes and output capacity.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_chachaOpen(
    env: *mut JNIEnv,
    _class: jclass,
    key: jbyteArray,
    nonce: jbyteArray,
    aad: jbyteArray,
    aad_off: jint,
    aad_len: jint,
    input: jbyteArray,
    in_off: jint,
    len: jint,
    out: jbyteArray,
    out_off: jint,
) -> jint {
    if len < TAG_LEN as jint || aad_len < 0 {
        return -2;
    }
    let (mut key_bytes, mut nonce_bytes) = ([0; 32], [0; 12]);
    if !region(env, key, 0, &mut key_bytes) || !region(env, nonce, 0, &mut nonce_bytes) {
        return -2;
    }
    let mut header = vec![0; aad_len as usize];
    if !region(env, aad, aad_off, &mut header) {
        return -2;
    }
    let mut sealed = vec![0; len as usize];
    if !region(env, input, in_off, &mut sealed) {
        return -2;
    }
    match StreamKey::new(&key_bytes)
        .unwrap()
        .open(nonce_bytes, &header, &mut sealed)
    {
        Some(len) => {
            let f = &**env;
            if out.is_null() {
                throw(
                    env,
                    c"java/lang/NullPointerException",
                    c"null crypto output",
                );
                return -2;
            }
            (f.SetByteArrayRegion.unwrap())(
                env,
                out,
                out_off,
                len as jint,
                sealed.as_ptr() as *const i8,
            );
            if (f.ExceptionCheck.unwrap())(env) != 0 {
                -2
            } else {
                len as jint
            }
        }
        None => -1,
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeCrypto_ecdsaDerToRaw(
    env: *mut JNIEnv,
    _class: jclass,
    der: jbyteArray,
) -> jbyteArray {
    let Some(der) = read(env, der) else {
        return null_mut();
    };
    match ecdsa_der_to_raw(&der) {
        Ok(raw) => bytes(env, &raw),
        Err(kind) => {
            let message = match kind {
                EcdsaError::Signature => c"Invalid ECDSA signature",
                EcdsaError::Integer => c"Invalid ECDSA integer",
            };
            throw(env, c"java/lang/IllegalArgumentException", message);
            null_mut()
        }
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeCrypto_ecdsaRawToDer(
    env: *mut JNIEnv,
    _class: jclass,
    raw: jbyteArray,
) -> jbyteArray {
    let Some(raw) = read(env, raw) else {
        return null_mut();
    };
    let Ok(raw) = raw.as_slice().try_into() else {
        throw(
            env,
            c"java/lang/IllegalArgumentException",
            c"Expected 64-byte ECDSA signature",
        );
        return null_mut();
    };
    bytes(env, &ecdsa_raw_to_der(raw))
}
