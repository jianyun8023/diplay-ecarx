//! iAP2 identification/authentication and wired/wireless control negotiation.
//! The JVM executes frame I/O, SDK signing, clock sampling and callbacks from these decisions.
mod jni;
use std::collections::HashSet;

#[derive(Clone, Copy)]
enum Setup {
    Complete = 0,
    Authenticate = 1,
    Power = 2,
    Subscribe = 3,
}
impl Setup {
    fn decode(value: i64) -> Option<Self> {
        Some(match value {
            0 => Self::Complete,
            1 => Self::Authenticate,
            2 => Self::Power,
            3 => Self::Subscribe,
            _ => return None,
        })
    }
}

enum Handshake {
    Identification {
        ready: bool,
        accepted: bool,
        retried: bool,
        omitted: Vec<i32>,
    },
    Authentication {
        accepted: bool,
    },
    Wired {
        stage: u8,
        setup: Setup,
        forwarded: i32,
        starts: i32,
        again_decided: bool,
        again_pending: bool,
    },
    Wireless {
        stage: u8,
        setup: Setup,
        forwarded: i32,
        wifi: i32,
        starts: i32,
        pre_wifi: i32,
        post_wifi: i32,
        flags: u8,
    },
}

impl Handshake {
    fn new(kind: i32) -> Option<Self> {
        Some(match kind {
            0 => Self::Identification {
                ready: false,
                accepted: false,
                retried: false,
                omitted: Vec::new(),
            },
            1 => Self::Authentication { accepted: false },
            2 => Self::Wired {
                stage: 0,
                setup: Setup::Authenticate,
                forwarded: 0,
                starts: 0,
                again_decided: false,
                again_pending: false,
            },
            3 => Self::Wireless {
                stage: 0,
                setup: Setup::Authenticate,
                forwarded: 0,
                wifi: 0,
                starts: 0,
                pre_wifi: 0,
                post_wifi: 0,
                flags: 0,
            },
            _ => return None,
        })
    }
    fn encode(&self) -> Vec<i64> {
        match self {
            Self::Identification {
                ready,
                accepted,
                retried,
                omitted,
            } => {
                let mut words = vec![0, *ready as i64, *accepted as i64, *retried as i64];
                words.extend(omitted.iter().map(|&id| id as i64));
                words
            }
            Self::Authentication { accepted } => vec![1, *accepted as i64],
            Self::Wired {
                stage,
                setup,
                forwarded,
                starts,
                again_decided,
                again_pending,
            } => vec![
                2,
                *stage as i64,
                *setup as i64,
                *forwarded as i64,
                *starts as i64,
                *again_decided as i64,
                *again_pending as i64,
            ],
            Self::Wireless {
                stage,
                setup,
                forwarded,
                wifi,
                starts,
                pre_wifi,
                post_wifi,
                flags,
            } => vec![
                3,
                *stage as i64,
                *setup as i64,
                *forwarded as i64,
                *wifi as i64,
                *starts as i64,
                *pre_wifi as i64,
                *post_wifi as i64,
                *flags as i64,
            ],
        }
    }
    fn decode(words: &[i64]) -> Option<Self> {
        Some(match *words.first()? {
            0 if words.len() >= 4 && words[1..4].iter().all(|v| (0..=1).contains(v)) => {
                let mut seen = HashSet::new();
                let mut omitted = Vec::new();
                for &id in &words[4..] {
                    let id = i32::try_from(id).ok()?;
                    if !seen.insert(id) {
                        return None;
                    }
                    omitted.push(id);
                }
                Self::Identification {
                    ready: words[1] != 0,
                    accepted: words[2] != 0,
                    retried: words[3] != 0,
                    omitted,
                }
            }
            1 if words.len() == 2 && (0..=1).contains(&words[1]) => Self::Authentication {
                accepted: words[1] != 0,
            },
            2 if words.len() == 7
                && (0..=3).contains(&words[1])
                && words[5..7].iter().all(|v| (0..=1).contains(v)) =>
            {
                Self::Wired {
                    stage: words[1] as u8,
                    setup: Setup::decode(words[2])?,
                    forwarded: i32::try_from(words[3]).ok()?,
                    starts: i32::try_from(words[4]).ok()?,
                    again_decided: words[5] != 0,
                    again_pending: words[6] != 0,
                }
            }
            3 if words.len() == 9 && (0..=6).contains(&words[1]) && (0..8).contains(&words[8]) => {
                Self::Wireless {
                    stage: words[1] as u8,
                    setup: Setup::decode(words[2])?,
                    forwarded: i32::try_from(words[3]).ok()?,
                    wifi: i32::try_from(words[4]).ok()?,
                    starts: i32::try_from(words[5]).ok()?,
                    pre_wifi: i32::try_from(words[6]).ok()?,
                    post_wifi: i32::try_from(words[7]).ok()?,
                    flags: words[8] as u8,
                }
            }
            _ => return None,
        })
    }
    fn message(&mut self, id: i32) -> i32 {
        match self {
            Self::Identification {
                ready, accepted, ..
            } => {
                if !*ready {
                    return 4;
                }
                match id {
                    0x1d00 => 1,
                    0x1d02 => {
                        *accepted = true;
                        2
                    }
                    0x1d03 => 3,
                    _ => 4,
                }
            }
            Self::Authentication { accepted } => match id {
                0xaa00 => 5,
                0xaa02 => 6,
                0xaa05 => {
                    *accepted = true;
                    7
                }
                0xaa04 => 8,
                _ => 9,
            },
            Self::Wired { setup, .. } => {
                if !matches!(setup, Setup::Complete) {
                    return 17;
                }
                match id {
                    0x4300 => 10,
                    0xfffa | 0xfffc => 14,
                    0xa100 | 0xa102 => 15,
                    _ => 16,
                }
            }
            Self::Wireless { setup, .. } => {
                if !matches!(setup, Setup::Complete) {
                    return 17;
                }
                match id {
                    0x5702 => 11,
                    0x4300 => 10,
                    0x4e0d => 12,
                    0x4e0e => 13,
                    0xfffa | 0xfffc => 14,
                    0xa100 | 0xa102 => 15,
                    _ => 16,
                }
            }
        }
    }
    fn event(&mut self, event: i32, value: i32, condition: bool) -> bool {
        match self {
            Self::Identification { ready, .. } if event == 0 => {
                *ready = true;
                true
            }
            Self::Wired {
                stage,
                setup,
                forwarded,
                starts,
                again_pending,
                ..
            } => {
                match event {
                    1 => {
                        *stage = 1;
                        *setup = Setup::Power;
                    }
                    2 => {
                        *stage = 2;
                        *setup = Setup::Complete;
                    }
                    3 => {
                        *stage = 3;
                        *starts = starts.wrapping_add(1);
                    }
                    4 => *forwarded = forwarded.wrapping_add(1),
                    10 => *again_pending = false,
                    11 => *setup = Setup::Subscribe,
                    _ => return false,
                }
                true
            }
            Self::Wireless {
                stage,
                setup,
                forwarded,
                wifi,
                starts,
                pre_wifi,
                post_wifi,
                flags,
            } => {
                match event {
                    1 => {
                        *stage = 1;
                        *setup = Setup::Subscribe;
                    }
                    2 => {
                        *stage = 2;
                        *setup = Setup::Complete;
                    }
                    3 => {
                        *stage = (*stage).max(4);
                        *starts = starts.wrapping_add(1);
                    }
                    4 => *forwarded = forwarded.wrapping_add(1),
                    6 if value == 1 => {
                        *stage = (*stage).max(3);
                        *wifi = wifi.wrapping_add(1);
                        *pre_wifi = pre_wifi.wrapping_add(1);
                    }
                    6 if value == 2 => {
                        *stage = (*stage).max(6);
                        *wifi = wifi.wrapping_add(1);
                        *post_wifi = post_wifi.wrapping_add(1);
                    }
                    7 => *flags |= if condition { 2 } else { 4 },
                    8 => *flags |= 1,
                    9 => *stage = (*stage).max(5),
                    _ => return false,
                }
                true
            }
            _ => false,
        }
    }
    fn query(&self, field: i32) -> i64 {
        match self {
            Self::Identification { accepted, .. } if field == 10 => *accepted as i64,
            Self::Authentication { accepted } if field == 10 => *accepted as i64,
            Self::Wired {
                stage,
                setup,
                forwarded,
                starts,
                again_decided,
                again_pending,
            } => match field {
                0 => *stage as i64,
                1 => *forwarded as i64,
                2 => *starts as i64,
                8 => *setup as i64,
                9 => (!*again_decided && *stage == 3) as i64,
                12 => *again_pending as i64,
                _ => 0,
            },
            Self::Wireless {
                stage,
                setup,
                forwarded,
                wifi,
                starts,
                pre_wifi,
                post_wifi,
                flags,
            } => match field {
                0 => *stage as i64,
                1 => *forwarded as i64,
                2 => *starts as i64,
                3 => *wifi as i64,
                4 => *post_wifi as i64,
                5 => (*flags & 1 != 0) as i64,
                6 => (*flags & 2 != 0) as i64,
                7 => (*flags & 4 != 0) as i64,
                8 => *setup as i64,
                11 => *pre_wifi as i64,
                _ => 0,
            },
            _ => 0,
        }
    }
    fn rejected(&mut self, ids: Option<Vec<i32>>) -> bool {
        let Self::Identification {
            retried, omitted, ..
        } = self
        else {
            return false;
        };
        if *retried {
            return false;
        }
        let Some(ids) = ids else {
            return false;
        };
        *retried = true;
        *omitted = ids;
        true
    }
    fn wifi_plan(&self) -> i32 {
        match self {
            Self::Wireless {
                pre_wifi,
                post_wifi,
                flags,
                ..
            } => {
                if flags & 1 != 0 {
                    if *post_wifi >= 2 {
                        0
                    } else {
                        2
                    }
                } else if *pre_wifi >= 5 {
                    0
                } else {
                    1
                }
            }
            _ => 0,
        }
    }
    fn start_again(&mut self, decision: i32) -> bool {
        let Self::Wired {
            again_decided,
            again_pending,
            ..
        } = self
        else {
            return false;
        };
        match decision {
            0 => {
                *again_pending = true;
                false
            }
            1 => {
                *again_decided = true;
                true
            }
            2 => {
                *again_decided = true;
                false
            }
            _ => false,
        }
    }
}

fn rejection_metadata(ids: &[i32], payloads: &[Vec<u8>]) -> [Option<Vec<i32>>; 3] {
    let mut seen = HashSet::new();
    let parameters = ids.iter().copied().filter(|&id| seen.insert(id)).collect();
    let mut result = [Some(parameters), None, None];
    for (slot, target) in [(1, 6), (2, 7)] {
        let mut counts = [0i32; 4];
        let mut messages = Vec::new();
        for (&id, payload) in ids.iter().zip(payloads) {
            if id != target {
                continue;
            }
            counts[0] = counts[0].wrapping_add(1);
            if payload.is_empty() {
                counts[1] = counts[1].wrapping_add(1);
            } else if payload.len() % 2 != 0 {
                counts[2] = counts[2].wrapping_add(1);
            } else {
                for pair in payload.chunks_exact(2) {
                    if messages.len() < 32 {
                        messages.push(u16::from_be_bytes([pair[0], pair[1]]) as i32);
                    } else {
                        counts[3] = counts[3].wrapping_add(1);
                    }
                }
            }
        }
        if counts[0] != 0 {
            let mut values = counts.to_vec();
            values.extend(messages);
            result[slot] = Some(values);
        }
    }
    result
}

fn omittable(parameters: &[i32], ids: &[i32], counts: &[i32]) -> Option<Vec<i32>> {
    if parameters.is_empty()
        || parameters.iter().any(|id| !matches!(id, 6 | 7))
        || counts.iter().any(|&count| count > 0)
    {
        return None;
    }
    let mut seen = HashSet::new();
    let ids: Vec<i32> = ids.iter().copied().filter(|&id| seen.insert(id)).collect();
    if ids.is_empty() || ids.iter().any(|id| (0xaa00..=0xaa05).contains(id)) {
        None
    } else {
        Some(ids)
    }
}

struct Deadline {
    kind: i32,
    flags: u8,
    value: i64,
}
impl Deadline {
    fn prepare(kind: i32, timeout: i64) -> Option<Self> {
        if !(0..=2).contains(&kind) {
            return None;
        }
        Some(Self {
            kind,
            flags: if kind == 2 && timeout == i64::MAX {
                0
            } else {
                1
            },
            value: timeout,
        })
    }
    fn start(&mut self, now: i64) {
        let delta = self.value.wrapping_mul(1_000_000);
        self.value = match self.kind {
            0 => now.wrapping_add(delta),
            1 => {
                if i64::MAX.wrapping_sub(now) < delta {
                    i64::MAX
                } else {
                    now.wrapping_add(delta)
                }
            }
            _ => {
                if now > i64::MAX.wrapping_sub(delta) {
                    i64::MAX
                } else {
                    now.wrapping_add(delta)
                }
            }
        };
        self.flags &= !1;
    }
    fn plan(&self) -> [i64; 2] {
        if self.kind == 2 {
            if self.flags & 2 != 0 {
                return [2, 0];
            }
            if self.value == i64::MAX {
                return [0, 300_000];
            }
        }
        [1, 0]
    }
    fn at(&self, now: i64) -> [i64; 2] {
        let remaining = self.value.wrapping_sub(now);
        if remaining <= 0 {
            return [if self.kind == 2 { 3 } else { 0 }, 0];
        }
        [
            0,
            (remaining.wrapping_add(999_999) / 1_000_000).min(300_000),
        ]
    }
    fn keep_alive(&mut self, plan: i32, alive: bool) -> i64 {
        if alive && plan == 3 {
            self.flags |= 2;
        }
        if alive {
            300_000
        } else {
            0
        }
    }
}

struct TransportWait {
    wait: i64,
    start: i64,
}
impl TransportWait {
    fn needs_clock(&self, sent: i32, notified: bool) -> bool {
        self.wait != i64::MAX && sent <= 0 && !notified
    }
    fn expired(&self, sent: i32, notified: bool, now: i64) -> bool {
        self.needs_clock(sent, notified)
            && now.wrapping_sub(self.start) >= self.wait.wrapping_mul(1_000_000)
    }
    fn bound(&self, requested: i64, sent: i32, notified: bool, now: i64) -> i64 {
        if !self.needs_clock(sent, notified) {
            return requested;
        }
        let remaining = self
            .wait
            .wrapping_mul(1_000_000)
            .wrapping_sub(now.wrapping_sub(self.start));
        if remaining <= 0 {
            1
        } else {
            requested.min(remaining.wrapping_add(999_999) / 1_000_000)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wired_setup_gates_control_and_start_again_is_once() {
        let mut state = Handshake::new(2).unwrap();
        assert_eq!(state.query(8), 1);
        assert_eq!(state.message(0x4300), 17);
        assert!(state.event(1, 0, false));
        assert_eq!(state.query(8), 2);
        assert_eq!(state.message(0x4300), 17);
        assert!(state.event(11, 0, false));
        assert_eq!(state.query(8), 3);
        assert!(state.event(2, 0, false));
        assert_eq!(state.message(0x4300), 10);
        assert!(state.event(3, 0, false));
        assert_eq!(state.query(9), 1);
        assert!(!state.start_again(0));
        assert_eq!(state.query(12), 1);
        assert!(state.event(10, 0, false));
        assert_eq!(state.query(12), 0);
        assert!(state.start_again(1));
        assert!(state.event(3, 0, false));
        assert_eq!(state.query(2), 2);
        assert_eq!(state.query(9), 0);
    }

    #[test]
    fn identification_retries_only_one_safe_rejection_and_retains_omissions() {
        let mut state = Handshake::new(0).unwrap();
        assert_eq!(state.message(0x1d00), 4);
        assert!(state.event(0, 0, false));
        assert_eq!(state.message(0x1d00), 1);
        assert!(!state.rejected(omittable(&[6], &[0xaa00], &[0, 0])));
        assert!(state.rejected(omittable(&[6, 7], &[0x4300, 0x4301, 0x4300], &[0, 0, 0, 0])));
        assert!(!state.rejected(omittable(&[6], &[0x4154], &[0, 0])));
        let words = state.encode();
        assert_eq!(&words[4..], &[0x4300, 0x4301]);
        let mut restored = Handshake::decode(&words).unwrap();
        assert_eq!(restored.message(0x1d00), 1);
        assert_eq!(restored.encode(), words);
        assert_eq!(restored.message(0x1d02), 2);
        assert_eq!(restored.query(10), 1);
    }

    #[test]
    fn authentication_keeps_existing_success_without_added_request_order() {
        let mut state = Handshake::new(1).unwrap();
        assert_eq!(state.message(0xaa00), 5);
        assert_eq!(state.message(0xaa02), 6);
        assert_eq!(state.message(0xaa04), 8);
        assert_eq!(state.message(0xaa01), 9);
        assert_eq!(state.message(0xaa05), 7);
        assert_eq!(state.query(10), 1);
        assert_eq!(Handshake::new(1).unwrap().message(0xaa05), 7);
    }

    #[test]
    fn wireless_budgets_split_at_transport_and_stage_never_regresses() {
        let mut state = Handshake::new(3).unwrap();
        assert!(state.event(1, 0, false));
        assert_eq!(state.query(8), 3);
        assert!(state.event(2, 0, false));
        for _ in 0..5 {
            assert_eq!(state.wifi_plan(), 1);
            assert!(state.event(6, 1, false));
        }
        assert_eq!(state.wifi_plan(), 0);
        assert!(state.event(7, 0, true));
        assert!(state.event(7, 0, false));
        assert!(state.event(8, 0, false));
        assert_eq!(state.query(5), 1);
        assert_eq!(state.query(0), 3); // Reception precedes fallible metadata decoding.
        assert!(state.event(9, 0, false));
        for _ in 0..2 {
            assert_eq!(state.wifi_plan(), 2);
            assert!(state.event(6, 2, false));
        }
        assert_eq!(state.wifi_plan(), 0);
        assert!(state.event(3, 0, false));
        assert!(state.event(9, 0, false));
        assert_eq!(state.query(0), 6);
        assert_eq!(state.query(3), 7);
        assert_eq!(state.query(4), 2);
        assert_eq!(state.query(6), 1);
        assert_eq!(state.query(7), 1);
        assert!(Handshake::decode(&state.encode()).is_some());
    }

    #[test]
    fn rejected_message_lists_keep_duplicates_and_bound_each_direction() {
        let metadata = rejection_metadata(
            &[6, 6, 6, 7, 7],
            &[
                vec![0x43, 0, 0x43, 0],
                vec![0x43],
                vec![0; 64],
                vec![],
                vec![0x43, 1],
            ],
        );
        assert_eq!(metadata[0].as_deref(), Some(&[6, 7][..]));
        let sent = metadata[1].as_ref().unwrap();
        assert_eq!(&sent[..6], &[3, 0, 1, 2, 0x4300, 0x4300]);
        assert_eq!(sent.len(), 36);
        assert_eq!(metadata[2].as_deref(), Some(&[2, 1, 0, 0, 0x4301][..]));
        assert!(omittable(&[6], &[0x4300], &[1, 0]).is_none());
        assert!(omittable(&[6, 8], &[0x4300], &[0, 0]).is_none());
    }

    #[test]
    fn deadlines_preserve_three_distinct_wrap_and_clock_policies() {
        let mut identification = Deadline::prepare(0, 20).unwrap();
        identification.start(i64::MAX - 10);
        assert_eq!(identification.at(i64::MAX - 10), [0, 20]);
        let mut authentication = Deadline::prepare(1, 20).unwrap();
        authentication.start(-1);
        assert_eq!(authentication.value, i64::MAX);
        assert_eq!(authentication.plan(), [1, 0]);
        assert_eq!(authentication.at(-1), [0, 0]);
        let mut wireless = Deadline::prepare(2, 20).unwrap();
        wireless.start(-1);
        assert_eq!(wireless.value, 19_999_999);
        assert_eq!(wireless.at(-1), [0, 20]);
        let infinite = Deadline::prepare(2, i64::MAX).unwrap();
        assert_eq!(infinite.plan(), [0, 300_000]);
        assert_eq!(infinite.flags, 0);
    }

    #[test]
    fn wireless_live_extension_keeps_reentrant_success_after_outer_false() {
        let mut state = Deadline::prepare(2, 1).unwrap();
        state.start(0);
        assert_eq!(state.at(1_000_000), [3, 0]);
        assert_eq!(state.keep_alive(3, true), 300_000);
        assert_eq!(state.keep_alive(3, false), 0);
        assert_eq!(state.plan(), [2, 0]);
        assert_eq!(state.keep_alive(2, false), 0);
        assert_eq!(state.keep_alive(2, true), 300_000);
    }

    #[test]
    fn transport_wait_observes_progress_and_wrap_without_extra_clock() {
        let state = TransportWait {
            wait: 2,
            start: i64::MAX - 10,
        };
        assert_eq!(state.bound(30_000, 0, false, i64::MAX - 10), 2);
        assert!(state.expired(0, false, (i64::MAX - 10).wrapping_add(2_000_000)));
        assert!(!state.needs_clock(1, false));
        assert!(!state.needs_clock(0, true));
        assert_eq!(state.bound(30_000, 1, false, 0), 30_000);
        let disabled = TransportWait {
            wait: i64::MAX,
            start: i64::MIN,
        };
        assert!(!disabled.needs_clock(0, false));
        assert!(!disabled.expired(0, false, i64::MAX));
    }
}
