//! One media engine's replacement barrier; sessions retain opaque eligibility in the JVM weak map.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Engine {
    Idle,
    Installing,
    Cancelled,
    Retiring,
}

pub(crate) const ELIGIBLE: i64 = 0;
const RETIRED: i64 = 1;
const UNCHANGED: i64 = 0;
const CLEAR: i64 = 1;
const RETIREMENT_ONLY: i64 = 2;

impl Engine {
    pub(crate) fn decode(token: i64) -> Option<Self> {
        match token {
            0 => Some(Self::Idle),
            1 => Some(Self::Installing),
            2 => Some(Self::Cancelled),
            3 => Some(Self::Retiring),
            _ => None,
        }
    }
    pub(crate) fn encode(self) -> i64 {
        match self {
            Self::Idle => 0,
            Self::Installing => 1,
            Self::Cancelled => 2,
            Self::Retiring => 3,
        }
    }

    // Results: next opaque state, accepted/close-candidate action, barrier disposition, optional old eligibility.
    pub(crate) fn step(mut self, operation: i32, eligibility: i64, facts: i32) -> Option<[i64; 4]> {
        let mask = match operation {
            0 | 2 | 4 => 7,
            1 | 3 => 31,
            _ => return None,
        };
        if ![ELIGIBLE, RETIRED].contains(&eligibility) || facts & !mask != 0 {
            return None;
        }
        let mut action = false;
        let mut barrier = UNCHANGED;
        let mut old_eligibility = -1;
        match operation {
            0 | 1 => {
                let closed = facts & 1 != 0;
                let has_retiring_stream = facts & 2 != 0;
                let cleanup_complete = facts & 4 != 0;
                // Release even for a request which will subsequently be declined.
                if self == Self::Retiring && has_retiring_stream && cleanup_complete {
                    self = Self::Idle;
                    barrier = CLEAR;
                }
                action = !closed && eligibility == ELIGIBLE && self == Self::Idle;
                if action && operation == 1 {
                    self = Self::Installing;
                    let has_previous_owner = facts & 8 != 0;
                    let same_owner_session = facts & 16 != 0;
                    if has_previous_owner && !same_owner_session {
                        old_eligibility = RETIRED;
                    }
                }
            }
            2 => {
                let matches_transition_session = facts & 1 != 0;
                let has_candidate = facts & 2 != 0;
                let has_registered_stream = facts & 4 != 0;
                if matches_transition_session && matches!(self, Self::Installing | Self::Cancelled)
                {
                    self = Self::Cancelled;
                    action = has_candidate;
                }
                if has_registered_stream && self == Self::Idle {
                    self = Self::Retiring;
                    barrier = RETIREMENT_ONLY;
                }
            }
            3 => {
                let close_returned_normally = facts & 1 != 0;
                let has_retiring_stream = facts & 2 != 0;
                let cleanup_complete = facts & 4 != 0;
                let same_transition = facts & 8 != 0;
                let closed = facts & 16 != 0;
                let retired = close_returned_normally && (!has_retiring_stream || cleanup_complete);
                action = retired
                    && same_transition
                    && self == Self::Installing
                    && !closed
                    && eligibility == ELIGIBLE;
                if same_transition && matches!(self, Self::Installing | Self::Cancelled) {
                    self = if retired { Self::Idle } else { Self::Retiring };
                    barrier = if retired { CLEAR } else { RETIREMENT_ONLY };
                }
            }
            4 => {
                let same_transition = facts & 1 != 0;
                let has_retiring_stream = facts & 2 != 0;
                let cleanup_complete = facts & 4 != 0;
                if same_transition
                    && has_retiring_stream
                    && cleanup_complete
                    && self == Self::Retiring
                {
                    self = Self::Idle;
                    barrier = CLEAR;
                }
            }
            _ => unreachable!(),
        }
        Some([self.encode(), action as i64, barrier, old_eligibility])
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn cleanup_after_throw_waits_for_next_preflight_even_if_that_request_is_closed() {
        let installing = Engine::Idle.step(1, ELIGIBLE, 8).unwrap();
        assert_eq!(installing, [1, 1, UNCHANGED, RETIRED]);
        let failed = Engine::Installing.step(3, ELIGIBLE, 2 | 4 | 8).unwrap();
        assert_eq!(failed, [3, 0, RETIREMENT_ONLY, -1]);
        assert_eq!(
            Engine::Retiring.step(0, RETIRED, 1 | 2 | 4),
            Some([0, 0, CLEAR, -1])
        );
    }
    #[test]
    fn cancellation_keeps_installing_barrier_and_does_not_revoke_other_sessions_retirement() {
        assert_eq!(
            Engine::Installing.step(2, ELIGIBLE, 1 | 2),
            Some([2, 1, UNCHANGED, -1])
        );
        assert_eq!(
            Engine::Cancelled.step(0, ELIGIBLE, 2 | 4),
            Some([2, 0, UNCHANGED, -1])
        );
        assert_eq!(
            Engine::Cancelled.step(3, ELIGIBLE, 1 | 2 | 4 | 8),
            Some([0, 0, CLEAR, -1])
        );
        assert_eq!(
            Engine::Idle.step(0, RETIRED, 0),
            Some([0, 0, UNCHANGED, -1])
        );
        assert_eq!(
            Engine::Idle.step(0, ELIGIBLE, 0),
            Some([0, 1, UNCHANGED, -1])
        );
    }
    #[test]
    fn absent_retiring_resource_only_counts_as_completed_in_setup() {
        assert_eq!(
            Engine::Installing.step(3, ELIGIBLE, 1 | 8),
            Some([0, 1, CLEAR, -1])
        );
        assert_eq!(
            Engine::Retiring.step(0, ELIGIBLE, 4),
            Some([3, 0, UNCHANGED, -1])
        );
        assert_eq!(
            Engine::Retiring.step(4, ELIGIBLE, 1 | 4),
            Some([3, 0, UNCHANGED, -1])
        );
        assert_eq!(
            Engine::Retiring.step(4, ELIGIBLE, 1 | 2 | 4),
            Some([0, 0, CLEAR, -1])
        );
    }
    #[test]
    fn stale_finish_does_not_release_the_current_barrier_and_invalid_tokens_are_rejected() {
        assert_eq!(
            Engine::Installing.step(3, ELIGIBLE, 1 | 2 | 4),
            Some([1, 0, UNCHANGED, -1])
        );
        assert_eq!(
            Engine::Retiring.step(4, ELIGIBLE, 2 | 4),
            Some([3, 0, UNCHANGED, -1])
        );
        for token in [i64::MIN, -1, 4, i64::MAX] {
            assert_eq!(Engine::decode(token), None);
        }
        assert_eq!(Engine::Idle.step(0, 2, 0), None);
        assert_eq!(Engine::Idle.step(9, ELIGIBLE, 0), None);
        assert_eq!(Engine::Idle.step(0, ELIGIBLE, 8), None);
    }
}
