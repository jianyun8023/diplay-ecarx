use super::{DecodeQueue, Job, ReferenceChain};
use jni_sys::{jboolean, jclass, jint, jlong, jlongArray, jobject, jobjectArray, JNIEnv};
use std::ptr::null_mut;

unsafe fn ok(env: *mut JNIEnv) -> Option<()> { (((**env).ExceptionCheck.unwrap())(env) == 0).then_some(()) }
unsafe fn invalid(env: *mut JNIEnv) {
    if ok(env).is_none() { return; }
    let c = ((**env).FindClass.unwrap())(env, c"java/lang/IllegalArgumentException".as_ptr());
    if !c.is_null() { ((**env).ThrowNew.unwrap())(env, c, c"Invalid video queue state or operation".as_ptr()); }
}
unsafe fn frame(env: *mut JNIEnv, work: impl FnOnce() -> Option<jobject>) -> jobject {
    if ((**env).PushLocalFrame.unwrap())(env, 16) < 0 { return null_mut(); }
    let result = work().unwrap_or(null_mut());
    ((**env).PopLocalFrame.unwrap())(env, result)
}
unsafe fn longs(env: *mut JNIEnv, values: &[i64]) -> Option<jlongArray> {
    let a = ((**env).NewLongArray.unwrap())(env, values.len() as i32); ok(env)?;
    ((**env).SetLongArrayRegion.unwrap())(env, a, 0, values.len() as i32, values.as_ptr()); ok(env)?;
    Some(a)
}
unsafe fn objects(env: *mut JNIEnv, size: usize) -> Option<jobjectArray> {
    let c = ((**env).FindClass.unwrap())(env, c"java/lang/Object".as_ptr()); ok(env)?;
    let a = ((**env).NewObjectArray.unwrap())(env, size as i32, c, null_mut());
    ((**env).DeleteLocalRef.unwrap())(env, c); ok(env)?;
    Some(a)
}
unsafe fn set(env: *mut JNIEnv, array: jobjectArray, index: i32, value: jobject) -> Option<()> {
    ((**env).SetObjectArrayElement.unwrap())(env, array, index, value); ok(env)
}
unsafe fn read(env: *mut JNIEnv, state: jobjectArray) -> Option<(DecodeQueue, jobjectArray, jobject)> {
    if state.is_null() || ((**env).GetArrayLength.unwrap())(env, state) != 3 { invalid(env); return None; }
    let meta = ((**env).GetObjectArrayElement.unwrap())(env, state, 0); ok(env)?;
    let jobs = ((**env).GetObjectArrayElement.unwrap())(env, state, 1); ok(env)?;
    let resync = ((**env).GetObjectArrayElement.unwrap())(env, state, 2); ok(env)?;
    if meta.is_null() || jobs.is_null() || resync.is_null() { invalid(env); return None; }
    let count = ((**env).GetArrayLength.unwrap())(env, meta); ok(env)?;
    let mut v = vec![0; count as usize];
    if count > 0 { ((**env).GetLongArrayRegion.unwrap())(env, meta, 0, count, v.as_mut_ptr()); ok(env)?; }
    let jobs_count = ((**env).GetArrayLength.unwrap())(env, jobs); ok(env)?;
    if v.len() < 3 || (v.len() - 3) % 3 != 0 || (v.len() - 3) / 3 != jobs_count as usize { invalid(env); return None; }
    Some((DecodeQueue { max_frames: v[0] as i32, max_bytes: v[1] as i32, drained: v[2] != 0,
        jobs: v[3..].chunks_exact(3).enumerate().map(|(i, e)| Job { slot: i as i32, kind: e[0], size: e[1], received: e[2] }).collect() }, jobs, resync))
}
unsafe fn job_array<'a>(env: *mut JNIEnv, jobs: impl ExactSizeIterator<Item = &'a Job>, old: jobjectArray,
    incoming: jobject, resync: jobject) -> Option<jobjectArray> {
    let out = objects(env, jobs.len())?;
    for (i, job) in jobs.enumerate() {
        let object = match job.slot {
            -1 => incoming,
            -2 => resync,
            index => { let o = ((**env).GetObjectArrayElement.unwrap())(env, old, index); ok(env)?; o },
        };
        let result = set(env, out, i as i32, object);
        if job.slot >= 0 { ((**env).DeleteLocalRef.unwrap())(env, object); }
        result?;
    }
    Some(out)
}
unsafe fn commit(env: *mut JNIEnv, state: jobjectArray, queue: &DecodeQueue, old: jobjectArray,
    incoming: jobject, resync: jobject, removed: &[Job]) -> Option<jobjectArray> {
    let mut meta = vec![queue.max_frames as i64, queue.max_bytes as i64, queue.drained as i64];
    for job in &queue.jobs { meta.extend([job.kind, job.size, job.received]); }
    // Allocate the replacement carrier and return values before publishing either state field.
    let meta = longs(env, &meta)?;
    let jobs = job_array(env, queue.jobs.iter(), old, incoming, resync)?;
    let removed = job_array(env, removed.iter(), old, incoming, resync)?;
    set(env, state, 0, meta)?; set(env, state, 1, jobs)?;
    Some(removed)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeVideoFlow_queueCreate(
    env: *mut JNIEnv, _: jclass, max_frames: jint, max_bytes: jint, resync: jobject,
) -> jobjectArray {
    frame(env, || {
        let state = objects(env, 3)?;
        let meta = longs(env, &[max_frames as i64, max_bytes as i64, 0])?;
        let jobs = objects(env, 0)?;
        set(env, state, 0, meta)?; set(env, state, 1, jobs)?; set(env, state, 2, resync)?;
        Some(state)
    })
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeVideoFlow_queueOffer(
    env: *mut JNIEnv, _: jclass, state: jobjectArray, job: jobject, kind: jint, size: jint, received: jlong,
) -> jobjectArray {
    frame(env, || {
        if !(0..=2).contains(&kind) { invalid(env); return None; }
        let (mut queue, old, resync) = read(env, state)?;
        let removed = queue.offer(Job { slot: -1, kind: kind as i64, size: size as i64, received });
        commit(env, state, &queue, old, job, resync, &removed)
    })
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeVideoFlow_queueRemove(
    env: *mut JNIEnv, _: jclass, state: jobjectArray, op: jint,
) -> jobjectArray {
    frame(env, || {
        if !(0..=3).contains(&op) { invalid(env); return None; }
        let (mut queue, old, resync) = read(env, state)?;
        let removed = queue.remove(op);
        commit(env, state, &queue, old, null_mut(), resync, &removed)
    })
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeVideoFlow_queueBacklog(
    env: *mut JNIEnv, _: jclass, state: jobjectArray,
) -> jlongArray { frame(env, || { let (queue, _, _) = read(env, state)?; longs(env, &queue.backlog()) }) }
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeVideoFlow_queueResync(
    env: *mut JNIEnv, _: jclass, state: jobjectArray,
) -> jboolean {
    let mut result = false;
    frame(env, || { result = read(env, state)?.0.resync_pending(); None });
    result as jboolean
}
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_media_NativeVideoFlow_reference(
    env: *mut JNIEnv, _: jclass, token: jlong, event: jint,
) -> jlong {
    let mut chain = ReferenceChain { needs_keyframe: token != 0 };
    match event { 0 | 1 => chain.reset(), 2 => chain.queued(), _ => invalid(env) }
    chain.needs_keyframe() as jlong
}
#[no_mangle]
pub extern "system" fn Java_com_shilapi_xcertplay_media_NativeVideoFlow_referenceNeeds(
    _: *mut JNIEnv, _: jclass, token: jlong,
) -> jboolean { ReferenceChain { needs_keyframe: token != 0 }.needs_keyframe() as jboolean }
#[no_mangle]
pub extern "system" fn Java_com_shilapi_xcertplay_media_NativeVideoFlow_referenceAccepts(
    _: *mut JNIEnv, _: jclass, token: jlong, random: jboolean,
) -> jboolean { ReferenceChain { needs_keyframe: token != 0 }.accepts(random != 0) as jboolean }
#[no_mangle]
pub extern "system" fn Java_com_shilapi_xcertplay_media_NativeVideoFlow_obsolete(
    _: *mut JNIEnv, _: jclass, now: jlong, received: jlong, pending: jint, newest: jlong, has_newest: jboolean,
) -> jboolean {
    crate::media::video::pacing::is_obsolete(now, received, pending.max(0) as usize, (has_newest != 0).then_some(newest)) as jboolean
}
