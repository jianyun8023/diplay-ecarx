use super::segment;
use std::collections::VecDeque;

pub(super) const NOTIFY: i32 = 16;
pub(super) const REMOVE: i32 = 32;
// Lower nibble describes a platform effect, not a second state machine in Kotlin.
pub(super) const ACK_CONNECT: i32 = 1;
pub(super) const ACK_DATA: i32 = 2;
pub(super) const ACK_FIN: i32 = 3;
pub(super) const RESET: i32 = 4;
pub(super) const FROZEN_HEADER: usize = 24;
enum Pending {
    Connect,
    Data(bool),
    Fin,
}
pub(super) struct Tcp {
    pub source: u16,
    pub destination: u16,
    pub sequence: u32,
    pub ack: u32,
    pub connected: bool,
    pub closed: bool,
    pub failure: u8,
    pub received: VecDeque<Vec<u8>>,
    pending: Option<Pending>,
}
impl Tcp {
    pub fn new(source: u16, destination: u16) -> Self {
        Self {
            source,
            destination,
            sequence: 0,
            ack: 0,
            connected: false,
            closed: false,
            failure: 0,
            received: VecDeque::new(),
            pending: None,
        }
    }
    pub fn status(&self) -> i32 {
        i32::from(self.connected)
            | (i32::from(self.closed) << 1)
            | (i32::from(!self.received.is_empty()) << 2)
            | ((self.failure as i32) << 8)
    }
    pub fn control(&self, command: i32) -> Vec<u8> {
        super::control(
            self.source,
            self.destination,
            self.sequence,
            self.ack,
            command,
        )
    }
    pub fn data(&self, payload: &[u8]) -> Vec<u8> {
        segment(
            self.source,
            self.destination,
            self.sequence,
            self.ack,
            0x10,
            payload,
        )
    }
    pub fn receive(&mut self, flags: u8, sequence: u32, payload: Vec<u8>) -> i32 {
        // Unlike LIVI, our existing implementation gives RST priority over SYN+ACK.
        if flags & 4 != 0 {
            self.failure = 1;
            self.closed = true;
            return RESET | NOTIFY | REMOVE;
        }
        if self.closed {
            return 0;
        }
        if flags & 0x12 == 0x12 {
            self.sequence = self.sequence.wrapping_add(1);
            self.ack = sequence.wrapping_add(1);
            self.pending = Some(Pending::Connect);
            return ACK_CONNECT;
        }
        if !payload.is_empty() {
            self.ack = self.ack.wrapping_add(payload.len() as u32);
            self.received.push_back(payload);
            self.pending = Some(Pending::Data(flags & 1 != 0));
            return ACK_DATA | NOTIFY;
        }
        if flags & 1 != 0 {
            self.ack = self.ack.wrapping_add(1);
            self.pending = Some(Pending::Fin);
            return ACK_FIN;
        }
        0
    }
    pub fn ack_sent(&mut self) -> i32 {
        match self.pending.take() {
            Some(Pending::Connect) if !self.closed => {
                self.connected = true;
                NOTIFY
            }
            Some(Pending::Data(true)) if !self.closed => {
                self.ack = self.ack.wrapping_add(1);
                self.pending = Some(Pending::Fin);
                ACK_FIN
            }
            Some(Pending::Fin) => {
                self.closed = true;
                NOTIFY | REMOVE
            }
            _ => 0,
        }
    }
    pub fn recv(&mut self, max: usize) -> Option<Vec<u8>> {
        let Some(mut packet) = self.received.pop_front() else {
            return self.closed.then(Vec::new);
        };
        if packet.len() > max {
            let rest = packet.split_off(max);
            self.received.push_front(rest);
        }
        Some(packet)
    }
    // 0: finalize only; 2: FIN; 3: reset. Kotlin serializes final retirement with writes.
    pub fn close_plan(&mut self, abort: bool) -> i32 {
        if self.closed {
            return 0;
        }
        self.closed = true;
        if abort {
            3
        } else if self.connected {
            2
        } else {
            0
        }
    }
    pub fn host_close(&mut self, failed: bool) -> bool {
        if self.closed {
            return false;
        }
        self.closed = true;
        if failed {
            self.failure = 2;
        }
        true
    }
    pub fn freeze(&self) -> Vec<u8> {
        let mut bytes = Vec::with_capacity(
            FROZEN_HEADER + self.received.iter().map(|p| p.len() + 4).sum::<usize>(),
        );
        bytes.extend_from_slice(&self.source.to_be_bytes());
        bytes.extend_from_slice(&self.destination.to_be_bytes());
        bytes.extend_from_slice(&self.sequence.to_be_bytes());
        bytes.extend_from_slice(&self.ack.to_be_bytes());
        bytes.extend_from_slice(&[u8::from(self.connected), self.failure, 0, 0]);
        bytes.extend_from_slice(&(FROZEN_HEADER as u32).to_be_bytes());
        bytes.extend_from_slice(&0u32.to_be_bytes());
        for packet in &self.received {
            bytes.extend_from_slice(&(packet.len() as u32).to_be_bytes());
            bytes.extend_from_slice(packet);
        }
        bytes
    }
}
