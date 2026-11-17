//! PairSetup owns the full SRP and long-term-key exchange; Android owns only UTF-8/store effects.
mod jni;
mod srp;
use super::{
    crypto::{controller_public_key, hkdf32, StreamKey},
    tlv8::{decode, encode},
};
use ring::signature::{self, Ed25519KeyPair};

struct PairSetup {
    signing: Option<Ed25519KeyPair>,
    public: Vec<u8>,
    identifier: Vec<u8>,
    srp: Option<srp::Srp>,
    key: Option<[u8; 64]>,
    complete: bool,
    // Production is always None (random SRP secrets). Tests pin the SRP salt/private so a
    // complete M1..M6 transcript can be asserted byte-for-byte against an external reference.
    srp_secret: Option<([u8; 16], [u8; 32])>,
}
struct Save {
    identifier: Vec<u8>,
    public: Vec<u8>,
    key: [u8; 64],
    encryption: [u8; 32],
}
enum Step {
    Reply(Vec<u8>),
    Save(Save),
}
fn error(state: u8) -> Vec<u8> {
    encode(&[(6, &[state]), (7, &[2])])
}
impl PairSetup {
    fn new(seed: &[u8], public: Vec<u8>, identifier: Vec<u8>) -> Self {
        Self {
            signing: seed
                .get(..32)
                .and_then(|key| Ed25519KeyPair::from_seed_unchecked(key).ok()),
            public,
            identifier,
            srp: None,
            key: None,
            complete: false,
            srp_secret: None,
        }
    }

    /// Test seam: pin the SRP salt and private value so the full transcript is reproducible.
    #[cfg(test)]
    fn use_srp_secret(mut self, salt: [u8; 16], private: [u8; 32]) -> Self {
        self.srp_secret = Some((salt, private));
        self
    }
    fn handle(&mut self, body: &[u8]) -> Step {
        let items = decode(body);
        let state = items.get(&6).and_then(|v| v.first()).copied().unwrap_or(0);
        let reply = match state {
            1 => {
                let srp = match self.srp_secret {
                    Some((salt, private)) => srp::Srp::with_secrets(salt, private),
                    None => match srp::Srp::start() {
                        Some(srp) => srp,
                        None => return Step::Reply(error(1)),
                    },
                };
                let reply = encode(&[(6, &[2]), (3, &srp.public), (2, &srp.salt)]);
                self.srp = Some(srp);
                reply
            }
            3 => {
                let (Some(srp), Some(a), Some(proof)) = (&self.srp, items.get(&3), items.get(&4))
                else {
                    return Step::Reply(error(4));
                };
                let Some((key, proof)) = srp.verify(a, proof) else {
                    return Step::Reply(error(4));
                };
                self.key = Some(key);
                encode(&[(6, &[4]), (4, &proof)])
            }
            5 => {
                let (Some(key), Some(sealed)) = (self.key, items.get(&5)) else {
                    return Step::Reply(error(6));
                };
                let encryption =
                    hkdf32(&key, b"Pair-Setup-Encrypt-Salt", b"Pair-Setup-Encrypt-Info");
                let mut sealed = sealed.clone();
                let Some(len) = StreamKey::new(&encryption).unwrap().open(
                    *b"\0\0\0\0PS-Msg05",
                    &[],
                    &mut sealed,
                ) else {
                    return Step::Reply(error(5));
                };
                let sub = decode(&sealed[..len]);
                let (Some(identifier), Some(public), Some(sig)) =
                    (sub.get(&1), sub.get(&3), sub.get(&10))
                else {
                    return Step::Reply(error(6));
                };
                let Some(key_prefix) = controller_public_key(public) else {
                    return Step::Reply(error(6));
                };
                let sign_key = hkdf32(
                    &key,
                    b"Pair-Setup-Controller-Sign-Salt",
                    b"Pair-Setup-Controller-Sign-Info",
                );
                let message = [sign_key.as_slice(), identifier, public].concat();
                if signature::UnparsedPublicKey::new(&signature::ED25519, key_prefix)
                    .verify(&message, sig)
                    .is_err()
                {
                    return Step::Reply(error(6));
                }
                // Return owned data before invoking Java; no Rust borrow/lock may span the store callback.
                return Step::Save(Save {
                    identifier: identifier.clone(),
                    public: public.clone(),
                    key,
                    encryption,
                });
            }
            _ => error(state),
        };
        Step::Reply(reply)
    }
    fn after_save(&mut self, saved: Save) -> Vec<u8> {
        // The store already committed, including when this identity cannot sign.
        let Some(signing) = &self.signing else {
            return error(5);
        };
        let sign_key = hkdf32(
            &saved.key,
            b"Pair-Setup-Accessory-Sign-Salt",
            b"Pair-Setup-Accessory-Sign-Info",
        );
        let signature =
            signing.sign(&[sign_key.as_slice(), &self.identifier, &self.public].concat());
        let sub = encode(&[
            (1, &self.identifier),
            (3, &self.public),
            (10, signature.as_ref()),
        ]);
        let Some(sealed) =
            StreamKey::new(&saved.encryption)
                .unwrap()
                .seal(*b"\0\0\0\0PS-Msg06", &[], &sub)
        else {
            return error(5);
        };
        self.complete = true;
        encode(&[(6, &[6]), (5, &sealed)])
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ring::signature::KeyPair;

    fn m5(key: &[u8; 64], public: &[u8], signer: &Ed25519KeyPair) -> Vec<u8> {
        let sign_key = hkdf32(
            key,
            b"Pair-Setup-Controller-Sign-Salt",
            b"Pair-Setup-Controller-Sign-Info",
        );
        let signature = signer.sign(&[&sign_key[..], b"controller\0", public].concat());
        let sub = encode(&[(1, b"controller\0"), (3, public), (10, signature.as_ref())]);
        let encryption = hkdf32(key, b"Pair-Setup-Encrypt-Salt", b"Pair-Setup-Encrypt-Info");
        let sealed = StreamKey::new(&encryption)
            .unwrap()
            .seal(*b"\0\0\0\0PS-Msg05", &[], &sub)
            .unwrap();
        encode(&[(6, &[5]), (5, &sealed)])
    }

    #[test]
    fn save_is_a_separate_commit_and_m6_keeps_the_pre_callback_session_key() {
        let accessory = Ed25519KeyPair::from_seed_unchecked(&[31; 32]).unwrap();
        let controller = Ed25519KeyPair::from_seed_unchecked(&[11; 32]).unwrap();
        let mut owner = PairSetup::new(
            &[31; 32],
            accessory.public_key().as_ref().to_vec(),
            b"accessory\0".to_vec(),
        );
        let key = [17; 64];
        owner.key = Some(key);
        let raw_public = [controller.public_key().as_ref(), &[7; 300]].concat();
        let Step::Save(saved) = owner.handle(&m5(&key, &raw_public, &controller)) else {
            panic!("valid M5 must request a store commit")
        };
        assert_eq!(saved.identifier, b"controller\0");
        assert_eq!(saved.public, raw_public);
        assert!(!owner.complete);
        // A store callback may reenter authentication; the in-flight M6 uses its captured K.
        owner.key = Some([23; 64]);
        let response = decode(&owner.after_save(saved));
        assert_eq!(response[&6], [6]);
        let encryption = hkdf32(&key, b"Pair-Setup-Encrypt-Salt", b"Pair-Setup-Encrypt-Info");
        let mut encrypted = response[&5].clone();
        let len = StreamKey::new(&encryption)
            .unwrap()
            .open(*b"\0\0\0\0PS-Msg06", &[], &mut encrypted)
            .unwrap();
        let sub = decode(&encrypted[..len]);
        assert_eq!(sub[&1], b"accessory\0");
        let sign_key = hkdf32(
            &key,
            b"Pair-Setup-Accessory-Sign-Salt",
            b"Pair-Setup-Accessory-Sign-Info",
        );
        signature::UnparsedPublicKey::new(&signature::ED25519, &sub[&3])
            .verify(&[&sign_key[..], &sub[&1], &sub[&3]].concat(), &sub[&10])
            .unwrap();
        assert!(owner.complete);
    }

    #[test]
    fn invalid_identity_signing_fails_after_the_save_step_without_completing() {
        let controller = Ed25519KeyPair::from_seed_unchecked(&[11; 32]).unwrap();
        let mut owner = PairSetup::new(&[31; 31], vec![0; 32], b"accessory".to_vec());
        let key = [17; 64];
        owner.key = Some(key);
        let Step::Save(saved) =
            owner.handle(&m5(&key, controller.public_key().as_ref(), &controller))
        else {
            panic!("invalid accessory seed must not prevent the store commit")
        };
        assert_eq!(owner.after_save(saved), error(5));
        assert!(!owner.complete);
        let Step::Reply(reply) = owner.handle(&m5(&key, &[1; 31], &controller)) else {
            panic!("short controller key must not reach the store")
        };
        assert_eq!(reply, error(6));
    }

    fn transcript() -> std::collections::HashMap<String, Vec<u8>> {
        let text = include_str!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../../test/resources/pair-setup-transcript.properties"
        ));
        let mut map = std::collections::HashMap::new();
        for line in text.lines() {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            if let Some((key, value)) = line.split_once('=') {
                let bytes: Vec<u8> = value
                    .as_bytes()
                    .chunks(2)
                    .map(|pair| u8::from_str_radix(std::str::from_utf8(pair).unwrap(), 16).unwrap())
                    .collect();
                map.insert(key.to_string(), bytes);
            }
        }
        map
    }

    /// Complete M1..M6 lifecycle against a fixed external transcript: M2/M4 replies, the
    /// save commit, and the sealed M6 must all equal the independent reference byte-for-byte.
    #[test]
    fn external_complete_transcript_matches_byte_for_byte_through_save_and_m6() {
        let fixture = transcript();
        let salt: [u8; 16] = fixture["srp_salt"].as_slice().try_into().unwrap();
        let b_private: [u8; 32] = fixture["srp_b_private"].as_slice().try_into().unwrap();
        let mut owner = PairSetup::new(
            &fixture["accessory_seed"],
            fixture["accessory_public"].clone(),
            fixture["accessory_id"].clone(),
        )
        .use_srp_secret(salt, b_private);

        let Step::Reply(m2) = owner.handle(&fixture["m1_request"]) else {
            panic!("M1 must reply")
        };
        assert_eq!(m2, fixture["expected_m2"]);

        let Step::Reply(m4) = owner.handle(&fixture["m3_request"]) else {
            panic!("M3 must reply")
        };
        assert_eq!(m4, fixture["expected_m4"]);

        let Step::Save(saved) = owner.handle(&fixture["m5_request"]) else {
            panic!("authenticated M5 must request a store commit")
        };
        assert_eq!(saved.identifier, fixture["controller_id"]);
        assert_eq!(saved.public, fixture["controller_public"]);
        assert!(!owner.complete);

        let m6 = owner.after_save(saved);
        assert_eq!(m6, fixture["expected_m6"]);
        assert!(owner.complete);
    }
}
