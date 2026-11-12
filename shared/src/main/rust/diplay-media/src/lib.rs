//! DiPlay's media data plane (see docs/ARCHITECTURE.zh-CN.md): the CarPlay media streams are
//! received, opened and framed here, and Kotlin gets pooled packet handles (`NativeMedia`).

// JNI references are opaque handles that the JVM checks; only the JVM calls the `Java_*` entry points.
#![allow(clippy::not_unsafe_ptr_arg_deref, clippy::missing_safety_doc)]

pub mod aaudio;
pub mod annexb;
pub mod buffered;
pub mod buffered_state;
pub mod audio_queue;
pub mod clock_trim;
pub mod crypto;
pub mod jvm;
pub mod ncm;
pub mod pacing;
pub mod packet;
pub mod pcm;
pub mod renderer;
pub mod rtsp;
mod rtsp_jni;
pub mod session_state;
pub mod stats;
pub mod uplink;
pub mod vehicle_status;

#[cfg(any(target_os = "android", target_os = "linux"))]
pub mod audio;
#[cfg(target_os = "android")]
pub mod ndk;
#[cfg(target_os = "android")]
pub mod vpipe;
#[cfg(any(target_os = "android", target_os = "linux"))]
pub mod ncm_engine;
#[cfg(any(target_os = "android", target_os = "linux"))]
pub mod stream;
#[cfg(any(target_os = "android", target_os = "linux"))]
pub mod video;

/// Stops a stream started by `startVideo` or `startAudio` and gives up its handle. Safe from the
/// stream's own callbacks; the receiver thread then ends by itself.
#[cfg(any(target_os = "android", target_os = "linux"))]
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_stop(
    _env: *mut jni_sys::JNIEnv, _class: jni_sys::jclass, handle: jni_sys::jlong,
) {
    stream::stop(handle)
}
