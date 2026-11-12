//! Screen framing, Annex B, decode/reference ownership and frame pacing.

pub mod annexb;
mod codec;
pub mod pacing;
pub mod selection;

#[cfg(any(target_os = "android", target_os = "linux"))]
pub mod receiver;
#[cfg(target_os = "android")]
pub mod vpipe;
