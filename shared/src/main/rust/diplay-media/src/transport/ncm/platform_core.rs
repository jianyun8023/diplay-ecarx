//! Core owners for the default Android USB/TUN adapters; no descriptor or I/O ownership.
use super::{ntb16_build, Reassembler, ReassemblyStep, Traffic};
use std::collections::VecDeque;

pub(super) struct ReceiveCore {
    reassembler: Reassembler,
    frames: VecDeque<Vec<u8>>,
    queued_bytes: usize,
}

pub(super) enum ReceiveStep {
    Idle,
    NeedTime,
    Report(String),
    Failed(String),
}

impl ReceiveCore {
    pub fn new(packet_size: usize) -> Self {
        Self {
            reassembler: Reassembler::new(packet_size),
            frames: VecDeque::new(),
            queued_bytes: 0,
        }
    }
    pub fn append(&mut self, bytes: &[u8]) {
        self.reassembler.buffered.extend_from_slice(bytes);
    }
    pub fn step(&mut self, now: Option<i64>) -> ReceiveStep {
        let mut now = now;
        loop {
            match self.reassembler.step(now.take()) {
                ReassemblyStep::Idle => return ReceiveStep::Idle,
                ReassemblyStep::NeedTime => return ReceiveStep::NeedTime,
                ReassemblyStep::Report(line) => return ReceiveStep::Report(line),
                ReassemblyStep::Failed(error) => return ReceiveStep::Failed(error),
                ReassemblyStep::Progress => {}
                ReassemblyStep::Block(frames) => {
                    for frame in frames {
                        if self.frames.len() >= 256 || self.queued_bytes + frame.len() > 1 << 20 {
                            return ReceiveStep::Failed(
                                "NCM frame queue exceeded its bounds".into(),
                            );
                        }
                        self.queued_bytes += frame.len();
                        self.frames.push_back(frame);
                    }
                }
            }
        }
    }
    pub fn poll(&mut self) -> Option<Vec<u8>> {
        let frame = self.frames.pop_front()?;
        self.queued_bytes -= frame.len();
        Some(frame)
    }
}

pub(super) struct Transmit {
    flags: i64,
    sequence: u16,
    dropped: i32,
    opened_at: i64,
}
impl Transmit {
    pub fn new() -> Self {
        Self {
            flags: 0,
            sequence: 0,
            dropped: 0,
            opened_at: 0,
        }
    }
    pub fn decode(words: &[i64]) -> Option<Self> {
        if words.len() != 4 || !(0..8).contains(&words[0]) {
            return None;
        }
        Some(Self {
            flags: words[0],
            sequence: u16::try_from(words[1]).ok()?,
            dropped: i32::try_from(words[2]).ok()?,
            opened_at: words[3],
        })
    }
    pub fn encode(&self) -> [i64; 4] {
        [
            self.flags,
            self.sequence as i64,
            self.dropped as i64,
            self.opened_at,
        ]
    }
    pub fn next_sequence(&mut self) -> u16 {
        let value = self.sequence;
        self.sequence = self.sequence.wrapping_add(1);
        value
    }
    pub fn timeout(&self, requested: i32) -> i32 {
        if self.flags & 1 != 0 {
            requested
        } else {
            requested.min(20)
        }
    }
    // Mode 0: dropped, 1: accepted before, 2: first positive completion (sample clock before reporting).
    pub fn begin(&mut self, transferred: i32) -> [i64; 3] {
        if transferred <= 0 {
            if self.flags & 1 == 0 {
                self.dropped = self.dropped.wrapping_add(1);
            }
            let report = self.flags & 2 == 0;
            self.flags |= 2;
            [0, report as i64, self.dropped as i64]
        } else if self.flags & 1 == 0 {
            self.flags |= 1;
            [2, 0, self.dropped as i64]
        } else {
            [1, 0, self.dropped as i64]
        }
    }
    pub fn opened(&mut self, now: i64) {
        self.flags |= 4;
        self.opened_at = now;
    }
    pub fn opened_at(&self) -> Option<i64> {
        (self.flags & 4 != 0).then_some(self.opened_at)
    }
    pub fn finish(&mut self, transferred: i32, expected: i32) -> i32 {
        if transferred != expected {
            return -1;
        }
        let report = self.flags & 2 != 0;
        self.flags &= !2;
        report as i32
    }
}

impl Traffic {
    pub(super) fn encode(&self) -> Vec<i64> {
        let mut words = vec![0; 36];
        words[..4].copy_from_slice(&[
            self.from_iphone as i64,
            self.ipv6 as i64,
            self.ipv4 as i64,
            self.to_iphone as i64,
        ]);
        for row in words[4..].chunks_exact_mut(4) {
            row[0] = -1;
        }
        for (row, &(port, count)) in words[4..].chunks_exact_mut(4).zip(&self.syns_by_port) {
            row[0] = port as i64;
            row[1] = count as i32 as i64;
            if let Some(&at) = self.last_syn_ns.get(&port) {
                row[2] = 1;
                row[3] = at;
            }
        }
        words
    }
    pub(super) fn decode(words: &[i64]) -> Option<Self> {
        if words.len() != 36 {
            return None;
        }
        let mut traffic = Self {
            from_iphone: words[0] as u64,
            ipv6: words[1] as u64,
            ipv4: words[2] as u64,
            to_iphone: words[3] as u64,
            ..Self::default()
        };
        let mut ended = false;
        for row in words[4..].chunks_exact(4) {
            if row[0] == -1 {
                ended = true;
                continue;
            }
            if ended || !(0..=1).contains(&row[2]) {
                return None;
            }
            let port = u16::try_from(row[0]).ok()?;
            if traffic.syns_by_port.iter().any(|entry| entry.0 == port) {
                return None;
            }
            traffic
                .syns_by_port
                .push((port, i32::try_from(row[1]).ok()? as u32));
            if row[2] != 0 {
                traffic.last_syn_ns.insert(port, row[3]);
            }
        }
        Some(traffic)
    }
}

pub(super) fn build(frame: &[u8], sequence: i32) -> Result<Vec<u8>, &'static str> {
    if frame.is_empty() {
        return Err("frame must not be empty");
    }
    if frame.len() > 0xffff - 28 {
        return Err("frame exceeds the NTB16 length field");
    }
    let sequence = u16::try_from(sequence).map_err(|_| "sequence must fit in u16")?;
    Ok(ntb16_build(frame, sequence, 512).unwrap())
}

pub(super) fn peer_decode(words: &[i64]) -> Option<Option<[u8; 6]>> {
    if words.len() != 1 || words[0] < 0 || words[0] >> 49 != 0 {
        return None;
    }
    if words[0] & (1 << 48) == 0 {
        return Some(None);
    }
    Some(Some(std::array::from_fn(|i| (words[0] >> (i * 8)) as u8)))
}
pub(super) fn peer_encode(mac: [u8; 6]) -> i64 {
    mac.iter()
        .enumerate()
        .fold(1i64 << 48, |value, (i, &byte)| {
            value | (byte as i64) << (i * 8)
        })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn transmit_value_keeps_signed_clock_bits_and_sequence_wrap() {
        let mut tx = Transmit::new();
        tx.sequence = u16::MAX;
        assert_eq!(tx.next_sequence(), u16::MAX);
        assert_eq!(tx.next_sequence(), 0);
        assert_eq!(tx.begin(-1), [0, 1, 1]);
        assert_eq!(tx.begin(1), [2, 0, 1]);
        tx.opened(i64::MIN);
        let mut restored = Transmit::decode(&tx.encode()).unwrap();
        assert_eq!(restored.opened_at(), Some(i64::MIN));
        assert_eq!(restored.finish(1, 2), -1);
        assert_eq!(restored.timeout(2_000), 2_000);
        assert_eq!(restored.finish(2, 2), 1);
        assert_eq!(restored.finish(2, 2), 0);
    }

    #[test]
    fn full_speed_pad_is_not_a_resync_and_queue_is_owned_until_polled() {
        let mut rx = ReceiveCore::new(64);
        let mut input = build(&[1; 36], 0).unwrap();
        assert_eq!(input.len(), 64);
        input.push(0);
        input.extend(build(&[2; 3], 1).unwrap());
        rx.append(&input);
        assert!(matches!(rx.step(None), ReceiveStep::Idle));
        assert_eq!(rx.poll(), Some(vec![1; 36]));
        assert_eq!(rx.poll(), Some(vec![2; 3]));
        assert_eq!(rx.poll(), None);
        assert_eq!(rx.queued_bytes, 0);
    }

    #[test]
    fn repeated_damage_is_timed_and_reported_before_parsing_next_damage() {
        let block = build(&[1; 3], 0).unwrap();
        let mut bytes = vec![0x55];
        bytes.extend(&block);
        bytes.push(0x66);
        bytes.extend(&block);
        let mut rx = ReceiveCore::new(512);
        rx.append(&bytes);
        assert!(matches!(rx.step(None), ReceiveStep::NeedTime));
        let ReceiveStep::Report(first) = rx.step(Some(100)) else {
            panic!()
        };
        assert!(first.ends_with("skipped=1 resyncs=1"));
        assert!(matches!(rx.step(None), ReceiveStep::NeedTime));
        let ReceiveStep::Report(second) = rx.step(Some(101)) else {
            panic!()
        };
        assert!(second.ends_with("skipped=1 resyncs=2"));
        assert!(matches!(rx.step(None), ReceiveStep::Idle));
    }

    #[test]
    fn zero_mac_is_distinct_from_an_unknown_peer() {
        assert_eq!(peer_decode(&[0]), Some(None));
        assert_eq!(peer_decode(&[peer_encode([0; 6])]), Some(Some([0; 6])));
        assert_eq!(
            peer_decode(&[peer_encode([0xff; 6])]),
            Some(Some([0xff; 6]))
        );
    }
}
