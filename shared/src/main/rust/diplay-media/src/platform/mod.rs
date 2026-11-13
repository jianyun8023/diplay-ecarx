//! JVM attachment/callbacks, native codec declarations and shared socket-worker lifetimes.

pub mod jvm;

#[cfg(target_os = "android")]
pub mod ndk;
#[cfg(any(target_os = "android", target_os = "linux"))]
pub mod stream;
