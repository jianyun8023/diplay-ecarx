//! ECARX publication intent, retry clocks, lane cache and applied snapshots.
mod jni;

const WANTED: usize = 0;
const RELEASE: usize = 1;
const START_ATTEMPTS: usize = 2;
const START_COUNTDOWN: usize = 3;
const COUNTDOWN: usize = 4;
const ATTEMPTS: usize = 5;
const REFRESH: usize = 6;
const OPTION: usize = 7;
const VISIBLE: usize = 8;
const AUTO_SHOW: usize = 9;
const GUIDANCE: usize = 10;
const ACTIVE: usize = 11;
const TOUCHED: usize = 12;
const FALLBACK: usize = 13;
const FAST_SECONDS: i32 = 2;
const FAST_ATTEMPTS: i32 = 30;
const SLOW_SECONDS: i32 = 10;

#[derive(PartialEq, Clone)]
struct Guidance {
    values: [i64; 8],
    road: Vec<u16>,
}
struct Hud {
    scalar: [i64; 14],
    cache: Vec<(i32, Option<Vec<i32>>)>,
    selected: Option<Vec<i32>>,
    applied_lanes: Option<Vec<i32>>,
    applied_guidance: Option<Guidance>,
    destination: Option<Vec<u16>>,
}
impl Default for Hud {
    fn default() -> Self {
        Self {
            scalar: [0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, -1],
            cache: Vec::new(),
            selected: None,
            applied_lanes: None,
            applied_guidance: None,
            destination: Some(Vec::new()),
        }
    }
}
impl Hud {
    fn cruise(&self, enabled: bool, connected: bool) -> bool {
        self.scalar[GUIDANCE] == 0 && self.scalar[VISIBLE] != 0 && enabled && connected
    }
    fn intent(&self, cruise: bool, connected: bool) -> i32 {
        if self.scalar[GUIDANCE] == 0 {
            if self.cruise(cruise, connected) {
                1
            } else {
                0
            }
        } else if self.scalar[VISIBLE] != 0 {
            1
        } else {
            2
        }
    }
    fn option(&mut self, option: i32, idle_wanted: bool) -> i32 {
        if !(1..=3).contains(&option) {
            return -1;
        }
        self.scalar[OPTION] = option as i64;
        match option {
            1 => {
                if self.scalar[GUIDANCE] != 0 {
                    self.scalar[AUTO_SHOW] as i32
                } else {
                    idle_wanted as i32
                }
            }
            2 => 1,
            _ => 0,
        }
    }
    fn visibility(&mut self, show: bool) {
        self.scalar[VISIBLE] = show as i64;
        if !show {
            self.scalar[WANTED] = 0;
        }
    }
    fn accept_presentation(&mut self, show: bool) {
        self.scalar[RELEASE] = 0;
        self.scalar[WANTED] = show as i64;
        if !show {
            self.scalar[ATTEMPTS] = 0;
            self.scalar[COUNTDOWN] = 0;
        }
    }
    fn start_guidance(&mut self, auto: bool, cruising: bool) {
        self.scalar[GUIDANCE] = 1;
        self.scalar[AUTO_SHOW] = (auto || cruising) as i64;
        self.scalar[VISIBLE] = match self.scalar[OPTION] {
            2 => 1,
            3 => 0,
            _ => self.scalar[AUTO_SHOW],
        };
    }
    fn idle_visibility(&mut self, wanted: bool) {
        if self.scalar[GUIDANCE] == 0 && !(2..=3).contains(&self.scalar[OPTION]) {
            self.scalar[VISIBLE] = wanted as i64;
        }
    }
    fn due(&mut self, startup: bool, platform_ready: bool) -> i32 {
        let (countdown, attempts) = if startup {
            if self.scalar[RELEASE] == 0 || self.scalar[GUIDANCE] != 0 || !platform_ready {
                return 0;
            }
            (START_COUNTDOWN, START_ATTEMPTS)
        } else {
            if self.scalar[WANTED] == 0 || !platform_ready {
                return 0;
            }
            (COUNTDOWN, ATTEMPTS)
        };
        if self.scalar[countdown] > 0 {
            self.scalar[countdown] -= 1;
            return 0;
        }
        let interval = if self.scalar[attempts] < FAST_ATTEMPTS as i64 {
            FAST_SECONDS
        } else {
            SLOW_SECONDS
        };
        self.scalar[countdown] = (interval - 1) as i64;
        interval
    }
    fn finished(&mut self, startup: bool, interval: i32, success: bool) -> bool {
        if interval == 0 {
            // A manual request starts a fresh fast phase after its platform call.
            self.scalar[ATTEMPTS] = 1;
            self.scalar[COUNTDOWN] = self.scalar[COUNTDOWN].max((FAST_SECONDS - 1) as i64);
            return false;
        }
        let field = if startup { START_ATTEMPTS } else { ATTEMPTS };
        self.scalar[field] = (self.scalar[field] as i32).wrapping_add(1) as i64;
        let count = self.scalar[field];
        if startup {
            if success {
                self.scalar[RELEASE] = 0;
            }
            success || count == 1 || count == (FAST_ATTEMPTS + 1) as i64
        } else {
            count == 1 || interval == SLOW_SECONDS && count % 6 == 0
        }
    }
    fn refresh(&mut self, missing_turn: bool) -> i32 {
        self.scalar[REFRESH] = (self.scalar[REFRESH] as i32).wrapping_add(1) as i64;
        if self.scalar[REFRESH] >= 5 {
            self.scalar[REFRESH] = 0;
            2
        } else if self.scalar[GUIDANCE] != 0 && missing_turn {
            1
        } else {
            0
        }
    }
    fn cache(&mut self, index: i32, group: Option<Vec<i32>>, turn: i32, cursor: i32) {
        let previous = self.cache.iter_mut().find(|(i, _)| *i == index);
        if previous.as_ref().is_none_or(|(_, p)| *p != group) {
            self.scalar[FALLBACK] = if turn >= 0 { turn as i64 } else { -1 };
        }
        if let Some((_, value)) = previous {
            *value = group;
        } else {
            self.cache.push((index, group));
        }
        if self.cache.len() > 32 {
            let discard = self.cache.iter().position(|(i, _)| *i != cursor).unwrap();
            self.cache.remove(discard);
        }
    }
    fn clear_cache(&mut self, reset_fallback: bool) {
        self.cache.clear();
        self.selected = None;
        if reset_fallback {
            self.scalar[FALLBACK] = -1;
        }
    }
    fn lanes(
        &mut self,
        force: bool,
        cursor: i32,
        showing: i32,
        has_turn: bool,
        turn: i32,
        enabled: i32,
    ) -> Vec<i32> {
        if self.scalar[GUIDANCE] == 0 {
            return vec![0];
        }
        self.selected = if showing == 0 {
            None
        } else if cursor >= 0 {
            self.cache
                .iter()
                .find(|(i, _)| *i == cursor)
                .and_then(|(_, v)| v.clone())
        } else if self.cache.len() == 1
            && has_turn
            && (self.scalar[FALLBACK] < 0 || self.scalar[FALLBACK] == turn as i64)
        {
            self.scalar[FALLBACK] = turn as i64;
            self.cache[0].1.clone()
        } else {
            None
        };
        if enabled < 0 {
            return vec![0];
        }
        let wanted = self
            .selected
            .as_ref()
            .filter(|_| enabled != 0 && self.scalar[ACTIVE] != 0);
        let Some(wanted) = wanted else {
            return vec![self.applied_lanes.is_some() as i32];
        };
        if !force && Some(wanted) == self.applied_lanes.as_ref() {
            return vec![0];
        }
        let mut result = vec![2];
        result.extend(wanted);
        result
    }
    fn clear_lanes(&mut self, force: bool) -> bool {
        self.scalar[FALLBACK] = -1;
        self.cache.clear();
        if !force && self.selected.is_none() && self.applied_lanes.is_none() {
            return false;
        }
        self.selected = None;
        true
    }
    fn stop_plan(&self, navi: bool, card: bool, arrows: bool) -> i32 {
        if self.scalar[TOUCHED] == 0 {
            return 0;
        }
        1 | if navi {
            2 | if card { 4 } else { 0 } | if arrows { 8 } else { 0 }
        } else {
            0
        }
    }
    fn stopped(&mut self) {
        self.applied_guidance = None;
        self.scalar[REFRESH] = 0;
        self.scalar[ACTIVE] = 0;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn retry_phases_commit_after_calls_and_never_clear_while_guiding() {
        let mut h = Hud::default();
        h.scalar[RELEASE] = 1;
        assert_eq!(h.due(true, false), 0);
        for attempt in 1..=31 {
            let interval = if attempt <= 30 { 2 } else { 10 };
            assert_eq!(h.due(true, true), interval);
            assert_eq!(
                h.finished(true, interval, false),
                attempt == 1 || attempt == 31
            );
            for _ in 1..interval {
                assert_eq!(h.due(true, true), 0);
            }
        }
        h.scalar[GUIDANCE] = 1;
        assert_eq!(h.due(true, true), 0);
        h.accept_presentation(true);
        assert_eq!(h.scalar[RELEASE], 0);
        h.scalar[COUNTDOWN] = 8;
        h.finished(false, 0, false);
        assert_eq!(h.scalar[COUNTDOWN], 8);
        h.accept_presentation(false);
        assert_eq!(h.scalar[GUIDANCE], 1);
        assert_eq!(h.scalar[ATTEMPTS], 0);
    }
    #[test]
    fn lane_cache_protects_cursor_and_does_not_reuse_an_old_fallback_at_the_next_turn() {
        let mut h = Hud::default();
        h.scalar[GUIDANCE] = 1;
        h.scalar[ACTIVE] = 1;
        h.cache(7, Some(vec![1, 42]), 1, -1);
        assert_eq!(h.lanes(false, -1, -1, true, 1, 1), [2, 1, 42]);
        h.applied_lanes = Some(vec![1, 42]);
        assert_eq!(h.lanes(false, -1, -1, true, 2, 1), [1]);
        h.cache(7, Some(vec![1, 42]), 2, -1);
        assert_eq!(h.lanes(false, -1, -1, true, 2, 1), [1]);
        for i in 8..80 {
            h.cache(i, Some(vec![1, i]), 2, 7);
        }
        assert_eq!(h.cache.len(), 32);
        assert_eq!(h.cache[0].0, 7);
        assert_eq!(h.lanes(true, 7, -1, false, 2, 1), [2, 1, 42]);
        assert_eq!(h.lanes(true, 7, 0, false, 2, 1), [1]);
    }
}
