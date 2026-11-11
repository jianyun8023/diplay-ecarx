//! ChaCha20-Poly1305 open for the CarPlay media streams, through ring's BoringSSL assembly.
//! Called from `NativeChacha.open`; see that class for the contract.

use jni_sys::{jbyteArray, jclass, jint, JNIEnv, JNI_ABORT};
use ring::aead::{Aad, LessSafeKey, Nonce, Tag, UnboundKey, CHACHA20_POLY1305};
use std::ptr::null_mut;

const TAG_LEN: usize = 16;

struct Pinned {
    env: *mut JNIEnv,
    array: jbyteArray,
    ptr: *mut u8,
    mode: jint,
}

impl Pinned {
    unsafe fn new(env: *mut JNIEnv, array: jbyteArray, mode: jint) -> Option<Pinned> {
        let ptr = ((**env).GetPrimitiveArrayCritical?)(env, array, null_mut()) as *mut u8;
        if ptr.is_null() { None } else { Some(Pinned { env, array, ptr, mode }) }
    }
}

impl Drop for Pinned {
    fn drop(&mut self) {
        unsafe {
            if let Some(release) = (**self.env).ReleasePrimitiveArrayCritical {
                release(self.env, self.array, self.ptr as *mut _, self.mode);
            }
        }
    }
}

/// Returns the plaintext length written at `out[out_off..]`, -1 when the tag does not verify,
/// -2 on bad arguments. Kotlin checks every bound before calling.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeChacha_open(
    env: *mut JNIEnv, _class: jclass, key: jbyteArray, nonce: jbyteArray,
    aad: jbyteArray, aad_off: jint, aad_len: jint,
    input: jbyteArray, in_off: jint, len: jint,
    out: jbyteArray, out_off: jint,
) -> jint {
    if len < TAG_LEN as jint { return -2; }
    let plain_len = len as usize - TAG_LEN;
    // Copy key, nonce and tag first, so at most three arrays are pinned at once.
    let (mut key_bytes, mut nonce_bytes, mut tag_bytes) = ([0u8; 32], [0u8; 12], [0u8; TAG_LEN]);
    {
        let Some(k) = Pinned::new(env, key, JNI_ABORT) else { return -2 };
        key_bytes.copy_from_slice(std::slice::from_raw_parts(k.ptr, 32));
        let Some(n) = Pinned::new(env, nonce, JNI_ABORT) else { return -2 };
        nonce_bytes.copy_from_slice(std::slice::from_raw_parts(n.ptr, 12));
    }
    let Ok(unbound) = UnboundKey::new(&CHACHA20_POLY1305, &key_bytes) else { return -2 };
    let sealing = LessSafeKey::new(unbound);

    let Some(src) = Pinned::new(env, input, JNI_ABORT) else { return -2 };
    let Some(dst) = Pinned::new(env, out, 0) else { return -2 };
    let source = std::slice::from_raw_parts(src.ptr.add(in_off as usize), len as usize);
    tag_bytes.copy_from_slice(&source[plain_len..]);
    let target = std::slice::from_raw_parts_mut(dst.ptr.add(out_off as usize), plain_len);
    target.copy_from_slice(&source[..plain_len]);
    drop(src);

    let opened = if aad_len > 0 {
        let Some(a) = Pinned::new(env, aad, JNI_ABORT) else { return -2 };
        let header = std::slice::from_raw_parts(a.ptr.add(aad_off as usize), aad_len as usize);
        sealing.open_in_place_separate_tag(
            Nonce::assume_unique_for_key(nonce_bytes), Aad::from(header), Tag::from(tag_bytes), target, 0..,
        ).is_ok()
    } else {
        sealing.open_in_place_separate_tag(
            Nonce::assume_unique_for_key(nonce_bytes), Aad::empty(), Tag::from(tag_bytes), target, 0..,
        ).is_ok()
    };
    if opened {
        plain_len as jint
    } else {
        target.fill(0);
        -1
    }
}
