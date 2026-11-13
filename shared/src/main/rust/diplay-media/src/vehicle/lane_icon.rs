//! ECARX cluster lane-icon ids (Kotlin's old `EcarxLaneIcon`). Pure lookup; the HUD/cluster
//! adapters stay Kotlin and keep their existing output behaviour.

use std::collections::BTreeSet;

/// `angles`: the lane's directions; `highlight`: the selected branch, only trusted when `status != 0`.
pub fn for_lane(status: i32, angles: &[i32], highlight_angle: Option<i32>) -> Option<i32> {
    if !(0..=2).contains(&status) {
        return None;
    }
    let highlight = if status != 0 { highlight_angle } else { None };
    let directions: BTreeSet<i32> = if angles.is_empty() {
        highlight_angle.into_iter().collect()
    } else {
        angles.iter().copied().chain(highlight).collect()
    };
    let eq = |values: &[i32]| directions.iter().copied().eq(values.iter().copied());
    let base = if eq(&[0]) {
        0
    } else if eq(&[-90]) {
        1
    } else if eq(&[-45]) || eq(&[-90, 0]) {
        2
    } else if eq(&[90]) {
        3
    } else if eq(&[45]) || eq(&[0, 90]) {
        4
    } else if eq(&[-180]) {
        5
    } else if eq(&[-90, 90]) {
        6
    } else if eq(&[-90, 0, 90]) {
        7
    } else if eq(&[-180, -90]) {
        11
    } else if eq(&[-180, -90, 0]) {
        return match highlight {
            Some(0) => Some(71),
            Some(-90) => Some(72),
            Some(-180) => Some(73),
            _ => if status == 2 { Some(56) } else { Some(49) },
        };
    } else {
        return None;
    };
    if directions.len() > 1 {
        if let Some(highlight) = highlight {
            return match base {
                2 => Some(if highlight == 0 { 30 } else { 31 }),
                4 => Some(if highlight == 0 { 32 } else { 33 }),
                6 => Some(if highlight == -90 { 34 } else { 35 }),
                7 => match highlight {
                    0 => Some(36),
                    -90 => Some(37),
                    _ => Some(38),
                },
                11 => Some(if highlight == -90 { 43 } else { 44 }),
                _ => None,
            };
        }
    }
    Some(base + if highlight.is_some() || status == 2 { 15 } else { 0 })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn known_directions_and_recommended_variants_match_oem_ids() {
        assert_eq!(for_lane(0, &[], Some(-90)), Some(1));
        assert_eq!(for_lane(0, &[-90], None), Some(1));
        assert_eq!(for_lane(0, &[0], None), Some(0));
        assert_eq!(for_lane(2, &[0], Some(90)), Some(33));
        assert_eq!(for_lane(2, &[-90], Some(0)), Some(30));
        assert_eq!(for_lane(1, &[0], Some(-90)), Some(31));
        assert_eq!(for_lane(0, &[-90, 0], None), Some(2));
        assert_eq!(for_lane(2, &[], Some(90)), Some(18));
    }

    #[test]
    fn unknown_directions_and_statuses_stay_unmapped() {
        assert_eq!(for_lane(0, &[], None), None);
        assert_eq!(for_lane(0, &[1000], None), None);
        assert_eq!(for_lane(3, &[0], None), None);
        assert_eq!(for_lane(0, &[-90, -90, 0], Some(90)), Some(2));
    }

    #[test]
    fn compound_left_uturn_highlights_the_selected_branch() {
        let left_and_uturn = [-180, -90];
        let three = [0, -180, -90];
        assert_eq!(for_lane(2, &left_and_uturn, Some(0)), Some(71));
        assert_eq!(for_lane(0, &left_and_uturn, None), Some(11));
        assert_eq!(for_lane(2, &left_and_uturn, None), Some(26));
        assert_eq!(for_lane(2, &left_and_uturn, Some(-90)), Some(43));
        assert_eq!(for_lane(2, &left_and_uturn, Some(-180)), Some(44));
        assert_eq!(for_lane(0, &three, None), Some(49));
        assert_eq!(for_lane(2, &three, None), Some(56));
        assert_eq!(for_lane(1, &three, Some(-90)), Some(72));
        assert_eq!(for_lane(2, &three, Some(-180)), Some(73));
    }
}

// ---- JNI boundary: `com.shihab.diplay.hud.NativeLaneIcon` ----

use jni_sys::{jboolean, jclass, jint, jintArray, JNIEnv};

/// `highlight_angle` is ignored when `has_highlight` is false. Returns -1 for no icon.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeLaneIcon_forLane(
    env: *mut JNIEnv, _class: jclass, status: jint, angles: jintArray, highlight_angle: jint, has_highlight: jboolean,
) -> jint {
    let mut values = Vec::new();
    if !angles.is_null() {
        let f = &**env;
        let count = (f.GetArrayLength.unwrap())(env, angles) as usize;
        values = vec![0i32; count];
        (f.GetIntArrayRegion.unwrap())(env, angles, 0, count as jint, values.as_mut_ptr());
        if crate::platform::jvm::Env(env).clear_exception() { return -1; }
    }
    let highlight = (has_highlight != 0).then_some(highlight_angle);
    for_lane(status, &values, highlight).unwrap_or(-1)
}
