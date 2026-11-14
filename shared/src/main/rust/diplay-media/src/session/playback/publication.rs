//! Media Center acquisition and publication decisions, committed after platform success.
use super::artwork_recovery::Metadata;

#[derive(Default)]
pub(super) struct Publication {
    previous: Option<Metadata>,
    artwork: Vec<i64>,
}
impl Publication {
    pub fn decode(words: &[i64]) -> Option<Self> {
        let len = usize::try_from(*words.first()?).ok()?;
        let end = 1usize.checked_add(len)?;
        let metadata = words.get(1..end)?;
        let artwork = words.get(end..)?.to_vec();
        if !valid_artwork(&artwork) { return None; }
        Some(Self { previous: if len == 0 { None } else { Some(Metadata::decode(metadata)?) }, artwork })
    }
    pub fn encode(&self) -> Vec<i64> {
        let metadata = self.previous.as_ref().map(Metadata::encode).unwrap_or_default();
        let mut out = vec![metadata.len() as i64];
        out.extend(metadata);
        if self.artwork.is_empty() { out.push(-1); } else { out.extend(&self.artwork); }
        out
    }
    pub fn plan(&self, info: &Metadata, artwork: &[i64], selected: bool) -> i32 {
        let acquire = self.previous.as_ref().map_or(true, |p|
            selected || (info.playing && (!p.playing || p.text[3] != info.text[3])));
        let mut media = info.clone();
        media.numbers[0] = None;
        let publish = self.previous.as_ref() != Some(&media) || self.artwork != artwork;
        acquire as i32 | ((publish as i32) << 1)
    }
    pub fn commit(&mut self, mut info: Metadata, artwork: Vec<i64>) {
        info.numbers[0] = None;
        self.previous = Some(info);
        self.artwork = artwork;
    }
}
pub(super) fn valid_artwork(words: &[i64]) -> bool {
    match words.first() {
        Some(-1) => words.len() == 1,
        Some(&len) if len >= 0 => len as usize == words.len() - 1 && words[1..].iter().all(|v| (0..=65535).contains(v)),
        _ => false,
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn commits_only_published_metadata_and_ignores_transfer_id() {
        let mut state = Publication::default();
        let mut info = Metadata::default();
        info.playing = true;
        info.numbers[0] = Some(1);
        assert_eq!(state.plan(&info, &[-1], false), 3);
        state.commit(info.clone(), vec![-1]);
        info.numbers[0] = Some(2);
        assert_eq!(state.plan(&info, &[-1], false), 0);
        assert_eq!(state.plan(&info, &[-1], true), 1);
        info.numbers[2] = Some(1);
        assert_eq!(state.plan(&info, &[-1], false), 2);
        assert_eq!(Publication::decode(&state.encode()).unwrap().plan(&info, &[-1], false), 2);
    }
}
