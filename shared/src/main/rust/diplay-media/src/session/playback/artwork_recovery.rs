//! Track identity and the one-attempt initial artwork recovery budget.
#[derive(Clone, Debug, Default, PartialEq)]
pub(super) struct Metadata {
    pub numbers: [Option<i64>; 3],
    pub playing: bool,
    pub text: [Option<Vec<u16>>; 4],
}
impl Metadata {
    pub fn decode(words: &[i64]) -> Option<Self> {
        if words.len() < 11 { return None; }
        let mut value = Self::default();
        for i in 0..3 {
            if !(0..=1).contains(&words[i * 2]) { return None; }
            value.numbers[i] = (words[i * 2] != 0).then_some(words[i * 2 + 1]);
        }
        if !(0..=1).contains(&words[6]) { return None; }
        value.playing = words[6] != 0;
        let mut at = 7;
        for text in &mut value.text {
            let len = *words.get(at)?;
            at += 1;
            if len == -1 { continue; }
            let len = usize::try_from(len).ok()?;
            let end = at.checked_add(len)?;
            let part = words.get(at..end)?;
            *text = Some(part.iter().map(|&v| u16::try_from(v).ok()).collect::<Option<_>>()?);
            at = end;
        }
        (at == words.len()).then_some(value)
    }
    pub fn encode(&self) -> Vec<i64> {
        let mut out = Vec::new();
        for value in self.numbers { out.extend([value.is_some() as i64, value.unwrap_or(0)]); }
        out.push(self.playing as i64);
        for text in &self.text {
            out.push(text.as_ref().map_or(-1, |s| s.len() as i64));
            if let Some(s) = text { out.extend(s.iter().map(|&v| v as i64)); }
        }
        out
    }
    pub fn track_changed(&self, next: &Self) -> bool {
        (self.text[3].is_some() && self.text[3] != next.text[3]) ||
        (self.text[1].is_some() && self.text[1] != next.text[1]) ||
        (self.numbers[1].is_some() && self.numbers[1] != next.numbers[1]) ||
        (self.numbers[2].is_some_and(|x| x > 15_000) && next.numbers[2].is_some_and(|x| x < 3_000))
    }
}

#[derive(Default)]
pub(super) struct Recovery {
    observed: Option<Metadata>, bound: bool, color: bool, attempted: bool,
    scheduled: bool, in_flight: bool, pub generation: i64, pub events: Vec<i64>,
}
impl Recovery {
    pub fn encode(&self) -> Vec<i64> {
        let metadata = self.observed.as_ref().map(Metadata::encode).unwrap_or_default();
        let mut out = vec![self.generation, self.bound as i64, self.color as i64, self.attempted as i64,
            self.scheduled as i64, self.in_flight as i64, metadata.len() as i64];
        out.extend(metadata);
        out.extend(&self.events);
        out
    }
    pub fn decode(words: &[i64]) -> Option<Self> {
        if words.len() < 7 || words[1..6].iter().any(|v| !(0..=1).contains(v)) { return None; }
        let len = usize::try_from(words[6]).ok()?;
        let end = 7usize.checked_add(len)?;
        let metadata = words.get(7..end)?;
        let events = words.get(end..)?.to_vec();
        if events.iter().any(|v| !(1..=4).contains(v)) { return None; }
        Some(Self { generation: words[0], bound: words[1] != 0, color: words[2] != 0,
            attempted: words[3] != 0, scheduled: words[4] != 0, in_flight: words[5] != 0,
            observed: if len == 0 { None } else { Some(Metadata::decode(metadata)?) }, events })
    }
    pub fn scheduled(&self) -> bool { self.scheduled }
    fn cancel(&mut self, reason: i64, report: bool) {
        if self.scheduled || self.in_flight || report { self.events.push(reason); }
        if self.scheduled || self.in_flight { self.generation = self.generation.wrapping_add(1); }
        self.scheduled = false;
        self.in_flight = false;
    }
    pub fn prepare(&mut self, action: i32, token: i64, info: Metadata, color: bool) -> bool {
        match action {
            0 => (),
            1 if token == self.generation && self.scheduled => (),
            2 | 3 if token == self.generation && self.in_flight && !self.attempted => (),
            _ => return false,
        }
        if self.bound && self.observed.as_ref().is_some_and(|old| old.track_changed(&info)) {
            self.cancel(1, false);
            self.bound = false; self.color = false; self.attempted = false;
        }
        self.observed = Some(info.clone());
        if self.attempted { return false; }
        if !info.playing { self.cancel(2, false); return false; }
        if info.numbers[0].is_none() { return false; }
        if !self.bound { self.bound = true; self.color = false; }
        if color {
            if !self.color { self.cancel(3, true); }
            self.color = true;
        }
        !self.color
    }
    pub fn complete(&mut self, action: i32, token: i64, allowed: bool) -> bool {
        if !allowed { self.cancel(4, false); return false; }
        match action {
            0 if !self.scheduled && !self.in_flight && !self.attempted => {
                self.scheduled = true; self.generation = self.generation.wrapping_add(1); true
            }
            1 if token == self.generation && self.scheduled => {
                self.scheduled = false; self.in_flight = true; true
            }
            2 if token == self.generation && self.in_flight => true,
            3 if token == self.generation && self.in_flight => { self.attempted = true; true }
            _ => false,
        }
    }
    pub fn finished(&mut self, token: i64) { if token == self.generation { self.in_flight = false; } }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn track() -> Metadata { Metadata { numbers: [Some(1), Some(180_000), Some(40_000)], playing: true, ..Default::default() } }
    #[test]
    fn recovery_budget_and_reentrant_invalidation() {
        let mut s = Recovery::default();
        assert!(s.prepare(0, 0, track(), false));
        assert!(s.complete(0, 0, true));
        let first = s.generation;
        assert!(s.prepare(1, first, track(), false));
        let mut paused = track(); paused.playing = false;
        assert!(!s.prepare(0, 0, paused, false));
        assert!(!s.complete(1, first, true));
        assert!(s.prepare(0, 0, track(), false));
        assert!(s.complete(0, 0, true));
        let next = s.generation;
        assert!(s.prepare(1, next, track(), false));
        assert!(s.complete(1, next, true));
        assert!(s.prepare(3, next, track(), false));
        assert!(s.complete(3, next, true));
        assert!(!s.prepare(0, 0, track(), false));
        assert_eq!(Recovery::decode(&s.encode()).unwrap().encode(), s.encode());
    }
}
