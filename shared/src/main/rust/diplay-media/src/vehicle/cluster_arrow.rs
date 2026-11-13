//! ECARX cluster turn-arrow code (Kotlin's old `EcarxArrowCode`), recovered from the head unit's
//! own navigation app. Pure tables only; the ECARX HUD/cluster adapters stay Kotlin.

pub const NO_ARROW: i32 = 0;
pub const NO_ICON: i32 = 120;

pub fn amap_to_ecarx(icon_id: i32, around_num: i32) -> i32 {
    let id = if (1..=10).contains(&around_num) && matches!(icon_id, 11 | 12 | 22) {
        around_num + 0x78
    } else if (1..=10).contains(&around_num) && matches!(icon_id, 17 | 18 | 25) {
        around_num + 0x82
    } else {
        icon_id
    };
    match id {
        1..=20 => id + 100,
        21 => 141,
        22 => 142,
        23 => 112,
        24 => 143,
        25 => 144,
        26 => 145,
        27 => 118,
        28 => 146,
        121..=140 => id,
        _ => NO_ARROW,
    }
}

/// Reverse lookup; the table is not injective, so the lowest matching Amap id wins, -1 is unknown.
pub fn ecarx_to_amap(arrow_id: i32) -> i32 {
    for id in 1..=28 {
        if amap_to_ecarx(id, 0) == arrow_id {
            return id;
        }
    }
    for id in 121..=140 {
        if amap_to_ecarx(id, 0) == arrow_id {
            return id;
        }
    }
    -1
}

pub fn apple_to_amap(maneuver_type: i32, driving_side: i32) -> i32 {
    match maneuver_type {
        0 | 3 => 9,
        1 | 20 | 47 => 2,
        2 | 21 | 48 => 3,
        13 | 49 | 52 => 4,
        14 | 50 | 53 => 5,
        8 | 9 => if driving_side == 1 { 4 } else { 5 },
        4 | 18 | 26 => 8,
        19 => 24,
        5 | 15 | 16 | 17 | 51 => 20,
        6 => 11,
        7 => 12,
        28..=46 => maneuver_type + 93,
        10 | 12 | 24 | 25 | 27 => 15,
        11 => 1,
        22 => 4,
        23 => 5,
        _ => -1,
    }
}

pub fn arrow_id(maneuver_type: i32, driving_side: i32) -> i32 {
    let amap = apple_to_amap(maneuver_type, driving_side);
    if amap > 0 { amap_to_ecarx(amap, 0) } else { NO_ARROW }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn recovered_table_maps_straight_and_turn_ids() {
        assert_eq!(amap_to_ecarx(9, 0), 109);
        assert_eq!(amap_to_ecarx(2, 0), 102);
        assert_eq!(amap_to_ecarx(3, 0), 103);
        assert_eq!(amap_to_ecarx(8, 0), 108);
        assert_eq!(amap_to_ecarx(11, 0), 111);
        assert_eq!(amap_to_ecarx(12, 0), 112);
        assert_eq!(amap_to_ecarx(15, 0), 115);
        assert_eq!(amap_to_ecarx(1, 0), 101);
        assert_eq!(amap_to_ecarx(128, 0), 128);
        assert_eq!(amap_to_ecarx(99, 0), NO_ARROW);
    }

    #[test]
    fn roundabout_prefix_rewrites_before_the_table() {
        assert_eq!(amap_to_ecarx(11, 3), 123);
        assert_eq!(amap_to_ecarx(17, 3), 133);
    }

    #[test]
    fn apple_maneuvers_name_the_same_cluster_codes_and_unknown_stay_zero() {
        assert_eq!(arrow_id(0, 0), 109);
        assert_eq!(arrow_id(1, 0), 102);
        assert_eq!(arrow_id(2, 0), 103);
        assert_eq!(arrow_id(4, 0), 108);
        assert_eq!(arrow_id(6, 0), 111);
        assert_eq!(arrow_id(7, 0), 112);
        assert_eq!(arrow_id(11, 0), 101);
        assert_eq!(arrow_id(10, 0), 115);
        assert_eq!(arrow_id(8, 1), 104);
        assert_eq!(arrow_id(8, 0), 105);
        assert_eq!(arrow_id(99, 0), NO_ARROW);
    }

    #[test]
    fn reverse_lookup_picks_the_lowest_amap_id() {
        assert_eq!(ecarx_to_amap(112), 12);
        assert_eq!(ecarx_to_amap(118), 18);
        assert_eq!(ecarx_to_amap(109), 9);
        assert_eq!(ecarx_to_amap(200), -1);
    }
}

// ---- JNI boundary: `com.shihab.diplay.hud.NativeClusterArrow` ----

use jni_sys::{jclass, jint, JNIEnv};

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeClusterArrow_amapToEcarx(
    _env: *mut JNIEnv, _class: jclass, icon_id: jint, around_num: jint,
) -> jint { amap_to_ecarx(icon_id, around_num) }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeClusterArrow_ecarxToAmap(
    _env: *mut JNIEnv, _class: jclass, arrow_id: jint,
) -> jint { ecarx_to_amap(arrow_id) }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeClusterArrow_appleToAmap(
    _env: *mut JNIEnv, _class: jclass, maneuver_type: jint, driving_side: jint,
) -> jint { apple_to_amap(maneuver_type, driving_side) }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_hud_NativeClusterArrow_arrowId(
    _env: *mut JNIEnv, _class: jclass, maneuver_type: jint, driving_side: jint,
) -> jint { arrow_id(maneuver_type, driving_side) }
