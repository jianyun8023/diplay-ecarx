//! Bounded, incremental CSM framing; transport threads and byte-stream I/O stay with the adapter.

const MAX_FRAME: usize = 65535;
const HEADER: usize = 6;

#[derive(Default)]
pub struct Framer { receive: Vec<u8> }

impl Framer {
    pub fn offer(&mut self, chunk: &[u8]) -> Vec<(u16, Vec<u8>)> {
        let mut frames = Vec::new();
        let mut offset = 0;
        while offset < chunk.len() {
            self.drain(&mut frames);
            let count = (chunk.len() - offset).min(MAX_FRAME - self.receive.len());
            debug_assert!(count > 0);
            self.receive.extend_from_slice(&chunk[offset..offset + count]);
            offset += count;
        }
        self.drain(&mut frames);
        frames
    }

    fn drain(&mut self, frames: &mut Vec<(u16, Vec<u8>)>) {
        let mut head = 0;
        while self.receive.len() - head >= 2 {
            if self.receive[head..head + 2] != [0x40, 0x40] { head += 1; continue; }
            if self.receive.len() - head < HEADER { break; }
            let length = u16::from_be_bytes([self.receive[head + 2], self.receive[head + 3]]) as usize;
            if length < HEADER { head += 1; continue; }
            if self.receive.len() - head < length { break; }
            let id = u16::from_be_bytes([self.receive[head + 4], self.receive[head + 5]]);
            frames.push((id, self.receive[head + HEADER..head + length].to_vec()));
            head += length;
        }
        self.receive.drain(..head);
    }
}

pub fn encode(parameter: bool, id: i32, payload: &[u8]) -> Result<Vec<u8>, String> {
    let name = if parameter { "parameter" } else { "message" };
    if !(0..=65535).contains(&id) { return Err(format!("CSM {name} id must fit in u16")); }
    let header = if parameter { 4 } else { HEADER };
    if payload.len() > MAX_FRAME - header {
        return Err(if parameter { "CSM parameter payload exceeds 65531 bytes" } else { "CSM payload exceeds 65529 bytes" }.into());
    }
    let mut out = Vec::with_capacity(header + payload.len());
    if !parameter { out.extend_from_slice(&[0x40, 0x40]); }
    out.extend_from_slice(&((header + payload.len()) as u16).to_be_bytes());
    out.extend_from_slice(&(id as u16).to_be_bytes());
    out.extend_from_slice(payload);
    Ok(out)
}

pub fn split(frame: &[u8], chunk_size: i32) -> Result<Vec<Vec<u8>>, &'static str> {
    if !(1..=65525).contains(&chunk_size) { return Err("iAP2 link chunk size must be in 1..65525"); }
    if !(HEADER..=MAX_FRAME).contains(&frame.len()) { return Err("CSM frame size must be 6..65535"); }
    if frame[..2] != [0x40, 0x40] { return Err("CSM frame must start with 0x4040"); }
    if u16::from_be_bytes([frame[2], frame[3]]) as usize != frame.len() { return Err("CSM frame length must equal its complete size"); }
    Ok(frame.chunks(chunk_size as usize).map(<[u8]>::to_vec).collect())
}

use super::iap2_body::{byte_array, read_bytes, throw};
use jni_sys::{jboolean, jbyteArray, jclass, jint, jlong, jobjectArray, jvalue, JNIEnv};
use std::ptr::null_mut;

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_iap2_wire_NativeIap2Csm_create(_: *mut JNIEnv, _: jclass) -> jlong {
    Box::into_raw(Box::new(Framer::default())) as jlong
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_iap2_wire_NativeIap2Csm_destroy(_: *mut JNIEnv, _: jclass, handle: jlong) {
    if handle != 0 { drop(Box::from_raw(handle as *mut Framer)); }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_iap2_wire_NativeIap2Csm_offer(env: *mut JNIEnv, _: jclass, handle: jlong, chunk: jbyteArray) -> jobjectArray {
    if handle == 0 { return null_mut(); }
    let Some(bytes) = read_bytes(env, chunk) else { return null_mut(); };
    let frames = (&mut *(handle as *mut Framer)).offer(&bytes);
    let f = &**env;
    if (f.PushLocalFrame.unwrap())(env, 16) < 0 { return null_mut(); }
    let result = (|| {
        let class = (f.FindClass.unwrap())(env, c"com/shilapi/xcertplay/iap2/wire/Iap2Frame".as_ptr());
        if class.is_null() { return None; }
        let init = (f.GetMethodID.unwrap())(env, class, c"<init>".as_ptr(), c"(I[B)V".as_ptr());
        if init.is_null() { return None; }
        let array = (f.NewObjectArray.unwrap())(env, frames.len() as jint, class, null_mut());
        if array.is_null() { return None; }
        for (index, (id, payload)) in frames.iter().enumerate() {
            let bytes = byte_array(env, payload);
            if bytes.is_null() { return None; }
            let args = [jvalue { i: *id as jint }, jvalue { l: bytes }];
            let frame = (f.NewObjectA.unwrap())(env, class, init, args.as_ptr());
            (f.DeleteLocalRef.unwrap())(env, bytes);
            if (f.ExceptionCheck.unwrap())(env) != 0 || frame.is_null() { return None; }
            (f.SetObjectArrayElement.unwrap())(env, array, index as jint, frame);
            (f.DeleteLocalRef.unwrap())(env, frame);
            if (f.ExceptionCheck.unwrap())(env) != 0 { return None; }
        }
        Some(array)
    })();
    (f.PopLocalFrame.unwrap())(env, result.unwrap_or(null_mut()))
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_iap2_wire_NativeIap2Csm_encode(env: *mut JNIEnv, _: jclass, parameter: jboolean, id: jint, payload: jbyteArray) -> jbyteArray {
    let Some(bytes) = read_bytes(env, payload) else { return null_mut(); };
    match encode(parameter != 0, id, &bytes) {
        Ok(out) => byte_array(env, &out),
        Err(message) => { throw(env, "java/lang/IllegalArgumentException", &message); null_mut() }
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_iap2_wire_NativeIap2Csm_split(env: *mut JNIEnv, _: jclass, frame: jbyteArray, chunk_size: jint) -> jobjectArray {
    let Some(bytes) = read_bytes(env, frame) else { return null_mut(); };
    let chunks = match split(&bytes, chunk_size) {
        Ok(chunks) => chunks,
        Err(message) => { throw(env, "java/lang/IllegalArgumentException", message); return null_mut(); }
    };
    let f = &**env;
    let class = (f.FindClass.unwrap())(env, c"[B".as_ptr());
    if class.is_null() { return null_mut(); }
    let array = (f.NewObjectArray.unwrap())(env, chunks.len() as jint, class, null_mut());
    (f.DeleteLocalRef.unwrap())(env, class);
    if array.is_null() { return null_mut(); }
    for (index, chunk) in chunks.iter().enumerate() {
        let item = byte_array(env, chunk);
        if item.is_null() { return null_mut(); }
        (f.SetObjectArrayElement.unwrap())(env, array, index as jint, item);
        (f.DeleteLocalRef.unwrap())(env, item);
        if (f.ExceptionCheck.unwrap())(env) != 0 { return null_mut(); }
    }
    array
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn arbitrary_chunking_garbage_bad_lengths_and_concatenation_resynchronize() {
        let first = encode(false, 0xffff, &[1, 2]).unwrap();
        let second = encode(false, 7, &[]).unwrap();
        let wire = [vec![1, 0x40, 0x40, 0, 1], first, second].concat();
        for size in 1..=wire.len() {
            let mut owner = Framer::default();
            let frames: Vec<_> = wire.chunks(size).flat_map(|chunk| owner.offer(chunk)).collect();
            assert_eq!(frames, vec![(65535, vec![1, 2]), (7, vec![])]);
            assert!(owner.receive.len() < HEADER);
        }
    }

    #[test]
    fn maximum_frames_bound_retained_bytes_and_split_reassembles() {
        let frame = encode(false, 1, &vec![42; 65529]).unwrap();
        let mut owner = Framer::default();
        assert!(owner.offer(&frame[..65534]).is_empty());
        assert_eq!(owner.receive.len(), 65534);
        assert_eq!(owner.offer(&frame[65534..]), vec![(1, vec![42; 65529])]);
        assert_eq!(split(&frame, 65525).unwrap().concat(), frame);
        assert_eq!(split(&[], 0).unwrap_err(), "iAP2 link chunk size must be in 1..65525");
    }
}
