//! Startup watchdog state and timer decisions. Kotlin owns scheduled/cancel callbacks and clocks.
mod jni;

const WORDS: usize = 10;
const CANCEL: i64 = 1;
const ARM: i64 = 2;
const TIMEOUT: i64 = 4;
const HOLD: i64 = 8;
const PROGRESS: i64 = 16;
const DUPLICATE: i64 = 32;
const ACCEPTED: i64 = 64;
const LOG_SUCCESS: i64 = 128;
const FIRST_ACCEPT: i64 = 512;
const FINISHED: i64 = 1;
const ARMED: i64 = 2;
const REARMED: i64 = 4;
const HELD: i64 = 8;
const SUCCESS: i64 = 16;
type Effect = [i64; 7];

struct Watchdog {
    words: [i64; WORDS],
}
impl Watchdog {
    fn new(kind: i32, now: i64, timeout: i64) -> Self {
        Self {
            words: [kind as i64, 0, 0, -1, now, 0, 0, 0, 0, timeout],
        }
    }
    fn from_words(words: [i64; WORDS]) -> Option<Self> {
        if !(0..=2).contains(&words[0])
            || !(0..32).contains(&words[1])
            || words[2] != words[2] as i32 as i64
            || !(-1..=6).contains(&words[3])
            || !(0..=1).contains(&words[5])
            || !(0..=1).contains(&words[7])
        {
            return None;
        }
        Some(Self { words })
    }
    fn has(&self, flag: i64) -> bool {
        self.words[1] & flag != 0
    }
    fn arm(&mut self, delay: i64, flags: i64) -> Effect {
        self.words[2] = (self.words[2] as i32).wrapping_add(1) as i64;
        [flags | ARM, delay, self.words[2], 0, 0, 0, 0]
    }
    fn event(&mut self, event: i32, value: i32, time: i64, condition: bool) -> Effect {
        match event {
            1 => {
                // Wired first StartSession.
                if self.has(ARMED | FINISHED) {
                    return [0; 7];
                }
                self.words[1] |= ARMED;
                self.arm(self.words[9], 0)
            }
            2 => {
                // Begin the once-only second request, before cancelling its predecessor.
                if !self.has(ARMED) || self.has(FINISHED | REARMED) {
                    return [0; 7];
                }
                self.words[1] |= REARMED;
                [CANCEL | ACCEPTED, 0, 0, 0, 0, 0, 0]
            }
            3 => {
                if self.has(FINISHED) {
                    return [0; 7];
                }
                self.words[1] |= FINISHED;
                [CANCEL | ACCEPTED, 0, 0, 0, 0, 0, 0]
            }
            4 => {
                // Only an eligible timer may sample the platform's second-request predicate.
                let decision = if self.has(FINISHED) || self.words[2] as i32 != value {
                    0
                } else if !self.has(HELD | REARMED) {
                    2
                } else {
                    1
                };
                [decision, 0, 0, 0, 0, 0, 0]
            }
            5 => {
                if condition {
                    self.words[1] |= HELD;
                    self.arm(45_000i64.wrapping_sub(self.words[9]), HOLD)
                } else {
                    self.words[1] |= FINISHED;
                    [TIMEOUT, 0, 0, 0, 0, 0, 0]
                }
            }
            6 => {
                // Bootstrap checkpoint commit precedes the platform log callback.
                if self.has(FINISHED) {
                    return [0; 7];
                }
                let highest = self.words[3];
                if highest >= value as i64 {
                    return [DUPLICATE, 0, 0, 0, highest, 0, 0];
                }
                let elapsed = elapsed(self.words[4], time);
                self.words[3] = value as i64;
                self.words[4] = time;
                [PROGRESS, 0, 0, elapsed, value as i64, 0, 0]
            }
            7 => {
                // Capture the stall and finish before sampling the injected clock.
                let reached = self.words[3];
                if self.has(FINISHED) || self.words[2] as i32 != value || !(0..6).contains(&reached)
                {
                    return [0; 7];
                }
                self.words[1] |= FINISHED;
                [TIMEOUT, 0, 0, 0, reached, reached + 1, self.words[4]]
            }
            8 => {
                if self.has(FINISHED) || self.words[5] != 0 {
                    return [0; 7];
                }
                self.words[5] = 1;
                self.words[6] = time;
                [PROGRESS, 0, 0, 0, 0, 0, time]
            }
            9 => {
                // Called after the StartSession log; acceptance may already have won there.
                if self.has(SUCCESS) || self.words[7] != 0 {
                    return [0; 7];
                }
                [ARM, self.words[9], 0, 0, 0, 0, self.words[6]]
            }
            10 => {
                if self.has(FINISHED) || !condition {
                    return [0; 7];
                }
                let first = self.words[7] == 0;
                if first {
                    self.words[7] = 1;
                    self.words[8] = time;
                }
                [
                    CANCEL | ACCEPTED | if first { FIRST_ACCEPT } else { 0 },
                    0,
                    0,
                    0,
                    0,
                    0,
                    time,
                ]
            }
            11 => {
                if self.has(FINISHED) {
                    return [0; 7];
                }
                [
                    ACCEPTED | if !self.has(SUCCESS) { LOG_SUCCESS } else { 0 },
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                ]
            }
            12 => {
                self.words[1] |= SUCCESS;
                [CANCEL | ACCEPTED, 0, 0, 0, 0, 0, 0]
            }
            13 => {
                if self.has(FINISHED | SUCCESS) || self.words[7] != 0 || self.words[5] == 0 {
                    return [0; 7];
                }
                self.words[1] |= FINISHED;
                [TIMEOUT, 0, 0, 0, 0, 0, self.words[6]]
            }
            14 => {
                // Bootstrap timer policy runs after its log, preserving callback/reentry order.
                match value {
                    6 => {
                        self.words[1] |= FINISHED;
                        [CANCEL, 0, 0, 0, 0, 0, 0]
                    }
                    5 => {
                        self.words[2] = (self.words[2] as i32).wrapping_add(1) as i64;
                        [CANCEL, 0, 0, 0, 0, 0, 0]
                    }
                    _ => {
                        let delay = budget(value + 1);
                        if delay <= 0 {
                            [0; 7]
                        } else {
                            self.arm(delay, CANCEL)
                        }
                    }
                }
            }
            15 => self.arm(self.words[9], 0),
            _ => [0; 7],
        }
    }
}

fn budget(missing: i32) -> i64 {
    match missing {
        1 => 45_000,
        2 => 30_000,
        3 => 65_000,
        4 | 5 => 45_000,
        _ => 0,
    }
}
fn elapsed(start: i64, now: i64) -> i64 {
    now.wrapping_sub(start).max(0) / 1_000_000
}
fn remaining(timeout: i64, start: i64, now: i64) -> i64 {
    timeout
        .wrapping_sub(now.wrapping_sub(start) / 1_000_000)
        .max(0)
}

fn start_again(
    now: i64,
    start: i64,
    opened: Option<i64>,
    active: bool,
    connection_attempt: bool,
) -> i32 {
    if active || connection_attempt {
        return 2;
    }
    let Some(opened) = opened else {
        return 0;
    };
    if opened.wrapping_sub(start) < 0 {
        2
    } else if now.wrapping_sub(opened) < 2_000_000_000 {
        0
    } else {
        1
    }
}
