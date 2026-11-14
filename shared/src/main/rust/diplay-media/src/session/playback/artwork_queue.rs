//! Bounded artwork requests and content-addressed decoded-image retention.
use ring::digest::{digest, SHA256};

pub(super) fn content_hash(bytes: &[u8]) -> [i64; 4] {
    let digest = digest(&SHA256, bytes);
    let mut hash = [0; 4];
    for (i, part) in digest.as_ref().chunks_exact(8).enumerate() {
        hash[i] = i64::from_be_bytes(part.try_into().unwrap());
    }
    hash
}

#[derive(Clone)]
struct Request { session: i64, id: i64, version: i64, hash: [i64; 4] }
#[derive(Clone)]
struct Ready { session: i64, id: i64, version: i64, image: i64 }
pub(super) struct ArtworkQueue {
    session: i64, active: bool, version: i64, latest: [i64; 256],
    pending: Vec<Request>, inflight: Option<Request>, ready: Vec<Ready>, cache: Vec<([i64; 4], i64)>,
}
impl Default for ArtworkQueue {
    fn default() -> Self {
        Self { session: 0, active: false, version: 0, latest: [0; 256], pending: Vec::new(),
            inflight: None, ready: Vec::new(), cache: Vec::new() }
    }
}
impl ArtworkQueue {
    pub fn encode(&self) -> Vec<i64> {
        let mut out = vec![self.session, self.active as i64, self.version];
        out.extend(self.latest);
        out.push(self.pending.len() as i64);
        for r in &self.pending { Self::put_request(&mut out, r); }
        out.push(self.inflight.is_some() as i64);
        if let Some(r) = &self.inflight { Self::put_request(&mut out, r); }
        out.push(self.ready.len() as i64);
        for r in &self.ready { out.extend([r.session, r.id, r.version, r.image]); }
        out.push(self.cache.len() as i64);
        for (hash, image) in &self.cache { out.extend(hash); out.push(*image); }
        out
    }
    fn put_request(out: &mut Vec<i64>, r: &Request) { out.extend([r.session, r.id, r.version]); out.extend(r.hash); }
    pub fn decode(words: &[i64]) -> Option<Self> {
        struct Reader<'a> { words: &'a [i64], at: usize }
        impl Reader<'_> {
            fn take(&mut self) -> Option<i64> { let n = *self.words.get(self.at)?; self.at += 1; Some(n) }
            fn request(&mut self) -> Option<Request> {
                let r = Request { session: self.take()?, id: self.take()?, version: self.take()?, hash: [self.take()?, self.take()?, self.take()?, self.take()?] };
                (0..=255).contains(&r.id).then_some(r)
            }
            fn count(&mut self, max: i64) -> Option<usize> { let n = self.take()?; (0..=max).contains(&n).then_some(n as usize) }
        }
        let mut r = Reader { words, at: 0 };
        let session = r.take()?; let active = r.take()?; let version = r.take()?;
        if !(0..=1).contains(&active) { return None; }
        let mut latest = [0; 256]; for v in &mut latest { *v = r.take()?; }
        let mut pending = Vec::new(); for _ in 0..r.count(4)? { pending.push(r.request()?); }
        let inflight = if r.count(1)? == 1 { Some(r.request()?) } else { None };
        let mut ready = Vec::new();
        for _ in 0..r.count(4)? {
            let entry = Ready { session: r.take()?, id: r.take()?, version: r.take()?, image: r.take()? };
            if !(0..=255).contains(&entry.id) { return None; }
            ready.push(entry);
        }
        let mut cache = Vec::new(); for _ in 0..r.count(4)? { cache.push(([r.take()?, r.take()?, r.take()?, r.take()?], r.take()?)); }
        if r.at != words.len() { return None; }
        Some(Self { session, active: active != 0, version, latest, pending, inflight, ready, cache })
    }
    fn current(&self, session: i64, id: i64, version: i64) -> bool {
        self.active && session == self.session && (0..=255).contains(&id) && self.latest[id as usize] == version
    }
    fn cached(&mut self, hash: [i64; 4]) -> Option<i64> {
        let pos = self.cache.iter().position(|(key, _)| *key == hash)?;
        let entry = self.cache.remove(pos); let image = entry.1; self.cache.push(entry); Some(image)
    }
    fn enqueue(&mut self, r: &Request, image: i64) {
        self.ready.retain(|x| x.id != r.id);
        self.ready.push(Ready { session: r.session, id: r.id, version: r.version, image });
        if self.ready.len() > 4 { self.ready.remove(0); }
    }
    pub fn event(&mut self, event: i32, session: i64, id: i64, version: i64, hash: &[i64]) -> [i64; 3] {
        match event {
            0 => { self.session = self.session.wrapping_add(1); self.active = true; self.pending.clear(); self.ready.clear(); self.cache.clear(); [self.session, 0, 0] }
            1 => { self.active = false; self.pending.clear(); self.ready.clear(); self.cache.clear(); [0, 0, 0] }
            2 if self.active && session == self.session && (0..=255).contains(&id) && hash.len() == 4 => {
                self.version = self.version.wrapping_add(1);
                self.latest[id as usize] = self.version;
                let hash: [i64; 4] = hash.try_into().unwrap();
                let r = Request { session, id, version: self.version, hash };
                self.pending.retain(|x| x.id != id);
                if let Some(image) = self.cached(hash) { self.enqueue(&r, image); }
                else { self.ready.retain(|x| x.id != id); self.pending.push(r); if self.pending.len() > 4 { self.pending.remove(0); } }
                [self.version, 0, 0]
            }
            3 => {
                while !self.pending.is_empty() {
                    let r = self.pending.remove(0);
                    if !self.current(r.session, r.id, r.version) { continue; }
                    if let Some(image) = self.cached(r.hash) { self.enqueue(&r, image); return [-1, 0, 0]; }
                    let result = [r.version, r.id, r.session]; self.inflight = Some(r); return result;
                }
                [0, 0, 0]
            }
            4 => {
                let Some(r) = self.inflight.take() else { return [0, 0, 0]; };
                if r.version != version || !self.current(r.session, r.id, r.version) { return [0, 0, 0]; }
                let image = if id == 0 { -1 } else if let Some(existing) = self.cached(r.hash) { existing } else {
                    self.cache.push((r.hash, r.version)); if self.cache.len() > 4 { self.cache.remove(0); } r.version
                };
                self.enqueue(&r, image);
                [if image == -1 { 1 } else if image == r.version { 2 } else { 3 }, image, 0]
            }
            5 => {
                if self.ready.is_empty() { return [0, 0, 0]; }
                let r = self.ready.remove(0); [r.version, r.id, r.image]
            }
            6 => [self.current(session, id, version) as i64, 0, 0],
            _ => [0, 0, 0],
        }
    }
    pub fn snapshot(&self, result: [i64; 3]) -> Vec<i64> {
        let mut out = result.to_vec(); out.push(self.pending.len() as i64);
        for r in &self.pending { out.push(r.version); }
        out.push(self.ready.len() as i64);
        for r in &self.ready { out.extend([r.version, r.id, r.image]); }
        out.push(self.cache.len() as i64);
        for (_, image) in &self.cache { out.push(*image); }
        out
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn bounded_dedup_and_session_invalidation() {
        let mut q = ArtworkQueue::default(); let session = q.event(0, 0, 0, 0, &[])[0];
        for id in 0..5 { q.event(2, session, id, 0, &content_hash(&[id as u8])); }
        assert_eq!(q.pending.len(), 4);
        let req = q.event(3, 0, 0, 0, &[]); assert_eq!(req[1], 1);
        assert_eq!(q.event(4, 0, 1, req[0], &[])[0], 2);
        q.event(2, session, 7, 0, &content_hash(&[1]));
        assert_eq!(q.ready.len(), 2); assert_eq!(q.ready[0].image, q.ready[1].image);
        let req = q.event(3, 0, 0, 0, &[]); assert_ne!(req[0], 0);
        q.event(0, 0, 0, 0, &[]);
        assert_eq!(q.event(4, 0, 1, req[0], &[])[0], 0);
        assert_eq!(ArtworkQueue::decode(&q.encode()).unwrap().encode(), q.encode());
    }
}
