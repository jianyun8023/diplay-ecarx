//! The video pipe's timing rules, ported one to one from Kotlin: `FramePacer` (the iPhone's frame time
//! on the local clock), `PacingDelay` (smooth video's display delay) and `VideoBacklogRecovery` (when a
//! growing input backlog drops the reference chain). Both Android decoder adapters use these owners.

mod jni;

pub struct FramePacer {
    window: usize,
    percentile: usize,
    refresh_every: i32,
    slew_per_second: f64,
    reset_nanos: i64,
    max_early_nanos: i64,
    residuals: std::collections::VecDeque<i64>,
    frames: i32,
    candidate: i64,
    offset: Option<i64>,
    last_arrival_nanos: i64,
}

const ONE_SECOND: i64 = 1_000_000_000;

impl Default for FramePacer {
    fn default() -> Self {
        FramePacer {
            window: 240, percentile: 5, refresh_every: 30, slew_per_second: 0.002, reset_nanos: 500_000_000,
            max_early_nanos: 100_000_000, residuals: Default::default(), frames: 0, candidate: 0, offset: None,
            last_arrival_nanos: 0,
        }
    }
}

impl FramePacer {
    /// The frame's local time, or 0 when that is implausible (show it as soon as it is decoded).
    pub fn local_time(&mut self, sender_nanos: i64, arrival_nanos: i64) -> i64 {
        self.residuals.push_back(arrival_nanos.wrapping_sub(sender_nanos));
        if self.residuals.len() > self.window { self.residuals.pop_front(); }
        let warming_up = self.frames < self.refresh_every;
        if warming_up || self.frames % self.refresh_every == 0 {
            let mut sorted: Vec<i64> = self.residuals.iter().copied().collect();
            sorted.sort_unstable();
            self.candidate = sorted[(sorted.len() - 1) * self.percentile / 100];
        }
        self.frames = self.frames.wrapping_add(1);
        let offset = match self.offset {
            Some(offset) if !warming_up && self.candidate.wrapping_sub(offset) <= self.reset_nanos &&
                offset.wrapping_sub(self.candidate) <= self.max_early_nanos => {
                let step = (arrival_nanos.wrapping_sub(self.last_arrival_nanos).max(0) as f64 * self.slew_per_second) as i64;
                offset.wrapping_add(self.candidate.wrapping_sub(offset).clamp(-step, step))
            }
            _ => self.candidate,
        };
        self.offset = Some(offset);
        self.last_arrival_nanos = arrival_nanos;
        let local = sender_nanos.wrapping_add(offset);
        if (-ONE_SECOND..=self.max_early_nanos).contains(&local.wrapping_sub(arrival_nanos)) { local } else { 0 }
    }
}

pub struct PacingDelay {
    min_nanos: i64,
    max_nanos: i64,
    percentile: usize,
    refresh_every: i32,
    margin_nanos: i64,
    rise_per_frame: i64,
    fall_per_frame: i64,
    needs: Vec<i64>,
    count: usize,
    next: usize,
    frames: i32,
    pub nanos: i64,
    goal: i64,
}

impl PacingDelay {
    pub fn new(initial_nanos: i64) -> PacingDelay {
        let (min_nanos, max_nanos) = (30_000_000, 200_000_000);
        let nanos = initial_nanos.clamp(min_nanos, max_nanos);
        PacingDelay {
            min_nanos, max_nanos, percentile: 90, refresh_every: 15, margin_nanos: 20_000_000,
            rise_per_frame: 1_000_000, fall_per_frame: 500_000, needs: vec![0; 120], count: 0, next: 0, frames: 0,
            nanos, goal: nanos,
        }
    }

    pub fn on_frame(&mut self, needed_nanos: i64) {
        let window = self.needs.len();
        self.needs[self.next] = needed_nanos.clamp(0, self.max_nanos);
        self.next = (self.next + 1) % window;
        if self.count < window { self.count += 1; }
        self.frames = self.frames.wrapping_add(1);
        if self.frames % self.refresh_every == 0 {
            let mut sorted = self.needs[..self.count].to_vec();
            sorted.sort_unstable();
            self.goal = sorted[(self.count - 1) * self.percentile / 100].wrapping_add(self.margin_nanos).clamp(self.min_nanos, self.max_nanos);
        }
        self.nanos = self.nanos.wrapping_add(self.goal.wrapping_sub(self.nanos).clamp(-self.fall_per_frame, self.rise_per_frame));
    }
}

#[derive(Default)]
pub struct BacklogRecovery {
    startup_ends_ns: Option<i64>,
    episode_start_ns: Option<i64>,
    initial_span_ns: i64,
    initial_pending_frames: i32,
    last_observed_ns: Option<i64>,
}

const SOFT_AGE_NS: i64 = 250_000_000;
const SOFT_SPAN_NS: i64 = 100_000_000;
const LOW_AGE_NS: i64 = 150_000_000;
const LOW_SPAN_NS: i64 = 50_000_000;
const HARD_AGE_NS: i64 = 1_500_000_000;
const GRACE_NS: i64 = 750_000_000;
const SPAN_GROWTH_NS: i64 = 100_000_000;
const FRAME_GROWTH: i32 = 4;
const STARTUP_GRACE_NS: i64 = 3_000_000_000;

impl BacklogRecovery {
    pub fn reset(&mut self) {
        self.episode_start_ns = None;
        self.initial_span_ns = 0;
        self.initial_pending_frames = 0;
        self.last_observed_ns = None;
    }

    pub fn decoder_started(&mut self, now_ns: i64) {
        self.reset();
        self.startup_ends_ns = Some(now_ns.wrapping_add(STARTUP_GRACE_NS));
    }

    /// True when the backlog behind the current frame calls for recovery.
    pub fn observe(&mut self, now_ns: i64, current_received_ns: i64, pending_frames: usize, newest_pending_received_ns: Option<i64>) -> bool {
        if let Some(ends) = self.startup_ends_ns {
            if now_ns < ends { self.reset(); return false; }
            self.startup_ends_ns = None;
        }
        let Some(newest) = newest_pending_received_ns else { self.reset(); return false };
        if pending_frames == 0 || current_received_ns > now_ns || newest > now_ns || newest < current_received_ns ||
            self.last_observed_ns.is_some_and(|last| now_ns < last) {
            self.reset();
            return false;
        }
        self.last_observed_ns = Some(now_ns);
        let age = now_ns.wrapping_sub(current_received_ns);
        let span = newest.wrapping_sub(current_received_ns);
        if age <= LOW_AGE_NS || span <= LOW_SPAN_NS { self.reset(); return false; }
        if age >= HARD_AGE_NS && span >= SOFT_SPAN_NS { self.reset(); return true; }
        if age <= SOFT_AGE_NS || span < SOFT_SPAN_NS { return false; }
        let Some(start) = self.episode_start_ns else {
            self.episode_start_ns = Some(now_ns);
            self.initial_span_ns = span;
            self.initial_pending_frames = pending_frames as i32;
            return false;
        };
        let growing = span.wrapping_sub(self.initial_span_ns) >= SPAN_GROWTH_NS ||
            (pending_frames as i32).wrapping_sub(self.initial_pending_frames) >= FRAME_GROWTH;
        if now_ns.wrapping_sub(start) >= GRACE_NS && growing { self.reset(); return true; }
        false
    }
}

/// `VideoRecoveryFrameAge.isObsolete`: a keyframe that waited 1.5 s with a newer frame 100 ms later
/// behind it is not worth decoding.
pub fn is_obsolete(now_ns: i64, received_ns: i64, pending_frames: usize, newest_pending_received_ns: Option<i64>) -> bool {
    now_ns >= received_ns && now_ns.wrapping_sub(received_ns) >= 1_500_000_000 && pending_frames > 0 &&
        newest_pending_received_ns.is_some_and(|newest| newest <= now_ns && newest.wrapping_sub(received_ns) >= 100_000_000)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_pacer_follows_the_fastest_frames_and_rejects_implausible_ones() {
        let mut pacer = FramePacer::default();
        // A steady 10 ms link delay: the local time is the arrival time.
        for n in 0..40 {
            let sender = n * 16_666_667;
            assert_eq!(pacer.local_time(sender, sender + 10_000_000), sender + 10_000_000);
        }
        // A frame stamped two seconds early is implausible.
        assert_eq!(pacer.local_time(0, 3 * ONE_SECOND), 0);
    }

    #[test]
    fn the_delay_moves_towards_its_goal_slowly() {
        let mut delay = PacingDelay::new(50_000_000);
        for _ in 0..15 { delay.on_frame(100_000_000); }
        // goal 120 ms; one 1 ms step on the frame that set it.
        assert_eq!(delay.nanos, 51_000_000);
    }

    #[test]
    fn a_growing_backlog_recovers_after_the_grace_and_a_new_decoder_waits() {
        let mut backlog = BacklogRecovery::default();
        assert!(!backlog.observe(1_000_000_000, 700_000_000, 5, Some(850_000_000)));
        assert!(backlog.observe(1_800_000_000, 1_500_000_000, 10, Some(1_700_000_000)));
        backlog.decoder_started(2_000_000_000);
        assert!(!backlog.observe(2_100_000_000, 0, 50, Some(2_000_000_000)));
    }
}
