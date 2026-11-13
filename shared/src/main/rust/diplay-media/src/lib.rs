//! CarPlay core owners and their JNI boundaries, grouped by functional responsibility.
//! See docs/ARCHITECTURE.zh-CN.md for the Kotlin/platform boundary.

// JNI references are opaque handles that the JVM checks; only the JVM calls the `Java_*` entry points.
#![allow(clippy::not_unsafe_ptr_arg_deref, clippy::missing_safety_doc)]

pub mod media;
pub mod platform;
pub mod protocols;
pub mod session;
pub mod transport;
pub mod vehicle;
