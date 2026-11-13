//! Wi-Fi Direct channel planning and bounded retries; Kotlin executes SDK calls and guards.
use jni_sys::{jboolean, jclass, jint, jintArray, jlong, JNIEnv};

const FIVE_CHANNELS: [i32; 9] = [36, 40, 44, 48, 149, 153, 157, 161, 165];
const FIVE_FREQUENCIES: [i32; 9] = [5180, 5200, 5220, 5240, 5745, 5765, 5785, 5805, 5825];
type Request = (i32, i32);

fn valid_channel(channel: i32) -> bool { matches!(channel, 0 | -5 | -2) || (1..=11).contains(&channel) || FIVE_CHANNELS.contains(&channel) }
fn frequency(channel: i32) -> i32 {
    if channel <= 0 { 0 } else if channel <= 11 { 2407 + channel * 5 } else { 5000 + channel * 5 }
}
fn remembered(value: i32) -> Option<Request> {
    if (2412..=2462).contains(&value) && (value - 2412) % 5 == 0 { Some((3, value)) }
    else if FIVE_FREQUENCIES.contains(&value) { Some((2, value)) } else { None }
}
fn push_channel(plan: &mut Vec<Request>, request: Request) {
    if !plan.iter().any(|(_, frequency)| *frequency == request.1) { plan.push(request); }
}

fn plan(station: i32, preferred_mode: i32, preferred_frequency: i32, has_preferred_frequency: bool,
    channel: i32, band_requests: bool, five_ghz: bool) -> Vec<Request> {
    if channel > 0 { return vec![(5, frequency(channel))]; }
    if matches!(channel, -5 | -2) {
        let five = channel == -5;
        let aligned = remembered(station).filter(|(_, value)| if five { (5150..=5895).contains(value) } else { (2412..=2484).contains(value) });
        let mut result = Vec::new();
        if let Some((_, value)) = aligned { result.push((if five { 0 } else { 1 }, value)); }
        if band_requests { result.push((if five { 6 } else { 7 }, 0)); }
        let fixed: &[i32] = if five { &[5180, 5745, 5220, 5785] } else { &[2437, 2412, 2462] };
        for value in fixed { push_channel(&mut result, (if five { 2 } else { 3 }, *value)); }
        return result;
    }
    let aligned_24 = (2412..=2462).contains(&station) && (station - 2412) % 5 == 0;
    let aligned_5 = FIVE_FREQUENCIES.contains(&station);
    let station_on_five = (5150..=5895).contains(&station);
    let preferred = if has_preferred_frequency { remembered(preferred_frequency) } else { None }
        .filter(|(_, value)| five_ghz || !(5150..=5895).contains(value));
    let switches = station_on_five && preferred.is_some_and(|(_, value)| (5150..=5895).contains(&value) && value != station);
    let mut result = Vec::new();
    if preferred_mode == 4 && !has_preferred_frequency && !station_on_five { result.push((4, 0)); }
    else if !switches { if let Some(value) = preferred { push_channel(&mut result, value); } }
    if aligned_24 { push_channel(&mut result, (1, station)); }
    if aligned_5 { push_channel(&mut result, (0, station)); }
    // Preserve the tested shared-radio order: avoid hopping beside a 5 GHz station.
    let fallback: &[i32] = if aligned_24 || station_on_five { &[2437, 2412, 2462, 5180, 5745] }
        else { &[5180, 5745, 2437, 2412, 2462] };
    for value in fallback {
        if *value < 5000 || five_ghz { push_channel(&mut result, (if *value < 5000 { 3 } else { 2 }, *value)); }
    }
    if switches { push_channel(&mut result, preferred.unwrap()); }
    if !result.iter().any(|(mode, _)| *mode == 4) { result.push((4, 0)); }
    result
}

fn current(owner: &[i32]) -> Option<Request> {
    if owner[0] == -1 { return Some((4, 0)); }
    let at = 3 + owner[0] as usize * 2;
    Some((*owner.get(at)?, *owner.get(at + 1)?))
}

// 0 preserves the platform failure, 1 adds the selected-channel context, 2 executes the guard/retry.
fn rejected(owner: &mut [i32], busy: bool, terminal: bool) -> i32 {
    if terminal { return 0; }
    if busy && owner[1] == 0 { owner[1] = 1; return 2; }
    if owner[0] as usize + 1 < (owner.len() - 3) / 2 {
        owner[0] += 1;
        owner[1] = 0;
        return 2;
    }
    owner[2]
}
fn compatibility(owner: &mut [i32]) -> i32 {
    if owner[2] != 0 { return 1; }
    if current(owner).is_some_and(|(mode, _)| mode == 4) { return 0; }
    owner[0] = -1;
    2
}

unsafe fn array(env: *mut JNIEnv, values: &[i32]) -> jintArray {
    let f = &**env;
    let result = (f.NewIntArray.unwrap())(env, values.len() as jint);
    if !result.is_null() { (f.SetIntArrayRegion.unwrap())(env, result, 0, values.len() as jint, values.as_ptr()); }
    result
}
unsafe fn read(env: *mut JNIEnv, owner: jintArray) -> Option<Vec<i32>> {
    let f = &**env;
    let length = (f.GetArrayLength.unwrap())(env, owner);
    let mut values = vec![0; length as usize];
    (f.GetIntArrayRegion.unwrap())(env, owner, 0, length, values.as_mut_ptr());
    if (f.ExceptionCheck.unwrap())(env) != 0 || values.len() < 5 { None } else { Some(values) }
}

#[no_mangle]
pub extern "system" fn Java_com_shihab_diplay_network_NativeP2pStartup_validChannel(_: *mut JNIEnv, _: jclass, channel: jint) -> jboolean { valid_channel(channel) as jboolean }
#[no_mangle]
pub extern "system" fn Java_com_shihab_diplay_network_NativeP2pStartup_frequency(_: *mut JNIEnv, _: jclass, channel: jint) -> jint { frequency(channel) }
#[no_mangle]
pub extern "system" fn Java_com_shihab_diplay_network_NativeP2pStartup_remembered(_: *mut JNIEnv, _: jclass, value: jint) -> jint { remembered(value).map(|(mode, _)| mode).unwrap_or(-1) }
#[no_mangle]
pub extern "system" fn Java_com_shihab_diplay_network_NativeP2pStartup_hotspotConflict(_: *mut JNIEnv, _: jclass, sdk: jint, hotspot: jboolean, busy: jboolean, reply: jlong) -> jboolean {
    (sdk >= 33 && hotspot != 0 && busy != 0 && reply >= 1000) as jboolean
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_network_NativeP2pStartup_plan(env: *mut JNIEnv, _: jclass, station: jint,
    preferred_mode: jint, preferred_frequency: jint, has_preferred_frequency: jboolean, channel: jint, band: jboolean, five: jboolean) -> jintArray {
    let values: Vec<_> = plan(station, preferred_mode, preferred_frequency, has_preferred_frequency != 0, channel, band != 0, five != 0)
        .into_iter().flat_map(|(mode, frequency)| [mode, frequency]).collect();
    array(env, &values)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_network_NativeP2pStartup_start(env: *mut JNIEnv, _: jclass, station: jint,
    preferred_mode: jint, preferred_frequency: jint, has_preferred_frequency: jboolean, channel: jint, band: jboolean, five: jboolean) -> jintArray {
    let mut values = vec![0, 0, (channel != 0) as i32];
    values.extend(plan(station, preferred_mode, preferred_frequency, has_preferred_frequency != 0, channel, band != 0, five != 0)
        .into_iter().flat_map(|(mode, frequency)| [mode, frequency]));
    array(env, &values)
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_network_NativeP2pStartup_current(env: *mut JNIEnv, _: jclass, owner: jintArray) -> jintArray {
    let Some(values) = read(env, owner) else { return std::ptr::null_mut(); };
    let Some((mode, frequency)) = current(&values) else { return std::ptr::null_mut(); };
    array(env, &[mode, frequency])
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_network_NativeP2pStartup_compatibility(env: *mut JNIEnv, _: jclass, owner: jintArray) -> jint {
    let Some(mut values) = read(env, owner) else { return 0; };
    let result = compatibility(&mut values);
    ((**env).SetIntArrayRegion.unwrap())(env, owner, 0, 2, values.as_ptr());
    result
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_network_NativeP2pStartup_rejected(env: *mut JNIEnv, _: jclass, owner: jintArray, busy: jboolean, terminal: jboolean) -> jint {
    let Some(mut values) = read(env, owner) else { return 0; };
    let result = rejected(&mut values, busy != 0, terminal != 0);
    ((**env).SetIntArrayRegion.unwrap())(env, owner, 0, 2, values.as_ptr());
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn shared_radio_plans_stay_aligned_deduplicate_and_respect_band_only_requests() {
        assert_eq!(plan(5200, 2, 5745, true, 0, true, true).iter().map(|(_, f)| *f).collect::<Vec<_>>(), [5200, 2437, 2412, 2462, 5180, 5745, 0]);
        assert_eq!(plan(2412, -1, 0, false, -2, true, true), [(1, 2412), (7, 0), (3, 2437), (3, 2462)]);
        assert_eq!(plan(0, -1, 0, false, 36, true, false), [(5, 5180)]);
        assert_eq!(plan(0, -1, 0, false, 0, false, false), [(3, 2437), (3, 2412), (3, 2462), (4, 0)]);
    }
    #[test]
    fn busy_budget_is_per_channel_and_uncertain_or_terminal_failures_do_not_advance() {
        let mut owner = vec![0, 0, 0, 2, 5180, 3, 2437, 4, 0];
        assert_eq!(rejected(&mut owner, true, false), 2);
        assert_eq!(current(&owner), Some((2, 5180)));
        assert_eq!(rejected(&mut owner, true, false), 2);
        assert_eq!(current(&owner), Some((3, 2437)));
        assert_eq!(rejected(&mut owner, true, true), 0);
        assert_eq!(current(&owner), Some((3, 2437)));
        assert_eq!(rejected(&mut owner, true, false), 2);
        assert_eq!(current(&owner), Some((3, 2437)));
        assert_eq!(rejected(&mut owner, true, false), 2);
        assert_eq!(current(&owner), Some((4, 0)));
        assert_eq!(compatibility(&mut owner), 0);
        owner[0] = 0;
        assert_eq!(compatibility(&mut owner), 2);
        assert_eq!(current(&owner), Some((4, 0)));
        owner[2] = 1;
        assert_eq!(compatibility(&mut owner), 1);
    }
}
