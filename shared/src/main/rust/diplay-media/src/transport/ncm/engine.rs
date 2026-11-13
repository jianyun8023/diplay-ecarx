//! Moves the wired link's bytes (Kotlin's `NativeNcmBridge`): bulk IN → NTB16 → Ethernet → IPv6 →
//! tun, and tun → Ethernet → NTB16 → bulk OUT, on two threads with synchronous usbdevfs transfers on
//! a duplicate of the claimed `UsbDeviceConnection` fd. Kotlin keeps the interface claim, the status
//! endpoint, the VPN and the lifecycle. Enabled by default, with an Advanced setting opt-out and
//! a Kotlin I/O fallback when the engine cannot start.

use crate::platform::jvm::Env;
use super::{ethernet_frame, fix_neighbor_advertisement, ipv6_payload, multicast_mac, ntb16_build, Drained, Reassembler, Traffic};
use crate::media::stats::clock;
use crate::platform::stream::{self, Callbacks, Control};
use jni_sys::{jbyteArray, jclass, jint, jlong, jobject, jstring, JNIEnv};
use std::os::raw::{c_uint, c_ulong, c_void};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex};

#[repr(C)]
struct BulkTransfer { ep: c_uint, len: c_uint, timeout: c_uint, data: *mut c_void }

/// `_IOWR('U', 2, struct usbdevfs_bulktransfer)`; the struct is 16 bytes on 32-bit and 24 on 64-bit.
const USBDEVFS_BULK: c_ulong =
    (3 << 30) | ((std::mem::size_of::<BulkTransfer>() as c_ulong) << 16) | (0x55 << 8) | 2;

fn bulk(fd: i32, endpoint: u32, data: *mut u8, len: usize, timeout_ms: u32) -> std::io::Result<usize> {
    let mut transfer = BulkTransfer { ep: endpoint, len: len as c_uint, timeout: timeout_ms, data: data as *mut c_void };
    let result = unsafe { libc::ioctl(fd, USBDEVFS_BULK as _, &mut transfer) };
    if result < 0 { Err(std::io::Error::last_os_error()) } else { Ok(result as usize) }
}

const READ_CHUNK_BYTES: usize = 32 * 1024;
const READ_TIMEOUT_MS: u32 = 1_000;
const WRITE_TIMEOUT_MS: u32 = 2_000;
/// Before the phone takes its first frame it NAKs bulk OUT; a frame that cannot go at once is dropped.
const NOT_READY_WRITE_TIMEOUT_MS: u32 = 20;
const TUN_READ_BYTES: usize = 4_096;
const NTB_PAD_BOUNDARY: usize = 512;

struct Shared {
    traffic: Mutex<Traffic>,
    peer_mac: Mutex<Option<[u8; 6]>>,
    /// CLOCK_MONOTONIC when bulk OUT first accepted a frame, 0 before.
    link_opened_ns: AtomicI64,
}

struct Engine { inbound: Arc<Control>, outbound: Arc<Control>, shared: Arc<Shared> }

unsafe fn engine<'a>(handle: jlong) -> &'a Engine { &*(handle as *const Engine) }

/// Starts both directions. `usb_fd` and `tun_fd` are duplicates the engine owns. 0 on bad arguments.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_ncmStart(
    env: *mut JNIEnv, _class: jclass, usb_fd: jint, in_endpoint: jint, out_endpoint: jint, packet_size: jint,
    tun_fd: jint, host_mac: jbyteArray, callbacks_in: jobject, callbacks_out: jobject,
) -> jlong {
    let e = Env(env);
    let mut mac = [0u8; 6];
    if usb_fd < 0 || tun_fd < 0 || e.array_len(host_mac) != 6 || !e.read_array(host_mac, 0, &mut mac) { return 0; }
    let (Some(cb_in), Some(cb_out)) = (Callbacks::new(e, callbacks_in), Callbacks::new(e, callbacks_out)) else { return 0 };
    let shared = Arc::new(Shared { traffic: Mutex::new(Traffic::default()), peer_mac: Mutex::new(None), link_opened_ns: AtomicI64::new(0) });
    // Each direction owns its own duplicates of both fds, so neither closes one the other still uses.
    let (usb_out, tun_out) = (libc::dup(usb_fd), libc::dup(tun_fd));
    if usb_out < 0 || tun_out < 0 {
        for fd in [usb_fd, tun_fd, usb_out, tun_out] { if fd >= 0 { libc::close(fd); } }
        return 0;
    }
    let (inbound_control, outbound_control) = (Control::new(usb_fd), Control::new(usb_out));
    let packet = packet_size.max(1) as usize;
    let (inbound, outbound) = (shared.clone(), shared.clone());
    let in_handle = stream::spawn("ncm-ipv6-in", inbound_control.clone(), cb_in, move |env, control, callbacks| {
        receive(env, control, callbacks, &inbound, in_endpoint as u32, packet, tun_fd);
        libc::close(tun_fd);
    });
    if in_handle == 0 { libc::close(tun_fd); libc::close(tun_out); return 0; }
    stream::release_handle(in_handle);
    let out_handle = stream::spawn("ncm-ipv6-out", outbound_control.clone(), cb_out, move |env, control, callbacks| {
        send(env, control, callbacks, &outbound, out_endpoint as u32, packet, tun_out, mac);
        libc::close(tun_out);
    });
    if out_handle == 0 { inbound_control.stop(); libc::close(tun_out); return 0; }
    stream::release_handle(out_handle);
    Box::into_raw(Box::new(Engine { inbound: inbound_control, outbound: outbound_control, shared })) as jlong
}

fn receive(env: Env, control: &Control, callbacks: &Callbacks, shared: &Shared, endpoint: u32, packet: usize, tun: i32) {
    let Some(fd) = control.socket() else { return };
    let mut reassembler = Reassembler::new(packet);
    let mut buffer = vec![0u8; READ_CHUNK_BYTES];
    let mut first = true;
    let failure: Option<String> = loop {
        if control.closed() { break None; }
        let count = match bulk(fd, endpoint, buffer.as_mut_ptr(), buffer.len(), READ_TIMEOUT_MS) {
            Ok(count) => count,
            Err(error) if error.raw_os_error() == Some(libc::ETIMEDOUT) || error.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(error) => break Some(format!("NCM bulk-in failed: {error}")),
        };
        let now = clock(libc::CLOCK_MONOTONIC);
        let mut reports = Vec::new();
        let frames = match reassembler.push(&buffer[..count], now, &mut reports) {
            Drained::Frames(frames) => frames,
            Drained::Failed(reason) => break Some(reason),
        };
        for line in reports { callbacks.report(env, &line); }
        for frame in frames {
            shared.traffic.lock().unwrap().received(&frame, now);
            let Some((source, ipv6)) = ipv6_payload(&frame) else { continue };
            *shared.peer_mac.lock().unwrap() = Some(source);
            if first {
                first = false;
                callbacks.log(env, 0, &format!("ncm first inbound ipv6 bytes={} impl=rust", ipv6.len()));
            }
            if unsafe { libc::write(tun, ipv6.as_ptr() as *const c_void, ipv6.len()) } < 0 && !control.closed() {
                let error = std::io::Error::last_os_error();
                if error.kind() != std::io::ErrorKind::Interrupted { break; }
            }
        }
    };
    if !control.closed() { callbacks.ended(env, failure.as_deref()); }
}

#[allow(clippy::too_many_arguments)]
fn send(env: Env, control: &Control, callbacks: &Callbacks, shared: &Shared, endpoint: u32, _packet: usize, tun: i32, host_mac: [u8; 6]) {
    let Some(fd) = control.socket() else { return };
    let mut buffer = vec![0u8; TUN_READ_BYTES];
    let mut sequence: u16 = 0;
    let (mut not_ready, mut waiting_logged, mut first) = (0u32, false, true);
    let failure: Option<String> = loop {
        let mut fds = [
            libc::pollfd { fd: tun, events: libc::POLLIN, revents: 0 },
            libc::pollfd { fd: control.wake_fd(), events: libc::POLLIN, revents: 0 },
        ];
        if unsafe { libc::poll(fds.as_mut_ptr(), 2, READ_TIMEOUT_MS as i32) } < 0 { continue; }
        if control.closed() || fds[1].revents != 0 { break None; }
        if fds[0].revents == 0 { continue; }
        let length = unsafe { libc::read(tun, buffer.as_mut_ptr() as *mut c_void, buffer.len()) };
        if length < 0 {
            let error = std::io::Error::last_os_error();
            if matches!(error.kind(), std::io::ErrorKind::Interrupted | std::io::ErrorKind::WouldBlock) { continue; }
            break Some(format!("NCM IPv6 tunnel read failed: {error}"));
        }
        if length == 0 { std::thread::sleep(std::time::Duration::from_millis(1)); continue; }
        let tun_packet = &buffer[..length as usize];
        let fixed = fix_neighbor_advertisement(tun_packet, &host_mac);
        let ipv6 = fixed.as_deref().unwrap_or(tun_packet);
        let Some(destination) = multicast_mac(ipv6).or(*shared.peer_mac.lock().unwrap()) else {
            if !waiting_logged {
                waiting_logged = true;
                callbacks.log(env, 0, &format!("ncm deferred outbound unicast bytes={length} until peer MAC is learned"));
            }
            continue;
        };
        // As Ntb16Codec.build: the pad byte follows a 512-byte boundary whatever the endpoint's packet size.
        let Some(mut block) = ntb16_build(&ethernet_frame(&host_mac, &destination, ipv6), sequence, NTB_PAD_BOUNDARY) else { continue };
        sequence = sequence.wrapping_add(1);
        let opened = shared.link_opened_ns.load(Ordering::Relaxed) != 0;
        let timeout = if opened { WRITE_TIMEOUT_MS } else { NOT_READY_WRITE_TIMEOUT_MS };
        match bulk(fd, endpoint, block.as_mut_ptr(), block.len(), timeout) {
            Ok(sent) if sent == block.len() => {}
            Ok(0) | Err(_) if !opened => { not_ready += 1; continue; }
            Ok(sent) => break Some(format!("NCM write transferred {sent} of {} bytes", block.len())),
            Err(error) => break Some(format!("NCM bulk-out failed: {error}")),
        }
        if !opened {
            shared.link_opened_ns.store(clock(libc::CLOCK_MONOTONIC), Ordering::Relaxed);
            callbacks.report(env, &format!("NCM bulk-out ready after {not_ready} dropped frames"));
        }
        if first {
            first = false;
            callbacks.log(env, 0, &format!("ncm first outbound ipv6 bytes={length} impl=rust"));
        }
        shared.traffic.lock().unwrap().sent();
    };
    if !control.closed() { callbacks.ended(env, failure.as_deref()); }
}

/// The traffic summary for the report (`NcmUsbBridge.trafficSummary`); `airplay_port` < 0 for none.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_ncmTraffic(
    env: *mut JNIEnv, _class: jclass, handle: jlong, airplay_port: jint,
) -> jstring {
    let port = (airplay_port >= 0).then_some(airplay_port as u16);
    Env(env).string(&engine(handle).shared.traffic.lock().unwrap().summary(port))
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_ncmAttemptSince(
    _env: *mut JNIEnv, _class: jclass, handle: jlong, port: jint, since_nanos: jlong,
) -> jni_sys::jboolean {
    engine(handle).shared.traffic.lock().unwrap().attempt_since(port as u16, since_nanos) as jni_sys::jboolean
}

/// CLOCK_MONOTONIC (System.nanoTime) of the first accepted bulk-OUT frame, or 0.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_ncmLinkOpened(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) -> jlong {
    engine(handle).shared.link_opened_ns.load(Ordering::Relaxed)
}

/// Stops both threads (each ends within its read timeout) and frees the handle.
#[no_mangle]
pub unsafe extern "system" fn Java_com_shihab_diplay_airplay_NativeMedia_ncmStop(
    _env: *mut JNIEnv, _class: jclass, handle: jlong,
) {
    if handle == 0 { return; }
    let engine = Box::from_raw(handle as *mut Engine);
    // Each thread closes its own fds as it ends: the reader within its bulk timeout, the writer at once.
    engine.inbound.stop();
    engine.outbound.stop();
}
