//! Complete accessory MFiSAP /auth-setup protocol; only certificate/signature SDK effects cross JNI.
//! Compared with LIVI 6ac88bab7351a39b0cffac20c5dcf92ca848319b livi-cp/auth_setup.rs.
//! This ports our existing MFI/BAA behavior, not its Tokio signer runtime or implementation.
mod jni;
use super::crypto::{random32, x25519_pair, x25519_shared};
use aes::cipher::{KeyIvInit, StreamCipher};
use ring::digest::{self, SHA1_FOR_LEGACY_USE_ONLY, SHA256};

#[derive(Debug)]
enum Error {
    Random,
    Agreement,
}
struct Sap {
    public: [u8; 32],
    peer: [u8; 32],
    key: [u8; 16],
    iv: [u8; 16],
}
fn hash(algorithm: &'static digest::Algorithm, parts: &[&[u8]]) -> Vec<u8> {
    let mut context = digest::Context::new(algorithm);
    for p in parts {
        context.update(p)
    }
    context.finish().as_ref().to_vec()
}
fn aes_ctr(key: &[u8; 16], iv: &[u8; 16], mut value: Vec<u8>) -> Vec<u8> {
    ctr::Ctr128BE::<aes::Aes128>::new(key.into(), iv.into()).apply_keystream(&mut value);
    value
}
impl Sap {
    fn start(body: &[u8]) -> Result<Option<Self>, Error> {
        if body.len() != 33 || body[0] != 1 {
            return Ok(None);
        }
        Self::new(
            body[1..].try_into().unwrap(),
            random32().ok_or(Error::Random)?,
        )
        .map(Some)
    }
    fn new(peer: [u8; 32], seed: [u8; 32]) -> Result<Self, Error> {
        let (private, public) = x25519_pair(seed);
        let shared = x25519_shared(private, peer).ok_or(Error::Agreement)?;
        // Match the existing NativeCrypto agreement failure, rather than LIVI's invalid-peer null.
        let key = hash(&SHA1_FOR_LEGACY_USE_ONLY, &[b"AES-KEY", &shared])[..16]
            .try_into()
            .unwrap();
        let iv = hash(&SHA1_FOR_LEGACY_USE_ONLY, &[b"AES-IV", &shared])[..16]
            .try_into()
            .unwrap();
        Ok(Self {
            public,
            peer,
            key,
            iv,
        })
    }
    fn challenge(&self, major: i32) -> Vec<u8> {
        hash(
            if major == 2 {
                &SHA1_FOR_LEGACY_USE_ONLY
            } else {
                &SHA256
            },
            &[&self.public, &self.peer],
        )
    }
    fn transcript(&self) -> Vec<u8> {
        [self.public.as_slice(), self.peer.as_slice()].concat()
    }
    fn encrypt(&self, signature: Vec<u8>) -> Vec<u8> {
        aes_ctr(&self.key, &self.iv, signature)
    }
    fn response(
        &self,
        certificate: &[u8],
        signature: &[u8],
        intermediate: Option<&[u8]>,
    ) -> Vec<u8> {
        let mut out = self.public.to_vec();
        for value in [certificate, signature] {
            out.extend_from_slice(&(value.len() as u32).to_be_bytes());
            out.extend_from_slice(value);
        }
        if let Some(value) = intermediate {
            let value = baic(value);
            out.extend_from_slice(&(value.len() as u32).to_be_bytes());
            out.extend(value);
        }
        out
    }
}
fn baic(intermediate: &[u8]) -> Vec<u8> {
    let mut out = b"\xe1\x44baIC".to_vec();
    let n = intermediate.len();
    if n <= 32 {
        out.push((0x70 + n) as u8)
    } else if n <= 255 {
        out.extend_from_slice(&[0x91, n as u8])
    } else if n <= 65535 {
        out.push(0x92);
        out.extend_from_slice(&(n as u16).to_le_bytes())
    } else {
        out.push(0x93);
        out.extend_from_slice(&(n as u32).to_le_bytes())
    }
    out.extend_from_slice(intermediate);
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    fn hex(s: &str) -> Vec<u8> {
        s.as_bytes()
            .chunks_exact(2)
            .map(|p| u8::from_str_radix(std::str::from_utf8(p).unwrap(), 16).unwrap())
            .collect()
    }
    #[test]
    fn aes_ctr128be_matches_nist_sp800_38a_f5_1() {
        // NIST SP 800-38A F.5.1, first two blocks; public non-secret primitive vector.
        let key = hex("2b7e151628aed2a6abf7158809cf4f3c").try_into().unwrap();
        let iv = hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff").try_into().unwrap();
        let plain = hex("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51");
        assert_eq!(
            aes_ctr(&key, &iv, plain),
            hex("874d6191b620e3261bef6864990db6ce9806f66b7970fdff8617187bb9fffdff")
        );
    }
    #[test]
    fn baic_boundaries_preserve_short_and_little_endian_length_markers() {
        for (n, prefix) in [
            (32, vec![0x90]),
            (33, vec![0x91, 33]),
            (255, vec![0x91, 255]),
            (256, vec![0x92, 0, 1]),
            (65535, vec![0x92, 255, 255]),
            (65536, vec![0x93, 0, 0, 1, 0]),
        ] {
            let value = baic(&vec![9; n]);
            assert_eq!(&value[..6], b"\xe1\x44baIC");
            assert_eq!(&value[6..6 + prefix.len()], prefix);
            assert_eq!(value.len(), 6 + prefix.len() + n);
        }
    }
    #[test]
    fn malformed_requests_skip_randomness_and_invalid_peer_keeps_agreement_failure() {
        assert!(Sap::start(&[]).unwrap().is_none());
        assert!(Sap::start(&[0; 33]).unwrap().is_none());
        assert!(matches!(Sap::new([0; 32], [1; 32]), Err(Error::Agreement)));
    }
}
