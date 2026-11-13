//! Control/event framing and both AEAD counters. The Kotlin owner serializes use and destruction.
mod jni;

use crate::protocols::crypto::{counter_nonce, StreamKey, TAG_LEN};

const MAX_PAYLOAD: usize = 0x4000;

#[derive(Debug, PartialEq, Eq)]
pub enum Error {
    InvalidKey,
    AuthFailed,
    SealFailed,
}

pub struct Decrypted {
    pub data: Vec<u8>,
    pub consumed: usize,
}

pub struct ControlCipher {
    // Validate once, but preserve the old lazy error: an incomplete frame never uses its key.
    read_key: Option<StreamKey>,
    write_key: Option<StreamKey>,
    read_counter: u64,
    write_counter: u64,
}

impl ControlCipher {
    pub fn new(read: &[u8], write: &[u8]) -> Self {
        Self {
            read_key: StreamKey::new(read),
            write_key: StreamKey::new(write),
            read_counter: 0,
            write_counter: 0,
        }
    }

    pub fn decrypt(&mut self, buffer: &[u8]) -> Result<Decrypted, Error> {
        let mut data = Vec::new();
        let mut offset = 0;
        while buffer.len() - offset >= 2 {
            let length = u16::from_le_bytes([buffer[offset], buffer[offset + 1]]) as usize;
            let frame_end = offset + 2 + length + TAG_LEN;
            if buffer.len() < frame_end {
                break;
            }
            let key = self.read_key.as_ref().ok_or(Error::InvalidKey)?;
            let mut sealed = buffer[offset + 2..frame_end].to_vec();
            let plain = key
                .open(
                    counter_nonce(self.read_counter),
                    &buffer[offset..offset + 2],
                    &mut sealed,
                )
                .ok_or(Error::AuthFailed)?;
            data.extend_from_slice(&sealed[..plain]);
            self.read_counter = self.read_counter.wrapping_add(1);
            offset = frame_end;
        }
        Ok(Decrypted {
            data,
            consumed: offset,
        })
    }

    pub fn encrypt(&mut self, plain: &[u8]) -> Result<Vec<u8>, Error> {
        let key = self.write_key.as_ref().ok_or(Error::InvalidKey)?;
        let mut data = Vec::new();
        let mut offset = 0;
        loop {
            let end = (offset + MAX_PAYLOAD).min(plain.len());
            let header = ((end - offset) as u16).to_le_bytes();
            let sealed = key
                .seal(
                    counter_nonce(self.write_counter),
                    &header,
                    &plain[offset..end],
                )
                .ok_or(Error::SealFailed)?;
            data.extend_from_slice(&header);
            data.extend_from_slice(&sealed);
            self.write_counter = self.write_counter.wrapping_add(1);
            offset = end;
            if offset == plain.len() {
                break;
            }
        }
        Ok(data)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reference_vectors_match_across_subsequent_calls() {
        // LIVI ts_vectors.json at 6ac88bab7351a39b0cffac20c5dcf92ca848319b; wire fixture only.
        let mut owner = ControlCipher::new(&[1; 32], &[2; 32]);
        let first = owner
            .encrypt(&[vec![0x41; 16384], b"tail".to_vec()].concat())
            .unwrap();
        assert_eq!(first.len(), 16424);
        let sha = ring::digest::digest(&ring::digest::SHA256, &first)
            .as_ref()
            .iter()
            .map(|v| format!("{v:02x}"))
            .collect::<String>();
        assert_eq!(
            sha,
            "d9a80f2f38ddd9c2af5f3ac536cd481e4dcfa9dd38c87b6d400289ccfc8cabd2"
        );
        let second = owner.encrypt(b"second").unwrap();
        assert_eq!(
            second
                .iter()
                .map(|v| format!("{v:02x}"))
                .collect::<String>(),
            "0600f46717c443a865be9990d158e4679a8fd58f23a1a863"
        );
    }

    #[test]
    fn empty_and_chunked_frames_keep_independent_directions() {
        let mut ours = ControlCipher::new(&[1; 32], &[2; 32]);
        let mut theirs = ControlCipher::new(&[2; 32], &[1; 32]);
        for length in [0, 1, 16384, 16385, 32768, 32769] {
            let plain = vec![7; length];
            let wire = ours.encrypt(&plain).unwrap();
            assert_eq!(
                wire.len(),
                length + length.max(1).div_ceil(MAX_PAYLOAD) * 18
            );
            let result = theirs.decrypt(&wire).unwrap();
            assert_eq!(result.data, plain);
            assert_eq!(result.consumed, wire.len());
            assert_eq!(
                ours.decrypt(&theirs.encrypt(b"reply").unwrap())
                    .unwrap()
                    .data,
                b"reply"
            );
        }
    }

    #[test]
    fn every_incomplete_boundary_preserves_counter_and_successful_prefix() {
        let mut encoder = ControlCipher::new(&[1; 32], &[1; 32]);
        let first = encoder.encrypt(b"first").unwrap();
        let second = encoder.encrypt(b"second").unwrap();
        for split in 0..second.len() {
            let mut decoder = ControlCipher::new(&[1; 32], &[1; 32]);
            let result = decoder
                .decrypt(&first[..split.min(first.len() - 1)])
                .unwrap();
            assert_eq!(result.consumed, 0);
            let result = decoder
                .decrypt(&[first.clone(), second[..split].to_vec()].concat())
                .unwrap();
            assert_eq!(result.data, b"first");
            assert_eq!(result.consumed, first.len());
            assert_eq!(decoder.decrypt(&second).unwrap().data, b"second");
        }
    }

    #[test]
    fn decode_accepts_the_full_unsigned_16bit_length() {
        let header = u16::MAX.to_le_bytes();
        let sealed = StreamKey::new(&[7; 32])
            .unwrap()
            .seal(counter_nonce(0), &header, &vec![9; 65535])
            .unwrap();
        let wire = [header.to_vec(), sealed].concat();
        let mut decoder = ControlCipher::new(&[7; 32], &[0; 32]);
        assert_eq!(decoder.decrypt(&wire).unwrap().data, vec![9; 65535]);
    }

    #[test]
    fn a_failure_keeps_the_counter_progress_of_preceding_successful_frames() {
        let mut encoder = ControlCipher::new(&[3; 32], &[3; 32]);
        let first = encoder.encrypt(b"first").unwrap();
        let second = encoder.encrypt(b"second").unwrap();
        let mut bad = second.clone();
        *bad.last_mut().unwrap() ^= 1;
        let mut decoder = ControlCipher::new(&[3; 32], &[3; 32]);
        assert!(matches!(
            decoder.decrypt(&[first, bad].concat()),
            Err(Error::AuthFailed)
        ));
        assert_eq!(decoder.read_counter, 1);
        assert_eq!(decoder.decrypt(&second).unwrap().data, b"second");
    }

    #[test]
    fn counters_wrap_with_the_same_little_endian_nonce_as_kotlin_long() {
        let mut ours = ControlCipher::new(&[5; 32], &[5; 32]);
        ours.read_counter = u64::MAX;
        ours.write_counter = u64::MAX;
        let first = ours.encrypt(b"last").unwrap();
        assert_eq!(ours.write_counter, 0);
        assert_eq!(ours.decrypt(&first).unwrap().data, b"last");
        assert_eq!(ours.read_counter, 0);
        let second = ours.encrypt(b"first").unwrap();
        assert_eq!(ours.decrypt(&second).unwrap().data, b"first");
    }

    #[test]
    fn bad_key_errors_remain_lazy_and_direction_specific() {
        let mut owner = ControlCipher::new(&[1; 31], &[2; 32]);
        assert_eq!(owner.decrypt(&[0, 0]).unwrap().consumed, 0);
        assert!(matches!(owner.decrypt(&[0; 18]), Err(Error::InvalidKey)));
        assert!(owner.encrypt(b"valid write").is_ok());
        let mut owner = ControlCipher::new(&[1; 32], &[]);
        assert_eq!(owner.encrypt(&[]), Err(Error::InvalidKey));
    }
}
