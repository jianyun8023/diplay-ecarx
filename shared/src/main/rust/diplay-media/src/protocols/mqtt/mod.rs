//! The existing DIM MQTT wire contract. Socket ownership and cancellation stay with the host.
mod jni;
mod observer;
mod projection;

pub(crate) fn frame(kind: u8, body: &[u8]) -> Vec<u8> {
    let mut out = vec![kind];
    let mut remaining = body.len();
    loop {
        let digit = (remaining % 128) as u8;
        remaining /= 128;
        out.push(digit | if remaining > 0 { 0x80 } else { 0 });
        if remaining == 0 {
            break;
        }
    }
    out.extend_from_slice(body);
    out
}

fn string(out: &mut Vec<u8>, bytes: &[u8]) {
    out.extend_from_slice(&(bytes.len() as u16).to_be_bytes());
    out.extend_from_slice(bytes);
}

fn connect(client: &[u8]) -> Vec<u8> {
    let mut body = b"\0\x04MQTT\x04\x02\0\0".to_vec();
    string(&mut body, client);
    frame(0x10, &body)
}

fn publish(id: i32, topic: &[u8], payload: &[u8]) -> Vec<u8> {
    let mut body = Vec::new();
    string(&mut body, topic);
    body.extend_from_slice(&(id as u16).to_be_bytes());
    body.extend_from_slice(payload);
    frame(0x32, &body)
}

pub(crate) fn escape(value: &[u16]) -> Vec<u16> {
    let mut out = Vec::with_capacity(value.len());
    for &ch in value {
        match ch {
            34 | 92 => {
                out.push(92);
                out.push(ch);
            }
            0..=31 => out.push(32),
            _ => out.push(ch),
        }
    }
    out
}

// Preserve the old reader's three-byte remaining-length limit and caller-provided body limit.
fn length_digit(
    length: &mut usize,
    multiplier: &mut usize,
    digit: u8,
    maximum: usize,
) -> Option<bool> {
    *length += (digit & 127) as usize * *multiplier;
    if *length > maximum {
        return None;
    }
    if digit & 128 == 0 {
        return Some(true);
    }
    *multiplier *= 128;
    (*multiplier < 128 * 128 * 128).then_some(false)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn fixed_peer_packets_and_boundaries() {
        assert_eq!(connect(b"id"), b"\x10\x0e\0\x04MQTT\x04\x02\0\0\0\x02id");
        assert_eq!(publish(0x1234, b"a", b"{}"), b"\x32\x07\0\x01a\x12\x34{}");
        for size in [0, 127, 128, 16383, 16384, 2097151] {
            let wire = frame(0x30, &vec![7; size]);
            let (mut n, mut m) = (0, 1);
            for &digit in &wire[1..] {
                if length_digit(&mut n, &mut m, digit, size).unwrap() {
                    break;
                }
            }
            assert_eq!(n, size);
        }
        let (mut n, mut m) = (0, 1);
        assert_eq!(length_digit(&mut n, &mut m, 3, 2), None);
        let (mut n, mut m) = (0, 1);
        for _ in 0..2 {
            assert_eq!(length_digit(&mut n, &mut m, 128, usize::MAX), Some(false));
        }
        assert_eq!(length_digit(&mut n, &mut m, 128, usize::MAX), None);
    }
    #[test]
    fn escaping_keeps_utf16_including_unpaired_surrogates() {
        assert_eq!(
            escape(&[34, 92, 0, 10, 13, 31, 32, 0xd800]),
            [92, 34, 92, 92, 32, 32, 32, 32, 32, 0xd800]
        );
    }
}
