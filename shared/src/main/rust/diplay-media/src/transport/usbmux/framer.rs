use super::{u16_at, u32_at, TCP_HEADER};
const HEADER: usize = 16;
const MAX_FRAME: usize = 65536;

pub(super) struct Frame {
    pub protocol: i32,
    pub length: i32,
    pub word: i32,
    pub sequence: u16,
    pub payload: Vec<u8>,
}
pub(super) enum Read {
    More,
    Frame(Frame),
    Report(String),
    Rejected(i32, String),
}
pub(super) struct Framer {
    pub bytes: Vec<u8>,
    previous: Option<(i32, i32)>,
    reports: i32,
    last_read: usize,
}
impl Framer {
    pub fn new() -> Self {
        Self {
            bytes: Vec::new(),
            previous: None,
            reports: 0,
            last_read: 0,
        }
    }
    pub fn append(&mut self, transfer: &[u8]) {
        self.last_read = transfer.len();
        self.bytes.extend_from_slice(transfer);
    }
    pub fn take(&mut self) -> Read {
        let bytes = &self.bytes;
        if bytes.len() < HEADER {
            return Read::More;
        }
        let length = u32_at(bytes, 4) as i32;
        if !(HEADER as i32..=MAX_FRAME as i32).contains(&length) {
            let first = u32_at(bytes, 0);
            if self.previous.is_some() && first != 6 && bytes.len() < 20 {
                return Read::More;
            }
            let protocol = u32_at(bytes, 4);
            let following = u32_at(bytes, 8) as usize;
            let candidate =
                self.previous.is_some() && first != 6 && u32_at(bytes, 12) == 0xfaceface;
            let valid = if candidate
                && protocol == 6
                && (HEADER + TCP_HEADER..=MAX_FRAME).contains(&following)
            {
                if bytes.len() < 4 + HEADER + TCP_HEADER {
                    return Read::More;
                }
                let header = ((bytes[4 + HEADER + 12] >> 4) as usize) * 4;
                (TCP_HEADER..=following - HEADER).contains(&header)
                    && u16_at(bytes, 4 + HEADER) != 0
                    && u16_at(bytes, 4 + HEADER + 2) != 0
            } else if candidate
                && protocol == 1
                && (HEADER + 2..=HEADER + 1024).contains(&following)
            {
                if bytes.len() < 4 + following {
                    return Read::More;
                }
                diagnostic(&bytes[4 + HEADER..4 + following])
            } else {
                self.previous.is_some()
                    && first != 6
                    && protocol == 0
                    && following == 20
                    && u32_at(bytes, 12) == 2
            };
            if let Some((old_protocol, old_length)) = self.previous.filter(|_| valid) {
                self.bytes.drain(..4);
                self.previous = None;
                let report = self.reports < 4;
                self.reports = self.reports.wrapping_add(1);
                if report {
                    return Read::Report(format!("USBMUX optional reply padding skipped bytes=4 previousProtocol={old_protocol} previousLength={old_length} nextProtocol={protocol} nextLength={following} lastUsbReadBytes={} bufferedBytes={}", self.last_read, self.bytes.len()));
                }
                return self.take();
            }
            return Read::Rejected(length, format!("USBMUX framing rejected declaredLength={length} bufferedBytes={} lastUsbReadBytes={} optionalReplyPadding={}", self.bytes.len(), self.last_read, self.previous.is_some()));
        }
        let length = length as usize;
        if bytes.len() < length {
            return Read::More;
        }
        let frame = Frame {
            protocol: u32_at(bytes, 0) as i32,
            length: length as i32,
            word: u32_at(bytes, 8) as i32,
            sequence: u16_at(bytes, 12),
            payload: bytes[HEADER..length].to_vec(),
        };
        self.bytes.drain(..length);
        self.previous = can_pad(&frame).then_some((frame.protocol, frame.length));
        Read::Frame(frame)
    }
}
fn diagnostic(payload: &[u8]) -> bool {
    (2..=1024).contains(&payload.len())
        && payload[0] == 4
        && payload[1..].iter().all(|b| (0x20..=0x7e).contains(b))
}
fn can_pad(frame: &Frame) -> bool {
    if frame.protocol == 0 && frame.length == 20 && frame.word == 2 {
        return true;
    }
    if frame.word as u32 != 0xfaceface {
        return false;
    }
    if frame.protocol == 1 {
        return diagnostic(&frame.payload);
    }
    if frame.protocol != 6 || frame.payload.len() < TCP_HEADER {
        return false;
    }
    let header = ((frame.payload[12] >> 4) as usize) * 4;
    (TCP_HEADER..=frame.payload.len()).contains(&header)
        && u16_at(&frame.payload, 0) != 0
        && u16_at(&frame.payload, 2) != 0
}

#[cfg(test)]
mod tests {
    use super::*;
    fn hex(text: &str) -> Vec<u8> {
        text.split_whitespace()
            .map(|v| u8::from_str_radix(v, 16).unwrap())
            .collect()
    }
    #[test]
    fn issue100_exact_private_remainders_and_following_payload_survive() {
        let version =
            hex("00 00 00 00 00 00 00 14 00 00 00 02 00 00 00 00 49 28 73 00 00 00 00 00");
        let syn = hex("00 00 00 06 00 00 00 24 fa ce fa ce 00 00 00 01 f2 7e 00 01 00 00 00 00 00 00 00 01 5f 12 02 00 7f 00 00 01 6d 43 6f 6e");
        let mut framer = Framer::new();
        framer.append(&version);
        assert!(matches!(framer.take(), Read::Frame(_)));
        assert_eq!(framer.bytes, [0; 4]);
        framer.append(&syn);
        assert!(matches!(framer.take(), Read::Report(_)));
        assert!(matches!(framer.take(), Read::Frame(_)));
        assert_eq!(framer.bytes, b"mCon");
        framer.append(&syn[..36]);
        assert!(matches!(framer.take(), Read::Report(_)));
        let Read::Frame(frame) = framer.take() else {
            panic!("following normal TCP frame must survive")
        };
        assert_eq!(frame.payload, syn[16..36]);
        assert!(framer.bytes.is_empty());
    }
}
