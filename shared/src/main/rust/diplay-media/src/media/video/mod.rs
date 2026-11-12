//! Screen framing, Annex B, decode/reference ownership and frame pacing.

pub mod annexb;
pub mod pacing;

#[cfg(any(target_os = "android", target_os = "linux"))]
pub mod receiver;
#[cfg(target_os = "android")]
pub mod vpipe;
