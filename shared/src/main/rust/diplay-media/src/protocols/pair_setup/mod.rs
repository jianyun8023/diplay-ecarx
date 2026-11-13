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
        }
    }
    fn handle(&mut self, body: &[u8]) -> Step {
        let items = decode(body);
        let state = items.get(&6).and_then(|v| v.first()).copied().unwrap_or(0);
        let reply = match state {
            1 => {
                let Some(srp) = srp::Srp::start() else {
                    return Step::Reply(error(1));
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
}
