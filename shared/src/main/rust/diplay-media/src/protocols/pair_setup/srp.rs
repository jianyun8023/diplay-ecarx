//! AirPlay SRP-6a (3072-bit group, SHA-512). Port of our Kotlin contract, compared with
//! LIVI 6ac88bab7351a39b0cffac20c5dcf92ca848319b; no LIVI implementation/runtime is copied.
use num_bigint::BigUint;
use ring::{
    digest,
    rand::{SecureRandom, SystemRandom},
};
use std::sync::OnceLock;

const N_HEX: &str = concat!(
    "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74",
    "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437",
    "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED",
    "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05",
    "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB",
    "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B",
    "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183",
    "995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A",
    "85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7AB",
    "F5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D8",
    "7602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E208",
    "E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF",
);

fn modulus() -> &'static BigUint {
    static N: OnceLock<BigUint> = OnceLock::new();
    N.get_or_init(|| BigUint::parse_bytes(N_HEX.as_bytes(), 16).unwrap())
}
fn hash(parts: &[&[u8]]) -> [u8; 64] {
    let mut hash = digest::Context::new(&digest::SHA512);
    for part in parts {
        hash.update(part);
    }
    hash.finish().as_ref().try_into().unwrap()
}
fn padded(value: &BigUint) -> Vec<u8> {
    let bytes = value.to_bytes_be();
    let mut result = vec![0; 384usize.saturating_sub(bytes.len())];
    result.extend_from_slice(&bytes);
    result
}
fn multiplier() -> &'static BigUint {
    static K: OnceLock<BigUint> = OnceLock::new();
    K.get_or_init(|| {
        BigUint::from_bytes_be(&hash(&[
            &modulus().to_bytes_be(),
            &padded(&BigUint::from(5u8)),
        ]))
    })
}

pub(super) struct Srp {
    pub salt: [u8; 16],
    pub public: Vec<u8>,
    verifier: BigUint,
    private: BigUint,
    b: BigUint,
}
impl Srp {
    pub fn start() -> Option<Self> {
        let mut salt = [0; 16];
        let mut private = [0; 32];
        let random = SystemRandom::new();
        random.fill(&mut salt).ok()?;
        random.fill(&mut private).ok()?;
        Some(Self::with_secrets(salt, private))
    }
    fn with_secrets(salt: [u8; 16], private: [u8; 32]) -> Self {
        let x = BigUint::from_bytes_be(&hash(&[&salt, &hash(&[b"Pair-Setup:3939"])]));
        let g = BigUint::from(5u8);
        let verifier = g.modpow(&x, modulus());
        let private = BigUint::from_bytes_be(&private);
        let b = (multiplier() * &verifier + g.modpow(&private, modulus())) % modulus();
        Self {
            salt,
            public: padded(&b),
            verifier,
            private,
            b,
        }
    }
    pub fn verify(&self, public: &[u8], proof: &[u8]) -> Option<([u8; 64], [u8; 64])> {
        let a = BigUint::from_bytes_be(public);
        if &a % modulus() == BigUint::ZERO {
            return None;
        }
        // pad is a minimum width, not a cap: old Kotlin accepts A>N and A wider than 384 bytes.
        let a_padded = padded(&a);
        let u = BigUint::from_bytes_be(&hash(&[&a_padded, &padded(&self.b)]));
        let secret = ((&a * self.verifier.modpow(&u, modulus())) % modulus())
            .modpow(&self.private, modulus());
        let key = hash(&[&secret.to_bytes_be()]);
        let hn = hash(&[&modulus().to_bytes_be()]);
        let hg = hash(&[&[5]]);
        let mut xor = [0; 64];
        for i in 0..64 {
            xor[i] = hn[i] ^ hg[i];
        }
        let expected = hash(&[
            &xor,
            &hash(&[b"Pair-Setup"]),
            &self.salt,
            &a_padded,
            &padded(&self.b),
            &key,
        ]);
        if proof != expected {
            return None;
        }
        Some((key, hash(&[&a_padded, proof, &key])))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn golden(name: &str) -> Vec<u8> {
        let file = include_str!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../../test/resources/pairsetup-srp-golden.properties"
        ));
        let hex = file
            .lines()
            .find_map(|line| line.strip_prefix(&format!("{name}=")))
            .unwrap();
        hex.as_bytes()
            .chunks(2)
            .map(|pair| u8::from_str_radix(std::str::from_utf8(pair).unwrap(), 16).unwrap())
            .collect()
    }
    #[test]
    fn independent_srp_transcript_and_pinned_livi_b_match() {
        let srp = Srp::with_secrets([0x11; 16], [0x22; 32]);
        assert_eq!(srp.public, golden("B"));
        assert_eq!(srp.salt.as_slice(), golden("salt"));
        let (key, proof) = srp.verify(&golden("A"), &golden("M1")).unwrap();
        assert_eq!(key.as_slice(), golden("K"));
        assert_eq!(proof.as_slice(), golden("M2"));
    }
    #[test]
    fn failures_and_leading_zero_a_preserve_session() {
        let srp = Srp::with_secrets([0x11; 16], [0x22; 32]);
        for a in [
            vec![],
            vec![0],
            modulus().to_bytes_be(),
            (modulus() * 2u8).to_bytes_be(),
        ] {
            assert!(srp.verify(&a, &golden("M1")).is_none());
        }
        assert!(srp.verify(&golden("A"), &[0; 64]).is_none());
        assert!(srp
            .verify(&[vec![0, 0], golden("A")].concat(), &golden("M1"))
            .is_some());
        assert_eq!(BigUint::ZERO.to_bytes_be(), [0]);
        assert_eq!(padded(&(BigUint::from(1u8) << 3080)).len(), 386);
    }
}
