//! USBMUX/TCP protocol owner over Android-owned USB I/O. Compared with LIVI mux.rs at
//! 6ac88bab7351a39b0cffac20c5dcf92ca848319b; ported from our Kotlin behavior, not its Tokio runtime.
mod framer;
mod jni;
mod tcp;

const TCP_HEADER: usize = 20;
fn u16_at(bytes: &[u8], at: usize) -> u16 {
    u16::from_be_bytes(bytes[at..at + 2].try_into().unwrap())
}
fn u32_at(bytes: &[u8], at: usize) -> u32 {
    u32::from_be_bytes(bytes[at..at + 4].try_into().unwrap())
}

struct Host {
    sequence: u16,
    acknowledgement: u16,
    next_port: u16,
    stale: u32,
}
impl Host {
    fn new() -> Self {
        Self {
            sequence: 0,
            acknowledgement: 0,
            next_port: 1,
            stale: 0,
        }
    }
    fn version() -> Vec<u8> {
        let mut bytes = vec![0; 20];
        bytes[4..8].copy_from_slice(&20u32.to_be_bytes());
        bytes[8..12].copy_from_slice(&2u32.to_be_bytes());
        bytes
    }
    // 0 accepts, 1 discards one stale TCP packet, 2 rejects; timeout remains an I/O concern.
    fn handshake(&mut self, protocol: i32, length: i32, word: i32) -> i32 {
        if protocol == 0 && length == 20 && word == 2 {
            return 0;
        }
        if protocol != 6 {
            return 2;
        }
        self.stale += 1;
        if self.stale > 32 {
            2
        } else {
            1
        }
    }
    fn frame(&self, protocol: i32, payload: &[u8]) -> Vec<u8> {
        let mut frame = Vec::with_capacity(16 + payload.len());
        frame.extend_from_slice(&protocol.to_be_bytes());
        frame.extend_from_slice(&((16 + payload.len()) as u32).to_be_bytes());
        frame.extend_from_slice(&0xfeedfaceu32.to_be_bytes());
        frame.extend_from_slice(&self.sequence.to_be_bytes());
        frame.extend_from_slice(&self.acknowledgement.to_be_bytes());
        frame.extend_from_slice(payload);
        frame
    }
    fn port(&mut self, active: &[i32]) -> Option<u16> {
        let active: std::collections::HashSet<i32> = active.iter().copied().collect();
        for _ in 0..65535 {
            let port = self.next_port;
            self.next_port = if port == u16::MAX { 1 } else { port + 1 };
            if !active.contains(&(port as i32)) {
                return Some(port);
            }
        }
        None
    }
}

fn segment(
    source: u16,
    destination: u16,
    sequence: u32,
    ack: u32,
    flags: u8,
    payload: &[u8],
) -> Vec<u8> {
    let mut frame = Vec::with_capacity(TCP_HEADER + payload.len());
    frame.extend_from_slice(&source.to_be_bytes());
    frame.extend_from_slice(&destination.to_be_bytes());
    frame.extend_from_slice(&sequence.to_be_bytes());
    frame.extend_from_slice(&ack.to_be_bytes());
    frame.extend_from_slice(&[0x50, flags, 2, 0, 0, 0, 0, 0]);
    frame.extend_from_slice(payload);
    frame
}
fn control(source: u16, destination: u16, sequence: u32, ack: u32, command: i32) -> Vec<u8> {
    let flags = match command {
        1 => 0x02,
        2 => 0x11,
        3 => 0x14,
        _ => 0x10,
    };
    segment(source, destination, sequence, ack, flags, &[])
}
fn parse_tcp(frame: &[u8]) -> Result<([i32; 5], &[u8]), &'static str> {
    if frame.len() < TCP_HEADER {
        return Err("USBMUX TCP frame is shorter than its header");
    }
    let header = ((frame[12] >> 4) as usize) * 4;
    if header < TCP_HEADER || header > frame.len() {
        return Err("Invalid USBMUX TCP header length");
    }
    Ok((
        [
            u16_at(frame, 2) as i32,
            frame[13] as i32,
            u32_at(frame, 4) as i32,
            u32_at(frame, 8) as i32,
            u16_at(frame, 14) as i32,
        ],
        &frame[header..],
    ))
}
