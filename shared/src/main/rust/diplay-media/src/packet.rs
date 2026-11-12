//! Pooled, reference-counted packet buffers. A receiver fills one and hands Kotlin its handle
//! (`NativePacket`); every owner releases its reference once, and the last release returns the
//! buffer to its pool. Kotlin never sees the bytes on the heap unless it asks for a copy.

use crate::jvm::Env;
use jni_sys::{jbyteArray, jclass, jint, jlong, jobject, JNIEnv};
use std::sync::atomic::{AtomicU32, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

/// Buffers kept for reuse per pool, and the largest one kept; bigger ones are freed on release.
const MAX_FREE: usize = 32;
const MAX_KEPT_CAPACITY: usize = 1 << 20;

pub struct Pool {
    // Boxed: a handle is the address of its packet, which must not move.
    #[allow(clippy::vec_box)]
    free: Mutex<Vec<Box<Packet>>>,
    /// Packets handed out and not yet released, reported with the stream's stats.
    outstanding: AtomicUsize,
}

pub struct Packet {
    data: Vec<u8>,
    len: usize,
    refs: AtomicU32,
    pool: Arc<Pool>,
}

impl Pool {
    pub fn new() -> Arc<Pool> {
        Arc::new(Pool { free: Mutex::new(Vec::new()), outstanding: AtomicUsize::new(0) })
    }

    /// A packet with room for `capacity` bytes and length 0.
    pub fn take(self: &Arc<Self>, capacity: usize) -> Box<Packet> {
        let reused = self.free.lock().unwrap().pop();
        self.outstanding.fetch_add(1, Ordering::Relaxed);
        let mut packet = reused.unwrap_or_else(|| Box::new(Packet {
            data: Vec::new(), len: 0, refs: AtomicU32::new(1), pool: self.clone(),
        }));
        packet.len = 0;
        packet.refs.store(1, Ordering::Relaxed);
        if packet.data.capacity() < capacity {
            packet.data = Vec::with_capacity(capacity);
        }
        packet
    }

    pub fn outstanding(&self) -> usize { self.outstanding.load(Ordering::Relaxed) }

    fn put(&self, packet: Box<Packet>) {
        self.outstanding.fetch_sub(1, Ordering::Relaxed);
        if packet.data.capacity() > MAX_KEPT_CAPACITY { return; }
        let mut free = self.free.lock().unwrap();
        if free.len() < MAX_FREE { free.push(packet); }
    }
}

impl Packet {
    /// Room for `capacity` bytes; the received bytes are written through [spare] and then [set_len].
    pub fn reserve(&mut self, capacity: usize) {
        if self.data.capacity() < capacity { self.data = Vec::with_capacity(capacity); }
    }

    pub fn spare(&mut self) -> *mut u8 { self.data.as_mut_ptr() }

    pub fn capacity(&self) -> usize { self.data.capacity() }

    /// # Safety
    /// The first `len` bytes must have been written.
    pub unsafe fn set_len(&mut self, len: usize) {
        debug_assert!(len <= self.data.capacity());
        self.data.set_len(len);
        self.len = len;
    }

    pub fn bytes(&self) -> &[u8] { &self.data[..self.len] }

    pub fn bytes_mut(&mut self) -> &mut [u8] { let len = self.len; &mut self.data[..len] }

    pub fn truncate(&mut self, len: usize) {
        if len < self.len { self.len = len; self.data.truncate(len); }
    }

    pub fn len(&self) -> usize { self.len }

    /// Gives up ownership; Kotlin releases the handle.
    pub fn into_handle(self: Box<Self>) -> jlong { Box::into_raw(self) as jlong }

    /// Returns the packet to its pool without handing it out.
    pub fn recycle(self: Box<Self>) {
        let pool = self.pool.clone();
        pool.put(self);
    }
}

unsafe fn packet<'a>(handle: jlong) -> &'a Packet { &*(handle as *const Packet) }

/// Drops one reference; the last one returns the buffer to its pool.
pub unsafe fn release(handle: jlong) {
    if handle == 0 { return; }
    let shared = packet(handle);
    if shared.refs.fetch_sub(1, Ordering::AcqRel) == 1 {
        Box::from_raw(handle as *mut Packet).recycle();
    }
}

static BUFFERED_POOL: OnceLock<Arc<Pool>> = OnceLock::new();

/// The pool for packets that Kotlin threads open themselves (the buffered audio stream).
pub fn shared_pool() -> &'static Arc<Pool> { BUFFERED_POOL.get_or_init(Pool::new) }

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_packetRetain(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) {
    retain(handle);
}

pub unsafe fn retain(handle: jlong) {
    if handle != 0 { packet(handle).refs.fetch_add(1, Ordering::Relaxed); }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_packetRelease(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) {
    release(handle);
}

/// Copies `length` bytes from `offset` into the direct buffer `target` at `position`.
/// Returns the bytes copied, or -1 when the target is not direct or a bound does not hold.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_packetCopyInto(
    env: *mut JNIEnv, _class: jclass, handle: jlong, offset: jint, length: jint, target: jobject, position: jint,
) -> jint {
    if handle == 0 || offset < 0 || length < 0 || position < 0 { return -1; }
    let source = packet(handle).bytes();
    let (offset, length, position) = (offset as usize, length as usize, position as usize);
    if offset > source.len() || length > source.len() - offset { return -1; }
    let Some((address, capacity)) = Env(env).direct_buffer(target) else { return -1 };
    if position > capacity || length > capacity - position { return -1; }
    std::ptr::copy_nonoverlapping(source.as_ptr().add(offset), address.add(position), length);
    length as jint
}

/// Copies `length` bytes from `offset` into `out` at `out_offset`; false when a bound does not hold.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_packetRead(
    env: *mut JNIEnv, _class: jclass, handle: jlong, offset: jint, length: jint, out: jbyteArray, out_offset: jint,
) -> jni_sys::jboolean {
    if handle == 0 || offset < 0 || length < 0 || out_offset < 0 { return 0; }
    let source = packet(handle).bytes();
    let (offset, length) = (offset as usize, length as usize);
    if offset > source.len() || length > source.len() - offset { return 0; }
    let e = Env(env);
    if (out_offset as usize) + length > e.array_len(out) { return 0; }
    e.write_array(out, out_offset as usize, &source[offset..offset + length]) as jni_sys::jboolean
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_last_release_returns_the_buffer_for_reuse() {
        let pool = Pool::new();
        let mut first = pool.take(64);
        let address = first.spare();
        unsafe { first.set_len(3) };
        let handle = first.into_handle();
        unsafe {
            Java_com_shilapi_xcertplay_airplay_NativeMedia_packetRetain(std::ptr::null_mut(), std::ptr::null_mut(), handle);
            release(handle);
            assert_eq!(pool.outstanding(), 1);
            release(handle);
        }
        assert_eq!(pool.outstanding(), 0);
        let mut again = pool.take(16);
        assert_eq!(again.spare(), address);
        assert_eq!(again.len(), 0);
        again.recycle();
    }

    #[test]
    fn large_buffers_are_not_kept() {
        let pool = Pool::new();
        pool.take(MAX_KEPT_CAPACITY + 1).recycle();
        assert!(pool.free.lock().unwrap().is_empty());
    }
}

pub unsafe fn size(handle: jlong) -> usize { if handle == 0 { 0 } else { packet(handle).len() } }

/// The bytes behind a live handle; the caller keeps its reference for as long as it reads them.
pub unsafe fn bytes<'a>(handle: jlong) -> &'a [u8] { packet(handle).bytes() }
