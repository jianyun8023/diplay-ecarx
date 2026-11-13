//! The existing EV snapshot conversion for iAP2 0xA101. Unknown energy stays omitted.

use crate::platform::jvm::Env;
use jni_sys::{jboolean, jbyteArray, jclass, jdouble, jint, jlong, JNIEnv};

pub struct VehicleStatus {
    pub range_km: i32,
    pub range_warning: bool,
    pub battery_percent: f64,
    pub current_charge_wh: Option<i64>,
    pub max_charge_wh: Option<i64>,
    pub max_range_km: i32,
    pub charging: bool,
}

impl VehicleStatus {
    pub fn payload(&self) -> Option<Vec<u8>> {
        // Kotlin's roundToLong rejects NaN; infinity is clamped by the existing percentage rule.
        if self.battery_percent.is_nan() { return None; }
        let range = (self.range_km.clamp(0, u16::MAX as i32) as u16).to_be_bytes();
        let warning = [self.range_warning as u8];
        let mut out = Vec::with_capacity(65);
        parameter(&mut out, 3, &range);
        parameter(&mut out, 6, &warning);
        parameter(&mut out, 11, &range);
        parameter(&mut out, 15, &warning);
        if self.current_charge_wh.is_some() || self.max_charge_wh.is_some() {
            parameter(&mut out, 21, &0u32.to_be_bytes());
        }
        for (id, charge) in [(22, self.current_charge_wh), (23, self.max_charge_wh)] {
            if let Some(charge) = charge {
                // The Kotlin u32 encoder rejects overflow rather than truncating high bits.
                parameter(&mut out, id, &u32::try_from(charge.max(0)).ok()?.to_be_bytes());
            }
        }
        let percent = (self.battery_percent.clamp(0.0, 100.0) * 1000.0).round() as u32;
        parameter(&mut out, 24, &percent.to_be_bytes());
        parameter(&mut out, 25, &[self.charging as u8]);
        parameter(&mut out, 30, &(self.max_range_km.clamp(0, u16::MAX as i32) as u16).to_be_bytes());
        Some(out)
    }
}

fn parameter(out: &mut Vec<u8>, id: u16, value: &[u8]) {
    out.extend_from_slice(&((value.len() + 4) as u16).to_be_bytes());
    out.extend_from_slice(&id.to_be_bytes());
    out.extend_from_slice(value);
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_vehicleStatusPayload(
    env: *mut JNIEnv, _class: jclass, range_km: jint, range_warning: jboolean,
    battery_percent: jdouble, current_charge_wh: jlong, max_charge_wh: jlong,
    energy_mask: jint, max_range_km: jint, charging: jboolean,
) -> jbyteArray {
    if !(0..=3).contains(&energy_mask) { return std::ptr::null_mut(); }
    let status = VehicleStatus {
        range_km, range_warning: range_warning != 0, battery_percent,
        current_charge_wh: (energy_mask & 1 != 0).then_some(current_charge_wh),
        max_charge_wh: (energy_mask & 2 != 0).then_some(max_charge_wh),
        max_range_km, charging: charging != 0,
    };
    status.payload().map_or(std::ptr::null_mut(), |bytes| Env(env).byte_array(&bytes))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn reading() -> VehicleStatus {
        VehicleStatus { range_km: 150, range_warning: false, battery_percent: 25.0,
            current_charge_wh: Some(25_100), max_charge_wh: Some(100_400), max_range_km: 600, charging: false }
    }

    #[test]
    fn known_energy_matches_the_existing_vehicle_status_bytes() {
        let expected = [
            0,6,0,3,0,150, 0,5,0,6,0, 0,6,0,11,0,150, 0,5,0,15,0,
            0,8,0,21,0,0,0,0, 0,8,0,22,0,0,0x62,0x0c,
            0,8,0,23,0,1,0x88,0x30, 0,8,0,24,0,0,0x61,0xa8,
            0,5,0,25,0, 0,6,0,30,2,0x58,
        ];
        assert_eq!(reading().payload().unwrap(), expected);
    }

    #[test]
    fn missing_energy_stays_absent_instead_of_becoming_a_zero_battery() {
        let mut status = reading();
        status.current_charge_wh = None;
        status.max_charge_wh = None;
        status.range_km = 36;
        status.battery_percent = 51.0;
        status.charging = true;
        let expected = [
            0,6,0,3,0,36, 0,5,0,6,0, 0,6,0,11,0,36, 0,5,0,15,0,
            0,8,0,24,0,0,0xc7,0x38, 0,5,0,25,1, 0,6,0,30,2,0x58,
        ];
        assert_eq!(status.payload().unwrap(), expected);
        status.current_charge_wh = Some(0);
        assert_eq!(status.payload().unwrap().len(), expected.len() + 16);
    }

    #[test]
    fn clamps_signed_values_but_preserves_u32_max_and_rejects_energy_overflow() {
        let mut status = reading();
        status.range_km = i32::MIN;
        status.max_range_km = i32::MAX;
        status.current_charge_wh = Some(i64::MIN);
        status.max_charge_wh = Some(u32::MAX as i64);
        status.battery_percent = f64::INFINITY;
        status.range_warning = true;
        let expected = [
            0,6,0,3,0,0, 0,5,0,6,1, 0,6,0,11,0,0, 0,5,0,15,1,
            0,8,0,21,0,0,0,0, 0,8,0,22,0,0,0,0,
            0,8,0,23,255,255,255,255, 0,8,0,24,0,1,0x86,0xa0,
            0,5,0,25,0, 0,6,0,30,255,255,
        ];
        assert_eq!(status.payload().unwrap(), expected);
        status.current_charge_wh = Some(u32::MAX as i64 + 1);
        assert!(status.payload().is_none());
        status.current_charge_wh = None;
        status.max_charge_wh = Some(i64::MAX);
        assert!(status.payload().is_none());
    }

    #[test]
    fn battery_rounding_and_nan_match_the_kotlin_boundary() {
        let mut status = reading();
        for (input, encoded) in [(f64::NEG_INFINITY, 0u32), (-1.0, 0), (0.0005, 1), (1.2345, 1235), (1000.0, 100_000)] {
            status.battery_percent = input;
            let payload = status.payload().unwrap();
            assert_eq!(&payload[50..54], &encoded.to_be_bytes());
        }
        status.battery_percent = f64::NAN;
        assert!(status.payload().is_none());
    }
}
