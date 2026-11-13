//! Receive-thread timing, the Rust twin of Kotlin's `StreamReceiveStats`: the same counters and the
//! same `Receive:` line, so session logs and the scripts that read them do not change. Only timing,
//! sizes and RTP sequence numbers are recorded; no payloads or addresses.

pub const LONG_READ_NS: i64 = 250_000_000;
/// Healthy-stream heartbeat for the session streams; anomalies report within [ANOMALY_MIN_INTERVAL_NS].
pub const SESSION_REPORT_INTERVAL_NS: i64 = 300_000_000_000;
pub const ANOMALY_MIN_INTERVAL_NS: i64 = 5_000_000_000;

pub trait Clock {
    fn now_ns(&self) -> i64;
    /// CPU time of the calling thread, or -1 when unknown.
    fn thread_cpu_ns(&self) -> i64;
}

pub struct SystemClock;

impl Clock for SystemClock {
    fn now_ns(&self) -> i64 { clock(libc::CLOCK_MONOTONIC) }
    fn thread_cpu_ns(&self) -> i64 { clock(libc::CLOCK_THREAD_CPUTIME_ID) }
}

/// `System.nanoTime()` on Android reads CLOCK_MONOTONIC, so arrival times compare with Kotlin's.
pub fn clock(id: libc::clockid_t) -> i64 {
    let mut ts = libc::timespec { tv_sec: 0, tv_nsec: 0 };
    if unsafe { libc::clock_gettime(id, &mut ts) } != 0 { return -1; }
    ts.tv_sec as i64 * 1_000_000_000 + ts.tv_nsec as i64
}

pub struct ReceiveStats<C: Clock> {
    label: String,
    report_interval_ns: i64,
    clock: C,
    window_start: i64,
    window_cpu_start: i64,
    read_start: i64,
    processing_start: i64,
    packets: u64,
    bytes: u64,
    max_read_ns: i64,
    reads_over_250ms: u32,
    seq_gap_after_read_over_250ms: u32,
    seq_missing_after_read_over_250ms: u32,
    last_received_ns: i64,
    max_inter_arrival_ns: i64,
    max_process_ns: i64,
    last_timestamp: Option<u32>,
    next_sequence: Option<u16>,
    forward_gap_packets: u32,
    sequence_gap_events: u32,
    max_sequence_gap: u32,
    last_sequence_gap: String,
    last_sequence_gap_at_ms: i64,
    late_or_duplicate: u32,
    decrypt_samples: u64,
    decrypt_bytes: u64,
    decrypt_sum_ns: i64,
    max_decrypt_ns: i64,
}

impl<C: Clock> ReceiveStats<C> {
    pub fn new(label: String, report_interval_ns: i64, clock: C) -> Self {
        let now = clock.now_ns();
        ReceiveStats {
            label, report_interval_ns, clock,
            window_start: now, window_cpu_start: -1, read_start: now, processing_start: now,
            packets: 0, bytes: 0, max_read_ns: 0, reads_over_250ms: 0,
            seq_gap_after_read_over_250ms: 0, seq_missing_after_read_over_250ms: 0,
            last_received_ns: 0, max_inter_arrival_ns: 0, max_process_ns: 0,
            last_timestamp: None, next_sequence: None, forward_gap_packets: 0, sequence_gap_events: 0,
            max_sequence_gap: 0, last_sequence_gap: "none".into(), last_sequence_gap_at_ms: -1,
            late_or_duplicate: 0, decrypt_samples: 0, decrypt_bytes: 0, decrypt_sum_ns: 0, max_decrypt_ns: 0,
        }
    }

    pub fn now(&self) -> i64 { self.clock.now_ns() }

    pub fn reading(&mut self) {
        self.read_start = self.clock.now_ns();
        if self.window_cpu_start < 0 { self.window_cpu_start = self.clock.thread_cpu_ns(); }
    }

    pub fn received(&mut self, size: usize, sequence: Option<u16>, timestamp: Option<u32>) {
        self.processing_start = self.clock.now_ns();
        let read_ns = self.processing_start - self.read_start;
        self.max_read_ns = self.max_read_ns.max(read_ns);
        if read_ns >= LONG_READ_NS { self.reads_over_250ms += 1; }
        if self.last_received_ns != 0 {
            self.max_inter_arrival_ns = self.max_inter_arrival_ns.max(self.processing_start - self.last_received_ns);
        }
        self.last_received_ns = self.processing_start;
        self.packets += 1;
        self.bytes += size as u64;
        if let Some(sequence) = sequence {
            let delta = match self.next_sequence { None => 0u32, Some(expected) => sequence.wrapping_sub(expected) as u32 };
            if delta < 0x8000 {
                self.forward_gap_packets += delta;
                if delta > 0 {
                    self.sequence_gap_events += 1;
                    if read_ns >= LONG_READ_NS {
                        self.seq_gap_after_read_over_250ms += 1;
                        self.seq_missing_after_read_over_250ms += delta;
                    }
                    self.max_sequence_gap = self.max_sequence_gap.max(delta);
                    let unknown = |t: Option<u32>| t.map(|v| v.to_string()).unwrap_or_else(|| "unknown".into());
                    self.last_sequence_gap = format!(
                        "expected={} received={} missing={} previousRtpTs={} receivedRtpTs={}",
                        self.next_sequence.unwrap(), sequence, delta, unknown(self.last_timestamp), unknown(timestamp),
                    );
                    self.last_sequence_gap_at_ms = (self.processing_start - self.window_start) / 1_000_000;
                }
                self.next_sequence = Some(sequence.wrapping_add(1));
            } else {
                self.late_or_duplicate += 1;
            }
        }
        if timestamp.is_some() { self.last_timestamp = timestamp; }
    }

    pub fn decrypted(&mut self, duration_ns: i64, size: usize) {
        self.decrypt_samples += 1;
        self.decrypt_bytes += size as u64;
        self.decrypt_sum_ns += duration_ns;
        self.max_decrypt_ns = self.max_decrypt_ns.max(duration_ns);
    }

    /// The report line, when one is due.
    pub fn processed(&mut self, extra: &dyn Fn() -> String) -> Option<String> {
        self.max_process_ns = self.max_process_ns.max(self.clock.now_ns() - self.processing_start);
        self.flush(false, extra)
    }

    pub fn flush(&mut self, ended: bool, extra: &dyn Fn() -> String) -> Option<String> {
        let now = self.clock.now_ns();
        let elapsed = now - self.window_start;
        let anomaly = self.sequence_gap_events > 0 || self.late_or_duplicate > 0;
        if !ended && elapsed < self.report_interval_ns && (!anomaly || elapsed < ANOMALY_MIN_INTERVAL_NS) {
            return None;
        }
        let cpu = self.thread_cpu_summary();
        let line = format!(
            "Receive: {} packets={} bytes={} readMaxMs={} processMaxUs={} seqForwardGaps={} lateOrDuplicate={} \
             interArrivalMaxMs={} seqGapEvents={} seqGapMax={} seqGapLast=[{}] seqGapAtMs={} ended={} windowMs={} \
             readsOver250Ms={} seqGapAfterReadOver250Ms={} seqMissingAfterReadOver250Ms={}{}{}{}",
            self.label, self.packets, self.bytes, self.max_read_ns / 1_000_000, self.max_process_ns / 1000,
            self.forward_gap_packets, self.late_or_duplicate, self.max_inter_arrival_ns / 1_000_000,
            self.sequence_gap_events, self.max_sequence_gap, self.last_sequence_gap, self.last_sequence_gap_at_ms,
            ended, elapsed.max(0) / 1_000_000, self.reads_over_250ms, self.seq_gap_after_read_over_250ms,
            self.seq_missing_after_read_over_250ms, cpu, self.decrypt_summary(), extra(),
        );
        self.window_start = now;
        self.decrypt_samples = 0;
        self.decrypt_bytes = 0;
        self.decrypt_sum_ns = 0;
        self.max_decrypt_ns = 0;
        self.packets = 0;
        self.bytes = 0;
        self.max_read_ns = 0;
        self.reads_over_250ms = 0;
        self.seq_gap_after_read_over_250ms = 0;
        self.seq_missing_after_read_over_250ms = 0;
        self.max_inter_arrival_ns = 0;
        self.max_process_ns = 0;
        self.forward_gap_packets = 0;
        self.sequence_gap_events = 0;
        self.max_sequence_gap = 0;
        self.last_sequence_gap = "none".into();
        self.last_sequence_gap_at_ms = -1;
        self.late_or_duplicate = 0;
        Some(line)
    }

    fn thread_cpu_summary(&mut self) -> String {
        let start = self.window_cpu_start;
        let cpu = self.clock.thread_cpu_ns();
        self.window_cpu_start = cpu;
        if start < 0 || cpu < 0 { String::new() } else { format!(" threadCpuMs={}", (cpu - start) / 1_000_000) }
    }

    fn decrypt_summary(&self) -> String {
        if self.decrypt_samples == 0 { return String::new(); }
        let mb_per_second = if self.decrypt_sum_ns == 0 { 0 } else { self.decrypt_bytes as i64 * 1000 / self.decrypt_sum_ns };
        format!(
            " decryptAvgUs={} decryptMaxUs={} decryptMBps={}",
            self.decrypt_sum_ns / self.decrypt_samples as i64 / 1000, self.max_decrypt_ns / 1000, mb_per_second,
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn video_input_drain_keeps_arrival_history_and_separates_idle_gaps() {
        let mut stats = VideoInputStats::default();
        stats.received(100, 1_000_000_000, Some(20_000_000));
        stats.received(200, 1_030_000_000, Some(10_000_000));
        assert_eq!(stats.take(), [2, 300, 30_000_000, 0, 0, 2, 30_000_000, 20_000_000]);
        stats.received(50, 3_530_000_000, None);
        assert_eq!(stats.take(), [1, 50, 0, 1, 2_500_000_000, 0, 0, 0]);
        assert_eq!(stats.take(), [0; 8]);
    }
    use std::cell::Cell;
    use std::rc::Rc;

    #[derive(Clone)]
    struct Fake(Rc<Cell<i64>>);
    impl Clock for Fake {
        fn now_ns(&self) -> i64 { self.0.get() }
        fn thread_cpu_ns(&self) -> i64 { -1 }
    }

    #[test]
    fn a_sequence_gap_reports_after_five_seconds_in_the_kotlin_format() {
        let time = Rc::new(Cell::new(0));
        let mut stats = ReceiveStats::new("audio type=100".into(), SESSION_REPORT_INTERVAL_NS, Fake(time.clone()));
        stats.reading();
        stats.received(100, Some(10), Some(1000));
        assert!(stats.processed(&|| String::new()).is_none());
        time.set(1_000_000_000);
        stats.reading();
        stats.received(100, Some(13), Some(4000));
        assert!(stats.processed(&|| String::new()).is_none());
        time.set(ANOMALY_MIN_INTERVAL_NS);
        let line = stats.processed(&|| " impl=rust".into()).unwrap();
        assert!(line.starts_with("Receive: audio type=100 packets=2 bytes=200 "), "{line}");
        assert!(line.contains("seqForwardGaps=2 lateOrDuplicate=0"), "{line}");
        assert!(line.contains("seqGapLast=[expected=11 received=13 missing=2 previousRtpTs=1000 receivedRtpTs=4000] seqGapAtMs=1000"), "{line}");
        assert!(line.ends_with(" impl=rust"), "{line}");
    }

    #[test]
    fn a_late_packet_counts_and_does_not_move_the_expected_sequence() {
        let time = Rc::new(Cell::new(0));
        let mut stats = ReceiveStats::new("x".into(), SESSION_REPORT_INTERVAL_NS, Fake(time));
        stats.received(1, Some(65535), None);
        stats.received(1, Some(0), None);
        stats.received(1, Some(65535), None);
        let line = stats.flush(true, &|| String::new()).unwrap();
        assert!(line.contains("seqForwardGaps=0 lateOrDuplicate=1"), "{line}");
    }
}
/// Per-decoder input metrics, drained with the output metrics without losing the last arrival.
#[derive(Default)]
pub struct VideoInputStats {
    last_arrival_ns: i64,
    /// received, bytes, active max gap, idle gaps/max, touch samples/sum/max.
    values: [i64; 8],
}

impl VideoInputStats {
    pub fn received(&mut self, size: usize, now: i64, touch_latency: Option<i64>) {
        if self.last_arrival_ns != 0 {
            let gap = now - self.last_arrival_ns;
            if gap >= 2_000_000_000 {
                self.values[3] += 1;
                self.values[4] = self.values[4].max(gap);
            } else {
                self.values[2] = self.values[2].max(gap);
            }
        }
        self.last_arrival_ns = now;
        self.values[0] += 1;
        self.values[1] += size as i64;
        if let Some(latency) = touch_latency.filter(|latency| *latency >= 0) {
            self.values[5] += 1;
            self.values[6] += latency;
            self.values[7] = self.values[7].max(latency);
        }
    }

    pub fn take(&mut self) -> [i64; 8] { std::mem::take(&mut self.values) }
}
