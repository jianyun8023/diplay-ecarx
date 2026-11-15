//! One AirPlay session's negotiation, stream ownership and close transitions.
//! Platform callbacks run between transitions, never while a Rust state borrow is alive.
mod jni;

const WORDS: usize = 54;
/// The first remote-control data stream id a session hands out.
const FIRST_REMOTE_CONTROL_STREAM_ID: i64 = 3;
const CLOSED: u32 = 1;
const NOTIFIED: u32 = 2;
const CONNECTING: u32 = 4;
const RENDERED: u32 = 8;
const TIMING_PEER: u32 = 16;
const MAIN_LIVE: u32 = 32;
const NAVIGATION_EXIT: u32 = 64;

struct Session {
    flags: u32,
    capabilities: u32,
    cluster_setups: i32,
    cluster_stream: i32,
    timing_port: i32,
    main_epoch: i64,
    streams: Vec<i32>,
    event_cseq: i32,
    pending_night: Option<bool>,
    night_requests: Vec<(i32, bool)>,
    navigation_request: Option<i64>,
    navigation_entity: Option<i32>,
    next_remote_control_stream_id: i64,
}

impl Session {
    fn new(capabilities: u32) -> Self {
        Self {
            flags: 0,
            capabilities,
            cluster_setups: 0,
            cluster_stream: 0,
            timing_port: 0,
            main_epoch: 0,
            streams: Vec::new(),
            event_cseq: 0,
            pending_night: None,
            night_requests: Vec::new(),
            navigation_request: None,
            navigation_entity: None,
            next_remote_control_stream_id: FIRST_REMOTE_CONTROL_STREAM_ID,
        }
    }

    fn token(&self) -> [i64; WORDS] {
        let mut words = [0; WORDS];
        words[..7].copy_from_slice(&[
            self.flags as i64,
            self.capabilities as i64,
            self.cluster_setups as i64,
            self.cluster_stream as i64,
            self.timing_port as i64,
            self.main_epoch,
            self.streams.len() as i64,
        ]);
        for (target, stream) in words[7..14].iter_mut().zip(&self.streams) {
            *target = *stream as i64;
        }
        words[14] = self.event_cseq as i64;
        words[15] = self.pending_night.map(|v| v as i64).unwrap_or(-1);
        words[16] = self.night_requests.len() as i64;
        for (target, &(seq, night)) in words[17..49].chunks_exact_mut(2).zip(&self.night_requests) {
            target.copy_from_slice(&[seq as i64, night as i64]);
        }
        words[49] = self.navigation_request.is_some() as i64;
        words[50] = self.navigation_request.unwrap_or(0);
        words[51] = self.navigation_entity.is_some() as i64;
        words[52] = self.navigation_entity.unwrap_or(0) as i64;
        words[53] = self.next_remote_control_stream_id;
        words
    }

    fn from_token(words: [i64; WORDS]) -> Option<Self> {
        if !(0..128).contains(&words[0])
            || !(0..32).contains(&words[1])
            || !(0..=7).contains(&words[6])
            || words[2..5].iter().any(|&v| v != v as i32 as i64)
            || words[14] != words[14] as i32 as i64
            || !(-1..=1).contains(&words[15])
            || !(0..=16).contains(&words[16])
            || !(0..=1).contains(&words[49])
            || !(0..=1).contains(&words[51])
            || words[52] != words[52] as i32 as i64
            || words[53] < FIRST_REMOTE_CONTROL_STREAM_ID
        {
            return None;
        }
        let mut streams = Vec::new();
        for &word in &words[7..7 + words[6] as usize] {
            if word != word as i32 as i64
                || kind(word as i32) == 0
                || streams.contains(&(word as i32))
            {
                return None;
            }
            streams.push(word as i32);
        }
        let mut night_requests = Vec::new();
        for pair in words[17..17 + words[16] as usize * 2].chunks_exact(2) {
            if pair[0] != pair[0] as i32 as i64
                || !(0..=1).contains(&pair[1])
                || night_requests.iter().any(|&(seq, _)| seq == pair[0] as i32)
            {
                return None;
            }
            night_requests.push((pair[0] as i32, pair[1] != 0));
        }
        Some(Self {
            flags: words[0] as u32,
            capabilities: words[1] as u32,
            cluster_setups: words[2] as i32,
            cluster_stream: words[3] as i32,
            timing_port: words[4] as i32,
            main_epoch: words[5],
            streams,
            event_cseq: words[14] as i32,
            pending_night: (words[15] >= 0).then_some(words[15] != 0),
            night_requests,
            navigation_request: (words[49] != 0).then_some(words[50]),
            navigation_entity: (words[51] != 0).then_some(words[52] as i32),
            next_remote_control_stream_id: words[53],
        })
    }

    fn query(&self, field: i32) -> i64 {
        match field {
            0 => (self.flags & CLOSED != 0) as i64,
            1 => (self.flags & RENDERED != 0 && self.flags & CLOSED == 0) as i64,
            2 => self.cluster_stream as i64,
            3 => self.timing_port as i64,
            5 => (self.flags & MAIN_LIVE != 0 && self.flags & CLOSED == 0) as i64,
            6 => self.main_epoch,
            7 => self.pending_night.map(|v| v as i64).unwrap_or(-1),
            8 => self.event_cseq as i64,
            9 => self.navigation_request.is_some() as i64,
            10 => self.navigation_request.unwrap_or(0),
            11 => 5_000,
            _ => 0,
        }
    }

    fn event(&mut self, event: i32, value: i32) -> bool {
        match event {
            1 | 2 | 3 => {
                let bit = match event {
                    1 => CLOSED,
                    2 => NOTIFIED,
                    _ => CONNECTING,
                };
                let first = self.flags & bit == 0;
                self.flags |= bit;
                first
            }
            4 => {
                if self.flags & CLOSED != 0 {
                    return false;
                }
                self.flags |= RENDERED;
                true
            }
            6 => {
                self.cluster_stream = 0;
                true
            }
            7 => {
                self.timing_port = value;
                true
            }
            8 => {
                self.flags |= TIMING_PEER;
                true
            }
            9 => {
                self.timing_port = 0;
                self.flags &= !TIMING_PEER;
                true
            }
            10 => {
                if let Some(index) = self.streams.iter().position(|&t| t == value) {
                    self.streams.remove(index);
                    true
                } else {
                    false
                }
            }
            12 => {
                self.pending_night = Some(value != 0);
                true
            }
            13 => {
                self.event_cseq = self.event_cseq.wrapping_add(1);
                true
            }
            14 => {
                self.pending_night = None;
                true
            }
            15 => {
                if self.night_requests.len() >= 16 {
                    self.night_requests.remove(0);
                }
                if let Some(entry) = self
                    .night_requests
                    .iter_mut()
                    .find(|(seq, _)| *seq == self.event_cseq)
                {
                    entry.1 = value != 0;
                } else {
                    self.night_requests.push((self.event_cseq, value != 0));
                }
                true
            }
            _ => false,
        }
    }

    fn night_response(&mut self, seq: i32) -> i32 {
        match self
            .night_requests
            .iter()
            .position(|&(request, _)| request == seq)
        {
            Some(index) => self.night_requests.remove(index).1 as i32,
            None => -1,
        }
    }

    /// Hands out this session's next remote-control data stream id and advances the counter:
    /// the iPhone tears a stream down by the streamID the SETUP reply carried.
    fn remote_control_stream(&mut self) -> i64 {
        let id = self.next_remote_control_stream_id;
        self.next_remote_control_stream_id = self.next_remote_control_stream_id.wrapping_add(1);
        id
    }

    /// Begin: -1 closed / 0 coalesced / 1 claim. Release: -1 absent / 0 closed / 1 write.
    /// Entity: bit 1 changed, bit 2 grants accessory navigation. No platform calls here.
    fn navigation(&mut self, event: i32, value: i64, present: bool) -> i32 {
        match event {
            1 => {
                if self.flags & CLOSED != 0 {
                    return -1;
                }
                if self.flags & NAVIGATION_EXIT != 0 {
                    return 0;
                }
                self.navigation_request = present.then_some(value);
                self.navigation_entity = None;
                self.flags |= NAVIGATION_EXIT;
                1
            }
            2 => {
                if self.flags & NAVIGATION_EXIT == 0 {
                    return -1;
                }
                self.flags &= !NAVIGATION_EXIT;
                (self.flags & CLOSED == 0) as i32
            }
            3 => {
                if present {
                    self.flags |= NAVIGATION_EXIT;
                } else {
                    self.flags &= !NAVIGATION_EXIT;
                }
                0
            }
            4 => {
                let entity = value as i32;
                let changed = self.navigation_entity != Some(entity);
                self.navigation_entity = Some(entity);
                changed as i32 | if entity == 2 { 2 } else { 0 }
            }
            _ => 0,
        }
    }

    /// 1 installs a main-screen lease; 2 starts a fresh cluster content generation.
    fn accepted(&mut self, stream: i32) -> i32 {
        if kind(stream) == 0 {
            return 0;
        }
        if !self.streams.contains(&stream) {
            self.streams.push(stream);
        }
        match stream {
            110 => {
                self.main_epoch = self.main_epoch.wrapping_add(1);
                self.flags |= MAIN_LIVE;
                1
            }
            111 => {
                self.cluster_setups = self.cluster_setups.wrapping_add(1);
                self.cluster_stream = self.cluster_setups;
                2
            }
            _ => 0,
        }
    }

    fn stream_action(&self, stream: i32) -> i32 {
        match kind(stream) {
            3 if self.capabilities & 16 == 0 => 5,
            other => other,
        }
    }

    fn prepare_teardown(&mut self, types: Option<&[i32]>) -> i32 {
        let mut actions = 0;
        if types.is_none_or(|types| types.contains(&110)) {
            self.flags &= !MAIN_LIVE;
            actions |= 1;
        }
        if types.is_none_or(|types| types.contains(&111)) {
            self.cluster_stream = 0;
            actions |= 2;
        }
        actions
    }

    fn finish_teardown(&mut self, all: bool) -> bool {
        if all {
            self.streams.clear();
        }
        if !self.streams.contains(&111) {
            self.cluster_stream = 0;
            true
        } else {
            false
        }
    }

    fn timing_action(&self, port: i32, has_peer: bool) -> i32 {
        (if self.timing_port == 0 { 1 } else { 0 })
            | (if port > 0 && has_peer && self.flags & TIMING_PEER == 0 {
                2
            } else {
                0
            })
    }

    fn route(&self, method: &str, path: &str, body_bytes: i32) -> i32 {
        match method {
            "SETUP" => return 1,
            "SET_PARAMETER" => {
                return if self.capabilities & 4 != 0 && body_bytes <= 512 * 1024 + 64 * 1024 {
                    2
                } else {
                    0
                }
            }
            "RECORD" => return 3,
            "TEARDOWN" => return 4,
            "SETRATE" | "SETRATEANCHORTIME" | "FLUSHBUFFERED" if self.capabilities & 16 != 0 => {
                return 5
            }
            "GETANCHOR" if self.capabilities & 16 != 0 => return 6,
            _ => {}
        }
        let path = path.to_lowercase();
        if path.ends_with("/pair-setup") {
            7
        } else if path.ends_with("/pair-verify") {
            8
        } else if path.ends_with("/auth-setup") {
            9
        } else if path.ends_with("/info") {
            10
        } else if method == "POST" && path.ends_with("/command") {
            11
        } else if method == "POST" && path.ends_with("/feedback") {
            12
        } else {
            0
        }
    }
}

fn kind(stream: i32) -> i32 {
    match stream {
        110 | 111 => 1,
        100..=102 => 2,
        103 => 3,
        130 => 4,
        _ => 0,
    }
}

fn features(capabilities: u32, proposed: &[String]) -> Vec<&'static str> {
    let mut result = Vec::new();
    if capabilities & 1 != 0 {
        result.push("hevc");
    }
    result.extend(["iAPChannel", "viewAreas"]);
    if capabilities & 2 != 0 {
        result.push("altScreen");
    }
    for (flag, name) in [
        (4, "cornerMasks"),
        (8, "videoPlayback"),
        (16, "mainBuffered"),
    ] {
        if capabilities & flag != 0 && proposed.iter().any(|s| s == name) {
            result.push(name);
        }
    }
    result
}

fn teardown_types(types: &[i32], ids: &[i64], present: &[u8]) -> Option<Vec<i32>> {
    if types.is_empty() {
        return None;
    }
    Some(
        types
            .iter()
            .zip(ids)
            .zip(present)
            .filter_map(|((&kind, &id), &has_id)| {
                (!(kind == 130 && has_id != 0 && id != 1)).then_some(kind)
            })
            .collect(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn remote_control_stream_ids_start_at_three_and_survive_the_token() {
        let mut session = Session::new(0);
        assert_eq!(session.remote_control_stream(), 3);
        assert_eq!(session.remote_control_stream(), 4);
        let mut restored = Session::from_token(session.token()).unwrap();
        assert_eq!(restored.remote_control_stream(), 5);
        // A session whose token carries a later counter continues from there.
        let mut later = Session::new(0);
        for _ in 0..9 {
            later.remote_control_stream();
        }
        assert_eq!(Session::from_token(later.token()).unwrap().remote_control_stream(), 12);
    }

    #[test]
    fn from_token_rejects_stream_id_counters_below_the_first_id() {
        let mut words = Session::new(0).token();
        words[53] = 2;
        assert!(Session::from_token(words).is_none());
        words[53] = 3;
        assert!(Session::from_token(words).is_some());
        words[53] = i64::MAX;
        assert!(Session::from_token(words).is_some());
    }
}
