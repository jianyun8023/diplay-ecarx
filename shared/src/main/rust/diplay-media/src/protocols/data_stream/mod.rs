//! Type-130 iAP tunnel / video-settings protocol owners. Actual sockets stay on Android.
//! Compared with LIVI 6ac88bab7351a39b0cffac20c5dcf92ca848319b livi-cp/iap_tunnel.rs
//! (c33d0c4d3993820014c5e25bf7c5dcaaa05bc915). No source/runtime or transcript is copied.
mod jni;
use super::crypto::{counter_nonce, StreamKey};
use std::collections::HashMap;

#[derive(Clone, Copy, PartialEq)]
enum Mode {
    Iap,
    Settings,
}
#[derive(Debug)]
enum Error {
    Key,
    Tag,
    Package(i32),
}
#[derive(Debug)]
enum Event {
    Iap(Vec<u8>),
    Reply(Vec<u8>),
}

#[derive(Default)]
struct Connection {
    ciphertext: Vec<u8>,
    plaintext: Vec<u8>,
    cipher_at: usize,
    plain_at: usize,
    decrypt: bool,
}
struct Owner {
    mode: Mode,
    read_counter: u64,
    write_counter: u64,
    next: u64,
    connections: HashMap<u64, Connection>,
}
impl Owner {
    fn new(mode: Mode) -> Self {
        Self {
            mode,
            read_counter: 0,
            write_counter: 0,
            next: 1,
            connections: HashMap::new(),
        }
    }
    fn begin(&mut self) -> u64 {
        // The receive-only tunnel resets its shared counter on each accepted connection;
        // settings counters belong to the channel and are never reset by accepting a socket.
        if self.mode == Mode::Iap {
            self.read_counter = 0;
        }
        let id = self.next;
        self.next = self.next.wrapping_add(1).max(1);
        self.connections.insert(id, Connection::default());
        id
    }
    fn append(&mut self, id: u64, bytes: &[u8]) {
        if let Some(c) = self.connections.get_mut(&id) {
            c.ciphertext.extend_from_slice(bytes);
            c.decrypt = true;
        }
    }
    fn next(&mut self, id: u64, read_key: &[u8], write_key: &[u8]) -> Result<Option<Event>, Error> {
        let Some(c) = self.connections.get_mut(&id) else {
            return Ok(None);
        };
        if c.decrypt {
            // Finish all complete AEAD frames before delivering any package callback/reply,
            // exactly as the old two-stage decryptFrames/handlePackages loop did.
            while c.ciphertext.len() - c.cipher_at >= 2 {
                let at = c.cipher_at;
                let n = u16::from_le_bytes([c.ciphertext[at], c.ciphertext[at + 1]]) as usize;
                let end = at + 2 + n + 16;
                if end > c.ciphertext.len() {
                    break;
                }
                let nonce = counter_nonce(self.read_counter);
                if self.mode == Mode::Settings {
                    self.read_counter = self.read_counter.wrapping_add(1);
                }
                let key = StreamKey::new(read_key).ok_or(Error::Key)?;
                let aad = [c.ciphertext[at], c.ciphertext[at + 1]];
                let sealed = &mut c.ciphertext[at + 2..end];
                let plain = key.open(nonce, &aad, sealed).ok_or(Error::Tag)?;
                if self.mode == Mode::Iap {
                    self.read_counter = self.read_counter.wrapping_add(1);
                }
                c.plaintext.extend_from_slice(&sealed[..plain]);
                c.cipher_at = end;
            }
            c.ciphertext.drain(..c.cipher_at);
            c.cipher_at = 0;
            c.decrypt = false;
        }
        while c.plaintext.len() - c.plain_at >= 32 {
            let at = c.plain_at;
            let size = i32::from_be_bytes(c.plaintext[at..at + 4].try_into().unwrap());
            let maximum = if self.mode == Mode::Iap {
                4 * 1024 * 1024
            } else {
                8 * 1024 * 1024
            };
            if size < 32 || size > maximum {
                return Err(Error::Package(size));
            }
            let size = size as usize;
            if size > c.plaintext.len() - at {
                break;
            }
            c.plain_at += size;
            if self.mode == Mode::Iap {
                // iAP uses the four-byte message type at 16, not the package kind at 4.
                if &c.plaintext[at + 16..at + 20] == b"comm" {
                    return Ok(Some(Event::Iap(c.plaintext[at + 32..at + size].to_vec())));
                }
            } else {
                let kind = &c.plaintext[at + 4..at + 16];
                let n = kind
                    .iter()
                    .rposition(|&b| b != 0)
                    .map(|i| i + 1)
                    .unwrap_or(0);
                if &kind[..n] == b"sync" {
                    let mut reply = [0; 32];
                    reply[3] = 32;
                    reply[4..8].copy_from_slice(b"rply");
                    reply[20..28].copy_from_slice(&c.plaintext[at + 20..at + 28]);
                    let nonce = counter_nonce(self.write_counter);
                    self.write_counter = self.write_counter.wrapping_add(1);
                    let key = StreamKey::new(write_key).ok_or(Error::Key)?;
                    let aad = [32, 0];
                    let mut bytes = aad.to_vec();
                    bytes.extend(key.seal(nonce, &aad, &reply).unwrap());
                    return Ok(Some(Event::Reply(bytes)));
                }
            }
        }
        c.plaintext.drain(..c.plain_at);
        c.plain_at = 0;
        Ok(None)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    const KEY: [u8; 32] = [7; 32];
    fn package(mode: Mode, body: &[u8]) -> Vec<u8> {
        let mut p = vec![0; 32];
        p[..4].copy_from_slice(&((32 + body.len()) as u32).to_be_bytes());
        if mode == Mode::Iap {
            p[16..20].copy_from_slice(b"comm")
        } else {
            p[4..8].copy_from_slice(b"sync");
            p[20..28].copy_from_slice(&[1, 2, 3, 4, 5, 6, 7, 8]);
        }
        p.extend(body);
        p
    }
    fn frame(counter: u64, plain: &[u8]) -> Vec<u8> {
        let aad = (plain.len() as u16).to_le_bytes();
        let mut f = aad.to_vec();
        f.extend(
            StreamKey::new(&KEY)
                .unwrap()
                .seal(counter_nonce(counter), &aad, plain)
                .unwrap(),
        );
        f
    }
    #[test]
    fn authentication_commit_and_whole_read_phase_preserve_each_mode() {
        for mode in [Mode::Iap, Mode::Settings] {
            let mut owner = Owner::new(mode);
            let id = owner.begin();
            let mut batch = frame(0, &package(mode, b"one"));
            let mut bad = frame(1, &package(mode, b"two"));
            *bad.last_mut().unwrap() ^= 1;
            batch.extend(bad);
            owner.append(id, &batch);
            assert!(matches!(owner.next(id, &KEY, &KEY), Err(Error::Tag)));
            assert_eq!(owner.read_counter, if mode == Mode::Iap { 1 } else { 2 });
            assert_eq!(owner.write_counter, 0);
        }
    }
    #[test]
    fn fragmented_frame_and_package_then_counter_wrap_deliver_iap() {
        let mut owner = Owner::new(Mode::Iap);
        let id = owner.begin();
        owner.read_counter = u64::MAX;
        let p = package(Mode::Iap, b"iap2");
        let f = frame(u64::MAX, &p[..17]);
        owner.append(id, &f[..3]);
        assert!(owner.next(id, &KEY, &[]).unwrap().is_none());
        owner.append(id, &f[3..]);
        assert!(owner.next(id, &KEY, &[]).unwrap().is_none());
        assert_eq!(owner.read_counter, 0);
        owner.append(id, &frame(0, &p[17..]));
        match owner.next(id, &KEY, &[]).unwrap() {
            Some(Event::Iap(b)) => assert_eq!(b, b"iap2"),
            _ => panic!("missing iAP"),
        }
        assert_eq!(owner.read_counter, 1);
        assert!(owner.next(id, &KEY, &[]).unwrap().is_none());
    }
    #[test]
    fn settings_reply_precedes_later_bad_header_and_preserves_sequence() {
        let mut owner = Owner::new(Mode::Settings);
        let id = owner.begin();
        owner.write_counter = u64::MAX;
        let mut p = package(Mode::Settings, b"ignored");
        let mut bad = [0; 32];
        bad[..4].copy_from_slice(&(-1i32).to_be_bytes());
        p.extend(bad);
        owner.append(id, &frame(0, &p));
        let bytes = match owner.next(id, &KEY, &KEY).unwrap() {
            Some(Event::Reply(b)) => b,
            _ => panic!("missing reply"),
        };
        let mut sealed = bytes[2..].to_vec();
        assert_eq!(
            StreamKey::new(&KEY)
                .unwrap()
                .open(counter_nonce(u64::MAX), &[32, 0], &mut sealed),
            Some(32)
        );
        let mut expected = [0; 32];
        expected[3] = 32;
        expected[4..8].copy_from_slice(b"rply");
        expected[20..28].copy_from_slice(&[1, 2, 3, 4, 5, 6, 7, 8]);
        assert_eq!(&sealed[..32], &expected);
        assert_eq!(owner.write_counter, 0);
        assert!(matches!(
            owner.next(id, &KEY, &KEY),
            Err(Error::Package(-1))
        ));
    }
    #[test]
    fn different_size_limits_key_failure_commits_and_reaccept_reset() {
        for mode in [Mode::Iap, Mode::Settings] {
            let mut owner = Owner::new(mode);
            let id = owner.begin();
            owner.append(id, &frame(0, &[]));
            assert!(matches!(owner.next(id, &KEY[..31], &KEY), Err(Error::Key)));
            assert_eq!(owner.read_counter, if mode == Mode::Iap { 0 } else { 1 });
            owner.connections.remove(&id);
            let id = owner.begin();
            let nonce = if mode == Mode::Iap { 0 } else { 1 };
            let mut p = vec![0; 32];
            p[..4].copy_from_slice(&(4u32 * 1024 * 1024 + 1).to_be_bytes());
            owner.append(id, &frame(nonce, &p));
            let result = owner.next(id, &KEY, &KEY);
            if mode == Mode::Iap {
                assert!(matches!(result, Err(Error::Package(_))))
            } else {
                assert!(result.unwrap().is_none())
            }
        }
    }
}
