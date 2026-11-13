//! Incremental NowPlaying metadata and renderer activity/route deadlines. JVM owns clocks and text decoding.
mod jni;
use crate::protocols::iap2_body::parse_parameter_list;

#[derive(Clone, Debug, Default, PartialEq)]
struct NowPlaying {
    text: [Option<Vec<u16>>; 4],
    // Artwork ID, duration ms, elapsed ms; -1 means absent on the wire.
    numbers: [Option<i64>; 3],
    playing: bool,
}
impl NowPlaying {
    fn encode(&self) -> Vec<i64> {
        let mut out: Vec<i64> = self.numbers.iter().map(|v| v.unwrap_or(-1)).collect();
        out.push(self.playing as i64);
        for value in &self.text {
            out.push(value.as_ref().map_or(-1, |v| v.len() as i64));
            if let Some(value) = value {
                out.extend(value.iter().map(|&v| v as i64));
            }
        }
        out
    }
    fn decode(words: &[i64]) -> Option<Self> {
        if words.len() < 8
            || !(-1..=255).contains(&words[0])
            || words[1..3]
                .iter()
                .any(|&v| !(-1..=u32::MAX as i64).contains(&v))
            || !(0..=1).contains(&words[3])
        {
            return None;
        }
        let mut state = Self {
            numbers: [None; 3],
            playing: words[3] != 0,
            ..Self::default()
        };
        for (to, &value) in state.numbers.iter_mut().zip(words) {
            *to = (value >= 0).then_some(value);
        }
        let mut cursor = 4;
        for text in &mut state.text {
            let length = *words.get(cursor)?;
            cursor += 1;
            if length == -1 {
                continue;
            }
            if length < 0 || length as u64 > words.len().saturating_sub(cursor) as u64 {
                return None;
            }
            let end = cursor + length as usize;
            *text = Some(
                words[cursor..end]
                    .iter()
                    .map(|&v| u16::try_from(v).ok())
                    .collect::<Option<_>>()?,
            );
            cursor = end;
        }
        (cursor == words.len()).then_some(state)
    }
    fn accept(
        &mut self,
        id: i32,
        payload: &[u8],
        mut text: impl FnMut(&[u8]) -> Option<Vec<u16>>,
    ) -> bool {
        if id != 0x5001 {
            return false;
        }
        let Ok(body) = parse_parameter_list(payload) else {
            return false;
        };
        let previous = self.clone();
        for (group, string_fields, number_fields) in [
            (
                0,
                &[(1, 0), (6, 1), (12, 2)][..],
                &[(26, 0, 1), (4, 1, 4)][..],
            ),
            (1, &[(7, 3)][..], &[(1, 2, 4)][..]),
        ] {
            let Some((_, raw)) = body.iter().find(|(id, _)| *id == group) else {
                continue;
            };
            let Ok(fields) = parse_parameter_list(raw) else {
                continue;
            };
            for &(id, index) in string_fields {
                if let Some((_, bytes)) = fields.iter().find(|(field, _)| *field == id) {
                    // A scalar string drops only the final terminator, preserving embedded NULs.
                    if bytes.last() == Some(&0) {
                        self.text[index] = text(&bytes[..bytes.len() - 1]);
                    }
                }
            }
            for &(id, index, width) in number_fields {
                if let Some((_, bytes)) = fields.iter().find(|(field, _)| *field == id) {
                    if bytes.len() == width {
                        self.numbers[index] = Some(bytes.iter().fold(0, |v, &b| v << 8 | b as i64));
                    }
                }
            }
            if group == 1 {
                if let Some((_, bytes)) = fields.iter().find(|(id, _)| *id == 0) {
                    if bytes.len() == 1 {
                        self.playing = bytes[0] == 1;
                    }
                }
            }
        }
        *self != previous
    }
    fn clear(&mut self, all: bool) -> bool {
        let previous = self.clone();
        if all {
            *self = Self::default();
        } else {
            self.playing = false;
        }
        *self != previous
    }
}

struct Entry {
    id: i64,
    stream: Option<i32>,
    until: i64,
    priority: bool,
    played: bool,
}
struct Navigation {
    next: i64,
    entries: Vec<Entry>,
}
impl Navigation {
    fn encode(&self) -> Vec<i64> {
        let mut words = vec![self.next];
        for entry in &self.entries {
            words.extend([
                entry.id,
                entry.stream.map_or(i64::MIN, i64::from),
                entry.until,
                entry.priority as i64,
                entry.played as i64,
            ]);
        }
        words
    }
    fn decode(words: &[i64]) -> Option<Self> {
        if words.is_empty() || (words.len() - 1) % 5 != 0 {
            return None;
        }
        let mut entries = Vec::new();
        for row in words[1..].chunks_exact(5) {
            if row[1] != i64::MIN && row[1] != row[1] as i32 as i64
                || !(0..=1).contains(&row[3])
                || !(0..=1).contains(&row[4])
            {
                return None;
            }
            entries.push(Entry {
                id: row[0],
                stream: (row[1] != i64::MIN).then_some(row[1] as i32),
                until: row[2],
                priority: row[3] != 0,
                played: row[4] != 0,
            });
        }
        Some(Self {
            next: words[0],
            entries,
        })
    }
    fn open(&mut self) -> i64 {
        let id = self.next;
        self.next = self.next.wrapping_add(1);
        self.entries.push(Entry {
            id,
            stream: None,
            until: 0,
            priority: false,
            played: false,
        });
        id
    }
    fn contains(&self, id: i64) -> bool {
        self.entries.iter().any(|entry| entry.id == id)
    }
    fn played(
        &mut self,
        id: i64,
        stream: Option<i32>,
        now: i64,
        buffered: i64,
        tail: i64,
        priority: bool,
    ) {
        if let Some(entry) = self.entries.iter_mut().find(|entry| entry.id == id) {
            entry.stream = stream;
            entry.until = now.wrapping_add(buffered.max(0)).wrapping_add(tail);
            entry.priority = priority;
            entry.played = true;
        }
    }
    fn close(&mut self, id: i64) {
        self.entries.retain(|entry| entry.id != id);
    }
    fn snapshot(&mut self, now: i64) -> [i64; 4] {
        // Expiry releases activity, but a live renderer may play again with the same token.
        for entry in &mut self.entries {
            if entry.played && entry.until <= now {
                entry.played = false;
            }
        }
        let navigation: Vec<_> = self
            .entries
            .iter()
            .filter(|entry| entry.played && !entry.priority)
            .collect();
        let route = navigation.first().and_then(|first| {
            first
                .stream
                .filter(|_| navigation.iter().all(|entry| entry.stream == first.stream))
        });
        [
            (!navigation.is_empty()) as i64,
            route.is_some() as i64,
            route.unwrap_or(0) as i64,
            self.entries
                .iter()
                .any(|entry| entry.played && entry.priority) as i64,
        ]
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocols::iap2_body::encode_parameter_list;
    #[test]
    fn malformed_fields_preserve_previous_values_and_first_parameter_wins() {
        let mut state = NowPlaying::default();
        let body = |fields: Vec<(u16, Vec<u8>)>| {
            encode_parameter_list(&[(0, encode_parameter_list(&fields).unwrap())]).unwrap()
        };
        let decode = |bytes: &[u8]| Some(String::from_utf8_lossy(bytes).encode_utf16().collect());
        assert!(state.accept(
            0x5001,
            &body(vec![(1, b"song\0".to_vec()), (4, vec![0, 0, 0, 7])]),
            decode
        ));
        assert!(!state.accept(
            0x5001,
            &body(vec![
                (1, b"bad".to_vec()),
                (1, b"other\0".to_vec()),
                (4, vec![9])
            ]),
            decode
        ));
        assert_eq!(NowPlaying::decode(&state.encode()), Some(state.clone()));
        assert!(state.accept(0x5001, &body(vec![(1, b"A\0B\0".to_vec())]), decode));
        assert_eq!(state.text[0], Some(vec![65, 0, 66]));
        assert!(state.clear(true));
        assert!(!state.clear(true));
    }
    #[test]
    fn renderer_expiry_route_conflicts_and_close_preserve_live_owners() {
        let mut state = Navigation {
            next: 1,
            entries: Vec::new(),
        };
        let first = state.open();
        let second = state.open();
        assert_eq!(state.snapshot(0), [0, 0, 0, 0]);
        state.played(first, Some(14), 0, 500, 900, false);
        state.played(second, None, 0, 0, 900, true);
        assert_eq!(state.snapshot(899), [1, 1, 14, 1]);
        assert_eq!(state.snapshot(900), [1, 1, 14, 0]);
        assert_eq!(state.snapshot(1400), [0, 0, 0, 0]);
        state.played(second, None, 1400, 0, 900, false);
        state.played(first, Some(14), 1400, -1, 900, false);
        assert_eq!(state.snapshot(1400), [1, 0, 0, 0]);
        state.close(second);
        assert!(!state.contains(second));
        assert_eq!(state.snapshot(1400), [1, 1, 14, 0]);
        assert_eq!(
            Navigation::decode(&state.encode()).unwrap().snapshot(1400),
            [1, 1, 14, 0]
        );
    }
}
