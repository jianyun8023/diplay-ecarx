//! Apple `$PASCD` vehicle-speed sentence (Kotlin's old `PascdEncoder`).
//! The sentence layout follows a real head unit's log; `C` and the `0` are copied as seen.

/// `gear`: 0=P, 1=R, 2=N, 3=D. `samples` are (elapsed milliseconds, metres per second).
pub fn pascd_encode(gear: i32, samples: &[(i64, f64)]) -> Option<String> {
    if samples.is_empty() || !(0..=3).contains(&gear) { return None; }
    let start = samples[0].0;
    let mut body = String::new();
    body.push_str("PASCD,");
    body.push_str(&fixed(start as f64 / 1000.0, 3));
    body.push_str(",C,");
    body.push(['P', 'R', 'N', 'D'][gear as usize]);
    body.push_str(",0,");
    body.push_str(&samples.len().to_string());
    for (elapsed, speed) in samples {
        body.push(',');
        body.push_str(&fixed((elapsed - start) as f64 / 1000.0, 2));
        body.push(',');
        body.push_str(&fixed(speed.max(0.0), 3));
    }
    let checksum = body.bytes().fold(0u8, |acc, byte| acc ^ byte);
    Some(format!("${body}*{checksum:02X}\r\n"))
}

/// Java `String.format(Locale.US, "%.Nf")` rounds half up (not Rust's half-to-even) and never emits
/// `-0.000`. Speed is clamped non-negative by the caller; elapsed/start are non-negative here.
fn fixed(value: f64, digits: usize) -> String {
    if !value.is_finite() { return format!("0.{}", "0".repeat(digits)); }
    let scale = 10f64.powi(digits as i32);
    let scaled = value * scale;
    let rounded = if scaled >= 0.0 { (scaled + 0.5).floor() } else { (scaled - 0.5).ceil() };
    let units = rounded as i128;
    let sign = if units < 0 { "-" } else { "" };
    let magnitude = units.unsigned_abs();
    let divisor = 10u128.pow(digits as u32);
    let whole = magnitude / divisor;
    let mut fraction = (magnitude % divisor).to_string();
    if digits > 0 {
        while fraction.len() < digits { fraction.insert(0, '0'); }
        format!("{sign}{whole}.{fraction}")
    } else {
        format!("{sign}{whole}")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_pinned_head_unit_sentence_matches() {
        let reading = [(17_877_092, 0.0), (17_877_252, 0.0)];
        assert_eq!(pascd_encode(0, &reading).unwrap(), "$PASCD,17877.092,C,P,0,2,0.00,0.000,0.16,0.000*54\r\n");
    }

    #[test]
    fn reverse_short_offsets_and_checksum_are_normalised() {
        let reading = [(1_000, 1.5), (1_250, 1.25)];
        let sentence = pascd_encode(1, &reading).unwrap();
        assert!(sentence.starts_with("$PASCD,1.000,C,R,0,2,0.00,1.500,0.25,1.250*"), "{sentence}");
        assert!(sentence.ends_with("\r\n"));
    }

    #[test]
    fn empty_samples_bad_gear_and_negative_speed_keep_the_old_rules() {
        assert!(pascd_encode(3, &[]).is_none());
        assert!(pascd_encode(4, &[(0, 1.0)]).is_none());
        assert!(pascd_encode(3, &[(0, -3.0)]).unwrap().contains(",0.000*"));
    }

    #[test]
    fn fixed_rounds_half_up_like_java_instead_of_half_to_even() {
        assert_eq!(fixed(0.0005, 3), "0.001");
        assert_eq!(fixed(1.2345, 3), "1.235");
        assert_eq!(fixed(0.0, 3), "0.000");
    }
}

// ---- JNI boundary: `com.shilapi.xcertplay.airplay.NativeVehicleSpeed` ----

use crate::platform::jvm::Env;
use jni_sys::{jclass, jdoubleArray, jint, jlongArray, jstring, JNIEnv};

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_transport_NativeVehicleSpeed_pascdEncode(
    env: *mut JNIEnv, _class: jclass, gear: jint, elapsed_ms: jlongArray, speeds: jdoubleArray,
) -> jstring {
    if elapsed_ms.is_null() || speeds.is_null() { return std::ptr::null_mut(); }
    let f = &**env;
    let count = (f.GetArrayLength.unwrap())(env, elapsed_ms) as usize;
    if (f.GetArrayLength.unwrap())(env, speeds) as usize != count { return std::ptr::null_mut(); }
    let mut ms = vec![0i64; count];
    let mut mps = vec![0f64; count];
    (f.GetLongArrayRegion.unwrap())(env, elapsed_ms, 0, count as jint, ms.as_mut_ptr());
    (f.GetDoubleArrayRegion.unwrap())(env, speeds, 0, count as jint, mps.as_mut_ptr());
    if Env(env).clear_exception() { return std::ptr::null_mut(); }
    let samples: Vec<(i64, f64)> = ms.into_iter().zip(mps).collect();
    match pascd_encode(gear, &samples) {
        Some(sentence) => Env(env).string(&sentence),
        None => std::ptr::null_mut(),
    }
}
