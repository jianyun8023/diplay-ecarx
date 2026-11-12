//! Head-unit day/night code tables (Kotlin's old `EcarxDayNightMapping`). The push sources,
//! broadcast receiver and ECARX AdaptAPI adapter stay Kotlin; only the pure decisions move here.

/// ECARX XUI AdaptAPI `IDayNightMode`: 1 = day, 2 = night, 0/unknown invalid.
pub fn from_adaptapi_day_night(value: i32) -> Option<bool> {
    match value {
        1 => Some(false),
        2 => Some(true),
        _ => None,
    }
}

/// Car AMap standard broadcast `AUTONAVI_STANDARD_BROADCAST_SEND` / KEY_TYPE 10019: 30 day, 32 night.
pub fn from_amap_state(state: i32) -> Option<bool> {
    match state {
        30 => Some(false),
        32 => Some(true),
        _ => None,
    }
}

/// Vehicle property `0x21400305` (TwliBriSts): 1 = day, 0 = night.
pub fn from_twilight_property(value: i32) -> Option<bool> {
    match value {
        1 => Some(false),
        0 => Some(true),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn each_source_maps_its_day_and_night_codes_only() {
        assert_eq!(from_adaptapi_day_night(1), Some(false));
        assert_eq!(from_adaptapi_day_night(2), Some(true));
        assert_eq!(from_adaptapi_day_night(0), None);
        assert_eq!(from_adaptapi_day_night(3), None);
        assert_eq!(from_amap_state(30), Some(false));
        assert_eq!(from_amap_state(32), Some(true));
        assert_eq!(from_amap_state(31), None);
        assert_eq!(from_amap_state(10019), None);
        assert_eq!(from_twilight_property(1), Some(false));
        assert_eq!(from_twilight_property(0), Some(true));
        assert_eq!(from_twilight_property(2), None);
    }
}

// ---- JNI boundary: `com.shilapi.xcertplay.hud.NativeDayNight` (-1 unknown, 0 day, 1 night) ----

use jni_sys::{jclass, jint, JNIEnv};

fn code(value: Option<bool>) -> jint { match value { None => -1, Some(false) => 0, Some(true) => 1 } }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_hud_NativeDayNight_fromAdaptapi(
    _env: *mut JNIEnv, _class: jclass, value: jint,
) -> jint { code(from_adaptapi_day_night(value)) }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_hud_NativeDayNight_fromAmap(
    _env: *mut JNIEnv, _class: jclass, state: jint,
) -> jint { code(from_amap_state(state)) }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_hud_NativeDayNight_fromTwilight(
    _env: *mut JNIEnv, _class: jclass, value: jint,
) -> jint { code(from_twilight_property(value)) }
