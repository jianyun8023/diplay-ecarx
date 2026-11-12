//! Android pointer-id → CarPlay HID slot state (Kotlin's old `AirPlayTouchSlots`).
//! A finger keeps one slot from press to release; a finger that is gone is released where it last was.
//! Kotlin supplies the Android pointer ids and normalized coordinates; this owner keeps the slots and
//! emits the same 12-byte report through [crate::vehicle::hid].

use crate::vehicle::hid::{touch_report, Contact};

/// `slots` is the report's finger count (`AirPlayHid.TOUCH_CONTACTS`, two today).
pub struct TouchSlots {
    slots: usize,
    pointers: Vec<Option<i32>>,
    held: Vec<Option<Contact>>,
}

impl TouchSlots {
    pub fn new(slots: usize) -> TouchSlots {
        TouchSlots { slots, pointers: vec![None; slots], held: vec![None; slots] }
    }

    /// The contacts to report, each with its slot as id, in slot order. Empty slots are left out.
    /// Coordinates are normalized here; the JNI boundary scales them to the display pixels.
    pub fn assign(&mut self, contacts: &[Contact]) -> Vec<Contact> {
        let mut report: Vec<Option<Contact>> = vec![None; self.slots];
        for contact in contacts {
            let existing = self.pointers.iter().position(|pointer| *pointer == Some(contact.id));
            let slot = existing.or_else(|| {
                if contact.down { self.pointers.iter().position(Option::is_none) } else { None }
            });
            let Some(slot) = slot else { continue };
            self.pointers[slot] = Some(contact.id);
            report[slot] = Some(Contact { id: slot as i32, x: contact.x, y: contact.y, down: contact.down });
        }
        for slot in 0..self.slots {
            if report[slot].is_none() {
                report[slot] = self.held[slot].map(|held| Contact { down: false, ..held });
            }
            match report[slot] {
                Some(sent) if sent.down => self.held[slot] = Some(sent),
                _ => {
                    self.held[slot] = None;
                    self.pointers[slot] = None;
                }
            }
        }
        report.into_iter().flatten().collect()
    }
}

/// The report bytes for `contacts`; the JNI entry point calls this after reading the arrays.
pub fn assign_report(slots: &mut TouchSlots, contacts: &[Contact], width: f64, height: f64) -> [u8; 12] {
    let scaled: Vec<Contact> = slots
        .assign(contacts)
        .into_iter()
        .map(|contact| Contact { x: contact.x * width, y: contact.y * height, ..contact })
        .collect();
    touch_report(&scaled)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn c(id: i32, x: f64, y: f64, down: bool) -> Contact {
        Contact { id, x, y, down }
    }

    #[test]
    fn a_finger_keeps_its_slot_while_the_other_pointer_index_changes() {
        let mut slots = TouchSlots::new(2);
        assert_eq!(slots.assign(&[c(7, 0.1, 0.2, true), c(9, 0.3, 0.4, true)]).iter().map(|v| v.id).collect::<Vec<_>>(), vec![0, 1]);
        // Pointer 9 lifts; 7's pointer index changes but keeps slot 0.
        let now = slots.assign(&[c(7, 0.11, 0.21, true)]);
        assert_eq!(now.len(), 2);
        assert_eq!((now[0].id, now[0].down), (0, true));
        assert_eq!((now[1].id, now[1].down), (1, false), "the lifted finger is released where it last was");
        assert_eq!((now[1].x, now[1].y), (0.3, 0.4));
    }

    #[test]
    fn empty_and_extra_fingers_release_every_slot_and_stay_within_the_report() {
        let mut slots = TouchSlots::new(2);
        slots.assign(&[c(1, 0.0, 0.0, true)]);
        assert_eq!(slots.assign(&[]), vec![Contact { id: 0, x: 0.0, y: 0.0, down: false }]);
        // A third finger beyond the two slots is left out; a release without a slot is ignored.
        let only_two = slots.assign(&[c(1, 0.1, 0.1, true), c(2, 0.2, 0.2, true), c(3, 0.3, 0.3, true)]);
        assert_eq!(only_two.len(), 2);
        // Both slots are held down, so an unknown third finger is left out and the held fingers release.
        let released = slots.assign(&[c(3, 0.3, 0.3, true)]);
        assert_eq!(released.len(), 2);
        assert!(released.iter().all(|contact| !contact.down));
    }

    #[test]
    fn report_scales_normalized_coordinates_and_reuses_the_hid_encoder() {
        let mut slots = TouchSlots::new(2);
        let report = assign_report(&mut slots, &[c(0, 0.5, 0.25, true)], 1920.0, 1080.0);
        // Slot 0 down, x=960=0x03c0, y=270=0x010e, little-endian.
        assert_eq!(report, [0, 1, 0xc0, 0x03, 0x0e, 0x01, 1, 0, 0, 0, 0, 0]);
    }
}

// ---- JNI boundary: `com.shilapi.xcertplay.airplay.NativeTouchSlots` ----

use crate::platform::jvm::Env;
use jni_sys::{jbooleanArray, jbyteArray, jclass, jdouble, jdoubleArray, jint, jintArray, jlong, JNIEnv};

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeTouchSlots_create(
    _env: *mut JNIEnv, _class: jclass, slots: jint,
) -> jlong {
    if !(1..=8).contains(&slots) { return 0; }
    Box::into_raw(Box::new(TouchSlots::new(slots as usize))) as jlong
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeTouchSlots_destroy(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) {
    if handle != 0 { drop(Box::from_raw(handle as *mut TouchSlots)); }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeTouchSlots_assignReport(
    env: *mut JNIEnv, _class: jclass, handle: jlong, ids: jintArray, xs: jdoubleArray, ys: jdoubleArray,
    down: jbooleanArray, width: jint, height: jint,
) -> jbyteArray {
    if handle == 0 || ids.is_null() || xs.is_null() || ys.is_null() || down.is_null() { return std::ptr::null_mut(); }
    let f = &**env;
    let count = (f.GetArrayLength.unwrap())(env, ids) as usize;
    if (f.GetArrayLength.unwrap())(env, xs) as usize != count || (f.GetArrayLength.unwrap())(env, ys) as usize != count
        || (f.GetArrayLength.unwrap())(env, down) as usize != count { return std::ptr::null_mut(); }
    let mut id_data = vec![0i32; count];
    let mut x_data = vec![0f64; count];
    let mut y_data = vec![0f64; count];
    let mut down_data = vec![0u8; count];
    (f.GetIntArrayRegion.unwrap())(env, ids, 0, count as jint, id_data.as_mut_ptr());
    (f.GetDoubleArrayRegion.unwrap())(env, xs, 0, count as jint, x_data.as_mut_ptr());
    (f.GetDoubleArrayRegion.unwrap())(env, ys, 0, count as jint, y_data.as_mut_ptr());
    (f.GetBooleanArrayRegion.unwrap())(env, down, 0, count as jint, down_data.as_mut_ptr());
    if Env(env).clear_exception() { return std::ptr::null_mut(); }
    let contacts: Vec<Contact> = (0..count).map(|i| Contact {
        id: id_data[i], x: x_data[i], y: y_data[i], down: down_data[i] != 0,
    }).collect();
    let report = assign_report(&mut *(handle as *mut TouchSlots), &contacts, width as f64, height as f64);
    Env(env).byte_array(&report)
}
