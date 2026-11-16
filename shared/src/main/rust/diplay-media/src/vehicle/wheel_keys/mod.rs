//! Per-press disposition and independent Siri time-window rules.
mod jni;

const REPEAT_GAP_MILLIS: i64 = 400;
const MIN_DOUBLE_PRESS_GAP_MILLIS: i64 = 180;

fn press_decision(token: i64, present: bool, down: bool, first: bool) -> i32 {
    if !down || present {
        if present && token == 1 { 1 } else { 0 }
    } else if first { 2 } else { 0 }
}

// Token words are presence, full-width last timestamp, and last result.
fn siri_step(mut token: [i64; 3], event: i32, now: i64, maximum_gap: i64) -> Option<[i64; 3]> {
    let last = (token[0] != 0).then_some(token[1]);
    match event {
        0 => {
            let opens = last.is_none_or(|last| now.wrapping_sub(last) >= REPEAT_GAP_MILLIS);
            token = [1, now, opens as i64];
        }
        1 => {
            if maximum_gap < MIN_DOUBLE_PRESS_GAP_MILLIS { return None; }
            let gap = last.map(|last| now.wrapping_sub(last));
            token = match gap {
                None => [1, now, 0],
                Some(gap) if gap <= 0 || gap > maximum_gap => [1, now, 0],
                Some(gap) if gap < MIN_DOUBLE_PRESS_GAP_MILLIS => [1, now, 1],
                Some(_) => [0, 0, 2],
            };
        }
        2 => token = [0, 0, 0],
        _ => return None,
    }
    Some(token)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn press_records_pass_and_consume_separately_from_absence() {
        assert_eq!(press_decision(0, false, true, true), 2);
        assert_eq!(press_decision(0, false, true, false), 0);
        assert_eq!(press_decision(0, true, true, true), 0);
        assert_eq!(press_decision(1, true, true, false), 1);
        assert_eq!(press_decision(1, true, false, false), 1);
        assert_eq!(press_decision(0, false, false, true), 0);
    }

    #[test]
    fn siri_uses_shared_wrapping_clock_and_rejects_invalid_interval() {
        let first = siri_step([0, 0, 0], 0, i64::MAX - 89, 0).unwrap();
        let repeat = siri_step(first, 1, i64::MIN + 89, 600).unwrap();
        assert_eq!(repeat[2], 1);
        assert_eq!(siri_step(repeat, 1, i64::MIN + 269, 600).unwrap(), [0, 0, 2]);
        assert!(siri_step(first, 1, 0, 179).is_none());
        assert!(siri_step(first, -1, 0, 600).is_none());
    }
}
