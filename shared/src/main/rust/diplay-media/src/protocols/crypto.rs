//! ChaCha20-Poly1305 through ring (BoringSSL's assembly). The receivers open each frame in place
//! with a key built once per stream; `NativeMedia.chachaOpen` serves the Kotlin callers.

use crate::platform::jvm::Env;
use jni_sys::{jbyteArray, jclass, jint, JNIEnv};
use ring::hkdf;
use ring::aead::{Aad, LessSafeKey, Nonce, Tag, UnboundKey, CHACHA20_POLY1305};

pub const TAG_LEN: usize = 16;

pub struct StreamKey(LessSafeKey);

impl StreamKey {
    pub fn new(key: &[u8]) -> Option<StreamKey> {
        UnboundKey::new(&CHACHA20_POLY1305, key).ok().map(|k| StreamKey(LessSafeKey::new(k)))
    }

    pub fn seal(&self, nonce: [u8; 12], aad: &[u8], plaintext: &[u8]) -> Option<Vec<u8>> {
        let mut sealed = plaintext.to_vec();
        self.0.seal_in_place_append_tag(Nonce::assume_unique_for_key(nonce), Aad::from(aad), &mut sealed).ok()?;
        Some(sealed)
    }

    /// Opens `sealed` (ciphertext followed by its tag) in place; the plaintext length on success.
    /// On failure the plaintext bytes are zeroed.
    pub fn open(&self, nonce: [u8; 12], aad: &[u8], sealed: &mut [u8]) -> Option<usize> {
        if sealed.len() < TAG_LEN { return None; }
        let plain = sealed.len() - TAG_LEN;
        let mut tag = [0u8; TAG_LEN];
        tag.copy_from_slice(&sealed[plain..]);
        let (text, _) = sealed.split_at_mut(plain);
        let opened = self.0
            .open_in_place_separate_tag(Nonce::assume_unique_for_key(nonce), Aad::from(aad), Tag::from(tag), text, 0..)
            .is_ok();
        if opened { Some(plain) } else { text.fill(0); None }
    }
}

/// The screen streams' nonce: an 8-byte little-endian frame counter after four zero bytes.
pub fn counter_nonce(counter: u64) -> [u8; 12] {
    let mut nonce = [0u8; 12];
    nonce[4..].copy_from_slice(&counter.to_le_bytes());
    nonce
}

/// Returns the plaintext length written at `out[out_off..]`, -1 when the tag does not verify,
/// -2 on bad arguments. Kotlin checks every bound before calling.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_chachaOpen(
    env: *mut JNIEnv, _class: jclass, key: jbyteArray, nonce: jbyteArray,
    aad: jbyteArray, aad_off: jint, aad_len: jint,
    input: jbyteArray, in_off: jint, len: jint,
    out: jbyteArray, out_off: jint,
) -> jint {
    if len < TAG_LEN as jint { return -2; }
    let e = Env(env);
    let (mut key_bytes, mut nonce_bytes) = ([0u8; 32], [0u8; 12]);
    if !e.read_array(key, 0, &mut key_bytes) || !e.read_array(nonce, 0, &mut nonce_bytes) { return -2; }
    let Some(stream_key) = StreamKey::new(&key_bytes) else { return -2 };
    let mut header = vec![0u8; aad_len.max(0) as usize];
    if aad_len > 0 && !e.read_array(aad, aad_off as usize, &mut header) { return -2; }
    let mut sealed = vec![0u8; len as usize];
    if !e.read_array(input, in_off as usize, &mut sealed) { return -2; }
    match stream_key.open(nonce_bytes, &header, &mut sealed) {
        Some(plain) => if e.write_array(out, out_off as usize, &sealed[..plain]) { plain as jint } else { -2 },
        None => -1,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ring::aead::{LessSafeKey, UnboundKey};

    #[test]
    fn opens_what_ring_sealed_and_rejects_a_flipped_bit() {
        let key = [7u8; 32];
        let sealer = LessSafeKey::new(UnboundKey::new(&CHACHA20_POLY1305, &key).unwrap());
        let mut data = b"carplay frame".to_vec();
        sealer.seal_in_place_append_tag(Nonce::assume_unique_for_key(counter_nonce(3)), Aad::from(b"hdr"), &mut data).unwrap();
        let opener = StreamKey::new(&key).unwrap();
        let mut good = data.clone();
        assert_eq!(opener.open(counter_nonce(3), b"hdr", &mut good), Some(13));
        assert_eq!(&good[..13], b"carplay frame");
        data[0] ^= 1;
        assert_eq!(opener.open(counter_nonce(3), b"hdr", &mut data), None);
    }
}

pub(super) fn controller_public_key(raw: &[u8]) -> Option<&[u8; 32]> {
    let key: &[u8; 32] = raw.get(..32)?.try_into().ok()?;
    let mut y = *key;
    y[31] &= 0x7f;
    let mut minus_one = [0xff; 32];
    minus_one[0] = 0xec;
    minus_one[31] = 0x7f;
    // BC 1.79 checkPointFullVar: canonical y, excluding the eight low-order points.
    // ring's verifier alone accepts e.g. identity A with the universal signature R=B, S=1.
    const ORDER8_Y: [[u8; 32]; 2] = [
        [
            0xc7, 0x17, 0x6a, 0x70, 0x3d, 0x4d, 0xd8, 0x4f, 0xba, 0x3c, 0x0b, 0x76, 0x0d, 0x10,
            0x67, 0x0f, 0x2a, 0x20, 0x53, 0xfa, 0x2c, 0x39, 0xcc, 0xc6, 0x4e, 0xc7, 0xfd, 0x77,
            0x92, 0xac, 0x03, 0x7a,
        ],
        [
            0x26, 0xe8, 0x95, 0x8f, 0xc2, 0xb2, 0x27, 0xb0, 0x45, 0xc3, 0xf4, 0x89, 0xf2, 0xef,
            0x98, 0xf0, 0xd5, 0xdf, 0xac, 0x05, 0xd3, 0xc6, 0x33, 0x39, 0xb1, 0x38, 0x02, 0x88,
            0x6d, 0x53, 0xfc, 0x05,
        ],
    ];
    if (y[1..].iter().all(|&b| b == 0) && y[0] <= 1)
        || y.iter().rev().cmp(minus_one.iter().rev()) != std::cmp::Ordering::Less
        || ORDER8_Y.contains(&y)
    {
        return None;
    }
    Some(key)
}

pub(super) fn hkdf32(input: &[u8], salt: &[u8], info: &[u8]) -> [u8; 32] {
    struct Length;
    impl hkdf::KeyType for Length {
        fn len(&self) -> usize {
            32
        }
    }
    let prk = hkdf::Salt::new(hkdf::HKDF_SHA512, salt).extract(input);
    let mut output = [0; 32];
    prk.expand(&[info], Length)
        .unwrap()
        .fill(&mut output)
        .unwrap();
    output
}
