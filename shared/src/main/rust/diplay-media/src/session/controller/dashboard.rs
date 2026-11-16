//! Controller-lifetime accepted selection, distinct from each session's delivered content.
pub(super) struct Dashboard {
    accepted_url: Option<Vec<u16>>,
    shown: bool,
    physical: bool,
    epoch: i32,
}

impl Dashboard {
    pub(super) fn new(accepted_url: Option<Vec<u16>>) -> Self {
        Self {
            accepted_url,
            shown: true,
            physical: false,
            epoch: 0,
        }
    }

    pub(super) fn encode(&self) -> Vec<i64> {
        let mut words = vec![
            self.epoch as i64,
            self.shown as i64,
            self.physical as i64,
            self.accepted_url.is_some() as i64,
        ];
        if let Some(url) = &self.accepted_url {
            words.extend(url.iter().map(|&unit| unit as i64));
        }
        words
    }

    pub(super) fn decode(words: &[i64]) -> Option<Self> {
        if words.len() < 4
            || words[0] != words[0] as i32 as i64
            || !words[1..4].iter().all(|v| (0..=1).contains(v))
            || (words[3] == 0 && words.len() != 4)
            || !words[4..].iter().all(|v| (0..=65535).contains(v))
        {
            return None;
        }
        Some(Self {
            epoch: words[0] as i32,
            shown: words[1] != 0,
            physical: words[2] != 0,
            accepted_url: (words[3] != 0).then(|| words[4..].iter().map(|&v| v as u16).collect()),
        })
    }

    pub(super) fn accepted_url(&self) -> Option<&[u16]> {
        self.accepted_url.as_deref()
    }

    pub(super) fn transition(
        &mut self,
        operation: i32,
        url: Option<Vec<u16>>,
        first: bool,
        second: bool,
        stream: i32,
    ) {
        match operation {
            0 if first && second => {
                self.accepted_url = url;
                self.epoch = self.epoch.wrapping_add(1);
            }
            1 if first && second && stream > 0 => {
                self.epoch = self.epoch.wrapping_add(1);
            }
            2 if !first => self.shown = true,
            3 => {
                self.shown = first;
                self.epoch = self.epoch.wrapping_add(1);
            }
            4 => {
                let next = first && !second;
                if self.physical != next {
                    self.physical = next;
                    self.epoch = self.epoch.wrapping_add(1);
                }
            }
            5 => self.physical = false,
            _ => {}
        }
    }

    pub(super) fn query(&self, operation: i32, first: bool, second: bool) -> i64 {
        match operation {
            0 => (self.shown || !first) as i64,
            1 => (!(first == self.shown && (!first || second))) as i64,
            3 => (first && second) as i64,
            _ => 0,
        }
    }

    pub(super) fn route(
        &self,
        same_stream: bool,
        stream: i32,
        url: Option<&[u16]>,
        closed: bool,
    ) -> i64 {
        if permits(
            url,
            self.physical,
            stream,
            self.shown || !same_stream,
            closed,
        ) {
            self.epoch as i64
        } else {
            0x1_0000_0000
        }
    }
}

pub(super) fn permits(
    url: Option<&[u16]>,
    physical: bool,
    stream: i32,
    shown: bool,
    closed: bool,
) -> bool {
    !closed
        && physical
        && stream > 0
        && shown
        && url.is_some_and(|url| {
            url.iter()
                .copied()
                .eq("maps:/car/instrumentcluster/map".encode_utf16())
                || url
                    .iter()
                    .copied()
                    .eq("maps:/car/instrumentcluster".encode_utf16())
        })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepted_selection_survives_failed_replacement_and_utf16_roundtrip() {
        let url = vec![0, 0xd800, 0xffff, 0xdc00];
        let mut state = Dashboard::new(Some(url.clone()));
        state.transition(0, Some(vec![42]), false, true, 1);
        state.transition(0, Some(vec![43]), true, false, 1);
        assert_eq!(
            Dashboard::decode(&state.encode()).unwrap().accepted_url(),
            Some(url.as_slice())
        );
        state.transition(0, Some(vec![]), true, true, 1);
        assert_eq!(state.accepted_url(), Some([].as_slice()));
        assert_eq!(state.epoch, 1);
    }

    #[test]
    fn paused_selection_restore_and_visibility_have_independent_commits() {
        let mut state = Dashboard::new(None);
        state.transition(3, None, false, false, 1);
        assert_eq!(state.query(0, true, false), 0);
        assert_eq!(state.query(0, false, false), 1);
        state.transition(0, Some(vec![42]), true, true, 1);
        assert!(!state.shown);
        state.transition(1, None, true, true, 0);
        assert_eq!(state.epoch, 2);
        state.transition(1, None, true, true, 1);
        assert_eq!(state.epoch, 3);
        state.transition(2, None, false, false, 1);
        assert!(state.shown);
        assert_eq!(state.query(1, true, true), 0);
        assert_eq!(state.query(1, true, false), 1);
        state.epoch = i32::MAX;
        state.transition(4, None, true, false, 1);
        assert_eq!(state.epoch, i32::MIN);
        state.transition(4, None, true, false, 1);
        assert_eq!(state.epoch, i32::MIN);
    }
}
