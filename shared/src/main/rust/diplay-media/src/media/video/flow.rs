//! Decode-chain and queue policy shared with the Android SDK adapter. SDK job objects stay in a GC carrier.
mod jni;
use std::collections::VecDeque;
use std::sync::atomic::{AtomicI64, Ordering};

static PENDING_TOUCH_NS: AtomicI64 = AtomicI64::new(0);
pub fn touch_sent(sent_at: i64) { let _ = PENDING_TOUCH_NS.compare_exchange(0, sent_at, Ordering::Relaxed, Ordering::Relaxed); }
pub fn touch_latency(now: i64) -> Option<i64> {
    let sent = PENDING_TOUCH_NS.swap(0, Ordering::Relaxed);
    (sent != 0).then_some(now.wrapping_sub(sent))
}
pub fn keyframe_due(last: i64, now: i64) -> bool { last == 0 || now.wrapping_sub(last) >= 1_000_000_000 }

// The SDK adapter executes one running/drain/dequeue/clock action at a time; no I/O lives here.
fn input_step(v: &mut [i64; 4], event: i32, value: i64) -> i32 {
    match event {
        0 => {},
        1 => v[0] = if value != 0 { 1 } else { 4 },
        2 => v[0] = 2,
        3 => if value >= 0 { v[1] = value; v[0] = 4; } else { v[0] = 3; },
        4 => v[0] = if value.wrapping_sub(v[2]) >= v[3] { 4 } else { 0 },
        _ => unreachable!(),
    }
    v[0] as i32
}

pub fn frame_overflow(frames: usize, bytes: i64, incoming: i64, max_frames: i32, max_bytes: i32) -> bool {
    frames as i64 >= max_frames as i64 || bytes.wrapping_add(incoming) > max_bytes as i64
}

pub struct ReferenceChain { needs_keyframe: bool }
impl Default for ReferenceChain { fn default() -> Self { Self { needs_keyframe: true } } }
impl ReferenceChain {
    pub fn reset(&mut self) { self.needs_keyframe = true; }
    pub fn queued(&mut self) { self.needs_keyframe = false; }
    pub fn needs_keyframe(&self) -> bool { self.needs_keyframe }
    pub fn accepts(&self, random_access: bool) -> bool { !self.needs_keyframe || random_access }
}

#[derive(Debug)]
struct Job { slot: i32, kind: i64, size: i64, received: i64 }
struct DecodeQueue { max_frames: i32, max_bytes: i32, drained: bool, jobs: VecDeque<Job> }
impl DecodeQueue {
    fn offer(&mut self, job: Job) -> Vec<Job> {
        let mut removed = Vec::new();
        if job.kind == 1 {
            if self.drained { return vec![job]; }
            let frames: Vec<_> = self.jobs.iter().filter(|j| j.kind == 1).collect();
            let bytes = frames.iter().fold(0i64, |sum, j| sum.wrapping_add(j.size));
            if frame_overflow(frames.len(), bytes, job.size, self.max_frames, self.max_bytes) {
                removed = self.remove(1);
                self.jobs.push_back(Job { slot: -2, kind: 2, size: 0, received: 0 });
            }
            if job.size > self.max_bytes as i64 { removed.push(job); return removed; }
        }
        self.jobs.push_back(job);
        removed
    }
    fn remove(&mut self, op: i32) -> Vec<Job> {
        match op {
            0 => self.jobs.pop_front().into_iter().collect(),
            1 => {
                let mut removed = Vec::new();
                let mut controls = VecDeque::new();
                for job in self.jobs.drain(..) {
                    if job.kind == 0 { controls.push_back(job); } else { removed.push(job); }
                }
                self.jobs = controls;
                removed
            },
            2 => {
                let count = self.jobs.iter().take_while(|j| j.kind != 0).count();
                self.jobs.drain(..count).collect()
            },
            3 => { self.drained = true; self.jobs.drain(..).collect() },
            _ => unreachable!(),
        }
    }
    fn backlog(&self) -> [i64; 3] {
        let mut count = 0;
        let mut newest: Option<i64> = None;
        for job in self.jobs.iter().take_while(|j| j.kind == 1) {
            count += 1;
            newest = Some(newest.map_or(job.received, |n| n.max(job.received)));
        }
        [count, newest.unwrap_or(0), newest.is_some() as i64]
    }
    fn resync_pending(&self) -> bool { self.jobs.iter().find(|j| j.kind != 1).is_some_and(|j| j.kind == 2) }
}

#[cfg(test)]
mod contracts {
    use super::*;
    fn frame(slot: i32, size: i64, received: i64) -> Job { Job { slot, kind: 1, size, received } }
    #[test]
    fn queue_overflow_preserves_control_order_and_drained_queue_rejects_only_frames() {
        let mut q = DecodeQueue { max_frames: 2, max_bytes: 8, drained: false, jobs: VecDeque::new() };
        q.offer(Job { slot: 0, kind: 0, size: 0, received: 0 });
        q.offer(frame(1, 3, 100)); q.offer(frame(2, 3, 200));
        assert_eq!(q.offer(frame(3, 3, 300)).iter().map(|j| j.slot).collect::<Vec<_>>(), [1, 2]);
        assert_eq!(q.remove(0)[0].slot, 0);
        assert!(q.resync_pending());
        assert_eq!(q.remove(0)[0].kind, 2);
        assert_eq!(q.backlog(), [1, 300, 1]);
        assert_eq!(q.remove(3)[0].slot, 3);
        assert_eq!(q.offer(frame(4, 1, 400))[0].slot, 4);
        q.offer(Job { slot: 5, kind: 0, size: 0, received: 0 });
        assert_eq!(q.remove(0)[0].slot, 5);
    }
    #[test]
    fn reference_is_accepted_only_after_input_queued_and_prefix_discard_stops_at_control() {
        let mut c = ReferenceChain::default();
        assert!(!c.accepts(false)); assert!(c.accepts(true)); assert!(c.needs_keyframe());
        c.queued(); assert!(c.accepts(false)); c.reset(); assert!(!c.accepts(false));
        let mut q = DecodeQueue { max_frames: 60, max_bytes: 8, drained: false, jobs: VecDeque::new() };
        q.offer(frame(0, 1, 200)); q.offer(frame(1, 1, 100));
        q.offer(Job { slot: 2, kind: 0, size: 0, received: 0 }); q.offer(frame(3, 1, 300));
        assert_eq!(q.backlog(), [2, 200, 1]);
        assert_eq!(q.remove(2).iter().map(|j| j.slot).collect::<Vec<_>>(), [0, 1]);
        assert_eq!(q.remove(0)[0].slot, 2);
        assert_eq!(q.backlog(), [1, 300, 1]);
    }
    #[test]
    fn sdk_input_budget_keeps_drain_dequeue_clock_order_and_accepts_late_success() {
        let mut v = [0, -1, 0, 100];
        assert_eq!(input_step(&mut v, 1, 1), 1);
        assert_eq!(input_step(&mut v, 2, 0), 2);
        assert_eq!(input_step(&mut v, 3, -1), 3);
        assert_eq!(input_step(&mut v, 4, 260), 4);
        assert_eq!(v[1], -1);
        let mut v = [0, -1, 0, 100];
        input_step(&mut v, 1, 1); input_step(&mut v, 2, 0);
        assert_eq!(input_step(&mut v, 3, 4), 4); assert_eq!(v[1], 4);
        assert!(!keyframe_due(i64::MAX - 100, i64::MIN + 100));
        assert!(keyframe_due(i64::MAX - 100, i64::MIN + 1_000_000_000));
    }
}
