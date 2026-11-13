//! Shared pairing TLV contract: incomplete trailing items are ignored, not partially retained.
use std::collections::HashMap;

pub(super) fn decode(buffer: &[u8]) -> HashMap<u8, Vec<u8>> {
    let mut output = HashMap::<u8, Vec<u8>>::new();
    let (mut position, mut previous) = (0, None);
    while buffer.len() - position >= 2 {
        let (kind, len) = (buffer[position], buffer[position + 1] as usize);
        position += 2;
        if len > buffer.len() - position {
            break;
        }
        let value = &buffer[position..position + len];
        if previous == Some((kind, 255)) {
            output.entry(kind).or_default().extend_from_slice(value);
        } else {
            output.insert(kind, value.to_vec());
        }
        position += len;
        previous = Some((kind, len));
    }
    output
}

pub(super) fn encode(items: &[(u8, &[u8])]) -> Vec<u8> {
    let mut output = Vec::new();
    let mut previous = None;
    for &(kind, value) in items {
        if previous == Some(kind) {
            output.extend_from_slice(&[255, 0]);
        }
        if value.is_empty() {
            output.extend_from_slice(&[kind, 0]);
        }
        for chunk in value.chunks(255) {
            output.extend_from_slice(&[kind, chunk.len() as u8]);
            output.extend_from_slice(chunk);
        }
        previous = Some(kind);
    }
    output
}

