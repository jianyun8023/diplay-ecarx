//! The vehicle-calibrated music PI controller; same targets, sampling and limits as MediaClockTrim.
use jni_sys::{jboolean, jclass, jdouble, jint, jlong, JNIEnv};
use std::sync::atomic::{AtomicU64, Ordering};

static LEARNED_TRIM: AtomicU64 = AtomicU64::new(0.0f64.to_bits());
const MAX_TRIM: f64 = 0.015;
const STALE_NS: i64 = 1_000_000_000;

struct Measuring { half_start_ns: Option<i64>, earlier_mean: Option<f64>, level_frame_ns: f64 }

impl Measuring {
    fn new(half_start_ns: Option<i64>, earlier_mean: Option<f64>) -> Self {
        Self { half_start_ns, earlier_mean, level_frame_ns: 0.0 }
    }
}

pub struct ClockTrim {
    sample_rate: i32,
    target: i64,
    pub integral: f64,
    measures_target: bool,
    level: Option<f64>,
    pub last_sample_ns: i64,
    last_buffered: i64,
    last_update_ns: Option<i64>,
    compressed_backlog: bool,
    measuring: Option<Measuring>,
    pub rate: i32,
}

impl ClockTrim {
    pub fn new(sample_rate: i32, target: i64, integral: f64, measures_target: bool) -> Self {
        Self { sample_rate, target, integral, measures_target, level: None, last_sample_ns: 0,
            last_buffered: 0, last_update_ns: None, compressed_backlog: false,
            measuring: measures_target.then(|| Measuring::new(None, None)), rate: sample_rate }
    }

    pub fn measure_again(&mut self) {
        if self.measures_target { self.measuring = Some(Measuring::new(None, None)); }
    }

    pub fn sample(&mut self, queued: i64, now_ns: i64, compressed: i64, head_moving: bool, arrived_frame_ns: f64) {
        if self.target <= 0 { return; }
        self.compressed_backlog = compressed > self.target;
        let buffered = queued.max(0) + compressed.clamp(0, self.sample_rate as i64 * 16);
        let elapsed_ns = now_ns - self.last_sample_ns;
        let start = self.last_buffered as f64;
        let drained = self.rate as f64 * elapsed_ns as f64 / 1e9;
        let mean = if drained <= 0.0 { start } else if drained <= start { start - drained / 2.0 }
            else { start * start / (2.0 * drained) } + if elapsed_ns > 0 { arrived_frame_ns / elapsed_ns as f64 } else { 0.0 };
        if let Some(measure) = self.measuring.as_mut() {
            if !head_moving { return; }
            if let Some(half_start) = measure.half_start_ns {
                measure.level_frame_ns += mean * elapsed_ns as f64;
                if now_ns - half_start >= 1_500_000_000 {
                    let half_mean = measure.level_frame_ns / (now_ns - half_start) as f64;
                    if let Some(earlier) = measure.earlier_mean.filter(|earlier| (half_mean - earlier).abs() <= self.sample_rate as f64 * 0.2) {
                        self.target = earlier.round() as i64;
                        self.level = Some(0.0);
                        self.measuring = None;
                    } else {
                        self.measuring = Some(Measuring::new(Some(now_ns), Some(half_mean)));
                    }
                }
            } else { self.measuring = Some(Measuring::new(Some(now_ns), None)); }
        } else {
            let error = |frames: f64| (frames - self.target as f64) / self.target as f64;
            self.level = Some(match self.level {
                Some(previous) if elapsed_ns <= STALE_NS => previous + (error(mean) - previous) * (1.0 - (-(elapsed_ns as f64) / 5e9).exp()),
                _ => error(buffered as f64),
            });
        }
        self.last_buffered = buffered;
        self.last_sample_ns = now_ns;
    }

    pub fn rate_due(&mut self, now_ns: i64) -> Option<i32> {
        let next = if self.measuring.is_some() {
            (self.sample_rate as f64 * (1.0 + self.integral)).round() as i32
        } else {
            let error = self.level?;
            if now_ns - self.last_sample_ns > STALE_NS { return None; }
            if self.last_update_ns.is_some_and(|last| now_ns - last < 1_000_000_000) { return None; }
            let seconds = self.last_update_ns.map(|last| ((now_ns - last) as f64 / 1e9).min(2.0)).unwrap_or(0.0);
            self.last_update_ns = Some(now_ns);
            if !self.compressed_backlog {
                self.integral = (self.integral + 0.001 * error * seconds).clamp(-MAX_TRIM, MAX_TRIM);
                LEARNED_TRIM.store(self.integral.to_bits(), Ordering::Relaxed);
            }
            (self.sample_rate as f64 * (1.0 + (0.03 * error + self.integral).clamp(-MAX_TRIM, MAX_TRIM))).round() as i32
        };
        if self.rate == next { None } else { self.rate = next; Some(next) }
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_clockCreate(
    _env: *mut JNIEnv, _class: jclass, sample_rate: jint, target: jlong, integral: jdouble, measures_target: jboolean,
) -> jlong {
    if sample_rate <= 0 || !integral.is_finite() { return 0; }
    Box::into_raw(Box::new(ClockTrim::new(sample_rate, target, integral.clamp(-MAX_TRIM, MAX_TRIM), measures_target != 0))) as jlong
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_clockSample(
    _env: *mut JNIEnv, _class: jclass, handle: jlong, queued: jlong, now_ns: jlong,
    compressed: jlong, head_moving: jboolean, arrived_frame_ns: jdouble,
) { if handle != 0 { (&mut *(handle as *mut ClockTrim)).sample(queued, now_ns, compressed, head_moving != 0, arrived_frame_ns); } }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_clockRateDue(
    _env: *mut JNIEnv, _class: jclass, handle: jlong, now_ns: jlong,
) -> jint {
    if handle == 0 { return 0; }
    (&mut *(handle as *mut ClockTrim)).rate_due(now_ns).unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_clockQuery(
    _env: *mut JNIEnv, _class: jclass, handle: jlong, what: jint,
) -> jlong {
    if handle == 0 { return 0; }
    let trim = &mut *(handle as *mut ClockTrim);
    match what { 0 => trim.rate as i64, 1 => trim.last_sample_ns, 2 => { trim.measure_again(); 0 }, _ => 0 }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_clockLearned(
    _env: *mut JNIEnv, _class: jclass, set: jboolean, value: jdouble,
) -> jdouble {
    if set != 0 && value.is_finite() { LEARNED_TRIM.store(value.clamp(-MAX_TRIM, MAX_TRIM).to_bits(), Ordering::Relaxed); }
    f64::from_bits(LEARNED_TRIM.load(Ordering::Relaxed))
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_clockDestroy(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) { if handle != 0 { drop(Box::from_raw(handle as *mut ClockTrim)); } }

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pause_and_decoder_congestion_do_not_teach_false_hardware_drift() {
        let mut trim = ClockTrim::new(48_000, 14_400, 0.0, false);
        trim.sample(14_400, 0, 0, true, 0.0);
        assert_eq!(trim.rate_due(0), None);
        trim.sample(10_080, 300_000_000_000, 0, true, 0.0);
        assert_eq!(trim.rate_due(300_000_000_000), Some(47_539));
        assert!((trim.integral + 0.0006).abs() < 1e-9);
        assert_eq!(trim.rate_due(302_000_000_000), None);
        let learned = trim.integral;
        for second in 303..330 {
            let now = second * 1_000_000_000;
            trim.sample(4800, now, 96 * 1024, true, 0.0);
            trim.rate_due(now);
        }
        assert_eq!(trim.integral, learned);
        assert!(trim.rate >= 48_000);
    }

    #[test]
    fn target_measurement_waits_for_a_moving_head_and_restarts_after_rebuffer() {
        let mut trim = ClockTrim::new(48_000, 14_400, -0.0075, true);
        trim.sample(48_000, 1_000_000_000, 0, false, 0.0);
        assert_eq!(trim.last_sample_ns, 0);
        assert_eq!(trim.rate_due(1_000_000_000), Some(47_640));
        for step in 1..=40 { trim.sample(48_000, step * 100_000_000, 0, true, 0.0); }
        assert!(trim.measuring.is_none());
        assert!(trim.target > 40_000);
        trim.measure_again();
        assert!(trim.measuring.is_some());
        assert_eq!(trim.integral, -0.0075);
    }

    #[test]
    fn the_trim_compensates_fast_and_slow_head_units_without_exhausting_the_music_buffer() {
        for drift in [0.0, 0.0076, -0.0076] {
            let mut trim = ClockTrim::new(48_000, 14_400, 0.0, false);
            let mut queued: f64 = 14_400.0;
            let mut played = 0.0;
            let mut lowest = queued;
            for step in 0..30_000 {
                // 10 ms decode/write bursts and 1024-frame hardware periods.
                queued += 480.0;
                let now = step * 10_000_000;
                trim.sample(queued as i64, now, 0, true, 0.0);
                trim.rate_due(now);
                played += trim.rate as f64 * (1.0 + drift) / 100.0;
                while played >= 1024.0 { played -= 1024.0; queued -= 1024.0; }
                lowest = queued.min(lowest);
            }
            assert!(lowest > 2880.0, "drift {drift}, low {lowest}");
            assert!((trim.rate as f64 - 48_000.0 / (1.0 + drift)).abs() < 15.0, "drift {drift}, rate {}", trim.rate);
            assert!(trim.integral.abs() <= MAX_TRIM);
        }
    }
}
