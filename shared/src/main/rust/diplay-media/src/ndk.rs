//! The few NDK media and window calls the video pipe uses. libmediandk and libandroid exist from
//! API 21; `AMediaCodec_setAsyncNotifyCallback` only from API 28, so it is looked up at run time and
//! the library still loads on an older debugging phone (whose pipes then cannot start).

#![allow(non_camel_case_types)]

use std::ffi::{c_char, c_void, CStr};
use std::sync::OnceLock;

pub enum AMediaCodec {}
pub enum AMediaFormat {}
pub enum ANativeWindow {}
pub type media_status_t = i32;
pub const AMEDIA_OK: media_status_t = 0;

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct AMediaCodecBufferInfo {
    pub offset: i32,
    pub size: i32,
    pub presentation_time_us: i64,
    pub flags: u32,
}

pub type InputAvailable = unsafe extern "C" fn(*mut AMediaCodec, *mut c_void, i32);
pub type OutputAvailable = unsafe extern "C" fn(*mut AMediaCodec, *mut c_void, i32, *mut AMediaCodecBufferInfo);
pub type FormatChanged = unsafe extern "C" fn(*mut AMediaCodec, *mut c_void, *mut AMediaFormat);
pub type Error = unsafe extern "C" fn(*mut AMediaCodec, *mut c_void, media_status_t, i32, *const c_char);

#[repr(C)]
pub struct AMediaCodecOnAsyncNotifyCallback {
    pub on_input: InputAvailable,
    pub on_output: OutputAvailable,
    pub on_format: FormatChanged,
    pub on_error: Error,
}

pub const KEY_WIDTH: &CStr = c"width";
pub const KEY_HEIGHT: &CStr = c"height";
pub const KEY_MIME: &CStr = c"mime";
pub const BUFFER_FLAG_END_OF_STREAM: u32 = 4;

#[cfg(target_os = "android")]
#[link(name = "mediandk")]
extern "C" {
    pub fn AMediaCodec_createCodecByName(name: *const c_char) -> *mut AMediaCodec;
    pub fn AMediaCodec_createDecoderByType(mime: *const c_char) -> *mut AMediaCodec;
    pub fn AMediaCodec_configure(codec: *mut AMediaCodec, format: *const AMediaFormat, surface: *mut ANativeWindow,
        crypto: *mut c_void, flags: u32) -> media_status_t;
    pub fn AMediaCodec_start(codec: *mut AMediaCodec) -> media_status_t;
    pub fn AMediaCodec_stop(codec: *mut AMediaCodec) -> media_status_t;
    pub fn AMediaCodec_delete(codec: *mut AMediaCodec) -> media_status_t;
    pub fn AMediaCodec_getInputBuffer(codec: *mut AMediaCodec, index: usize, size: *mut usize) -> *mut u8;
    pub fn AMediaCodec_queueInputBuffer(codec: *mut AMediaCodec, index: usize, offset: i64, size: usize, time_us: u64,
        flags: u32) -> media_status_t;
    pub fn AMediaCodec_releaseOutputBuffer(codec: *mut AMediaCodec, index: usize, render: bool) -> media_status_t;
    pub fn AMediaCodec_releaseOutputBufferAtTime(codec: *mut AMediaCodec, index: usize, timestamp_ns: i64) -> media_status_t;
    pub fn AMediaCodec_setOutputSurface(codec: *mut AMediaCodec, surface: *mut ANativeWindow) -> media_status_t;
    pub fn AMediaCodec_getName(codec: *mut AMediaCodec, out_name: *mut *mut c_char) -> media_status_t;
    pub fn AMediaCodec_releaseName(codec: *mut AMediaCodec, name: *mut c_char);
    pub fn AMediaFormat_new() -> *mut AMediaFormat;
    pub fn AMediaFormat_delete(format: *mut AMediaFormat) -> media_status_t;
    pub fn AMediaFormat_setString(format: *mut AMediaFormat, name: *const c_char, value: *const c_char);
    pub fn AMediaFormat_setInt32(format: *mut AMediaFormat, name: *const c_char, value: i32);
    pub fn AMediaFormat_setBuffer(format: *mut AMediaFormat, name: *const c_char, data: *const c_void, size: usize);
    pub fn AMediaFormat_getInt32(format: *mut AMediaFormat, name: *const c_char, out: *mut i32) -> bool;
    pub fn AMediaFormat_toString(format: *mut AMediaFormat) -> *const c_char;
}

#[cfg(target_os = "android")]
#[link(name = "android")]
extern "C" {
    pub fn ANativeWindow_fromSurface(env: *mut jni_sys::JNIEnv, surface: jni_sys::jobject) -> *mut ANativeWindow;
    pub fn ANativeWindow_release(window: *mut ANativeWindow);
}

type SetAsync = unsafe extern "C" fn(*mut AMediaCodec, AMediaCodecOnAsyncNotifyCallback, *mut c_void) -> media_status_t;

/// `AMediaCodec_setAsyncNotifyCallback` (API 28), or None before it.
pub fn set_async_notify_callback() -> Option<SetAsync> {
    static FUNCTION: OnceLock<usize> = OnceLock::new();
    let address = *FUNCTION.get_or_init(|| unsafe {
        let library = libc::dlopen(c"libmediandk.so".as_ptr(), libc::RTLD_NOW);
        if library.is_null() { 0 } else { libc::dlsym(library, c"AMediaCodec_setAsyncNotifyCallback".as_ptr()) as usize }
    });
    (address != 0).then(|| unsafe { std::mem::transmute::<usize, SetAsync>(address) })
}
