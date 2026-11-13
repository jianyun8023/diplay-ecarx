//! One CarPlay RTP audio stream (Kotlin's `AudioStream`): a 12-byte RTP header, ciphertext, a
//! 16-byte tag and an 8-byte little-endian nonce per datagram; the header's timestamp and SSRC are
//! the associated data. Datagrams arrive in batches through recvmmsg and are opened in place, so the
//! packet Kotlin receives is the plain RTP packet.

use super::{queue::AudioPacket, route::{self, AudioRoute, Delivery}};
use std::sync::Arc;
use crate::protocols::crypto::{StreamKey, TAG_LEN};
use crate::platform::jvm::Env;
use crate::media::packet::{Packet, Pool};
use crate::media::stats::{clock, ReceiveStats, SystemClock, SESSION_REPORT_INTERVAL_NS};
use crate::platform::stream::{self, Callbacks, Control};
use jni_sys::{jbyteArray, jclass, jint, jlong, jobject, JNIEnv};

const RTP_HEADER_LEN: usize = 12;
const NONCE_LEN: usize = 8;
const TAIL_LEN: usize = TAG_LEN + NONCE_LEN;
const DATAGRAM_BYTES: usize = 4096;
const BATCH: usize = 8;
/// android.os.Process.THREAD_PRIORITY_AUDIO: default-priority audio threads wait behind the head
/// unit's 360° camera (#737).
const THREAD_PRIORITY_AUDIO: jint = -16;

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_startAudio(
    env: *mut JNIEnv, _class: jclass, fd: jint, key: jbyteArray, stream_type: jint, callbacks: jobject, route_handle: jlong,
) -> jlong {
    let e = Env(env);
    let mut key_bytes = [0u8; 32];
    if fd < 0 || e.array_len(key) != 32 || !e.read_array(key, 0, &mut key_bytes) { return 0; }
    let Some(stream_key) = StreamKey::new(&key_bytes) else { return 0 };
    let Some(callbacks) = Callbacks::new(e, callbacks) else { return 0 };
    let Some(route) = route::retain(route_handle) else { return 0 };
    let capture_wire = route.capture_wire();
    let control = Control::new(fd);
    stream::spawn(e, "airplay-audio-rx", control, callbacks, move |env, control, callbacks| {
        env.set_thread_priority(THREAD_PRIORITY_AUDIO);
        receive(env, control, callbacks, stream_key, stream_type, route, capture_wire)
    })
}

struct Batch {
    packets: Vec<Option<Box<Packet>>>,
    iovecs: Vec<libc::iovec>,
    headers: Vec<libc::mmsghdr>,
}

impl Batch {
    fn new() -> Batch {
        Batch {
            packets: (0..BATCH).map(|_| None).collect(),
            iovecs: vec![libc::iovec { iov_base: std::ptr::null_mut(), iov_len: 0 }; BATCH],
            headers: (0..BATCH).map(|_| unsafe { std::mem::zeroed() }).collect(),
        }
    }

    /// Refills every slot whose packet was handed on, and points the message headers at them.
    fn arm(&mut self, pool: &std::sync::Arc<Pool>) {
        for i in 0..BATCH {
            let packet = self.packets[i].get_or_insert_with(|| pool.take(DATAGRAM_BYTES));
            packet.reserve(DATAGRAM_BYTES);
            self.iovecs[i] = libc::iovec { iov_base: packet.spare() as *mut _, iov_len: packet.capacity().min(DATAGRAM_BYTES) };
            let header = &mut self.headers[i];
            *header = unsafe { std::mem::zeroed() };
            header.msg_hdr.msg_iov = &mut self.iovecs[i];
            header.msg_hdr.msg_iovlen = 1;
        }
    }

    fn recycle(&mut self) {
        for slot in self.packets.iter_mut() {
            if let Some(packet) = slot.take() { packet.recycle(); }
        }
    }
}

enum Wait { Readable, Stopped, Failed(std::io::Error) }

fn wait(fd: i32, control: &Control) -> Wait {
    let mut fds = [
        libc::pollfd { fd, events: libc::POLLIN, revents: 0 },
        libc::pollfd { fd: control.wake_fd(), events: libc::POLLIN, revents: 0 },
    ];
    loop {
        if control.closed() { return Wait::Stopped; }
        let n = unsafe { libc::poll(fds.as_mut_ptr(), 2, -1) };
        if n < 0 {
            let error = std::io::Error::last_os_error();
            if error.kind() == std::io::ErrorKind::Interrupted { continue; }
            return Wait::Failed(error);
        }
        if control.closed() || fds[1].revents != 0 { return Wait::Stopped; }
        if fds[0].revents & (libc::POLLERR | libc::POLLNVAL) != 0 && fds[0].revents & libc::POLLIN == 0 {
            return Wait::Failed(std::io::Error::other("socket error"));
        }
        return Wait::Readable;
    }
}

fn receive(env: Env, control: &Control, callbacks: &Callbacks, key: StreamKey, stream_type: i32, route: Arc<AudioRoute>, capture_wire: bool) {
    let Some(fd) = control.socket() else { return };
    let pool = Pool::new();
    let mut stats = ReceiveStats::new(format!("audio type={stream_type}"), SESSION_REPORT_INTERVAL_NS, SystemClock);
    let extra = |pool: &Pool| format!(" impl=rust pooled={}", pool.outstanding());
    let mut batch = Batch::new();
    let (mut received, mut decrypted, mut failures) = (0u64, 0u64, 0u64);
    let failure: Option<String> = 'receive: loop {
        stats.reading();
        match wait(fd, control) {
            Wait::Readable => {}
            Wait::Stopped => break None,
            Wait::Failed(error) => break Some(format!("IOException: {error}")),
        }
        batch.arm(&pool);
        let count = unsafe {
            libc::recvmmsg(fd, batch.headers.as_mut_ptr(), BATCH as _, libc::MSG_DONTWAIT as _, std::ptr::null_mut())
        };
        if count < 0 {
            let error = std::io::Error::last_os_error();
            match error.kind() {
                std::io::ErrorKind::WouldBlock | std::io::ErrorKind::Interrupted => continue,
                // As the Kotlin receiver did, a failed receive is skipped unless the stream is closing.
                _ if control.closed() => break None,
                _ => continue,
            }
        }
        let arrival = clock(libc::CLOCK_MONOTONIC);
        for i in 0..count as usize {
            if i > 0 { stats.reading(); }
            let length = batch.headers[i].msg_len as usize;
            let mut packet = batch.packets[i].take().unwrap();
            unsafe { packet.set_len(length) };
            let wire = packet.bytes();
            let captured = capture_wire.then(|| wire.to_vec());
            let has_header = length >= RTP_HEADER_LEN;
            stats.received(
                length,
                has_header.then(|| u16::from_be_bytes([wire[2], wire[3]])),
                has_header.then(|| u32::from_be_bytes([wire[4], wire[5], wire[6], wire[7]])),
            );
            received += 1;
            if length < RTP_HEADER_LEN + TAIL_LEN {
                if received == 1 {
                    callbacks.log(env, 1, &format!("audio stream type={stream_type} short packet bytes={length}"));
                }
                if let Some(wire) = &captured { callbacks.audio_wire(env, wire, None, 0, 1); }
                packet.recycle();
            } else {
                let sample = u32::from_be_bytes([wire[4], wire[5], wire[6], wire[7]]);
                let mut nonce = [0u8; 12];
                nonce[4..].copy_from_slice(&wire[length - NONCE_LEN..]);
                let mut aad = [0u8; 8];
                aad.copy_from_slice(&wire[4..RTP_HEADER_LEN]);
                let started = stats.now();
                let opened = key.open(nonce, &aad, &mut packet.bytes_mut()[RTP_HEADER_LEN..length - NONCE_LEN]);
                match opened {
                    Some(plain) => {
                        stats.decrypted(stats.now() - started, length - RTP_HEADER_LEN - NONCE_LEN);
                        packet.truncate(RTP_HEADER_LEN + plain);
                        decrypted += 1;
                        if decrypted == 1 {
                            callbacks.log(env, 0, &format!(
                                "audio stream type={stream_type} packet=1 sample={} wireBytes={length} payloadBytes={plain} impl=rust",
                                sample as i32));
                        }
                        if let Some(wire) = &captured { callbacks.audio_wire(env, wire, Some(packet.bytes()), sample, 0); }
                        if route.closed() { packet.recycle(); }
                        else {
                            if decrypted == 1 { callbacks.audio_started(env, sample as i32); }
                            if let Some(hz) = route.observe(sample, clock(libc::CLOCK_MONOTONIC)) {
                                callbacks.audio_clock(env, hz);
                            }
                            let packet = AudioPacket { handle: packet.into_handle(), sample,
                                arrived_ns: clock(libc::CLOCK_MONOTONIC) };
                            match route.deliver(packet) {
                                Delivery::Callback(packet) => {
                                    let size = packet.size();
                                    callbacks.packet(env, packet.into_handle(), size, 0, sample as i64, arrival);
                                }
                                Delivery::Queued(queue) => {
                                    while let Some(event) = queue.event() { callbacks.audio_queue_event(env, event); }
                                }
                                Delivery::Closed => {}
                            }
                        }
                    }
                    None => {
                        failures += 1;
                        if failures == 1 {
                            callbacks.log(env, 1, &format!(
                                "audio stream type={stream_type} first decrypt failure bytes={length} (tag mismatch)"));
                        }
                        if let Some(wire) = &captured { callbacks.audio_wire(env, wire, None, sample, 2); }
                        packet.recycle();
                    }
                }
            }
            if let Some(line) = stats.processed(&|| extra(&pool)) { callbacks.report(env, &line); }
            if control.closed() { break 'receive None; }
        }
    };
    batch.recycle();
    if let Some(line) = stats.flush(true, &|| extra(&pool)) { callbacks.report(env, &line); }
    if !control.closed() { callbacks.ended(env, failure.as_deref()); }
}
