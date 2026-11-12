//! Vendor-neutral iAP2 route owner. Android supplies monotonic/wall clocks and session liveness.
use std::collections::HashMap;

#[derive(Debug, PartialEq, Eq, Clone, Copy)]
#[repr(i32)]
pub enum Change { None, Guidance, Clear }

struct Maneuver { kind: i32, side: i32, road: String }

#[derive(Debug, PartialEq, Eq)]
pub struct Guidance<'a> {
    pub distance: i32, pub kind: i32, pub side: i32, pub road: &'a str,
    pub remaining_seconds: Option<i64>, pub remaining_meters: Option<i64>,
    pub arrival: Option<i64>, pub eta_raw: Option<i64>,
}

pub struct RouteGuidance {
    stale_ns: i64, empty_ns: i64, keep_no_route: bool,
    maneuvers: HashMap<i32, Maneuver>, active: bool,
    pub index: i32, pub announced: bool, pub input_state: Option<i32>, pub phone_state: Option<i32>,
    phone_ns: Option<i64>, pub destination: String,
    pub lane_index: Option<i32>, pub lane_showing: Option<bool>,
    distance_known: bool, distance: i32, road: String,
    pub arrival: Option<i64>, eta_raw: Option<i64>, remaining_seconds: Option<i64>, pub remaining_meters: Option<i64>,
    empty_since: Option<i64>, updated: Option<i64>, end_reason: String, pub diagnostic_input: String,
}

impl RouteGuidance {
    pub fn new(stale_ns: i64, empty_ns: i64, keep_no_route: bool) -> Self {
        Self { stale_ns, empty_ns, keep_no_route, maneuvers: HashMap::new(), active: false,
            index: -1, announced: false, input_state: None, phone_state: None, phone_ns: None,
            destination: String::new(), lane_index: None, lane_showing: None,
            distance_known: false, distance: 0, road: String::new(), arrival: None, eta_raw: None,
            remaining_seconds: None, remaining_meters: None, empty_since: None, updated: None,
            end_reason: "not-started".into(), diagnostic_input: "none".into() }
    }

    pub fn expired(&self, now: i64, live: bool) -> bool {
        self.updated.is_some_and(|updated| now.wrapping_sub(updated) >= self.stale_ns && !live)
    }

    pub fn phone_age_ms(&self, now: i64) -> i64 {
        self.phone_ns.map(|then| now.wrapping_sub(then) / 1_000_000).unwrap_or(0)
    }

    pub fn current(&self, now: i64, live: bool, dim: bool) -> Option<Guidance<'_>> {
        if !self.active || self.index < 0 || self.updated.is_none() || self.expired(now, live) ||
            self.empty_since.is_some_and(|since| now.wrapping_sub(since) >= self.empty_ns) || dim && !self.distance_known {
            return None;
        }
        let maneuver = self.maneuvers.get(&self.index)?;
        Some(Guidance { distance: self.distance, kind: maneuver.kind, side: maneuver.side,
            road: if maneuver.road.is_empty() { &self.road } else { &maneuver.road },
            remaining_seconds: self.remaining_seconds, remaining_meters: self.remaining_meters,
            arrival: self.arrival, eta_raw: self.eta_raw })
    }

    pub fn clear(&mut self, reason: &str) -> bool {
        let was_active = self.active;
        self.end_reason = reason.into(); self.active = false; self.announced = false;
        self.destination.clear(); self.index = -1; self.lane_index = None; self.lane_showing = None;
        self.distance = 0; self.distance_known = false; self.road.clear(); self.arrival = None;
        self.eta_raw = None; self.remaining_seconds = None; self.remaining_meters = None;
        self.empty_since = None; self.updated = None; self.maneuvers.clear();
        was_active
    }

    pub fn diagnostic_state(&self, now: i64, live: bool) -> String {
        let route_age = self.updated.map(|at| now.wrapping_sub(at));
        let empty_age = self.empty_since.map(|at| now.wrapping_sub(at));
        let reason = if !self.active { self.end_reason.as_str() }
            else if self.index < 0 { "missing-index" }
            else if route_age.is_none() { "missing-route-update" }
            else if self.expired(now, live) { "route-stale" }
            else if empty_age.is_some_and(|age| age >= self.empty_ns) { "empty-list-timeout" }
            else if !self.maneuvers.contains_key(&self.index) { "missing-maneuver" }
            else { "active" };
        format!("reason={reason} routeAgeMs={} emptyAgeMs={} sessionActive={live}",
            route_age.map(|age| age / 1_000_000).unwrap_or(-1), empty_age.map(|age| age / 1_000_000).unwrap_or(-1))
    }

    pub fn accept(&mut self, id: i32, payload: &[u8], now: i64, wall: i64, live: bool) -> Change {
        self.input_state = None;
        let mut offset = 0;
        while offset < payload.len() {
            if payload.len() - offset < 4 { break; }
            let len = u16(&payload[offset..]) as usize;
            if len < 4 || len > payload.len() - offset { break; }
            offset += len;
        }
        if offset != payload.len() {
            self.diagnostic_input = format!("invalid-tlv bytes={}", payload.len());
            return Change::None;
        }
        match id {
            0x5201 => self.route_update(payload, now, wall, live),
            0x5202 => self.maneuver_update(payload, now, live),
            _ => Change::None,
        }
    }

    fn route_update(&mut self, data: &[u8], now: i64, wall: i64, live: bool) -> Change {
        if self.keep_no_route {
            let mut no_route = false;
            for (id, value) in tlvs(data) { if id == 1 && !value.is_empty() { no_route = value[0] == 0; } }
            if no_route { return Change::None; }
        }
        self.updated = Some(now);
        let (mut state, mut distance, mut first) = (None, None, None);
        let (mut list_present, mut eta_present, mut remaining_present) = (false, false, false);
        for (id, value) in tlvs(data) {
            match id {
                1 if !value.is_empty() => state = Some(value[0] as i32),
                3 => self.road = utf8(value),
                4 => { self.destination = utf8(value); if self.destination == "未知位置" { self.destination.clear(); } }
                5 if value.len() >= 8 => {
                    eta_present = true;
                    self.eta_raw = Some(u64(value)).filter(|&it| it > 0);
                    self.arrival = self.eta_raw.map(|it| if it >= 1_000_000_000 { it } else { wall.wrapping_add(it) });
                }
                6 if value.len() >= 8 => { remaining_present = true; self.remaining_seconds = Some(u64(value)).filter(|&it| it >= 0); }
                7 if value.len() >= 4 => self.remaining_meters = Some(u32(value)),
                0x0a if value.len() >= 4 => distance = Some(u32(value).min(i32::MAX as i64) as i32),
                0x0d => { list_present = true; if value.len() >= 2 { first = Some(u16(value) as i32); } }
                0x10 if value.len() == 2 => self.lane_index = Some(u16(value) as i32),
                0x12 if value.len() == 1 => self.lane_showing = Some(value[0] != 0),
                _ => {}
            }
        }
        if remaining_present && (!eta_present || self.arrival.is_none()) {
            self.arrival = self.remaining_seconds.map(|it| wall.wrapping_add(it));
        }
        self.input_state = state;
        if let Some(state) = state {
            self.phone_state = Some(state); self.phone_ns = self.updated;
            if state == 1 { self.announced = true; }
        }
        self.diagnostic_input = format!("state={} next={} list={}",
            state.map(|it| it.to_string()).unwrap_or("absent".into()),
            distance.map(|it| it.to_string()).unwrap_or("absent".into()),
            if list_present { first.map(|it| it.to_string()).unwrap_or("empty".into()) } else { "absent".into() });
        if matches!(state, Some(0 | 2)) {
            return if self.clear(if state == Some(0) { "no-route" } else { "arrivedCandidate" }) { Change::Clear } else { Change::None };
        }
        if list_present && first.is_none() {
            self.empty_since.get_or_insert(now);
            return Change::None;
        }
        if first.is_some() { self.empty_since = None; }
        if state.is_some() { self.active = true; }
        if let Some(first) = first {
            if self.index >= 0 && self.index != first { self.distance_known = false; }
            self.index = first; self.active = true;
        }
        if let Some(distance) = distance { self.distance_known = true; self.distance = distance.max(0); }
        if self.current(now, live, false).is_some() { Change::Guidance } else { Change::None }
    }

    fn maneuver_update(&mut self, data: &[u8], now: i64, live: bool) -> Change {
        let (mut index, mut kind, mut side, mut road) = (None, None, 0, String::new());
        for (id, value) in tlvs(data) {
            match id {
                1 if value.len() >= 2 => index = Some(u16(value) as i32),
                3 if !value.is_empty() => kind = Some(value[0] as i32),
                4 => road = utf8(value),
                8 if !value.is_empty() => side = value[0] as i32,
                _ => {}
            }
        }
        if let (Some(index), Some(kind)) = (index, kind) { self.maneuvers.insert(index, Maneuver { kind, side, road }); }
        self.diagnostic_input = format!("index={} type={} side={side}",
            index.map(|it| it.to_string()).unwrap_or("absent".into()), kind.map(|it| it.to_string()).unwrap_or("absent".into()));
        if self.current(now, live, false).is_some() { Change::Guidance } else { Change::None }
    }
}

// Called only after the owning boundary validates all TLV lengths, before any state changes.
fn tlvs(data: &[u8]) -> impl Iterator<Item = (u16, &[u8])> {
    let mut offset = 0;
    std::iter::from_fn(move || {
        if offset == data.len() { return None; }
        let len = u16(&data[offset..]) as usize;
        let item = (u16(&data[offset + 2..]), &data[offset + 4..offset + len]);
        offset += len; Some(item)
    })
}
fn u16(data: &[u8]) -> u16 { u16::from_be_bytes(data[..2].try_into().unwrap()) }
fn u32(data: &[u8]) -> i64 { u32::from_be_bytes(data[..4].try_into().unwrap()) as i64 }
fn u64(data: &[u8]) -> i64 { i64::from_be_bytes(data[..8].try_into().unwrap()) }

fn utf8(data: &[u8]) -> String {
    let data = &data[..data.iter().position(|&b| b == 0).unwrap_or(data.len())];
    let mut result = String::new();
    let mut offset = 0;
    while offset < data.len() {
        match std::str::from_utf8(&data[offset..]) {
            Ok(value) => { result.push_str(value); break; }
            Err(error) => {
                // valid_up_to is guaranteed to end at a valid UTF-8 boundary.
                result.push_str(unsafe { std::str::from_utf8_unchecked(&data[offset..offset + error.valid_up_to()]) });
                offset += error.valid_up_to();
                // Java consumes an encoded surrogate as one malformed sequence, including a truncated prefix.
                let count = if data[offset] == 0xed && data.get(offset + 1).is_some_and(|b| (0xa0..=0xbf).contains(b)) {
                    if data.get(offset + 2).is_some_and(|b| b & 0xc0 == 0x80) { 3 } else { 2 }
                } else { error.error_len().unwrap_or(data.len() - offset) };
                result.push('\u{fffd}'); offset += count;
            }
        }
    }
    result.trim_matches(|c: char| c.is_whitespace() && c != '\u{0085}' || ('\u{001c}'..='\u{001f}').contains(&c)).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    fn tlv(id: u16, value: &[u8]) -> Vec<u8> {
        [(value.len() as u16 + 4).to_be_bytes().as_slice(), &id.to_be_bytes(), value].concat()
    }
    fn fields(values: &[(u16, &[u8])]) -> Vec<u8> { values.iter().flat_map(|(id, value)| tlv(*id, value)).collect() }
    fn route() -> RouteGuidance { RouteGuidance::new(30_000_000_000, 3_000_000_000, false) }
    fn start(state: &mut RouteGuidance, now: i64) {
        state.accept(0x5202, &fields(&[(1, &[0, 7]), (3, &[2]), (4, " 滨海路😀\0ignored".as_bytes()), (8, &[1])]), now, 0, false);
        assert_eq!(state.accept(0x5201, &fields(&[(1, &[1]), (0x0d, &[0, 7]), (0x0a, &[0, 0, 1, 94])]), now, 0, false), Change::Guidance);
    }

    #[test]
    fn cached_maneuvers_and_distance_gate_survive_turn_changes() {
        let mut state = route();
        state.accept(0x5202, &fields(&[(1, &[0, 7]), (3, &[2])]), 0, 0, false);
        assert!(state.current(0, false, false).is_none());
        state.accept(0x5201, &fields(&[(1, &[6]), (3, b" Current road\0"), (0x0d, &[0, 7])]), 0, 0, false);
        assert!(!state.announced);
        assert_eq!(state.current(0, false, false).unwrap().road, "Current road");
        assert!(state.current(0, false, true).is_none());
        state.accept(0x5201, &tlv(0x0a, &u32::MAX.to_be_bytes()), 0, 0, false);
        assert_eq!(state.current(0, false, true).unwrap().distance, i32::MAX);
        state.accept(0x5201, &tlv(0x0d, &[0, 8]), 0, 0, false);
        state.accept(0x5202, &fields(&[(1, &[0, 8]), (3, &[99]), (8, &[255])]), 0, 0, false);
        assert_eq!(state.current(0, false, false).unwrap().kind, 99);
        assert_eq!(state.current(0, false, false).unwrap().side, 255);
        assert!(state.current(0, false, true).is_none());
        state.accept(0x5201, &tlv(0x0a, &[0; 4]), 0, 0, false);
        assert_eq!(state.current(0, false, true).unwrap().distance, 0);
    }

    #[test]
    fn empty_lists_have_a_fixed_grace_period_and_do_not_evict_prefetch() {
        let mut state = route(); start(&mut state, 0);
        let empty = tlv(0x0d, &[]);
        state.accept(0x5201, &empty, 100, 0, false);
        state.accept(0x5201, &empty, 2_000_000_000, 0, false);
        assert!(state.current(3_000_000_099, true, true).is_some());
        assert!(state.current(3_000_000_100, true, false).is_none());
        assert!(state.diagnostic_state(3_000_000_100, true).contains("reason=empty-list-timeout"));
        state.accept(0x5201, &tlv(0x0d, &[0, 7]), 4_000_000_000, 0, false);
        assert_eq!(state.current(4_000_000_000, false, false).unwrap().road, "滨海路😀");
    }

    #[test]
    fn explicit_end_and_clear_reset_cache_but_keep_phone_diagnostics() {
        for (end, reason) in [(0, "no-route"), (2, "arrivedCandidate")] {
            let mut state = route(); start(&mut state, 0);
            assert_eq!(state.accept(0x5201, &tlv(1, &[end]), 10_000_000, 0, true), Change::Clear);
            assert_eq!(state.input_state, Some(end as i32));
            assert_eq!(state.phone_state, Some(end as i32));
            assert_eq!(state.phone_age_ms(11_000_000), 1);
            assert!(state.diagnostic_state(20_000_000, false).starts_with(&format!("reason={reason} ")));
            assert!(!state.clear("manual"));
            assert!(state.current(20_000_000, true, false).is_none());
            state.accept(0x5201, &fields(&[(1, &[1]), (0x0d, &[0, 7])]), 30_000_000, 0, false);
            assert!(state.current(30_000_000, false, false).is_none());
        }
    }

    #[test]
    fn ignored_no_route_does_not_refresh_retained_guidance() {
        let mut state = RouteGuidance::new(30_000_000_000, 3_000_000_000, true); start(&mut state, 0);
        let diagnostic = state.diagnostic_input.clone();
        state.accept(0x5201, &fields(&[(1, &[0]), (4, b"ignored"), (5, &99i64.to_be_bytes())]), 29_000_000_000, 1000, false);
        assert_eq!(state.diagnostic_input, diagnostic);
        assert_eq!(state.phone_state, Some(1)); assert_eq!(state.input_state, None);
        assert_eq!(state.arrival, None); assert!(state.destination.is_empty());
        assert!(state.current(30_000_000_000, false, false).is_none());
        assert_eq!(state.accept(0x5201, &tlv(1, &[2]), 30_000_000_001, 0, false), Change::Clear);
    }

    #[test]
    fn only_live_session_extends_route_timeout_not_maneuver_updates() {
        let mut state = route(); start(&mut state, 0);
        state.accept(0x5202, &fields(&[(1, &[0, 7]), (3, &[2])]), 29_000_000_000, 0, false);
        assert!(state.current(29_999_999_999, false, true).is_some());
        assert!(state.expired(30_000_000_000, false));
        assert!(state.current(30_000_000_000, false, true).is_none());
        assert!(state.current(90_000_000_000, true, true).is_some());
        assert!(!state.expired(90_000_000_000, true));
    }

    #[test]
    fn eta_duration_epoch_zero_unknown_and_signed_overflow_keep_old_rules() {
        let mut state = route(); start(&mut state, 0);
        state.accept(0x5201, &tlv(5, &100i64.to_be_bytes()), 0, 1_700_000_000, false);
        assert_eq!(state.arrival, Some(1_700_000_100)); assert_eq!(state.eta_raw, Some(100));
        state.accept(0x5201, &[], 1, 1_700_000_020, false);
        assert_eq!(state.arrival, Some(1_700_000_100));
        state.accept(0x5201, &tlv(6, &300i64.to_be_bytes()), 2, 1_700_000_020, false);
        assert_eq!(state.arrival, Some(1_700_000_320));
        state.accept(0x5201, &fields(&[(5, &1_800_000_000i64.to_be_bytes()), (6, &60i64.to_be_bytes())]), 3, 1_700_000_030, false);
        assert_eq!(state.arrival, Some(1_800_000_000));
        state.accept(0x5201, &fields(&[(5, &[0; 8]), (6, &[0; 8])]), 4, 1_700_000_040, false);
        assert_eq!(state.arrival, Some(1_700_000_040)); assert_eq!(state.eta_raw, None);
        state.accept(0x5201, &fields(&[(5, &[255; 8]), (6, &[255; 8]), (7, &[255; 4])]), 5, 0, false);
        assert_eq!(state.arrival, None); assert_eq!(state.remaining_seconds, None); assert_eq!(state.remaining_meters, Some(4_294_967_295));
        state.accept(0x5201, &tlv(6, &i64::MAX.to_be_bytes()), 6, 1, false);
        assert_eq!(state.arrival, Some(i64::MIN));
    }

    #[test]
    fn malformed_tlv_is_atomic_and_unknown_messages_only_reset_input_state() {
        let mut state = route(); start(&mut state, 0);
        let invalid = [tlv(0x0a, &[0; 4]), vec![0, 3, 0, 1]].concat();
        assert_eq!(state.accept(0x5201, &invalid, 29_000_000_000, 0, true), Change::None);
        assert_eq!(state.current(29_000_000_000, true, false).unwrap().distance, 350);
        assert_eq!(state.input_state, None); assert_eq!(state.phone_state, Some(1));
        assert_eq!(state.diagnostic_input, "invalid-tlv bytes=12");
        assert!(state.expired(30_000_000_000, false));
        state.accept(0x1234, &tlv(1, &[2]), 40_000_000_000, 0, false);
        assert_eq!(state.phone_state, Some(1));
        assert_eq!(state.diagnostic_input, "invalid-tlv bytes=12");
    }

    #[test]
    fn repeated_fields_short_values_and_lane_unknowns_match_wire_contract() {
        let mut state = route(); start(&mut state, 0);
        state.accept(0x5201, &fields(&[(0x0d, &[0, 7]), (0x0d, &[]), (0x10, &[255, 255]), (0x12, &[255]), (4, "未知位置\0".as_bytes())]), 1, 0, false);
        assert!(state.current(1, false, true).is_some());
        assert_eq!(state.lane_index, Some(65535)); assert_eq!(state.lane_showing, Some(true)); assert!(state.destination.is_empty());
        state.accept(0x5201, &fields(&[(0x10, &[1]), (0x12, &[1, 1]), (0x0a, &[0]), (6, &[0])]), 2, 0, false);
        assert_eq!(state.lane_index, Some(65535)); assert_eq!(state.lane_showing, Some(true));
        assert_eq!(state.current(2, false, true).unwrap().distance, 350);
    }

    #[test]
    fn java_utf8_replacement_nul_and_whitespace_are_preserved() {
        assert_eq!(utf8(b"a\xed\xa0\x80b"), "a\u{fffd}b");
        assert_eq!(utf8(b"a\xed\xa0b"), "a\u{fffd}b");
        assert_eq!(utf8(b"a\xed\xa0"), "a\u{fffd}");
        assert_eq!(utf8(b"\xf0\x9f\x98"), "\u{fffd}");
        assert_eq!(utf8(b"\xe0\x80\x80"), "\u{fffd}\u{fffd}\u{fffd}");
        assert_eq!(utf8("\u{001c}\u{00a0}路😀\u{2007}\u{001f}\0ignored".as_bytes()), "路😀");
        assert_eq!(utf8("\u{0085}road\u{0085}".as_bytes()), "\u{0085}road\u{0085}");
    }

    #[test]
    fn monotonic_clock_wrap_and_negative_ages_use_jvm_long_arithmetic() {
        let mut state = RouteGuidance::new(30, 3, false);
        start(&mut state, i64::MAX - 10);
        assert!(!state.expired(i64::MIN + 18, false));
        assert!(state.expired(i64::MIN + 19, false));
        assert!(!state.expired(i64::MAX - 11, false));
        assert_eq!(state.phone_age_ms((i64::MAX - 10).wrapping_sub(1_000_001)), -1);
    }
}
