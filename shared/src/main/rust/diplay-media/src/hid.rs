//! CarPlay HID wire bytes, ported from this project's AirPlayHid contract.
//! Slot ids and low-bit coordinate wrapping intentionally differ from LIVI's positional/saturated axes.
use jni_sys::{jboolean, jbooleanArray, jbyteArray, jclass, jdoubleArray, jint, jintArray, JNIEnv};
use std::ptr::null_mut;

pub struct Contact { pub id: i32, pub x: f64, pub y: f64, pub down: bool }

fn coordinate(value: f64) -> u16 {
    let value = if value < 0.0 { 0.0 } else { value };
    // Math.round(double): exact half ties go up; NaN is zero and overflow saturates to Long.MAX.
    let bits = value.to_bits();
    let shift = 1074 - ((bits >> 52) & 0x7ff) as i32;
    let rounded = if (0..64).contains(&shift) {
        let significand = (bits & 0x000f_ffff_ffff_ffff) | 0x0010_0000_0000_0000;
        (((significand >> shift) + 1) >> 1) as i64
    } else { value as i64 };
    // Kotlin narrows Long to Int, then writes only its low 16 bits.
    rounded as u16
}

pub fn touch_report(contacts: &[Contact]) -> [u8; 12] {
    let mut report = [0; 12];
    for slot in 0..2 {
        let offset = slot * 6;
        report[offset] = slot as u8;
        let Some(contact) = contacts.iter().find(|contact| contact.id == slot as i32) else { continue; };
        report[offset + 1] = contact.down as u8;
        report[offset + 2..offset + 4].copy_from_slice(&coordinate(contact.x).to_le_bytes());
        report[offset + 4..offset + 6].copy_from_slice(&coordinate(contact.y).to_le_bytes());
    }
    report
}

pub fn knob_report(select: bool, home: bool, back: bool, x: i32, y: i32, wheel: i32) -> [u8; 4] {
    [select as u8 | (home as u8) << 1 | (back as u8) << 2,
        x.clamp(-127, 127) as u8, y.clamp(-127, 127) as u8, wheel.clamp(-127, 127) as u8]
}

pub fn index_report(index: i32) -> [u8; 1] { [index as u8] }

pub fn descriptor(kind: i32, x_max: i32, y_max: i32) -> Option<Vec<u8>> {
    Some(match kind {
        0 => {
            let [x_lo, x_hi] = (x_max as u16).to_le_bytes();
            let [y_lo, y_hi] = (y_max as u16).to_le_bytes();
            let finger = [
                0x05, 0x0d, 0x09, 0x22, 0xa1, 0x02,
                0x09, 0x38, 0x75, 0x08, 0x95, 0x01, 0x81, 0x02,
                0x15, 0x00, 0x25, 0x01, 0x09, 0x33, 0x75, 0x01, 0x95, 0x01, 0x81, 0x02,
                0x95, 0x07, 0x81, 0x03,
                0x05, 0x01, 0x26, x_lo, x_hi, 0x09, 0x30, 0x75, 0x10, 0x95, 0x01, 0x81, 0x02,
                0x26, y_lo, y_hi, 0x09, 0x31, 0x81, 0x02, 0xc0,
            ];
            let mut bytes = Vec::with_capacity(109);
            bytes.extend_from_slice(&[0x05, 0x0d, 0x09, 0x04, 0xa1, 0x01]);
            bytes.extend_from_slice(&finger); bytes.extend_from_slice(&finger); bytes.push(0xc0);
            bytes
        }
        1 => KNOB_DESCRIPTOR.to_vec(), 2 => MEDIA_DESCRIPTOR.to_vec(), 3 => TELEPHONY_DESCRIPTOR.to_vec(),
        _ => return None,
    })
}

const KNOB_DESCRIPTOR: &[u8] = &[
        0x05, 0x01, 0x09, 0x08, 0xa1, 0x01,
        0x05, 0x09, 0x09, 0x01, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x01, 0x81, 0x02,
        0x05, 0x0c, 0x0a, 0x23, 0x02, 0x0a, 0x24, 0x02, 0x95, 0x02, 0x81, 0x02,
        0x95, 0x05, 0x81, 0x01,
        0x05, 0x01, 0x09, 0x01, 0xa1, 0x00,
        // Preserve legacy absolute X/Y. Remote focus uses the relative wheel below.
        0x09, 0x30, 0x09, 0x31, 0x15, 0x81, 0x25, 0x7f, 0x75, 0x08, 0x95, 0x02, 0x81, 0x02, 0xc0,
        0x09, 0x38, 0x15, 0x81, 0x25, 0x7f, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06, 0xc0,
];

const MEDIA_DESCRIPTOR: &[u8] = &[
        0x05, 0x0c, 0x09, 0x01, 0xa1, 0x01,
        0x15, 0x00, 0x25, 0x06, 0x05, 0x0c,
        0x0a, 0x00, 0x00, 0x0a, 0xb0, 0x00, 0x0a, 0xb1, 0x00, 0x0a, 0xcd, 0x00,
        0x0a, 0xb5, 0x00, 0x0a, 0xb6, 0x00, 0x0a, 0x9e, 0x02,
        0x75, 0x08, 0x95, 0x01, 0x81, 0x00, 0xc0,
];

const TELEPHONY_DESCRIPTOR: &[u8] = &[
        0x05, 0x0b, 0x09, 0x07, 0xa1, 0x01,
        0x15, 0x00, 0x25, 0x11, 0x05, 0x0b,
        0x09, 0x00, 0x09, 0x20, 0x09, 0x21, 0x09, 0x26, 0x09, 0x2f,
        0x09, 0xb0, 0x09, 0xb1, 0x09, 0xb2, 0x09, 0xb3, 0x09, 0xb4, 0x09, 0xb5,
        0x09, 0xb6, 0x09, 0xb7, 0x09, 0xb8, 0x09, 0xb9, 0x09, 0xba, 0x09, 0xbb,
        0x05, 0x07, 0x09, 0x2a,
        0x75, 0x08, 0x95, 0x01, 0x81, 0x00, 0xc0,
];

unsafe fn bytes(env: *mut JNIEnv, bytes: &[u8]) -> jbyteArray {
    let f = &**env;
    let result = (f.NewByteArray.unwrap())(env, bytes.len() as jint);
    if result.is_null() { return null_mut(); }
    (f.SetByteArrayRegion.unwrap())(env, result, 0, bytes.len() as jint, bytes.as_ptr() as *const i8);
    if (f.ExceptionCheck.unwrap())(env) != 0 { null_mut() } else { result }
}

unsafe fn invalid(env: *mut JNIEnv, message: &std::ffi::CStr) -> jbyteArray {
    let class = ((**env).FindClass.unwrap())(env, c"java/lang/IllegalArgumentException".as_ptr());
    if !class.is_null() { ((**env).ThrowNew.unwrap())(env, class, message.as_ptr()); }
    null_mut()
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_hidTouchReport(
    env: *mut JNIEnv, _class: jclass, ids: jintArray, xy: jdoubleArray, down: jbooleanArray,
) -> jbyteArray {
    if ids.is_null() || xy.is_null() || down.is_null() { return invalid(env, c"HID contact arrays are null"); }
    let f = &**env;
    let count = (f.GetArrayLength.unwrap())(env, ids) as usize;
    if (f.GetArrayLength.unwrap())(env, xy) as usize != count * 2 || (f.GetArrayLength.unwrap())(env, down) as usize != count {
        return invalid(env, c"HID contact array lengths differ");
    }
    let mut ids_data = vec![0; count];
    (f.GetIntArrayRegion.unwrap())(env, ids, 0, count as jint, ids_data.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 { return null_mut(); }
    let mut xy_data = vec![0.0; count * 2];
    (f.GetDoubleArrayRegion.unwrap())(env, xy, 0, (count * 2) as jint, xy_data.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 { return null_mut(); }
    let mut down_data = vec![0; count];
    (f.GetBooleanArrayRegion.unwrap())(env, down, 0, count as jint, down_data.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 { return null_mut(); }
    let contacts: Vec<_> = ids_data.iter().enumerate().map(|(i, &id)| Contact {
        id, x: xy_data[i * 2], y: xy_data[i * 2 + 1], down: down_data[i] != 0,
    }).collect();
    bytes(env, &touch_report(&contacts))
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_hidKnobReport(
    env: *mut JNIEnv, _class: jclass, select: jboolean, home: jboolean, back: jboolean, x: jint, y: jint, wheel: jint,
) -> jbyteArray { bytes(env, &knob_report(select != 0, home != 0, back != 0, x, y, wheel)) }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_hidIndexReport(
    env: *mut JNIEnv, _class: jclass, index: jint,
) -> jbyteArray { bytes(env, &index_report(index)) }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_hidDescriptor(
    env: *mut JNIEnv, _class: jclass, kind: jint, x_max: jint, y_max: jint,
) -> jbyteArray {
    match descriptor(kind, x_max, y_max) {
        Some(descriptor) => bytes(env, &descriptor),
        None => invalid(env, c"HID descriptor kind must be in 0..3"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn hex(bytes: &[u8]) -> String { bytes.iter().map(|byte| format!("{byte:02x}")).collect() }

    #[test]
    fn compatible_touch_and_descriptor_match_the_pinned_wire_vectors() {
        // LIVI ts_vectors.json at 6ac88bab7351a39b0cffac20c5dcf92ca848319b; wire vector only.
        assert_eq!(hex(&touch_report(&[
            Contact { id: 0, x: 100.5, y: 719.49, down: true },
            Contact { id: 1, x: 3.0, y: 4.0, down: false },
        ])), "00016500cf02010003000400");
        assert_eq!(hex(&knob_report(true, false, false, -200, 4, -1)), "018104ff");
        assert_eq!(hex(&descriptor(0, 1920, 1080).unwrap()),
            "050d0904a101050d0922a1020938750895018102150025010933750195018102950781030501268007093075109501810226380409318102c0050d0922a1020938750895018102150025010933750195018102950781030501268007093075109501810226380409318102c0c0");
    }

    #[test]
    fn touch_selects_first_contact_for_each_id_and_ignores_unknown_ids() {
        assert_eq!(touch_report(&[]), [0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0]);
        let contacts = [
            Contact { id: 2, x: 999.0, y: 999.0, down: true },
            Contact { id: 1, x: 300.0, y: 400.0, down: false },
            Contact { id: 1, x: 10.0, y: 20.0, down: true },
            Contact { id: -1, x: 50.0, y: 60.0, down: true },
        ];
        assert_eq!(touch_report(&contacts), [0, 0, 0, 0, 0, 0, 1, 0, 0x2c, 1, 0x90, 1]);
    }

    #[test]
    fn coordinates_keep_java_rounding_special_values_and_low_sixteen_bits() {
        for (value, expected) in [
            (f64::NAN, 0), (f64::INFINITY, 0xffff), (f64::NEG_INFINITY, 0),
            (-1.5, 0), (-0.0, 0), (0.0, 0), (f64::MIN_POSITIVE, 0),
            (0.49999999999999994, 0), (0.5, 1), (1.5, 2),
            (65535.49, 65535), (65535.5, 0), (65536.5, 1), (70000.0, 4464),
            (4294967297.0, 1), (9_223_372_036_854_774_784.0, 0xfc00),
            (9_223_372_036_854_775_808.0, 0xffff), (f64::MAX, 0xffff),
        ] { assert_eq!(coordinate(value), expected, "{value}"); }
    }

    #[test]
    fn buttons_clamp_axes_and_index_reports_keep_only_the_low_byte() {
        for flags in 0..8 {
            assert_eq!(knob_report(flags & 1 != 0, flags & 2 != 0, flags & 4 != 0,
                i32::MIN, 128, -128), [flags, 0x81, 0x7f, 0x81]);
        }
        for axis in [-127, -1, 0, 1, 127] { assert_eq!(knob_report(false, false, false, axis, axis, axis), [0, axis as u8, axis as u8, axis as u8]); }
        for (index, expected) in [(i32::MIN, 0), (-257, 255), (-1, 255), (0, 0), (255, 255), (256, 0), (i32::MAX, 255)] {
            assert_eq!(index_report(index), [expected]);
        }
    }

    #[test]
    fn descriptor_bounds_wrap_and_static_devices_keep_the_existing_bytes() {
        assert_eq!(descriptor(0, -1, i32::MIN), descriptor(0, 65535, 0));
        assert_eq!(descriptor(0, i32::MAX, -65536), descriptor(0, 65535, 0));
        assert!(descriptor(-1, 0, 0).is_none()); assert!(descriptor(4, 0, 0).is_none());
        for (kind, len, expected) in [
            (1, 70, "578e322c44eb6957e2f5f1dad2522ce78ad2493bc1fbb63c76c35e75948f6924"), (2, 40, "01e3eb508f51acfffd6c3c00060b1f707cd216c794290c2efe49df84aeea41a7"), (3, 57, "2cc77897f585a5754e285315bc759d8d1f94a8c989542850ad0da2c80f31c77f"),
        ] {
            let bytes = descriptor(kind, 123, -456).unwrap();
            assert_eq!(bytes.len(), len);
            assert_eq!(hex(ring::digest::digest(&ring::digest::SHA256, &bytes).as_ref()), expected);
        }
    }
}
