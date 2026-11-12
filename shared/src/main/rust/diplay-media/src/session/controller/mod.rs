//! Controller generations, permission/re-enumeration budgets, transport adoption and teardown plans.
//! The JVM retains SDK objects; this value contains the only active lifecycle/decision state.
mod jni;

const HEADER: usize = 14;
const CLOSED: i64 = 1;
const PERMISSION: i64 = 2;
const VPN_BOUND: i64 = 4;
const RADIO_RETURN: i64 = 8;
const PREREQUISITE_FAILED: i64 = 16;
const WIRED_ACTIVE: i64 = 32;

struct Controller {
    header: [i64; HEADER],
    grant_attempts: Vec<Vec<u16>>,
    pending_grants: Vec<Vec<u16>>,
}
impl Controller {
    fn new(wireless: bool, location: bool) -> Self {
        let mut header = [0; HEADER];
        header[0] = wireless as i64 | ((location as i64) << 1);
        Self {
            header,
            grant_attempts: Vec::new(),
            pending_grants: Vec::new(),
        }
    }
    fn valid_header(h: &[i64; HEADER]) -> bool {
        (0..4).contains(&h[0])
            && (0..64).contains(&h[1])
            && (0..=6).contains(&h[2])
            && h[3..8].iter().all(|&v| v == v as i32 as i64)
            && (0..=1).contains(&h[8])
            && (0..=1).contains(&h[10])
            && h[12] >= 0
            && h[13] >= 0
    }
    fn decode(words: &[i64]) -> Option<Self> {
        let header: [i64; HEADER] = words.get(..HEADER)?.try_into().ok()?;
        if !Self::valid_header(&header) {
            return None;
        }
        let mut cursor = HEADER;
        let mut sets = [Vec::new(), Vec::new()];
        for (set, &count) in sets.iter_mut().zip(&header[12..14]) {
            if count as u64 > words.len().saturating_sub(cursor) as u64 {
                return None;
            }
            for _ in 0..count {
                let length = *words.get(cursor)?;
                cursor += 1;
                if length < 0 || length as u64 > words.len().saturating_sub(cursor) as u64 {
                    return None;
                }
                let end = cursor + length as usize;
                let mut key = Vec::new();
                for &unit in &words[cursor..end] {
                    key.push(u16::try_from(unit).ok()?);
                }
                if set.contains(&key) {
                    return None;
                }
                set.push(key);
                cursor = end;
            }
        }
        if cursor != words.len() {
            return None;
        }
        let [grant_attempts, pending_grants] = sets;
        Some(Self {
            header,
            grant_attempts,
            pending_grants,
        })
    }
    fn encode(&self) -> Vec<i64> {
        let mut words = self.header.to_vec();
        words[12] = self.grant_attempts.len() as i64;
        words[13] = self.pending_grants.len() as i64;
        for key in self.grant_attempts.iter().chain(&self.pending_grants) {
            words.push(key.len() as i64);
            words.extend(key.iter().map(|&unit| unit as i64));
        }
        words
    }
    fn closed(&self) -> bool {
        self.header[1] & CLOSED != 0
    }
    fn bump(&mut self, slot: usize) -> i64 {
        self.header[slot] = (self.header[slot] as i32).wrapping_add(1) as i64;
        self.header[slot]
    }
    fn flag(&mut self, bit: i64, enabled: bool) {
        if enabled {
            self.header[1] |= bit;
        } else {
            self.header[1] &= !bit;
        }
    }
    fn wired_current(&self, run: i64) -> bool {
        !self.closed() && self.header[3] == run && matches!(self.header[2], 5 | 6)
    }
    fn query(h: &[i64; HEADER], field: i32) -> i64 {
        match field {
            0 => (h[1] & CLOSED != 0) as i64,
            1 => h[2],
            2 => h[3],
            3 => h[4],
            4 => h[5],
            5 => h[6],
            6 => h[7],
            7 => (h[1] & WIRED_ACTIVE != 0) as i64,
            8 => h[8],
            9 => h[9],
            10 => (h[1] & VPN_BOUND != 0) as i64,
            _ => 0,
        }
    }
    fn event(&mut self, event: i32, value: i64, present: bool) -> i64 {
        match event {
            1 => {
                if self.closed() {
                    0
                } else {
                    self.header[2] = 1;
                    1
                }
            }
            2 => {
                if self.closed() {
                    0
                } else {
                    self.bump(3);
                    self.header[2] = 3;
                    1
                }
            }
            3 => {
                self.bump(3);
                self.bump(5);
                self.header[2] = 2;
                self.bump(4)
            }
            4 => {
                if self.closed() {
                    0
                } else {
                    self.flag(CLOSED, true);
                    1
                }
            }
            5 => {
                self.header[2] = 4;
                self.bump(7)
            }
            6 => {
                if self.closed() {
                    0
                } else {
                    self.header[2] = 5;
                    1
                }
            }
            7 => {
                if self.wired_current(value) {
                    self.header[2] = 6;
                    1
                } else {
                    0
                }
            }
            8 => {
                // Discovery reset follows the phase/run commit, matching platform callback order.
                self.grant_attempts.clear();
                self.pending_grants.clear();
                self.header[7] = 0;
                self.header[10] = 0;
                0
            }
            9 => {
                self.flag(PERMISSION, false);
                0
            }
            10 => {
                let claim = self.header[1] & PERMISSION == 0;
                self.flag(PERMISSION, true);
                claim as i64
            }
            11 => {
                self.flag(PERMISSION, true);
                0
            }
            12 => self.bump(5),
            13 => self.bump(6),
            14 => self.bump(4),
            15 => {
                self.flag(RADIO_RETURN, true);
                0
            }
            16 => {
                let pending = self.header[1] & RADIO_RETURN != 0;
                self.flag(RADIO_RETURN, false);
                pending as i64
            }
            17 => {
                let first = self.header[1] & PREREQUISITE_FAILED == 0;
                self.flag(PREREQUISITE_FAILED, true);
                first as i64
            }
            18 => {
                self.flag(WIRED_ACTIVE, present);
                0
            }
            19 => {
                self.header[8] = present as i64;
                self.header[9] = value;
                0
            }
            20 => {
                let bind = self.header[1] & VPN_BOUND == 0;
                self.flag(VPN_BOUND, true);
                bind as i64
            }
            21 => {
                self.flag(VPN_BOUND, false);
                0
            }
            22 => {
                let unbind = self.header[1] & VPN_BOUND != 0;
                self.flag(VPN_BOUND, false);
                unbind as i64
            }
            23 => {
                // First absence starts a monotonic USB-discovery wait.
                if self.header[10] == 0 {
                    self.header[10] = 1;
                    self.header[11] = value;
                }
                (value.wrapping_sub(self.header[11]) >= timing_policy(12)) as i64
            }
            _ => 0,
        }
    }
    fn decide(&self, decision: i32, value: i64, other: i64, facts: i64) -> i64 {
        match decision {
            0 => self.wired_current(value) as i64,
            1 => {
                (!self.closed()
                    && (!matches!(facts & 1, 1) || value == self.header[4])
                    && facts & 2 == 0) as i64
            }
            2 => (!self.closed() && facts & 3 == 3) as i64,
            3 => {
                (!self.closed()
                    && value == self.header[6]
                    && (facts & 1 == 0 || self.header[2] == 1)) as i64
            }
            4 => (!self.closed() && value == self.header[2] && other == self.header[5]) as i64,
            5 => {
                (!self.closed()
                    && self.header[2] == 4
                    && value == self.header[7]
                    && other == self.header[5]) as i64
            }
            6 => (!self.closed() && value == self.header[2] && other == self.header[6]) as i64,
            7 => (!self.closed() && (facts & 1 == 0 || value == self.header[4])) as i64,
            8 => {
                // Failure may still post after retirement; progress may only post for the live run.
                (!self.closed()
                    && facts & 1 != 0
                    && (facts & 2 == 0 || value == self.header[4] && facts & 12 != 0)
                    && facts & 16 == 0) as i64
            }
            9 => {
                if self.header[0] & 1 == 0 {
                    i64::MAX
                } else if self.header[0] & 2 != 0 {
                    24 * 60 * 60 * 1000
                } else {
                    5 * 60 * 1000
                }
            }
            _ => 0,
        }
    }
    fn key(&mut self, event: i32, key: Vec<u16>) -> bool {
        match event {
            0 => self.pending_grants.contains(&key),
            1 => {
                if self.grant_attempts.contains(&key) {
                    false
                } else {
                    self.grant_attempts.push(key);
                    true
                }
            }
            2 => {
                if !self.pending_grants.contains(&key) {
                    self.pending_grants.push(key);
                }
                true
            }
            3 => {
                if let Some(index) = self.pending_grants.iter().position(|k| k == &key) {
                    self.pending_grants.remove(index);
                }
                true
            }
            _ => false,
        }
    }
    fn teardown_plan(&self) -> Vec<i32> {
        if self.header[0] & 1 != 0 {
            vec![1, 2, 4, 6, 7]
        } else {
            vec![3, 4, 5, 6, 7]
        }
    }
}

fn wireless_close_plan(current: bool) -> Vec<i32> {
    let mut actions = vec![1, 2, 3];
    if current {
        actions.push(4);
    }
    actions.extend([5, 6, 7, 8, 9, 10]);
    actions
}

fn handoff_in_progress(requested: bool, tunnel: bool, session: bool) -> bool {
    requested || tunnel || session
}
fn expected_control_close(stage: i32, facts: i32) -> bool {
    stage == 3 && facts & 7 == 7 && facts & 56 == 0
}
fn configuration(found: bool, mode: bool, started: bool, tried: i32, maximum: i32) -> i32 {
    if found && mode && started && tried == 0 {
        2
    } else if found && mode {
        0
    } else if found && tried > 0 {
        1
    } else if tried < maximum {
        2
    } else {
        3
    }
}

fn timing_policy(field: i32) -> i64 {
    match field {
        0 => 120_000,
        1 => 500,
        2 => 2_000,
        3 => 45_000,
        4 => 60_000,
        5 => 20_000,
        6 => 300_000,
        7 => 10_000,
        8 => 60_000,
        9 => 35_000,
        10 => 2_000,
        11 => 10_000,
        12 => 20_000,
        13 => 2_000,
        _ => 0,
    }
}

struct Vpn {
    active: bool,
    generation: i32,
}
impl Vpn {
    fn event(&mut self, event: i32, generation: i32, current: bool) -> i32 {
        match event {
            0 => {
                if !current {
                    0
                } else if self.active {
                    2
                } else {
                    1
                }
            }
            1 => {
                self.active = true;
                self.generation = self.generation.wrapping_add(1);
                self.generation
            }
            2 => {
                self.active = false;
                self.generation = self.generation.wrapping_add(1);
                self.generation
            }
            3 => (self.active && self.generation == generation && current) as i32,
            4 => (self.generation == generation) as i32,
            _ => 0,
        }
    }
}

struct Proof {
    generation: i32,
    flags: i64,
}
impl Proof {
    fn event(&mut self, event: i32, generation: i32, same_session: bool) -> bool {
        match event {
            0 => {
                self.generation = generation;
                self.flags = 8;
                true
            }
            1 => {
                if self.generation != generation || same_session {
                    return false;
                }
                self.flags = (self.flags & 8) | 1;
                true
            }
            2 => {
                if self.generation != generation || self.flags & 1 == 0 {
                    return false;
                }
                self.flags |= 2;
                self.confirm()
            }
            3 => {
                if self.generation != generation || !same_session {
                    return false;
                }
                self.flags |= 4;
                self.confirm()
            }
            4 => {
                if self.generation != generation || !same_session {
                    return false;
                }
                self.flags &= 8;
                true
            }
            5 => {
                self.generation = -1;
                self.flags = 0;
                true
            }
            6 => self.generation == generation && self.flags & 1 != 0,
            7 => self.generation == generation && self.flags & 5 == 5,
            _ => false,
        }
    }
    fn confirm(&mut self) -> bool {
        if self.flags & 14 != 14 {
            return false;
        }
        self.flags &= !8;
        true
    }
}
