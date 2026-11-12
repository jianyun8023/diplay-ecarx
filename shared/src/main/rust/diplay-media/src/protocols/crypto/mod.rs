//! ChaCha20-Poly1305 through ring (BoringSSL's assembly). The receivers open each frame in place
//! with a key built once per stream. The same owner supplies the host primitive and MFi DER bridges.

mod jni;
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

#[derive(Debug, PartialEq)]
enum EcdsaError { Signature, Integer }

// P-256 signature syntax only, not a certificate/ASN.1 object model. JCA signing stays on Android.
fn ecdsa_der_to_raw(der: &[u8]) -> Result<[u8; 64], EcdsaError> {
    fn length(der: &[u8], at: &mut usize) -> Option<Option<usize>> {
        let first = *der.get(*at)?;
        *at += 1;
        if first == 0x80 { return Some(None); }
        if first < 0x80 { return Some(Some(first as usize)); }
        let count = (first & 0x7f) as usize;
        if count == 0 || count > 4 { return None; }
        let mut len = 0usize;
        for _ in 0..count {
            len = len.checked_mul(256)?.checked_add(*der.get(*at)? as usize)?;
            *at += 1;
        }
        Some(Some(len))
    }
    if der.first() != Some(&0x30) { return Err(EcdsaError::Signature); }
    let mut at = 1;
    let size = length(der, &mut at).ok_or(EcdsaError::Signature)?;
    let end = match size {
        Some(size) => at.checked_add(size).filter(|&end| end == der.len()).ok_or(EcdsaError::Signature)?,
        None => der.len().checked_sub(2).filter(|&end| der.get(end..) == Some(&[0, 0][..])).ok_or(EcdsaError::Signature)?,
    };
    let mut values: [&[u8]; 2] = [&[], &[]];
    for value in &mut values {
        if der.get(at) != Some(&2) { return Err(EcdsaError::Signature); }
        at += 1;
        let len = length(der, &mut at).flatten().ok_or(EcdsaError::Signature)?;
        let next = at.checked_add(len).filter(|&next| next <= end).ok_or(EcdsaError::Signature)?;
        *value = &der[at..next];
        if value.is_empty() { return Err(EcdsaError::Signature); }
        // Default BC ASN1Integer validation rejects redundant sign octets.
        if value.len() > 1 && ((value[0] == 0 && value[1] & 0x80 == 0)
            || (value[0] == 0xff && value[1] & 0x80 != 0)) { return Err(EcdsaError::Signature); }
        at = next;
    }
    if at != end { return Err(EcdsaError::Signature); }
    let mut raw = [0; 64];
    for (component, value) in values.into_iter().enumerate() {
        if value[0] & 0x80 != 0 { return Err(EcdsaError::Integer); }
        let positive = if value[0] == 0 { &value[1..] } else { value };
        if positive.is_empty() || positive.len() > 32 { return Err(EcdsaError::Integer); }
        raw[component * 32 + 32 - positive.len()..(component + 1) * 32].copy_from_slice(positive);
    }
    Ok(raw)
}

fn ecdsa_raw_to_der(raw: &[u8; 64]) -> Vec<u8> {
    let mut content = Vec::with_capacity(70);
    for value in raw.chunks_exact(32) {
        let first = value.iter().position(|&byte| byte != 0).unwrap_or(31);
        let value = &value[first..];
        let sign = value[0] & 0x80 != 0;
        content.extend_from_slice(&[2, (value.len() + usize::from(sign)) as u8]);
        if sign { content.push(0); }
        content.extend_from_slice(value);
    }
    let mut der = vec![0x30, content.len() as u8];
    der.extend_from_slice(&content);
    der
}
