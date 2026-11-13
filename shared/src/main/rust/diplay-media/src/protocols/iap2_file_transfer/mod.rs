//! iAP2 v2 artwork transfer owner; Android retains only opaque GC buffers and the UI callback.
//! Compared with LIVI 6ac88bab7351a39b0cffac20c5dcf92ca848319b iap2-link/lib.rs:
//! its link emits raw session-12 FileTransfer events. No receiver or golden transcript is copied.
mod jni;

const MAX_PENDING: usize = 4;
const WORDS: usize = 2 + MAX_PENDING * 4;
struct Entry {
    id: u8,
    expected: usize,
    written: usize,
    slot: usize,
}
struct Receiver {
    maximum: usize,
    entries: Vec<Entry>,
}
#[derive(Default)]
struct Outcome {
    replies: Vec<[u8; 2]>,
    // None means no UI update; Some(id,None) clears artwork; Some(id,slot) transfers a buffer.
    completed: Option<(u8, Option<usize>)>,
    retired: Vec<usize>,
    allocate: Option<(usize, usize)>,
    append: Option<(usize, usize, usize)>,
}
impl Receiver {
    fn decode(words: &[i32]) -> Self {
        Self {
            maximum: words[0] as usize,
            entries: (0..words[1] as usize)
                .map(|i| {
                    let p = 2 + i * 4;
                    Entry {
                        id: words[p] as u8,
                        expected: words[p + 1] as usize,
                        written: words[p + 2] as usize,
                        slot: words[p + 3] as usize,
                    }
                })
                .collect(),
        }
    }
    fn encode(&self) -> [i32; WORDS] {
        let mut words = [0; WORDS];
        words[0] = self.maximum as i32;
        words[1] = self.entries.len() as i32;
        for (i, e) in self.entries.iter().enumerate() {
            let p = 2 + i * 4;
            words[p..p + 4].copy_from_slice(&[
                e.id as i32,
                e.expected as i32,
                e.written as i32,
                e.slot as i32,
            ]);
        }
        words
    }
    fn remove(&mut self, index: usize, out: &mut Outcome) -> Entry {
        let entry = self.entries.remove(index);
        out.retired.push(entry.slot);
        entry
    }
    fn accept(&mut self, header: &[u8], length: usize) -> Outcome {
        let mut out = Outcome::default();
        if length < 2 {
            return out;
        }
        let id = header[0];
        let flags = header[1];
        let index = self.entries.iter().position(|e| e.id == id);
        match flags & 15 {
            4 => {
                if let Some(i) = index {
                    self.remove(i, &mut out);
                }
                if length < 12 {
                    out.replies.push([id, 2]);
                    return out;
                }
                let size = u64::from_be_bytes(header[2..10].try_into().unwrap());
                let kind = u16::from_be_bytes(header[10..12].try_into().unwrap());
                if kind != 2 {
                    out.replies.push([id, 2]);
                    return out;
                }
                if size > self.maximum as u64 {
                    out.replies.push([id, 2]);
                    out.completed = Some((id, None));
                    return out;
                }
                out.replies.push([id, 1]);
                if size == 0 {
                    out.replies.push([id, 5]);
                    out.completed = Some((id, None));
                    return out;
                }
                if self.entries.len() == MAX_PENDING {
                    self.remove(0, &mut out);
                }
                let slot = (0..MAX_PENDING)
                    .find(|s| self.entries.iter().all(|e| e.slot != *s))
                    .unwrap();
                self.entries.push(Entry {
                    id,
                    expected: size as usize,
                    written: 0,
                    slot,
                });
                out.allocate = Some((slot, size as usize));
            }
            0 => {
                let index = match index {
                    Some(i) => i,
                    None => return out,
                };
                let e = &mut self.entries[index];
                let count = length - 2;
                if count > e.expected - e.written {
                    self.remove(index, &mut out);
                    out.replies.push([id, 2]);
                    out.completed = Some((id, None));
                    return out;
                }
                out.append = Some((e.slot, e.written, count));
                e.written += count;
                if flags & 0x40 == 0 && e.written < e.expected {
                    return out;
                }
                let e = self.remove(index, &mut out);
                if e.written == e.expected {
                    out.replies.push([id, 5]);
                    out.completed = Some((id, Some(e.slot)));
                } else {
                    out.replies.push([id, 2]);
                    out.completed = Some((id, None));
                }
            }
            2 => {
                if let Some(i) = index {
                    self.remove(i, &mut out);
                    out.completed = Some((id, None));
                }
            }
            _ => {}
        }
        out
    }
}
