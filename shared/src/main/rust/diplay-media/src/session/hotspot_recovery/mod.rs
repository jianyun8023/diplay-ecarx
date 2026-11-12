//! Hotspot stable-sample confirmation, radio-return budget and process outage memory.
mod jni;

struct Readiness {
    words: [i64; 4],
}
impl Readiness {
    fn sample(&mut self, selected: bool, had_previous: bool, same: bool, confirmed: bool) -> bool {
        if had_previous && !same {
            self.words[3] = 1;
        }
        if self.words[2] == -1 {
            self.words[2] = (selected && confirmed) as i64;
        }
        self.words[1] = if same {
            (self.words[1] as i32).wrapping_add(1) as i64
        } else {
            1
        };
        let required = if self.words[2] == 1 && self.words[3] == 0 {
            2
        } else {
            3
        };
        selected && self.words[1] >= required
    }
    fn time(&self, expired: bool, now: i64) -> i64 {
        if expired {
            (now >= self.words[0]) as i64
        } else {
            self.words[0].wrapping_sub(now).clamp(1, 250)
        }
    }
}
fn return_wait(start: i64, timeout: i64, now: i64) -> i64 {
    timeout.wrapping_sub(now.wrapping_sub(start)).clamp(0, 500)
}
fn outage_event(marked: &mut i64, operation: i32, now: i64, budget: i64) -> bool {
    match operation {
        0 => {
            *marked = now.max(1);
            false
        }
        1 => {
            *marked = 0;
            false
        }
        2 => *marked > 0 && now.wrapping_sub(*marked) < budget,
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn initially_confirmed_hotspot_needs_two_samples_and_any_change_falls_back_to_three() {
        let mut state = Readiness {
            words: [1000, 0, -1, 0],
        };
        assert!(!state.sample(true, false, false, true));
        assert!(state.sample(true, true, true, true));
        let mut state = Readiness {
            words: [1000, 0, -1, 0],
        };
        assert!(!state.sample(true, false, false, true));
        assert!(!state.sample(false, true, false, false));
        assert!(!state.sample(true, false, false, true));
        assert!(!state.sample(true, true, true, true));
        assert!(state.sample(true, true, true, true));
        assert_eq!(state.time(true, 1000), 1);
        assert_eq!(state.time(false, 1000), 1);
    }
    #[test]
    fn outage_budget_and_return_wait_preserve_wrapping_and_clock_rollback() {
        let mut marked = 0;
        assert!(!outage_event(&mut marked, 2, 1, 10));
        outage_event(&mut marked, 0, 0, 10);
        assert!(outage_event(&mut marked, 2, 0, 10));
        assert!(!outage_event(&mut marked, 2, 11, 10));
        outage_event(&mut marked, 1, 0, 10);
        assert!(!outage_event(&mut marked, 2, 1, 10));
        assert_eq!(return_wait(i64::MAX, 1000, i64::MIN), 500);
        assert_eq!(return_wait(0, 500, 500), 0);
        assert_eq!(return_wait(0, 500, 499), 1);
    }
}
