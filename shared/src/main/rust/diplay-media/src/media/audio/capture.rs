//! Microphone frame assembly, echo cancellation and sample accounting. Android owns recorder/effects.
mod jni;
mod stats;
#[cfg(test)]
mod tests;

use super::{echo, uplink::Uplink};
use std::ffi::c_void;

extern "C" {
    fn diplay_speex_create(frame: i32, filter: i32, rate: i32, suppress: i32, active: i32) -> *mut c_void;
    fn diplay_speex_process(owner: *mut c_void, frame: *mut u8, length: usize, reference: *const i16, samples: usize) -> i32;
    fn diplay_speex_destroy(owner: *mut c_void);
}

struct Speex(*mut c_void);
impl Speex {
    fn new(samples: usize, rate: i32) -> Option<Self> {
        let handle = unsafe { diplay_speex_create(samples as i32, (rate / 4).max(samples as i32), rate, -40, -15) };
        (!handle.is_null()).then_some(Self(handle))
    }
    fn process(&mut self, frame: &mut [u8], reference: &[i16]) -> bool {
        unsafe { diplay_speex_process(self.0, frame.as_mut_ptr(), frame.len(), reference.as_ptr(), reference.len()) != 0 }
    }
}
impl Drop for Speex { fn drop(&mut self) { unsafe { diplay_speex_destroy(self.0) }; } }

struct Capture {
    sender: Box<Uplink>,
    frame: Vec<u8>,
    filled: usize,
    rate: i32,
    channels: i32,
    clock: [i64; 3],
    speex: Option<Speex>,
    reference: Vec<i16>,
    echo: Option<echo::SharedReference>,
    // Global reference for platform identity only; the independently retained Rust ring owns PCM.
    source: jni_sys::jobject,
}

impl Capture {
    fn fill(&mut self, input: &[u8]) -> usize {
        let copied = input.len().min(self.frame.len() - self.filled);
        self.frame[self.filled..self.filled + copied].copy_from_slice(&input[..copied]);
        self.filled += copied;
        copied
    }

    fn process_echo(&mut self, end_ns: i64) -> bool {
        self.reference.fill(0);
        if let Some(reference) = &self.echo {
            let mut reference = reference.lock().unwrap();
            if reference.rate != self.rate as i64 { return false; }
            reference.read(&mut self.reference, end_ns.wrapping_add(30_000_000));
        }
        self.speex.as_mut().is_none_or(|speex| speex.process(&mut self.frame, &self.reference))
    }
}
