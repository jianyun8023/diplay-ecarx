//! The wired data path between the iPhone's NCM link and Android's VPN tun (Kotlin's `Ipv6NcmBridge`
//! over `NcmUsbBridge`'s bulk transfers): NTB16 framing, the Ethernet II header, the neighbor
//! advertisement fix-up and the traffic counters, ported one to one from `Ntb16Codec`,
//! `EthernetIpv6Codec`, `NcmTrafficCounter` and the bridge's reassembly and resync. The engine that
//! moves the bytes ([crate::ncm_engine]) is off by default until a wired test confirms it.

use std::collections::{HashMap, VecDeque};

pub const NTH16_SIG: u32 = 0x484d434e;
pub const NDP16_SIG: u32 = 0x304d434e;
const NTH_LENGTH: usize = 12;
const NDP_LENGTH: usize = 16;
const DATAGRAM_INDEX: usize = 28;
const MAX_ENTRIES_PER_BLOCK: usize = 256;
const MAX_DATAGRAM_BYTES: usize = 0xffff - DATAGRAM_INDEX;
pub const MAC_BYTES: usize = 6;
const ETHERNET_HEADER: usize = 14;
const ETHERTYPE_IPV6: u16 = 0x86dd;
const ETHERTYPE_IPV4: u16 = 0x0800;
const IPV6_HEADER: usize = 40;
const ICMPV6: u8 = 58;
const NEIGHBOR_ADVERTISEMENT: u8 = 136;
const NA_BYTES: usize = 24;
const TARGET_LINK_LAYER_OPTION: u8 = 2;

fn u16_le(b: &[u8], at: usize) -> usize { b[at] as usize | (b[at + 1] as usize) << 8 }
fn u32_le(b: &[u8], at: usize) -> u32 { u32::from_le_bytes(b[at..at + 4].try_into().unwrap()) }
fn u16_be(b: &[u8], at: usize) -> usize { (b[at] as usize) << 8 | b[at + 1] as usize }

/// One Ethernet frame in one NTB16 block, with the pad byte when it ends on a `packet_size` boundary.
pub fn ntb16_build(frame: &[u8], sequence: u16, packet_size: usize) -> Option<Vec<u8>> {
    if frame.is_empty() || frame.len() > MAX_DATAGRAM_BYTES { return None; }
    let block_length = DATAGRAM_INDEX + frame.len();
    let mut block = Vec::with_capacity(block_length + 1);
    block.extend_from_slice(&NTH16_SIG.to_le_bytes());
    block.extend_from_slice(&(NTH_LENGTH as u16).to_le_bytes());
    block.extend_from_slice(&sequence.to_le_bytes());
    block.extend_from_slice(&(block_length as u16).to_le_bytes());
    block.extend_from_slice(&(NTH_LENGTH as u16).to_le_bytes());
    block.extend_from_slice(&NDP16_SIG.to_le_bytes());
    block.extend_from_slice(&(NDP_LENGTH as u16).to_le_bytes());
    block.extend_from_slice(&0u16.to_le_bytes());
    block.extend_from_slice(&(DATAGRAM_INDEX as u16).to_le_bytes());
    block.extend_from_slice(&(frame.len() as u16).to_le_bytes());
    block.extend_from_slice(&[0, 0, 0, 0]);
    block.extend_from_slice(frame);
    if block.len() % packet_size == 0 { block.push(0); }
    Some(block)
}

/// The frames of one NTB16 block (`Ntb16Codec.parse`): garbage yields none, bad entries are skipped,
/// and a block with a repeated NDP, too many entries or overlapping entries is dropped whole.
pub fn ntb16_parse(block: &[u8]) -> Vec<&[u8]> {
    let end = block.len();
    if end < NTH_LENGTH || u32_le(block, 0) != NTH16_SIG { return Vec::new(); }
    let mut frames = Vec::with_capacity(1);
    let mut ndp = u16_le(block, 10);
    let mut visited = Vec::new();
    let (mut entries, mut total) = (0usize, 0usize);
    while ndp != 0 {
        if ndp + 12 > end { break; }
        if visited.contains(&ndp) { return Vec::new(); }
        visited.push(ndp);
        if u32_le(block, ndp) & 0x00ff_ffff != NDP16_SIG & 0x00ff_ffff { break; }
        let ndp_length = u16_le(block, ndp + 4);
        let next = u16_le(block, ndp + 6);
        let ndp_end = (ndp + ndp_length).min(end);
        let mut entry = ndp + 8;
        while entry + 4 <= ndp_end {
            let (index, length) = (u16_le(block, entry), u16_le(block, entry + 2));
            if index == 0 || length == 0 { break; }
            entries += 1;
            if entries > MAX_ENTRIES_PER_BLOCK { return Vec::new(); }
            if index <= end && index + length <= end {
                total += length;
                if total > end { return Vec::new(); }
                frames.push(&block[index..index + length]);
            }
            entry += 4;
        }
        ndp = next;
    }
    frames
}

/// Reassembles NTB16 blocks from bulk-IN completions that may split or merge them (the bridge's
/// `drainFrames`): honours the optional short-packet pad and resynchronizes at the next plausible
/// header after damage, failing only when damage keeps recurring.
pub struct Reassembler {
    buffered: Vec<u8>,
    packet_size: usize,
    optional_pad: bool,
    resync_times: VecDeque<i64>,
    resync_reason: Option<String>,
    pub resync_count: u32,
    resync_skipped: usize,
}

const MAX_RESYNCS_PER_WINDOW: usize = 16;
const RESYNC_WINDOW_NS: i64 = 10_000_000_000;
const LOGGED_RESYNCS: u32 = 5;

pub enum Drained { Frames(Vec<Vec<u8>>), Failed(String) }

impl Reassembler {
    pub fn new(packet_size: usize) -> Reassembler {
        Reassembler {
            buffered: Vec::new(), packet_size: packet_size.max(1), optional_pad: false, resync_times: VecDeque::new(),
            resync_reason: None, resync_count: 0, resync_skipped: 0,
        }
    }

    /// Adds one completion and returns the whole frames it finished, and a report line on resync.
    pub fn push(&mut self, chunk: &[u8], now_ns: i64, reports: &mut Vec<String>) -> Drained {
        self.buffered.extend_from_slice(chunk);
        let mut frames = Vec::new();
        loop {
            if self.optional_pad && !self.buffered.is_empty() {
                self.optional_pad = false;
                match self.buffered[0] {
                    0 => { self.buffered.drain(..1); }
                    b if b == (NTH16_SIG & 0xff) as u8 => {}
                    _ => match self.resynchronize("Invalid NTB16 short-packet pad", now_ns) {
                        Err(failure) => return Drained::Failed(failure),
                        Ok(false) => break,
                        Ok(true) => {}
                    },
                }
            }
            if self.buffered.len() < NTH_LENGTH { break; }
            let block_length = u16_le(&self.buffered, 8);
            if u32_le(&self.buffered, 0) != NTH16_SIG || block_length < 28 {
                let reason = if u32_le(&self.buffered, 0) != NTH16_SIG {
                    "NCM read buffer does not begin with an NTB16 header".to_string()
                } else {
                    format!("Invalid NTB16 block length {block_length}")
                };
                match self.resynchronize(&reason, now_ns) {
                    Err(failure) => return Drained::Failed(failure),
                    Ok(false) => break,
                    Ok(true) => continue,
                }
            }
            if self.buffered.len() < block_length { break; }
            frames.extend(ntb16_parse(&self.buffered[..block_length]).into_iter().map(<[u8]>::to_vec));
            self.buffered.drain(..block_length);
            self.optional_pad = block_length % self.packet_size == 0;
            if let Some(reason) = self.resync_reason.take() {
                if self.resync_count <= LOGGED_RESYNCS || self.resync_count % 50 == 0 {
                    reports.push(format!("NCM resynchronized after {reason}; skipped={} resyncs={}", self.resync_skipped, self.resync_count));
                }
            }
        }
        Drained::Frames(frames)
    }

    fn plausible_header_at(&self, at: usize) -> bool {
        let b = &self.buffered;
        if u32_le(b, at) != NTH16_SIG { return false; }
        let (header, block, ndp) = (u16_le(b, at + 4), u16_le(b, at + 8), u16_le(b, at + 10));
        header == NTH_LENGTH && block >= 28 && ndp >= NTH_LENGTH && ndp % 4 == 0 && ndp + 8 <= block
    }

    /// Ok(true) when a header is next, Ok(false) while none is buffered yet, Err when damage recurs.
    fn resynchronize(&mut self, reason: &str, now_ns: i64) -> Result<bool, String> {
        if self.resync_reason.is_none() {
            while self.resync_times.front().is_some_and(|t| now_ns - t > RESYNC_WINDOW_NS) { self.resync_times.pop_front(); }
            if self.resync_times.len() >= MAX_RESYNCS_PER_WINDOW { return Err(format!("{reason} (repeated)")); }
            self.resync_times.push_back(now_ns);
            self.resync_count += 1;
            self.resync_reason = Some(reason.to_string());
            self.resync_skipped = 0;
        }
        let limit = self.buffered.len().saturating_sub(NTH_LENGTH);
        let next = (1..=limit).find(|&at| self.plausible_header_at(at));
        let skipped = next.unwrap_or_else(|| self.buffered.len().saturating_sub(NTH_LENGTH - 1));
        self.buffered.drain(..skipped);
        self.resync_skipped += skipped;
        Ok(next.is_some())
    }
}

/// The source MAC and the IPv6 payload of an untagged IPv6 Ethernet frame.
pub fn ipv6_payload(frame: &[u8]) -> Option<([u8; 6], &[u8])> {
    if frame.len() <= ETHERNET_HEADER || u16_be(frame, 12) != ETHERTYPE_IPV6 as usize { return None; }
    Some((frame[6..12].try_into().unwrap(), &frame[ETHERNET_HEADER..]))
}

/// Ethernet's 33:33 group address for an IPv6 multicast destination.
pub fn multicast_mac(ipv6: &[u8]) -> Option<[u8; 6]> {
    if ipv6.len() < IPV6_HEADER || ipv6[0] >> 4 != 6 || ipv6[24] != 0xff { return None; }
    Some([0x33, 0x33, ipv6[36], ipv6[37], ipv6[38], ipv6[39]])
}

pub fn ethernet_frame(source: &[u8; 6], destination: &[u8; 6], ipv6: &[u8]) -> Vec<u8> {
    let mut frame = Vec::with_capacity(ETHERNET_HEADER + ipv6.len());
    frame.extend_from_slice(destination);
    frame.extend_from_slice(source);
    frame.extend_from_slice(&ETHERTYPE_IPV6.to_be_bytes());
    frame.extend_from_slice(ipv6);
    frame
}

/// A tun has no link-layer address, so Android's neighbor advertisements lack the target link-layer
/// option the Ethernet peer needs: set it to `host_mac` (adding it when absent) and repair the IPv6
/// payload length and the ICMPv6 checksum. Other packets pass through unchanged.
pub fn fix_neighbor_advertisement(ipv6: &[u8], host_mac: &[u8; 6]) -> Option<Vec<u8>> {
    if ipv6.len() < IPV6_HEADER + NA_BYTES || ipv6[0] >> 4 != 6 { return None; }
    if ipv6[6] != ICMPV6 || ipv6[IPV6_HEADER] != NEIGHBOR_ADVERTISEMENT { return None; }
    let payload = u16_be(ipv6, 4);
    if payload < NA_BYTES || IPV6_HEADER + payload > ipv6.len() { return None; }
    let end = IPV6_HEADER + payload;
    let mut option = IPV6_HEADER + NA_BYTES;
    while option + 2 <= end {
        let bytes = ipv6[option + 1] as usize * 8;
        if bytes == 0 || option + bytes > end { break; }
        if ipv6[option] == TARGET_LINK_LAYER_OPTION && bytes >= 8 {
            let mut result = ipv6.to_vec();
            result[option + 2..option + 8].copy_from_slice(host_mac);
            icmpv6_checksum(&mut result);
            return Some(result);
        }
        option += bytes;
    }
    let mut result = ipv6[..end].to_vec();
    result.resize(end + 8, 0);
    result[4..6].copy_from_slice(&((payload + 8) as u16).to_be_bytes());
    result[end] = TARGET_LINK_LAYER_OPTION;
    result[end + 1] = 1;
    result[end + 2..end + 8].copy_from_slice(host_mac);
    icmpv6_checksum(&mut result);
    Some(result)
}

fn icmpv6_checksum(ipv6: &mut [u8]) {
    let payload = u16_be(ipv6, 4);
    ipv6[IPV6_HEADER + 2] = 0;
    ipv6[IPV6_HEADER + 3] = 0;
    let words = |b: &[u8]| -> u64 {
        let mut sum = 0u64;
        let mut chunks = b.chunks_exact(2);
        for w in &mut chunks { sum += u16::from_be_bytes([w[0], w[1]]) as u64; }
        if let [last] = chunks.remainder() { sum += (*last as u64) << 8; }
        sum
    };
    let mut sum = words(&ipv6[8..40]) + (payload as u64 >> 16) + (payload as u64 & 0xffff) + ICMPV6 as u64 +
        words(&ipv6[IPV6_HEADER..IPV6_HEADER + payload]);
    while sum >> 16 != 0 { sum = (sum & 0xffff) + (sum >> 16); }
    let checksum = !(sum as u16);
    ipv6[IPV6_HEADER + 2..IPV6_HEADER + 4].copy_from_slice(&checksum.to_be_bytes());
}

/// `NcmTrafficCounter`: frames each way, and connection attempts by destination port; headers only.
#[derive(Default)]
pub struct Traffic {
    from_iphone: u64,
    ipv6: u64,
    ipv4: u64,
    to_iphone: u64,
    syns_by_port: Vec<(u16, u32)>,
    last_syn_ns: HashMap<u16, i64>,
}

const MAX_PORTS: usize = 8;

impl Traffic {
    pub fn received(&mut self, frame: &[u8], now_ns: i64) {
        self.from_iphone += 1;
        if frame.len() < 14 { return; }
        let tcp = match u16_be(frame, 12) as u16 {
            ETHERTYPE_IPV6 => {
                self.ipv6 += 1;
                if frame.get(ETHERNET_HEADER + 6) == Some(&6) { ETHERNET_HEADER + IPV6_HEADER } else { return }
            }
            ETHERTYPE_IPV4 => {
                self.ipv4 += 1;
                let Some(&first) = frame.get(ETHERNET_HEADER) else { return };
                if frame.get(ETHERNET_HEADER + 9) == Some(&6) { ETHERNET_HEADER + (first as usize & 0x0f) * 4 } else { return }
            }
            _ => return,
        };
        let Some(&flags) = frame.get(tcp + 13) else { return };
        if flags & 0x02 == 0 || flags & 0x10 != 0 || frame.len() < tcp + 4 { return; }
        let port = u16_be(frame, tcp + 2) as u16;
        if let Some(entry) = self.syns_by_port.iter_mut().find(|(p, _)| *p == port) {
            entry.1 += 1;
        } else if self.syns_by_port.len() < MAX_PORTS {
            self.syns_by_port.push((port, 1));
        } else {
            return;
        }
        self.last_syn_ns.insert(port, now_ns);
    }

    pub fn sent(&mut self) { self.to_iphone += 1; }

    pub fn attempt_since(&self, port: u16, since_ns: i64) -> bool {
        self.last_syn_ns.get(&port).is_some_and(|t| t - since_ns >= 0)
    }

    pub fn summary(&self, airplay_port: Option<u16>) -> String {
        let to_airplay = airplay_port.and_then(|p| self.syns_by_port.iter().find(|(q, _)| *q == p)).map(|e| e.1).unwrap_or(0);
        let others: Vec<String> = self.syns_by_port.iter().filter(|(p, _)| Some(*p) != airplay_port)
            .map(|(p, n)| format!("{p}:{n}")).collect();
        format!("fromIphone={} ipv6={} ipv4={} tcpConnectToAirPlay={to_airplay} tcpConnectOther={} toIphone={}",
            self.from_iphone, self.ipv6, self.ipv4, if others.is_empty() { "none".into() } else { others.join(",") }, self.to_iphone)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hex(b: &[u8]) -> String { b.iter().map(|x| format!("{x:02x}")).collect() }

    /// The same block as Kotlin's `NcmNativeCodecGoldenTest`.
    #[test]
    fn a_block_matches_the_kotlin_codec_and_parses_back() {
        let frame: Vec<u8> = (0..20u8).collect();
        let block = ntb16_build(&frame, 0x1234, 512).unwrap();
        assert_eq!(hex(&block), NTB_GOLDEN);
        assert_eq!(ntb16_parse(&block), vec![&frame[..]]);
        let padded = ntb16_build(&vec![1u8; 512 - 28], 1, 512).unwrap();
        assert_eq!(padded.len(), 513);
    }

    const NTB_GOLDEN: &str = "4e434d480c00341230000c004e434d30100000001c00140000000000000102030405060708090a0b0c0d0e0f10111213";

    /// The same advertisement as Kotlin's `NcmNativeCodecGoldenTest`.
    #[test]
    fn a_neighbor_advertisement_gains_the_target_option_and_a_valid_checksum() {
        let fixed = fix_neighbor_advertisement(&neighbor_advertisement(), &[2, 0, 0, 0, 0, 1]).unwrap();
        assert_eq!(fixed.len(), 40 + 24 + 8);
        assert_eq!(&fixed[64..72], &[2, 1, 2, 0, 0, 0, 0, 1]);
        assert_eq!(hex(&fixed[42..44]), NA_CHECKSUM_GOLDEN);
        assert!(fix_neighbor_advertisement(&[0x60; 30], &[0; 6]).is_none());
    }

    const NA_CHECKSUM_GOLDEN: &str = "579b";

    pub(crate) fn neighbor_advertisement() -> Vec<u8> {
        let mut p = vec![0u8; 64];
        p[0] = 0x60;
        p[4..6].copy_from_slice(&24u16.to_be_bytes());
        p[6] = 58;
        p[7] = 255;
        p[8] = 0xfe; p[9] = 0x80; p[23] = 1;
        p[24] = 0xff; p[25] = 0x02; p[39] = 1;
        p[40] = 136;
        p[44] = 0x20;
        p[48] = 0xfe; p[49] = 0x80; p[63] = 1;
        p
    }

    #[test]
    fn split_and_merged_completions_reassemble_and_damage_resyncs() {
        let a = ntb16_build(&[0xaa; 40], 1, 512).unwrap();
        let b = ntb16_build(&[0xbb; 60], 2, 512).unwrap();
        let mut stream = a.clone();
        stream.extend_from_slice(&b);
        let mut r = Reassembler::new(512);
        let mut reports = Vec::new();
        let mut frames = Vec::new();
        for chunk in stream.chunks(17) {
            if let Drained::Frames(f) = r.push(chunk, 0, &mut reports) { frames.extend(f); }
        }
        assert_eq!(frames, vec![vec![0xaa; 40], vec![0xbb; 60]]);
        let mut damaged = vec![9u8; 5];
        damaged.extend_from_slice(&a);
        let Drained::Frames(f) = r.push(&damaged, 1, &mut reports) else { panic!() };
        assert_eq!(f, vec![vec![0xaa; 40]]);
        assert_eq!(reports.len(), 1);
    }

    #[test]
    fn a_syn_to_a_port_is_counted_and_timed() {
        let mut frame = vec![0u8; 14 + 40 + 20];
        frame[12..14].copy_from_slice(&0x86ddu16.to_be_bytes());
        frame[14 + 6] = 6;
        frame[54 + 2..54 + 4].copy_from_slice(&7000u16.to_be_bytes());
        frame[54 + 13] = 0x02;
        let mut traffic = Traffic::default();
        traffic.received(&frame, 50);
        traffic.sent();
        assert!(traffic.attempt_since(7000, 50) && !traffic.attempt_since(7000, 51));
        assert_eq!(traffic.summary(Some(7000)), "fromIphone=1 ipv6=1 ipv4=0 tcpConnectToAirPlay=1 tcpConnectOther=none toIphone=1");
    }
}
