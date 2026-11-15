//! Session-level diagnostics only: no playback decisions, per-packet JNI or retained resources.
use crate::platform::jvm::Env;
use jni_sys::{jclass, jint, jlong, jlongArray, jstring, JNIEnv};

const SIZE: usize = 24;
const LAST_REPORT: usize = 16;
const LAST_ANOMALY: usize = 17;
const PHASE: usize = 18; // running, closing, final
const ACTIVE: usize = 19;
const DIRTY: usize = 20;
const ANOMALY_SEEN: usize = 21;
const INITIALIZED: usize = 22;
const INTERVAL_MS: i64 = 60_000;

fn summary(s: &[i64; SIZE]) -> String {
    format!("Media health totals audioRx={} audioQueueDrops={} underruns={} writeErrors={} zeroWrites={} partialWrites={} decoderDrops={} rebuffers={} videoRx={} videoRendered={} videoRecoveries={} videoCodecErrors={} videoQueueDrops={} audioEnded={} videoEnded={} writtenFrames={} pendingWorkers={} closing={} final={}",
        s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], s[8], s[9], s[10], s[11], s[12], s[13], s[14], s[15],
        s[ACTIVE], s[PHASE] != 0, s[PHASE] == 2)
}

fn step(s: &mut [i64; SIZE], previous: &mut [i64; 3], event: i32, now: i64, v: &[i64]) -> Option<String> {
    if event == 4 { return Some(summary(s)); }
    if s[PHASE] == 2 { return None; }
    // The output track has its own underrun origin; decoder drops/rebuffers belong to the renderer.
    if event == 5 { previous[0] = 0; return None; }
    if s[INITIALIZED] == 0 {
        s[INITIALIZED] = 1;
        s[LAST_REPORT] = now;
    }
    let mut delta = [0i64; 16];
    let mut ended = false;
    match event {
        0 => { s[ACTIVE] += 1; return None; }
        1 => {
            delta[..6].copy_from_slice(&v[..6]);
            // Android underruns and the renderer's existing decoder/rebuffer facts are cumulative.
            for (index, (slot, current)) in [(2, v[2]), (6, v[6]), (7, v[7])].iter().enumerate() {
                delta[*slot] = if *current >= previous[index] { *current - previous[index] } else { *current };
                previous[index] = *current;
            }
            delta[15] = v[8];
            ended = v[9] != 0;
            delta[13] = ended as i64;
        }
        2 => {
            delta[8..13].copy_from_slice(&v[..5]);
            ended = v[5] != 0;
            delta[14] = ended as i64;
        }
        3 => { if s[PHASE] != 0 { return None; } s[PHASE] = 1; }
        _ => return None,
    }
    if ended { s[ACTIVE] = (s[ACTIVE] - 1).max(0); }
    for i in 0..16 { s[i] = s[i].saturating_add(delta[i].max(0)); }
    let anomaly = [1, 2, 3, 4, 6, 7, 10, 11, 12].iter().any(|&i| delta[i] > 0);
    let first_anomaly = anomaly && s[ANOMALY_SEEN] == 0;
    if delta.iter().any(|&v| v > 0) { s[DIRTY] = 1; }
    let final_sample = s[PHASE] == 1 && s[ACTIVE] == 0;
    if final_sample { s[PHASE] = 2; }
    // Normal and repeated-anomaly output share a one-minute budget. The first anomaly is immediate.
    if event == 3 || final_sample || first_anomaly || (s[DIRTY] != 0 && now - s[LAST_REPORT] >= INTERVAL_MS) {
        if anomaly { s[ANOMALY_SEEN] = 1; s[LAST_ANOMALY] = now; }
        s[LAST_REPORT] = now;
        s[DIRTY] = 0;
        return Some(summary(s));
    }
    None
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_healthCreate(env: *mut JNIEnv, _: jclass) -> jlongArray {
    ((**env).NewLongArray.unwrap())(env, SIZE as i32)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_healthStep(
    env: *mut JNIEnv, _: jclass, token: jlongArray, previous: jlongArray, event: jint, now: jlong, values: jlongArray,
) -> jstring {
    let f = &**env;
    let count = match event { 1 => 10, 2 => 6, 0 | 3 | 4 | 5 => 0, _ => -1 };
    let has_previous = event == 1 || event == 5;
    if token.is_null() || (f.GetArrayLength.unwrap())(env, token) != SIZE as i32 || count < 0 ||
        (count > 0 && (values.is_null() || (f.GetArrayLength.unwrap())(env, values) != count)) ||
        (has_previous && (previous.is_null() || (f.GetArrayLength.unwrap())(env, previous) != 3)) {
        let class = (f.FindClass.unwrap())(env, c"java/lang/IllegalArgumentException".as_ptr());
        (f.ThrowNew.unwrap())(env, class, c"Invalid media health sample".as_ptr());
        (f.DeleteLocalRef.unwrap())(env, class);
        return std::ptr::null_mut();
    }
    let mut state = [0i64; SIZE];
    let mut before = [0i64; 3];
    let mut sample = vec![0i64; count as usize];
    (f.GetLongArrayRegion.unwrap())(env, token, 0, SIZE as i32, state.as_mut_ptr());
    if has_previous { (f.GetLongArrayRegion.unwrap())(env, previous, 0, 3, before.as_mut_ptr()); }
    if count > 0 { (f.GetLongArrayRegion.unwrap())(env, values, 0, count, sample.as_mut_ptr()); }
    let line = step(&mut state, &mut before, event, now, &sample);
    (f.SetLongArrayRegion.unwrap())(env, token, 0, SIZE as i32, state.as_ptr());
    if has_previous { (f.SetLongArrayRegion.unwrap())(env, previous, 0, 3, before.as_ptr()); }
    line.map_or(std::ptr::null_mut(), |line| Env(env).string(&line))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn track_change_only_resets_underruns_without_ending_or_extra_reports() {
        let mut s = [0; SIZE];
        let mut previous = [0; 3];
        step(&mut s, &mut previous, 0, 100, &[]);
        assert!(step(&mut s, &mut previous, 1, 200, &[10,0,2,0,0,0,4,1,100,0]).is_some());
        // Flush the old track's last three underruns before its replacement.
        assert!(step(&mut s, &mut previous, 1, 300, &[2,0,5,0,0,0,4,1,20,0]).is_none());
        assert!(step(&mut s, &mut previous, 5, 0, &[]).is_none());
        assert_eq!(previous, [0, 4, 1]);
        // The new count already exceeds the old count, so a decrease cannot detect this reset.
        assert!(step(&mut s, &mut previous, 1, 400, &[1,0,8,0,0,0,4,1,10,0]).is_none());
        assert_eq!(s[2], 13);
        assert_eq!(s[6], 4);
        assert_eq!(s[7], 1);
        assert_eq!(s[13], 0);
        assert_eq!(s[ACTIVE], 1);
    }
    #[test]
    fn one_hour_of_short_prompts_is_at_most_one_normal_line_per_minute() {
        let mut s = [0; SIZE];
        let mut lines = 0;
        for second in 0..3600 {
            let mut previous = [0; 3];
            step(&mut s, &mut previous, 0, second * 1000, &[]);
            if step(&mut s, &mut previous, 1, second * 1000, &[100,0,0,0,0,0,0,0,4800,1]).is_some() { lines += 1; }
        }
        assert_eq!(lines, 59);
        let end = step(&mut s, &mut [0; 3], 3, 3_600_000, &[]).unwrap();
        assert!(end.contains("audioEnded=3600"));
        assert!(end.contains("final=true"));
    }

    #[test]
    fn repeated_errors_and_late_worker_exit_are_counted_without_flooding() {
        let mut s = [0; SIZE];
        let mut previous = [0; 3];
        step(&mut s, &mut previous, 0, 0, &[]);
        let mut lines = 0;
        for i in 0..600 {
            if step(&mut s, &mut previous, 1, i * 100, &[1,1,i+1,1,0,0,i+1,0,1,0]).is_some() { lines += 1; }
        }
        assert_eq!(lines, 1);
        assert_eq!(s[1], 600);
        assert_eq!(s[2], 600);
        assert_eq!(s[6], 600);
        assert!(step(&mut s, &mut previous, 3, 60_000, &[]).unwrap().contains("final=false"));
        assert!(step(&mut s, &mut previous, 1, 60_001, &[0,0,600,0,0,0,600,0,0,1]).unwrap().contains("final=true"));
        assert!(step(&mut s, &mut previous, 3, 60_002, &[]).is_none());
    }
}
