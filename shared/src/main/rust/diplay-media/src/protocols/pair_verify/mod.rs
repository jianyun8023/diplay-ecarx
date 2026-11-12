//! Pair-verify responder. Compared against LIVI 6ac88bab7351a39b0cffac20c5dcf92ca848319b;
//! ported from our Kotlin contract, including its partial M1 updates and truncated-TLV handling.
mod jni;

use crate::protocols::crypto::StreamKey;
use ring::{
    agreement, hkdf,
    rand::SystemRandom,
    signature::{self, Ed25519KeyPair},
};
use std::collections::HashMap;

const IDENTIFIER: u8 = 1;
const PUBLIC_KEY: u8 = 3;
const ENCRYPTED: u8 = 5;
const STATE: u8 = 6;
const SIGNATURE: u8 = 10;

pub struct PairVerify {
    signing: Option<Ed25519KeyPair>,
    accessory_id: Vec<u8>,
    own_public: Option<Vec<u8>>,
    client_public: Option<Vec<u8>>,
    pub shared: Option<[u8; 32]>,
    encryption_key: Option<[u8; 32]>,
    pub keys: Option<([u8; 32], [u8; 32])>,
    pub controller_id: Option<Vec<u8>>,
}

impl PairVerify {
    pub fn new(seed: Vec<u8>, accessory_id: Vec<u8>) -> Self {
        let signing = seed
            .get(..32)
            .and_then(|seed| Ed25519KeyPair::from_seed_unchecked(seed).ok());
        Self {
            signing,
            accessory_id,
            own_public: None,
            client_public: None,
            shared: None,
            encryption_key: None,
            keys: None,
            controller_id: None,
        }
    }

    pub fn handle(
        &mut self,
        body: &[u8],
        lookup: impl FnOnce(&[u8]) -> Option<Vec<u8>>,
    ) -> Vec<u8> {
        let tlv = decode(body);
        let state = tlv
            .get(&STATE)
            .and_then(|v| v.first())
            .copied()
            .unwrap_or(0);
        match state {
            1 => self.m2(&tlv).unwrap_or_else(|| error(1)),
            3 => self.m4(&tlv, lookup).unwrap_or_else(|| error(3)),
            _ => error(state),
        }
    }

    fn m2(&mut self, tlv: &HashMap<u8, Vec<u8>>) -> Option<Vec<u8>> {
        let Some(client) = tlv.get(&PUBLIC_KEY) else {
            return Some(error(2));
        };
        let private =
            agreement::EphemeralPrivateKey::generate(&agreement::X25519, &SystemRandom::new())
                .ok()?;
        let public = private.compute_public_key().ok()?.as_ref().to_vec();
        // Kotlin publishes these before agreement; a failed new M1 must not leave an old transcript usable.
        self.own_public = Some(public.clone());
        self.client_public = Some(client.clone());
        // BC's offset constructor consumes the first 32 bytes, but signatures include the entire raw key.
        let peer = agreement::UnparsedPublicKey::new(&agreement::X25519, client.get(..32)?);
        let shared = agreement::agree_ephemeral(private, &peer, |bytes| {
            <[u8; 32]>::try_from(bytes).unwrap()
        })
        .ok()?;
        self.shared = Some(shared);
        let encryption_key = hkdf32(
            &shared,
            b"Pair-Verify-Encrypt-Salt",
            b"Pair-Verify-Encrypt-Info",
        );
        self.encryption_key = Some(encryption_key);

        let message = [public.as_slice(), &self.accessory_id, client].concat();
        // An invalid identity still fails at signing, after shared/encryption have been published.
        let signature = self.signing.as_ref()?.sign(&message);
        let sub = encode(&[
            (IDENTIFIER, &self.accessory_id),
            (SIGNATURE, signature.as_ref()),
        ]);
        let sealed = StreamKey::new(&encryption_key)?.seal(*b"\0\0\0\0PV-Msg02", &[], &sub)?;
        Some(encode(&[
            (STATE, &[2]),
            (PUBLIC_KEY, &public),
            (ENCRYPTED, &sealed),
        ]))
    }

    fn m4(
        &mut self,
        tlv: &HashMap<u8, Vec<u8>>,
        lookup: impl FnOnce(&[u8]) -> Option<Vec<u8>>,
    ) -> Option<Vec<u8>> {
        let (Some(encryption_key), Some(shared), Some(own), Some(client), Some(sealed)) = (
            self.encryption_key.as_ref(),
            self.shared.as_ref(),
            self.own_public.as_ref(),
            self.client_public.as_ref(),
            tlv.get(&ENCRYPTED),
        ) else {
            return Some(error(4));
        };
        let mut sealed = sealed.clone();
        let len = StreamKey::new(encryption_key)?.open(*b"\0\0\0\0PV-Msg03", &[], &mut sealed)?;
        let sub = decode(&sealed[..len]);
        let (Some(identifier), Some(signature)) = (sub.get(&IDENTIFIER), sub.get(&SIGNATURE))
        else {
            return Some(error(4));
        };
        let Some(ltpk) = lookup(identifier) else {
            return Some(error(4));
        };
        let Some(ltpk) = controller_public_key(&ltpk) else {
            return Some(error(4));
        };
        let message = [client.as_slice(), identifier, own].concat();
        if signature::UnparsedPublicKey::new(&signature::ED25519, ltpk)
            .verify(&message, signature)
            .is_err()
        {
            return Some(error(4));
        }
        let read = hkdf32(shared, b"Control-Salt", b"Control-Write-Encryption-Key");
        let write = hkdf32(shared, b"Control-Salt", b"Control-Read-Encryption-Key");
        self.keys = Some((read, write));
        self.controller_id = Some(identifier.clone());
        Some(encode(&[(STATE, &[4])]))
    }
}

fn controller_public_key(raw: &[u8]) -> Option<&[u8; 32]> {
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

fn hkdf32(input: &[u8], salt: &[u8], info: &[u8]) -> [u8; 32] {
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

fn error(state: u8) -> Vec<u8> {
    encode(&[(STATE, &[state]), (7, &[2])])
}

// Private to this owner: other pairing messages still use the Kotlin TLV codec.
fn decode(buffer: &[u8]) -> HashMap<u8, Vec<u8>> {
    let mut output = HashMap::<u8, Vec<u8>>::new();
    let (mut position, mut previous) = (0, None);
    while buffer.len() - position >= 2 {
        let (kind, len) = (buffer[position], buffer[position + 1] as usize);
        position += 2;
        if len > buffer.len() - position {
            break;
        }
        let value = &buffer[position..position + len];
        if previous == Some((kind, 255)) {
            output.entry(kind).or_default().extend_from_slice(value);
        } else {
            output.insert(kind, value.to_vec());
        }
        position += len;
        previous = Some((kind, len));
    }
    output
}

fn encode(items: &[(u8, &[u8])]) -> Vec<u8> {
    let mut output = Vec::new();
    let mut previous = None;
    for &(kind, value) in items {
        if previous == Some(kind) {
            output.extend_from_slice(&[255, 0]);
        }
        if value.is_empty() {
            output.extend_from_slice(&[kind, 0]);
        }
        for chunk in value.chunks(255) {
            output.extend_from_slice(&[kind, chunk.len() as u8]);
            output.extend_from_slice(chunk);
        }
        previous = Some(kind);
    }
    output
}

#[cfg(test)]
mod tests {
    use super::*;
    use ring::{digest, signature::KeyPair};

    fn responder() -> PairVerify {
        PairVerify::new(vec![5; 32], b"accessory\0id".to_vec())
    }
    fn controller() -> Ed25519KeyPair {
        Ed25519KeyPair::from_seed_unchecked(&[9; 32]).unwrap()
    }
    fn hex(bytes: &[u8]) -> String {
        bytes.iter().map(|b| format!("{b:02x}")).collect()
    }

    fn begin(owner: &mut PairVerify, suffix: &[u8]) -> (Vec<u8>, Vec<u8>, [u8; 32]) {
        let private =
            agreement::EphemeralPrivateKey::generate(&agreement::X25519, &SystemRandom::new())
                .unwrap();
        let raw = [private.compute_public_key().unwrap().as_ref(), suffix].concat();
        let reply = decode(
            &owner.handle(&encode(&[(STATE, &[1]), (PUBLIC_KEY, &raw)]), |_| {
                panic!("M1 must not consult storage")
            }),
        );
        assert_eq!(reply[&STATE], [2]);
        let own = &reply[&PUBLIC_KEY];
        let shared = agreement::agree_ephemeral(
            private,
            &agreement::UnparsedPublicKey::new(&agreement::X25519, own),
            |bytes| <[u8; 32]>::try_from(bytes).unwrap(),
        )
        .unwrap();
        assert_eq!(owner.shared, Some(shared));
        let key = hkdf32(
            &shared,
            b"Pair-Verify-Encrypt-Salt",
            b"Pair-Verify-Encrypt-Info",
        );
        let mut sealed = reply[&ENCRYPTED].clone();
        let len = StreamKey::new(&key)
            .unwrap()
            .open(*b"\0\0\0\0PV-Msg02", &[], &mut sealed)
            .unwrap();
        let sub = decode(&sealed[..len]);
        assert_eq!(sub[&IDENTIFIER], owner.accessory_id);
        let accessory = Ed25519KeyPair::from_seed_unchecked(&[5; 32]).unwrap();
        signature::UnparsedPublicKey::new(&signature::ED25519, accessory.public_key())
            .verify(
                &[own.as_slice(), &owner.accessory_id, &raw].concat(),
                &sub[&SIGNATURE],
            )
            .unwrap();
        (raw, own.clone(), shared)
    }

    fn finish(
        raw: &[u8],
        own: &[u8],
        shared: &[u8],
        id: &[u8],
        signing: &Ed25519KeyPair,
    ) -> Vec<u8> {
        let signature = signing.sign(&[raw, id, own].concat());
        let sub = encode(&[(IDENTIFIER, id), (SIGNATURE, signature.as_ref())]);
        let key = hkdf32(
            shared,
            b"Pair-Verify-Encrypt-Salt",
            b"Pair-Verify-Encrypt-Info",
        );
        let sealed = StreamKey::new(&key)
            .unwrap()
            .seal(*b"\0\0\0\0PV-Msg03", &[], &sub)
            .unwrap();
        encode(&[(STATE, &[3]), (ENCRYPTED, &sealed)])
    }

    #[test]
    fn pinned_livi_primitive_and_tlv_vectors() {
        // LIVI's ts_vectors.json at 6ac88bab7351a39b0cffac20c5dcf92ca848319b (no full Verify transcript vector).
        assert_eq!(
            hex(&hkdf32(
                &[9; 32],
                b"Control-Salt",
                b"Control-Read-Encryption-Key"
            )),
            "1685445548b7b7ca1bbf7f87f12bbe0565947c3b71d23fd1210346031274f100"
        );
        assert_eq!(hex(Ed25519KeyPair::from_seed_unchecked(&[5; 32]).unwrap().sign(b"sign me").as_ref()),
            "c30aa1a26a5de91b79931f02fdf8e338690c52abbd2cef801983ca6beb9141816638ae6776ffbf74b234515e2cdd385b75858a36da963948cfd505e1c3b34707");
        assert_eq!(
            hex(&StreamKey::new(&[4; 32])
                .unwrap()
                .seal(*b"\0\0\0\0PV-Msg02", &[], b"sub tlv")
                .unwrap()),
            "f550e0b9ef38a5d0558f3b5daa13b289de319324a5a10d"
        );
        let tlv = encode(&[(6, &[1]), (3, &[7; 300]), (3, b"x"), (1, &[])]);
        assert_eq!(tlv.len(), 314);
        assert_eq!(
            hex(digest::digest(&digest::SHA256, &tlv).as_ref()),
            "967d480c85875da5c4d93f5069885682a34715f8b85e57c9da3a7d5924fe222c"
        );
    }

    #[test]
    fn complete_handshake_authenticates_both_sides_and_crosses_control_keys() {
        let mut owner = responder();
        let (raw, own, shared) = begin(&mut owner, &[]);
        assert!(owner.keys.is_none());
        assert!(owner.controller_id.is_none());
        let id = b"controller\0\xff";
        let request = finish(&raw, &own, &shared, id, &controller());
        let reply = owner.handle(&request, |found| {
            assert_eq!(found, id);
            Some(controller().public_key().as_ref().to_vec())
        });
        assert_eq!(reply, encode(&[(STATE, &[4])]));
        assert_eq!(owner.controller_id.as_deref(), Some(id.as_slice()));
        assert_eq!(
            owner.keys,
            Some((
                hkdf32(&shared, b"Control-Salt", b"Control-Write-Encryption-Key"),
                hkdf32(&shared, b"Control-Salt", b"Control-Read-Encryption-Key")
            ))
        );
    }

    #[test]
    fn long_public_keys_use_prefix_for_crypto_but_complete_raw_for_transcript() {
        let mut owner = PairVerify::new(
            [vec![5; 32], vec![7; 80]].concat(),
            b"accessory\0id".to_vec(),
        );
        let (raw, own, shared) = begin(&mut owner, &[17; 300]);
        let mut stored = controller().public_key().as_ref().to_vec();
        stored.extend_from_slice(b"ignored key suffix");
        assert_eq!(
            owner.handle(
                &finish(&raw, &own, &shared, b"id", &controller()),
                |_| Some(stored)
            ),
            encode(&[(STATE, &[4])])
        );
    }

    #[test]
    fn malformed_and_out_of_order_messages_preserve_error_state() {
        let mut owner = responder();
        for state in [0, 2, 4, 255] {
            assert_eq!(
                owner.handle(&encode(&[(STATE, &[state])]), |_| None),
                error(state)
            );
        }
        assert_eq!(owner.handle(&[], |_| None), error(0));
        assert_eq!(owner.handle(&encode(&[(STATE, &[1])]), |_| None), error(2));
        assert_eq!(owner.handle(&encode(&[(STATE, &[3])]), |_| None), error(4));
        for key in [vec![], vec![1; 31], vec![0; 32]] {
            assert_eq!(
                owner.handle(&encode(&[(STATE, &[1]), (PUBLIC_KEY, &key)]), |_| None),
                error(1)
            );
        }
        begin(&mut owner, &[]);
        assert_eq!(
            owner.handle(&encode(&[(STATE, &[3]), (ENCRYPTED, &[0; 5])]), |_| None),
            error(3)
        );
        assert_eq!(
            owner.handle(&encode(&[(STATE, &[3]), (ENCRYPTED, &[0; 16])]), |_| None),
            error(3)
        );
    }

    #[test]
    fn unknown_controller_bad_signatures_and_short_stored_keys_are_authentication_errors() {
        let mut owner = responder();
        let (raw, own, shared) = begin(&mut owner, &[]);
        let request = finish(&raw, &own, &shared, b"id", &controller());
        assert_eq!(owner.handle(&request, |_| None), error(4));
        assert_eq!(owner.handle(&request, |_| Some(vec![1; 31])), error(4));
        let other = Ed25519KeyPair::from_seed_unchecked(&[8; 32]).unwrap();
        let forged = finish(&raw, &own, &shared, b"id", &other);
        assert_eq!(
            owner.handle(&forged, |_| Some(
                controller().public_key().as_ref().to_vec()
            )),
            error(4)
        );
        assert!(owner.keys.is_none());
    }

    #[test]
    fn stored_ed_public_keys_reject_low_order_and_noncanonical_y_including_both_signs() {
        let mut bad = vec![[0; 32], {
            let mut p = [0; 32];
            p[0] = 1;
            p
        }];
        for encoded in [
            "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a",
            "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05",
        ] {
            let bytes: Vec<u8> = encoded
                .as_bytes()
                .chunks(2)
                .map(|pair| u8::from_str_radix(std::str::from_utf8(pair).unwrap(), 16).unwrap())
                .collect();
            bad.push(bytes.try_into().unwrap());
        }
        for first in 0xec..=0xff {
            let mut point = [0xff; 32];
            point[0] = first;
            point[31] = 0x7f;
            bad.push(point);
        }
        for point in bad {
            for sign in [0, 0x80] {
                let mut point = point;
                point[31] |= sign;
                assert!(controller_public_key(&point).is_none(), "{}", hex(&point));
            }
        }
        assert!(controller_public_key(controller().public_key().as_ref()).is_some());

        let mut owner = responder();
        let (_, _, shared) = begin(&mut owner, &[]);
        let mut forged = [0; 64];
        forged[..32].fill(0x66);
        forged[0] = 0x58;
        forged[32] = 1;
        let sub = encode(&[(IDENTIFIER, b"identity"), (SIGNATURE, &forged)]);
        let key = hkdf32(
            &shared,
            b"Pair-Verify-Encrypt-Salt",
            b"Pair-Verify-Encrypt-Info",
        );
        let sealed = StreamKey::new(&key)
            .unwrap()
            .seal(*b"\0\0\0\0PV-Msg03", &[], &sub)
            .unwrap();
        let reply = owner.handle(&encode(&[(STATE, &[3]), (ENCRYPTED, &sealed)]), |_| {
            let mut identity = vec![0; 32];
            identity[0] = 1;
            Some(identity)
        });
        assert_eq!(reply, error(4));
        assert!(owner.keys.is_none());
    }

    #[test]
    fn failed_new_m1_replaces_public_transcript_but_retains_previous_shared_secret() {
        let mut owner = responder();
        let (raw, own, shared) = begin(&mut owner, &[]);
        let old_m3 = finish(&raw, &own, &shared, b"id", &controller());
        assert_eq!(
            owner.handle(&encode(&[(STATE, &[1]), (PUBLIC_KEY, &[1; 31])]), |_| None),
            error(1)
        );
        assert_eq!(owner.shared, Some(shared));
        assert_eq!(
            owner.handle(&old_m3, |_| Some(
                controller().public_key().as_ref().to_vec()
            )),
            error(4)
        );
        assert!(owner.keys.is_none());
    }

    #[test]
    fn repeated_or_failed_handshakes_do_not_clear_verified_metadata_or_keys() {
        let mut owner = responder();
        let (raw, own, shared) = begin(&mut owner, &[]);
        assert_eq!(
            owner.handle(
                &finish(&raw, &own, &shared, b"id", &controller()),
                |_| Some(controller().public_key().as_ref().to_vec())
            ),
            encode(&[(STATE, &[4])])
        );
        let keys = owner.keys;
        begin(&mut owner, &[]);
        assert_eq!(owner.keys, keys);
        assert_eq!(owner.controller_id.as_deref(), Some(b"id".as_slice()));
        assert_eq!(
            owner.handle(&encode(&[(STATE, &[3]), (ENCRYPTED, &[0; 16])]), |_| None),
            error(3)
        );
        assert_eq!(owner.keys, keys);
        assert_eq!(owner.controller_id.as_deref(), Some(b"id".as_slice()));
    }

    #[test]
    fn bad_identity_seed_is_rejected_at_signing_after_shared_is_published() {
        let mut owner = PairVerify::new(vec![5; 31], b"id".to_vec());
        let private =
            agreement::EphemeralPrivateKey::generate(&agreement::X25519, &SystemRandom::new())
                .unwrap();
        assert_eq!(
            owner.handle(
                &encode(&[
                    (STATE, &[1]),
                    (PUBLIC_KEY, private.compute_public_key().unwrap().as_ref())
                ]),
                |_| None
            ),
            error(1)
        );
        assert!(owner.shared.is_some());
        assert!(owner.encryption_key.is_some());
        assert!(owner.keys.is_none());
    }

    #[test]
    fn truncated_items_are_ignored_and_duplicate_fragment_rules_match_kotlin() {
        assert_eq!(decode(&[6, 1, 1, 3, 2, 8])[&6], [1]);
        assert!(!decode(&[6, 1, 1, 3, 2, 8]).contains_key(&3));
        let mut data = encode(&[(3, &[7; 300]), (3, b"x"), (1, &[])]);
        data.extend_from_slice(&[3, 4, 1]);
        let decoded = decode(&data);
        assert_eq!(decoded[&3], b"x");
        assert_eq!(decoded[&255], []);
        assert_eq!(decoded[&1], []);
        let mut fragmented = vec![3, 255];
        fragmented.extend_from_slice(&[7; 255]);
        fragmented.extend_from_slice(&[3, 1, 8]);
        assert_eq!(decode(&fragmented)[&3], [vec![7; 255], vec![8]].concat());
    }
}
