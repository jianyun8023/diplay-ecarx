//! What every receiver shares: the socket it owns, how Kotlin stops it, and the Kotlin callbacks
//! (`NativeStreamCallbacks`) it reports to from its own attached thread.

use crate::platform::jvm::{Attached, Env};
use jni_sys::{jlong, jmethodID, jobject, jvalue};
use std::os::raw::c_int;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

pub struct Control {
    closed: AtomicBool,
    /// The receiver's duplicate of the Kotlin socket; None once the thread has closed it.
    socket: Mutex<Option<c_int>>,
    /// Wakes a receiver waiting in poll (UDP has no shutdown that reliably wakes a reader).
    wake: c_int,
}

impl Control {
    pub fn new(socket: c_int) -> Arc<Control> {
        let wake = unsafe { libc::eventfd(0, libc::EFD_CLOEXEC | libc::EFD_NONBLOCK) };
        Arc::new(Control { closed: AtomicBool::new(false), socket: Mutex::new(Some(socket)), wake })
    }

    pub fn closed(&self) -> bool { self.closed.load(Ordering::Acquire) }

    pub fn wake_fd(&self) -> c_int { self.wake }

    pub fn socket(&self) -> Option<c_int> { *self.socket.lock().unwrap() }

    /// Stops the receiver: wakes it in poll and ends a blocking TCP read.
    pub fn stop(&self) {
        self.closed.store(true, Ordering::Release);
        if self.wake >= 0 {
            let one: u64 = 1;
            unsafe { libc::write(self.wake, &one as *const u64 as *const _, 8) };
        }
        if let Some(fd) = *self.socket.lock().unwrap() {
            unsafe { libc::shutdown(fd, libc::SHUT_RDWR) };
        }
    }

    /// Called once by the receiver as it exits.
    pub fn close_socket(&self) {
        if let Some(fd) = self.socket.lock().unwrap().take() {
            unsafe { libc::close(fd) };
        }
    }
}

impl Drop for Control {
    fn drop(&mut self) {
        self.close_socket();
        if self.wake >= 0 { unsafe { libc::close(self.wake) }; }
    }
}

/// The Kotlin side of one stream. Method ids come from the callbacks object's class.
pub struct Callbacks {
    object: jobject,
    on_packet: jmethodID,
    on_audio_started: jmethodID,
    on_audio_wire: jmethodID,
    on_audio_clock: jmethodID,
    on_audio_queue: jmethodID,
    on_config: jmethodID,
    on_receiving: jmethodID,
    on_log: jmethodID,
    on_report: jmethodID,
    on_ended: jmethodID,
}

// The global reference and method ids are valid on every thread.
unsafe impl Send for Callbacks {}

impl Callbacks {
    pub fn new(env: Env, object: jobject) -> Option<Callbacks> {
        let class = env.object_class(object);
        if class.is_null() { return None; }
        let callbacks = Callbacks {
            object: env.new_global(object),
            on_packet: env.method(class, "onPacket", "(JIIJJ)V"),
            on_audio_started: env.method(class, "onAudioStarted", "(I)V"),
            on_audio_wire: env.method(class, "onAudioWire", "([B[BJI)V"),
            on_audio_clock: env.method(class, "onAudioClock", "(I)V"),
            on_audio_queue: env.method(class, "onAudioQueueEvent", "(IJJ)V"),
            on_config: env.method(class, "onConfig", "([B)V"),
            on_receiving: env.method(class, "onReceiving", "()V"),
            on_log: env.method(class, "onLog", "(ILjava/lang/String;)V"),
            on_report: env.method(class, "onReport", "(Ljava/lang/String;)V"),
            on_ended: env.method(class, "onEnded", "(Ljava/lang/String;)V"),
        };
        env.delete_local(class);
        let complete = !callbacks.object.is_null() && !callbacks.on_packet.is_null() && !callbacks.on_config.is_null() &&
            !callbacks.on_receiving.is_null() && !callbacks.on_log.is_null() && !callbacks.on_report.is_null() && !callbacks.on_ended.is_null();
        let complete = complete && !callbacks.on_audio_started.is_null() && !callbacks.on_audio_wire.is_null()
            && !callbacks.on_audio_clock.is_null() && !callbacks.on_audio_queue.is_null();
        if complete { Some(callbacks) } else { env.delete_global(callbacks.object); None }
    }

    /// Hands a packet's handle to Kotlin, which owns it from here on.
    pub fn packet(&self, env: Env, handle: jlong, size: usize, flags: i32, a: i64, b: i64) {
        let args = [jvalue { j: handle }, jvalue { i: size as i32 }, jvalue { i: flags }, jvalue { j: a }, jvalue { j: b }];
        env.call_void(self.object, self.on_packet, &args);
    }

    pub fn audio_started(&self, env: Env, sample: i32) {
        env.call_void(self.object, self.on_audio_started, &[jvalue { i: sample }]);
    }

    pub fn audio_clock(&self, env: Env, hz: i32) {
        env.call_void(self.object, self.on_audio_clock, &[jvalue { i: hz }]);
    }

    pub fn audio_queue_event(&self, env: Env, event: [i64; 3]) {
        env.call_void(self.object, self.on_audio_queue, &[
            jvalue { i: event[0] as i32 }, jvalue { j: event[1] }, jvalue { j: event[2] }]);
    }

    /// Debug capture only: preserve the original datagram and opened RTP, including rejected wire.
    pub fn audio_wire(&self, env: Env, wire: &[u8], opened: Option<&[u8]>, sample: u32, error: i32) {
        let wire = env.byte_array(wire);
        let opened = opened.map(|bytes| env.byte_array(bytes)).unwrap_or(std::ptr::null_mut());
        env.call_void(self.object, self.on_audio_wire, &[
            jvalue { l: wire }, jvalue { l: opened }, jvalue { j: sample as i64 }, jvalue { i: error }]);
        env.delete_local(opened);
        env.delete_local(wire);
    }

    pub fn config(&self, env: Env, bytes: &[u8]) {
        let array = env.byte_array(bytes);
        env.call_void(self.object, self.on_config, &[jvalue { l: array }]);
        env.delete_local(array);
    }

    pub fn receiving(&self, env: Env) { env.call_void(self.object, self.on_receiving, &[]); }

    /// Logcat: 0 info, 1 warning.
    pub fn log(&self, env: Env, level: i32, line: &str) {
        let text = env.string(line);
        env.call_void(self.object, self.on_log, &[jvalue { i: level }, jvalue { l: text }]);
        env.delete_local(text);
    }

    /// The session's diagnostic log.
    pub fn report(&self, env: Env, line: &str) {
        let text = env.string(line);
        env.call_void(self.object, self.on_report, &[jvalue { l: text }]);
        env.delete_local(text);
    }

    pub fn ended(&self, env: Env, error: Option<&str>) {
        let text = error.map(|e| env.string(e)).unwrap_or(std::ptr::null_mut());
        env.call_void(self.object, self.on_ended, &[jvalue { l: text }]);
        env.delete_local(text);
    }

    pub fn dispose(self, env: Env) { env.delete_global(self.object); }
}

/// Starts `body` on a named thread attached to the JVM; the callbacks are released when it ends.
/// Returns the handle Kotlin stops the stream with.
pub fn spawn<F>(name: &str, control: Arc<Control>, callbacks: Callbacks, body: F) -> jlong
where
    F: FnOnce(Env, &Control, &Callbacks) + Send + 'static,
{
    let thread_control = control.clone();
    let thread_name = name.to_string();
    let started = std::thread::Builder::new().name(name.to_string()).spawn(move || {
        let Some(attached) = Attached::current(&thread_name) else {
            thread_control.close_socket();
            return;
        };
        body(attached.env, &thread_control, &callbacks);
        thread_control.close_socket();
        callbacks.dispose(attached.env);
    });
    if started.is_err() {
        control.close_socket();
        return 0;
    }
    Box::into_raw(Box::new(control)) as jlong
}

/// Gives up a handle from [spawn] without stopping its thread, for an owner that keeps the control.
pub unsafe fn release_handle(handle: jlong) {
    if handle != 0 { drop(Box::from_raw(handle as *mut Arc<Control>)); }
}

/// Stops the stream and gives up Kotlin's handle; safe to call from the receiver's own callbacks.
pub unsafe fn stop(handle: jlong) {
    if handle == 0 { return; }
    let control = Box::from_raw(handle as *mut Arc<Control>);
    control.stop();
}

/// A blocking read of exactly `out.len()` bytes; false at EOF, on error, or once stopped.
pub fn recv_exact(fd: c_int, out: *mut u8, len: usize) -> Result<bool, std::io::Error> {
    let mut done = 0usize;
    while done < len {
        let n = unsafe { libc::recv(fd, out.add(done) as *mut _, len - done, libc::MSG_WAITALL) };
        if n > 0 {
            done += n as usize;
        } else if n == 0 {
            return Ok(false);
        } else {
            let error = std::io::Error::last_os_error();
            if error.kind() == std::io::ErrorKind::Interrupted { continue; }
            return Err(error);
        }
    }
    Ok(true)
}

/// Stops a stream started by `startVideo` or `startAudio` and gives up its handle. Safe from the
/// stream's own callbacks; the receiver thread then ends by itself.
#[cfg(any(target_os = "android", target_os = "linux"))]
#[no_mangle]
pub unsafe extern "system" fn Java_com_shilapi_xcertplay_airplay_NativeMedia_stop(
    _env: *mut jni_sys::JNIEnv, _class: jni_sys::jclass, handle: jni_sys::jlong,
) {
    stop(handle)
}
