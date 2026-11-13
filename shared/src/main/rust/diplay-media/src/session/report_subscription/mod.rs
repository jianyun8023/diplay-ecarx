//! Per-link LocationInformation/VehicleStatus subscription state. Android owns provider and I/O.
mod jni;

const WORDS: usize = 4;
#[derive(Clone, Copy, Debug)]
struct Subscription {
    vehicle: bool,
    active: bool,
    last: i64,
    logged: bool,
}
impl Subscription {
    fn new(vehicle: bool) -> Self {
        Self {
            vehicle,
            active: false,
            last: 0,
            logged: false,
        }
    }
    fn from_words(words: [i64; WORDS]) -> Option<Self> {
        if !(0..=1).contains(&words[0])
            || !(0..=1).contains(&words[1])
            || !(0..=1).contains(&words[3])
        {
            return None;
        }
        Some(Self {
            vehicle: words[0] != 0,
            active: words[1] != 0,
            last: words[2],
            logged: words[3] != 0,
        })
    }
    fn words(self) -> [i64; WORDS] {
        [
            self.vehicle as i64,
            self.active as i64,
            self.last,
            self.logged as i64,
        ]
    }
    fn start(&mut self, active: bool) {
        self.active = active;
        if !self.vehicle {
            self.logged = false;
        }
    }
    fn stop(&mut self) {
        self.start(false);
    }
    // Active was sampled by the caller before its reentrant clock callback. Read the current last
    // timestamp here, just as the original Kotlin subtraction did after that callback returned.
    fn due(self, now: i64) -> bool {
        let elapsed = now.wrapping_sub(self.last);
        if self.vehicle {
            elapsed >= 30_000_000_000
        } else {
            elapsed / 1_000_000 >= 1_000
        }
    }
    fn timeout(self, remaining: i64, now: i64) -> i64 {
        let interval = if self.vehicle {
            30_000
        } else {
            1_000i64
                .wrapping_sub(now.wrapping_sub(self.last) / 1_000_000)
                .max(1)
        };
        remaining.min(interval)
    }
    fn first_sent(&mut self) -> bool {
        let first = !self.logged;
        self.logged = true;
        first
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn location_attempts_and_vehicle_successes_have_distinct_deadlines() {
        let mut gps = Subscription::new(false);
        gps.start(true);
        gps.last = 123;
        assert!(!gps.due(1_000_000_122));
        assert!(gps.due(1_000_000_123));
        assert_eq!(gps.timeout(5_000, 400_000_123), 600);
        assert_eq!(gps.timeout(-4, 400_000_123), -4);
        assert_eq!(gps.timeout(5_000, 2_000_000_123), 1);
        assert!(gps.first_sent());
        assert!(!gps.first_sent());
        gps.stop();
        assert!(!gps.active);
        assert!(!gps.logged);
        assert_eq!(gps.last, 123);
        let mut ev = Subscription::new(true);
        ev.start(true);
        ev.last = 123;
        assert!(!ev.due(30_000_000_122));
        assert!(ev.due(30_000_000_123));
        assert_eq!(ev.timeout(50_000, i64::MIN), 30_000);
        assert_eq!(ev.timeout(-4, i64::MAX), -4);
    }
    #[test]
    fn timestamps_keep_all_long_bits_and_original_signed_wrapping() {
        for last in [i64::MIN, -1, 0, i64::MAX] {
            let mut state = Subscription::new(false);
            state.last = last;
            assert_eq!(Subscription::from_words(state.words()).unwrap().last, last);
        }
        let mut state = Subscription::new(false);
        state.last = i64::MAX - 500_000_000;
        assert!(state.due(i64::MIN + 500_000_000));
        assert_eq!(
            state.timeout(i64::MAX, state.last.wrapping_sub(1_999_999)),
            1_001
        );
        assert!(!state.due(state.last.wrapping_sub(2_000_000_000)));
        assert!(Subscription::from_words([0, 2, 0, 0]).is_none());
    }
}
