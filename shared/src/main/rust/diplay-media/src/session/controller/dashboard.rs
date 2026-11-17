//! Controller-lifetime accepted selection, distinct from each session's delivered content.

/// Operation codes crossing the JNI boundary (`NativeControllerDashboard_transition`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) enum DashboardTransition {
    /// Commit an accepted selection after a send attempt.
    CommitSelection,
    /// Commit a restore attempt for a stream.
    CommitRestore,
    /// Begin a visibility plan without committing the shown flag yet.
    BeginVisibility,
    /// Commit the shown flag.
    CommitVisibility,
    /// Apply the physical/instrument visibility for a session.
    SetPhysicalVisible,
    /// Clear the physical visibility.
    Close,
}

impl DashboardTransition {
    const ALL: [DashboardTransition; 6] = [
        DashboardTransition::CommitSelection,
        DashboardTransition::CommitRestore,
        DashboardTransition::BeginVisibility,
        DashboardTransition::CommitVisibility,
        DashboardTransition::SetPhysicalVisible,
        DashboardTransition::Close,
    ];

    fn code(self) -> i64 {
        match self {
            DashboardTransition::CommitSelection => 0,
            DashboardTransition::CommitRestore => 1,
            DashboardTransition::BeginVisibility => 2,
            DashboardTransition::CommitVisibility => 3,
            DashboardTransition::SetPhysicalVisible => 4,
            DashboardTransition::Close => 5,
        }
    }

    pub(super) fn from_code(code: i64) -> Option<Self> {
        Self::ALL
            .into_iter()
            .find(|&operation| operation.code() == code)
    }
}

/// Operation codes crossing the JNI boundary (`NativeControllerDashboard_query`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) enum DashboardQuery {
    /// Whether the accepted selection should be sent for the current snapshot.
    Selection,
    /// Whether a visibility event needs a send.
    Visibility,
    /// Result of a selection commit.
    CommitResult,
}

impl DashboardQuery {
    const ALL: [DashboardQuery; 3] = [
        DashboardQuery::Selection,
        DashboardQuery::Visibility,
        DashboardQuery::CommitResult,
    ];

    fn code(self) -> i64 {
        match self {
            DashboardQuery::Selection => 0,
            DashboardQuery::Visibility => 1,
            DashboardQuery::CommitResult => 3,
        }
    }

    pub(super) fn from_code(code: i64) -> Option<Self> {
        Self::ALL
            .into_iter()
            .find(|&operation| operation.code() == code)
    }
}

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
        operation: DashboardTransition,
        url: Option<Vec<u16>>,
        first: bool,
        second: bool,
        stream: i32,
    ) {
        match operation {
            DashboardTransition::CommitSelection if first && second => {
                self.accepted_url = url;
                self.epoch = self.epoch.wrapping_add(1);
            }
            DashboardTransition::CommitRestore if first && second && stream > 0 => {
                self.epoch = self.epoch.wrapping_add(1);
            }
            DashboardTransition::BeginVisibility if !first => self.shown = true,
            DashboardTransition::CommitVisibility => {
                self.shown = first;
                self.epoch = self.epoch.wrapping_add(1);
            }
            DashboardTransition::SetPhysicalVisible => {
                let next = first && !second;
                if self.physical != next {
                    self.physical = next;
                    self.epoch = self.epoch.wrapping_add(1);
                }
            }
            DashboardTransition::Close => self.physical = false,
            _ => {}
        }
    }

    pub(super) fn query(&self, operation: DashboardQuery, first: bool, second: bool) -> i64 {
        match operation {
            DashboardQuery::Selection => (self.shown || !first) as i64,
            DashboardQuery::Visibility => (!(first == self.shown && (!first || second))) as i64,
            DashboardQuery::CommitResult => (first && second) as i64,
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
        state.transition(
            DashboardTransition::CommitSelection,
            Some(vec![42]),
            false,
            true,
            1,
        );
        state.transition(
            DashboardTransition::CommitSelection,
            Some(vec![43]),
            true,
            false,
            1,
        );
        assert_eq!(
            Dashboard::decode(&state.encode()).unwrap().accepted_url(),
            Some(url.as_slice())
        );
        state.transition(
            DashboardTransition::CommitSelection,
            Some(vec![]),
            true,
            true,
            1,
        );
        assert_eq!(state.accepted_url(), Some([].as_slice()));
        assert_eq!(state.epoch, 1);
    }

    #[test]
    fn paused_selection_restore_and_visibility_have_independent_commits() {
        let mut state = Dashboard::new(None);
        state.transition(DashboardTransition::CommitVisibility, None, false, false, 1);
        assert_eq!(state.query(DashboardQuery::Selection, true, false), 0);
        assert_eq!(state.query(DashboardQuery::Selection, false, false), 1);
        state.transition(
            DashboardTransition::CommitSelection,
            Some(vec![42]),
            true,
            true,
            1,
        );
        assert!(!state.shown);
        state.transition(DashboardTransition::CommitRestore, None, true, true, 0);
        assert_eq!(state.epoch, 2);
        state.transition(DashboardTransition::CommitRestore, None, true, true, 1);
        assert_eq!(state.epoch, 3);
        state.transition(DashboardTransition::BeginVisibility, None, false, false, 1);
        assert!(state.shown);
        assert_eq!(state.query(DashboardQuery::Visibility, true, true), 0);
        assert_eq!(state.query(DashboardQuery::Visibility, true, false), 1);
        state.epoch = i32::MAX;
        state.transition(
            DashboardTransition::SetPhysicalVisible,
            None,
            true,
            false,
            1,
        );
        assert_eq!(state.epoch, i32::MIN);
        state.transition(
            DashboardTransition::SetPhysicalVisible,
            None,
            true,
            false,
            1,
        );
        assert_eq!(state.epoch, i32::MIN);
    }

    #[test]
    fn dashboard_operation_codes_map_both_ways() {
        for code in 0..=5 {
            let operation = DashboardTransition::from_code(code).expect("known transition code");
            assert_eq!(operation.code(), code);
        }
        assert_eq!(DashboardTransition::from_code(6), None);
        assert_eq!(DashboardTransition::from_code(-1), None);

        for (code, expected) in [
            (0, DashboardQuery::Selection),
            (1, DashboardQuery::Visibility),
            (3, DashboardQuery::CommitResult),
        ] {
            assert_eq!(DashboardQuery::from_code(code), Some(expected));
            assert_eq!(expected.code(), code);
        }
        assert_eq!(DashboardQuery::from_code(2), None);
        assert_eq!(DashboardQuery::from_code(4), None);
    }
}
