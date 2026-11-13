//! iAP2 link state, framing, bounded queues and retransmission timers. The host owns stream I/O.
mod jni;

use std::collections::VecDeque;

const MAX_FRAME: usize = 65535;
const HEADER: usize = 9;
const MARKER: &[u8] = &[0xff, 0x55, 2, 0, 0xee, 0x10];
const SYN: u8 = 0x80;
const ACK: u8 = 0x40;
const EXT_ACK: u8 = 0x20;
const RESET: u8 = 0x10;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Synchronization {
    pub outgoing: u8,
    pub length: u16,
    pub retransmission_ms: u16,
    pub acknowledgement_ms: u16,
    pub retransmissions: u8,
    pub acknowledgements: u8,
    pub sessions: Vec<[u8; 3]>,
}

impl Synchronization {
    pub fn encode(&self) -> Vec<u8> {
        let mut bytes = vec![1, self.outgoing];
        bytes.extend_from_slice(&self.length.to_be_bytes());
        bytes.extend_from_slice(&self.retransmission_ms.to_be_bytes());
        bytes.extend_from_slice(&self.acknowledgement_ms.to_be_bytes());
        bytes.extend_from_slice(&[self.retransmissions, self.acknowledgements]);
        bytes.extend(self.sessions.iter().flatten());
        bytes
    }

    pub fn decode(bytes: &[u8]) -> Option<Self> {
        if bytes.len() < 10 || bytes[0] != 1 { return None; }
        Some(Self {
            outgoing: bytes[1], length: u16::from_be_bytes([bytes[2], bytes[3]]),
            retransmission_ms: u16::from_be_bytes([bytes[4], bytes[5]]),
            acknowledgement_ms: u16::from_be_bytes([bytes[6], bytes[7]]),
            retransmissions: bytes[8], acknowledgements: bytes[9],
            sessions: bytes[10..].chunks_exact(3).map(|b| [b[0], b[1], b[2]]).collect(),
        })
    }
}

#[derive(Clone)]
pub struct Config {
    pub outgoing: u8,
    pub outgoing_delta: u8,
    pub acknowledgement_ms: u16,
    pub zero_acknowledgements: bool,
    pub control_version: u8,
    pub queued_packets: usize,
    pub out_of_order_packets: usize,
    pub output_bytes: usize,
    pub events: usize,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
#[repr(i32)]
pub enum State { Idle, Detecting, Negotiating, Normal, Dead }

#[derive(PartialEq, Eq, Debug)]
pub enum Event { Control(Vec<u8>), Session(u8, Vec<u8>), Writable(bool), Dead(Option<String>) }

#[derive(Clone)]
struct Packet { sequence: u8, session: u8, payload: Vec<u8>, retransmissions: i32, deadline: i64 }

#[derive(Clone, Copy)]
struct FrameHeader { length: usize, control: u8, sequence: u8, acknowledgement: u8, session: u8 }

pub struct Engine {
    config: Config,
    pub state: State,
    pub writable: bool,
    pub peer: Synchronization,
    received_sync: bool,
    sent_sequence: u8,
    sent_acknowledged: Option<u8>,
    received_in_order: u8,
    received_acknowledged: Option<u8>,
    accumulated_acknowledgements: i32,
    unacknowledged: Vec<Packet>,
    queued: VecDeque<Packet>,
    out_of_order: Vec<Packet>,
    pub events: VecDeque<Event>,
    pub output: Vec<u8>,
    receive: VecDeque<u8>,
    awaiting_payload: Option<FrameHeader>,
    // Marker, synchronization, delayed acknowledgement, retransmission; priority is contractual.
    deadlines: [Option<i64>; 4],
}

fn distance(sequence: u8, previous: Option<u8>) -> i32 {
    previous.map_or(0, |p| sequence.wrapping_sub(p) as i32)
}

impl Engine {
    pub fn new(config: Config) -> Self {
        let peer = Self::local(&config);
        Self {
            config, state: State::Idle, writable: false, peer, received_sync: false,
            sent_sequence: 99, sent_acknowledged: None, received_in_order: 0,
            received_acknowledged: None, accumulated_acknowledgements: 0,
            unacknowledged: Vec::new(), queued: VecDeque::new(), out_of_order: Vec::new(),
            events: VecDeque::new(), output: Vec::new(), receive: VecDeque::new(),
            awaiting_payload: None, deadlines: [None; 4],
        }
    }

    fn local(config: &Config) -> Synchronization {
        let zero = config.zero_acknowledgements;
        Synchronization {
            outgoing: config.outgoing, length: MAX_FRAME as u16,
            retransmission_ms: if zero { 0 } else { 4000 },
            acknowledgement_ms: if zero { 0 } else { config.acknowledgement_ms },
            retransmissions: if zero { 0 } else { 4 }, acknowledgements: if zero { 0 } else { 3 },
            sessions: vec![[10, 0, config.control_version], [11, 2, 1], [12, 1, 2]],
        }
    }

    pub fn next_deadline(&self) -> Option<i64> { self.deadlines.iter().flatten().copied().min() }

    pub fn start(&mut self, wired: bool, now: i64) {
        if self.state != State::Idle { return; }
        self.state = State::Detecting;
        if !self.append_output(MARKER) { return; }
        self.deadlines[0] = Some(now.wrapping_add(1000));
        if wired && self.state != State::Dead { self.negotiate(now); }
    }

    pub fn feed(&mut self, bytes: &[u8], now: i64) {
        let mut offset = 0;
        while offset < bytes.len() && self.state != State::Dead {
            let room = MAX_FRAME - self.receive.len();
            if room == 0 {
                if !self.parse(now) { self.die(Some(format!("iAP2 inbound frame exceeds {MAX_FRAME} bytes"))); return; }
                continue;
            }
            let count = room.min(bytes.len() - offset);
            self.receive.extend(&bytes[offset..offset + count]);
            offset += count;
            while self.parse(now) && self.state != State::Dead {}
        }
    }

    pub fn advance(&mut self, now: i64) {
        while self.state != State::Dead {
            let Some(deadline) = self.next_deadline() else { return; };
            if deadline > now { return; }
            let index = self.deadlines.iter().position(|v| v.is_some_and(|d| d <= now)).unwrap();
            self.deadlines[index] = None;
            match index {
                0 if self.state == State::Detecting => {
                    if self.append_output(MARKER) { self.deadlines[0] = Some(now.wrapping_add(1000)); }
                }
                1 if self.state == State::Negotiating => {
                    self.send_synchronization();
                    if self.state != State::Dead { self.deadlines[1] = Some(now.wrapping_add(500)); }
                }
                2 if self.state == State::Normal => {
                    self.received_acknowledged = Some(self.received_in_order);
                    self.send_acknowledgement();
                }
                3 => self.retransmit(now),
                _ => {}
            }
        }
    }

    pub fn send_session(&mut self, session: i32, bytes: &[u8], now: i64) -> Result<(), String> {
        if !(0..=255).contains(&session) { return Err("iAP2 session id must fit in one byte".into()); }
        if bytes.len() > MAX_FRAME - HEADER - 1 { return Err("iAP2 session payload exceeds 65525 bytes".into()); }
        if (self.received_sync || self.state == State::Normal) && !self.accepts(bytes.len()) {
            return Err(format!("iAP2 session payload exceeds peer maxLength {}", self.peer.length));
        }
        self.send_packet(Packet { sequence: 0, session: session as u8, payload: bytes.to_vec(), retransmissions: 0, deadline: 0 }, now);
        Ok(())
    }

    fn parse(&mut self, now: i64) -> bool {
        if self.state == State::Dead { return false; }
        if self.state == State::Detecting {
            if self.receive.len() < MARKER.len() { return false; }
            if !self.receive.iter().take(MARKER.len()).copied().eq(MARKER.iter().copied()) {
                self.die(Some("iAP2 marker was not received".into())); return false;
            }
            self.receive.drain(..MARKER.len());
            self.negotiate(now);
            return true;
        }
        if let Some(header) = self.awaiting_payload {
            let count = header.length - HEADER;
            if self.receive.len() < count { return false; }
            let mut payload: Vec<u8> = self.receive.drain(..count).collect();
            self.awaiting_payload = None;
            if payload.iter().fold(0u8, |a, b| a.wrapping_add(*b)) == 0 {
                payload.pop();
                self.process(header, Some(payload), now);
            }
            return true;
        }
        let mut discarded = false;
        while self.receive.len() >= 2 && (self.receive[0] != 0xff || self.receive[1] != 0x5a) {
            self.receive.pop_front(); discarded = true;
        }
        if self.receive.len() < HEADER { return discarded; }
        let raw: Vec<u8> = self.receive.drain(..HEADER).collect();
        if raw.iter().fold(0u8, |a, b| a.wrapping_add(*b)) != 0 { return true; }
        let header = FrameHeader {
            length: u16::from_be_bytes([raw[2], raw[3]]) as usize,
            control: raw[4], sequence: raw[5], acknowledgement: raw[6], session: raw[7],
        };
        if header.length < HEADER { return true; }
        if header.length == HEADER { self.process(header, None, now); }
        else { self.awaiting_payload = Some(header); }
        true
    }

    fn negotiate(&mut self, now: i64) {
        self.state = State::Negotiating;
        self.deadlines[0] = None;
        self.send_synchronization();
        if self.state != State::Dead { self.deadlines[1] = Some(now.wrapping_add(500)); }
    }

    fn process(&mut self, header: FrameHeader, payload: Option<Vec<u8>>, now: i64) {
        if header.control & RESET != 0 { self.die(Some("peer sent iAP2 reset".into())); return; }
        if header.control & SYN != 0 && self.state == State::Negotiating {
            if let Some(sync) = Synchronization::decode(payload.as_deref().unwrap_or(&[])) {
                self.peer = sync; self.received_sync = true;
                self.received_in_order = header.sequence;
                self.received_acknowledged = Some(header.sequence);
                self.send_acknowledgement();
            }
        }
        if header.control & ACK != 0 {
            self.accumulated_acknowledgements = self.accumulated_acknowledgements.wrapping_add(1);
            self.handle_ack(header.acknowledgement, now);
        }
        if header.control & EXT_ACK != 0 {
            if let Some(missing) = payload.as_deref() { self.handle_extended_ack(missing); }
        }
        if header.control & !ACK == 0 {
            if let Some(payload) = payload {
                self.handle_data(Packet { sequence: header.sequence, session: header.session, payload, retransmissions: 0, deadline: 0 }, now);
            }
        }
        if self.peer.acknowledgements > 0 && self.accumulated_acknowledgements >= self.peer.acknowledgements as i32 {
            self.accumulated_acknowledgements = 0;
            self.received_acknowledged = Some(self.received_in_order);
            self.send_acknowledgement();
        }
    }

    fn accepts(&self, length: usize) -> bool { self.peer.length as usize > HEADER + 1 && length <= self.peer.length as usize - HEADER - 1 }

    fn handle_ack(&mut self, ack: u8, now: i64) {
        if self.state == State::Negotiating {
            if !self.received_sync || !self.accepts(0) || self.queued.iter().any(|p| !self.accepts(p.payload.len())) {
                self.die(Some("peer iAP2 maxLength cannot carry pending control packets".into())); return;
            }
            self.state = State::Normal;
            self.deadlines[1] = None;
            self.set_writable(true);
        }
        self.sent_acknowledged = Some(ack);
        self.deadlines[3] = None;
        while let Some(first) = self.unacknowledged.first() {
            let delta = distance(first.sequence, self.sent_acknowledged);
            if delta > 0 && delta <= self.peer.acknowledgements as i32 + 10 {
                self.deadlines[3] = Some(first.deadline); break;
            }
            self.unacknowledged.remove(0);
        }
        while distance(self.sent_sequence, self.sent_acknowledged) < self.peer.outgoing as i32 {
            let Some(packet) = self.queued.pop_front() else { break; };
            self.send_packet(packet, now);
            self.set_writable(true);
        }
    }

    fn handle_extended_ack(&mut self, missing: &[u8]) {
        if self.state != State::Normal { return; }
        for index in 0..self.unacknowledged.len() {
            let packet = &mut self.unacknowledged[index];
            if !missing.contains(&packet.sequence) { continue; }
            packet.retransmissions = packet.retransmissions.wrapping_add(1);
            if packet.retransmissions == self.peer.retransmissions as i32 {
                let sequence = packet.sequence;
                self.die(Some(format!("iAP2 packet {sequence} was not acknowledged"))); return;
            }
            let packet = packet.clone();
            self.write_packet(Some(&packet.payload), packet.sequence, ACK, packet.session);
            self.deadlines[2] = None;
            self.deadlines[3] = Some(packet.deadline);
        }
    }

    fn handle_data(&mut self, packet: Packet, now: i64) {
        let delta = distance(packet.sequence, Some(self.received_in_order));
        if delta == 0 || delta > self.peer.outgoing as i32 + 10 { self.send_acknowledgement(); return; }
        if self.out_of_order.iter().any(|p| p.sequence == packet.sequence) { return; }
        if self.out_of_order.len() >= self.config.out_of_order_packets {
            self.die(Some("iAP2 out-of-order packet limit exceeded".into())); return;
        }
        let sequence = packet.sequence;
        self.out_of_order.push(packet);
        if delta > 1 {
            if delta >= self.peer.outgoing as i32 {
                let mut missing = Vec::new();
                let mut prior = self.received_in_order;
                while distance(sequence, Some(prior)) > 1 { prior = prior.wrapping_add(1); missing.push(prior); }
                self.deadlines[2] = None;
                self.write_packet(Some(&missing), self.sent_sequence, EXT_ACK, 0);
            }
            return;
        }
        let prior = self.received_in_order;
        self.out_of_order.sort_by_key(|p| distance(p.sequence, Some(prior)));
        while self.out_of_order.first().is_some_and(|p| distance(p.sequence, Some(self.received_in_order)) == 1) {
            let packet = self.out_of_order.remove(0);
            self.received_in_order = packet.sequence;
            self.enqueue(if packet.session == 10 { Event::Control(packet.payload) } else { Event::Session(packet.session, packet.payload) });
        }
        if self.peer.acknowledgements == 0 { return; }
        let window = (self.peer.outgoing as i32 - self.config.outgoing_delta as i32).max(1);
        if distance(self.received_in_order, self.received_acknowledged) >= window {
            self.deadlines[2] = None;
            self.received_acknowledged = Some(self.received_in_order);
            self.send_acknowledgement();
        } else { self.deadlines[2] = Some(now.wrapping_add(self.peer.acknowledgement_ms as i64)); }
    }

    fn send_packet(&mut self, mut packet: Packet, now: i64) {
        if self.state != State::Normal || distance(self.sent_sequence, self.sent_acknowledged) > self.peer.outgoing as i32 {
            if self.queued.len() >= self.config.queued_packets { self.die(Some("iAP2 outbound queue limit exceeded".into())); }
            else { self.queued.push_back(packet); self.set_writable(false); }
            return;
        }
        self.sent_sequence = self.sent_sequence.wrapping_add(1);
        packet.sequence = self.sent_sequence;
        packet.retransmissions = 0;
        packet.deadline = now.wrapping_add(self.peer.retransmission_ms as i64);
        self.deadlines[2] = None;
        self.write_packet(Some(&packet.payload), packet.sequence, ACK, packet.session);
        self.received_acknowledged = Some(self.received_in_order);
        if self.peer.retransmissions > 0 { self.deadlines[3] = Some(packet.deadline); self.unacknowledged.push(packet); }
        else { self.sent_acknowledged = Some(self.sent_sequence); }
    }

    fn retransmit(&mut self, now: i64) {
        if self.state != State::Normal { return; }
        let Some((index, _)) = self.unacknowledged.iter().enumerate().min_by_key(|(_, p)| p.deadline) else { return; };
        let second = self.unacknowledged.iter().enumerate().filter(|(i, _)| *i != index).map(|(_, p)| p.deadline).min();
        let packet = &mut self.unacknowledged[index];
        packet.deadline = now.wrapping_add(self.peer.retransmission_ms as i64);
        packet.retransmissions = packet.retransmissions.wrapping_add(1);
        if packet.retransmissions == self.peer.retransmissions as i32 {
            let sequence = packet.sequence;
            self.die(Some(format!("iAP2 packet {sequence} was not acknowledged"))); return;
        }
        let packet = packet.clone();
        self.write_packet(Some(&packet.payload), packet.sequence, ACK, packet.session);
        self.deadlines[3] = Some(second.unwrap_or(packet.deadline));
    }

    fn send_synchronization(&mut self) { self.write_packet(Some(&Self::local(&self.config).encode()), self.sent_sequence, SYN, 0); }
    fn send_acknowledgement(&mut self) { self.write_packet(None, self.sent_sequence, ACK, 0); }

    fn write_packet(&mut self, payload: Option<&[u8]>, sequence: u8, control: u8, session: u8) {
        if self.state == State::Dead { return; }
        self.accumulated_acknowledgements = 0;
        let length = payload.map_or(HEADER, |p| p.len() + HEADER + 1);
        let mut frame = vec![0xff, 0x5a, (length >> 8) as u8, length as u8, control, sequence, self.received_in_order, session];
        frame.push(0u8.wrapping_sub(frame.iter().fold(0u8, |a, b| a.wrapping_add(*b))));
        if let Some(payload) = payload {
            frame.extend_from_slice(payload);
            frame.push(0u8.wrapping_sub(payload.iter().fold(0u8, |a, b| a.wrapping_add(*b))));
        }
        self.append_output(&frame);
    }

    fn set_writable(&mut self, value: bool) {
        if self.writable == value { return; }
        self.writable = value;
        self.enqueue(Event::Writable(value));
    }

    pub fn die(&mut self, reason: Option<String>) {
        if self.state == State::Dead { return; }
        self.deadlines = [None; 4]; self.state = State::Dead; self.writable = false;
        self.output.clear(); self.events.clear(); self.events.push_back(Event::Dead(reason));
    }

    fn append_output(&mut self, bytes: &[u8]) -> bool {
        if self.state == State::Dead { return false; }
        if bytes.len() > self.config.output_bytes - self.output.len() {
            self.die(Some("iAP2 pending output limit exceeded".into())); return false;
        }
        self.output.extend_from_slice(bytes); true
    }

    fn enqueue(&mut self, event: Event) {
        if self.state == State::Dead { return; }
        if self.events.len() >= self.config.events { self.die(Some("iAP2 pending event limit exceeded".into())); return; }
        self.events.push_back(event);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn config() -> Config {
        Config { outgoing: 30, outgoing_delta: 0, acknowledgement_ms: 500, zero_acknowledgements: false,
            control_version: 1, queued_packets: 64, out_of_order_packets: 64, output_bytes: 1_048_576, events: 256 }
    }

    fn wire(control: u8, sequence: u8, acknowledgement: u8, session: u8, payload: &[u8]) -> Vec<u8> {
        let length = payload.len() + 10;
        let mut bytes = vec![255, 90, (length >> 8) as u8, length as u8, control, sequence, acknowledgement, session];
        bytes.push((-(bytes.iter().map(|v| *v as i32).sum::<i32>()) & 255) as u8);
        bytes.extend_from_slice(payload);
        bytes.push((-(payload.iter().map(|v| *v as i32).sum::<i32>()) & 255) as u8);
        bytes
    }

    fn established(sequence: u8) -> Engine {
        let mut engine = Engine::new(config());
        engine.start(true, 0);
        engine.output.clear();
        // Independent peer: window 8, max frame 4096, retransmit 2000 ms, delayed ACK 500 ms.
        let peer = [1, 8, 16, 0, 7, 208, 1, 244, 4, 3, 10, 0, 2, 12, 1, 2];
        engine.feed(&wire(SYN | ACK, sequence, 99, 0, &peer), 1);
        assert_eq!(engine.state, State::Normal);
        assert_eq!(engine.events.pop_front(), Some(Event::Writable(true)));
        engine.output.clear();
        engine
    }

    #[test]
    fn wired_start_has_exact_marker_header_descriptor_order_and_checksums() {
        let mut engine = Engine::new(config());
        engine.start(true, 7);
        assert_eq!(engine.output, vec![
            255, 85, 2, 0, 238, 16,
            255, 90, 0, 29, 128, 99, 0, 0, 167,
            1, 30, 255, 255, 15, 160, 1, 244, 4, 3, 10, 0, 1, 11, 2, 1, 12, 1, 2, 16,
        ]);
        assert_eq!(engine.next_deadline(), Some(507));
        engine.start(false, 10);
        assert_eq!(engine.output.len(), 35);
    }

    #[test]
    fn fragmented_sync_and_wrapped_out_of_order_delivery_keep_session_identity() {
        let mut engine = established(254);
        let later = wire(ACK, 0, 99, 12, &[8, 9]);
        for byte in later { engine.feed(&[byte], 2); }
        assert!(engine.events.is_empty());
        engine.feed(&wire(ACK, 255, 99, 10, &[7]), 3);
        assert_eq!(engine.events.pop_front(), Some(Event::Control(vec![7])));
        assert_eq!(engine.events.pop_front(), Some(Event::Session(12, vec![8, 9])));
        assert_eq!(engine.next_deadline(), Some(503));
        engine.feed(&wire(ACK, 0, 99, 12, &[8, 9]), 4);
        assert!(engine.events.is_empty());
    }

    #[test]
    fn retransmission_deadline_and_terminal_reason_are_preserved() {
        let mut engine = established(1);
        engine.send_session(10, &[1, 2, 3], 10).unwrap();
        let original = std::mem::take(&mut engine.output);
        assert_eq!(engine.next_deadline(), Some(2010));
        engine.advance(2009);
        assert!(engine.output.is_empty());
        for now in [2010, 4010, 6010] {
            engine.advance(now);
            assert_eq!(std::mem::take(&mut engine.output), original);
        }
        engine.advance(8010);
        assert_eq!(engine.state, State::Dead);
        assert_eq!(engine.events.pop_front(), Some(Event::Dead(Some("iAP2 packet 100 was not acknowledged".into()))));
        assert_eq!(engine.next_deadline(), None);
        engine.die(None);
        assert!(engine.events.is_empty());
    }

    #[test]
    fn pending_limits_and_peer_length_rejection_keep_validation_order() {
        let mut limits = config();
        limits.queued_packets = 1;
        let mut engine = Engine::new(limits);
        engine.send_session(10, &[1], 0).unwrap();
        engine.send_session(10, &[2], 0).unwrap();
        assert_eq!(engine.events.pop_back(), Some(Event::Dead(Some("iAP2 outbound queue limit exceeded".into()))));
        let mut engine = established(1);
        assert_eq!(engine.send_session(-1, &vec![0; 65526], 0).unwrap_err(), "iAP2 session id must fit in one byte");
        assert_eq!(engine.send_session(10, &vec![0; 65526], 0).unwrap_err(), "iAP2 session payload exceeds 65525 bytes");
        assert_eq!(engine.send_session(10, &vec![0; 4087], 0).unwrap_err(), "iAP2 session payload exceeds peer maxLength 4096");
        let mut limits = config(); limits.output_bytes = 5;
        let mut engine = Engine::new(limits);
        engine.start(true, 0);
        assert_eq!(engine.events.pop_front(), Some(Event::Dead(Some("iAP2 pending output limit exceeded".into()))));
        assert!(engine.output.is_empty());
    }

    #[test]
    fn wireless_marker_deadlines_bad_payload_checksum_and_reset_remain_distinct() {
        let mut detecting = Engine::new(config());
        detecting.start(false, 10);
        detecting.output.clear();
        detecting.advance(1009);
        assert!(detecting.output.is_empty());
        detecting.advance(1010);
        assert_eq!(detecting.output, MARKER);
        detecting.feed(&[0; 6], 1011);
        assert_eq!(detecting.events.pop_front(), Some(Event::Dead(Some("iAP2 marker was not received".into()))));
        let mut engine = established(1);
        let mut bad = wire(ACK, 2, 99, 10, &[1]);
        *bad.last_mut().unwrap() ^= 1;
        engine.feed(&bad, 2);
        assert!(engine.events.is_empty());
        engine.feed(&wire(RESET, 2, 99, 0, &[]), 3);
        assert_eq!(engine.events.pop_front(), Some(Event::Dead(Some("peer sent iAP2 reset".into()))));
        engine.feed(&[0; 100], 4);
        assert_eq!(engine.state, State::Dead);
    }

    #[test]
    fn synchronization_requires_version_and_ignores_only_incomplete_descriptor_tail() {
        assert!(Synchronization::decode(&[1; 9]).is_none());
        assert!(Synchronization::decode(&[2; 10]).is_none());
        let bytes = [1, 8, 16, 0, 7, 208, 1, 244, 4, 3, 12, 1, 2, 42, 42];
        let sync = Synchronization::decode(&bytes).unwrap();
        assert_eq!(sync.sessions, vec![[12, 1, 2]]);
        assert_eq!(sync.encode(), bytes[..13]);
    }
}
