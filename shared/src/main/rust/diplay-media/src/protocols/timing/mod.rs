//! CarPlay timing packets and the complete NtpClock sample/offset owner.
//! Compared with LIVI 6ac88bab7351a39b0cffac20c5dcf92ca848319b timing.rs/timing_sync.rs;
//! ported from our Kotlin contract: signed NTP values, Long wrapping and Java rounding stay intact.
mod jni;

const WORDS: usize = 17;
const PACKET_BYTES: usize = 32;
const TWO32: f64 = 4_294_967_296.0;

struct TimingClock {
    offset_ns: i64,
    delays: [f64; 8],
    delay_index: usize,
    pick_left: u32,
    pick_rtt: f64,
    pick_offset: f64,
    pending: Option<i128>,
    synced: bool,
}

impl TimingClock {
    fn new(mono: i64, wall_millis: i64) -> Self {
        let seconds = wall_millis as f64 / 1000.0 + 2_208_988_800.0;
        let whole = seconds.floor() as i64;
        let fraction = ((seconds - whole as f64) * TWO32).floor() as i64;
        let ntp = (((whole as i128) << 32) | fraction as i128) as u64;
        let wall_ns = ((ntp as u128 * 1_000_000_000) >> 32) as i64;
        Self { offset_ns: wall_ns.wrapping_sub(mono), delays: [f64::INFINITY; 8], delay_index: 0,
            pick_left: 2, pick_rtt: f64::INFINITY, pick_offset: 0.0, pending: None, synced: false }
    }

    fn synced_ntp(&self, mono: i64) -> i128 { ntp_from_nanos(mono.wrapping_add(self.offset_ns)) }

    fn request(&mut self, mono: i64) -> [u8; PACKET_BYTES] {
        let mut packet = header(210);
        let t1 = self.synced_ntp(mono);
        self.pending = Some(t1);
        packet[24..32].copy_from_slice(&(t1 as u64).to_be_bytes());
        packet
    }

    fn reply(&self, request: &[u8; PACKET_BYTES], receive_ns: i64, send_ns: i64) -> [u8; PACKET_BYTES] {
        let mut packet = header(211);
        packet[8..16].copy_from_slice(&request[24..32]);
        packet[16..24].copy_from_slice(&(self.synced_ntp(receive_ns) as u64).to_be_bytes());
        packet[24..32].copy_from_slice(&(self.synced_ntp(send_ns) as u64).to_be_bytes());
        packet
    }

    fn sample(&mut self, response: &[u8; PACKET_BYTES], mono: i64) {
        let t4 = self.synced_ntp(mono);
        let read = |at| u64::from_be_bytes(response[at..at + 8].try_into().unwrap()) as i128;
        let (t1, t2, t3) = (read(8), read(16), read(24));
        if self.pending != Some(t1) { return; }
        self.pending = None;
        // Kotlin combines exact BigInteger differences before converting once to Double.
        let offset = ((t2 - t1) + (t3 - t4)) as f64 * 0.5 / TWO32;
        let rtt = ((t4 - t1) - (t3 - t2)) as f64 / TWO32;
        if rtt < 0.0 { return; }
        if rtt < self.pick_rtt { self.pick_rtt = rtt; self.pick_offset = offset; }
        self.pick_left -= 1;
        if self.pick_left > 0 { return; }
        let (rtt, offset) = (self.pick_rtt, self.pick_offset);
        self.pick_left = 2;
        self.pick_rtt = f64::INFINITY;
        let use_sample = self.delays.iter().all(|&delay| rtt <= delay);
        self.delays[self.delay_index] = rtt;
        self.delay_index = (self.delay_index + 1) % self.delays.len();
        if use_sample {
            let stepping = !self.synced || offset.abs() > 0.128;
            let applied = if stepping { offset } else { offset / 8.0 };
            self.offset_ns = self.offset_ns.wrapping_add(java_round(applied * 1e9));
            if stepping {
                self.delays.fill(f64::INFINITY);
                self.delay_index = 0;
                self.pending = None;
                self.synced = true;
            }
        }
    }

    fn token(&self) -> [i64; WORDS] {
        let mut words = [0; WORDS];
        words[0] = self.offset_ns;
        for (word, delay) in words[1..9].iter_mut().zip(self.delays) { *word = delay.to_bits() as i64; }
        words[9] = self.delay_index as i64;
        words[10] = self.pick_left as i64;
        words[11] = self.pick_rtt.to_bits() as i64;
        words[12] = self.pick_offset.to_bits() as i64;
        if let Some(pending) = self.pending {
            words[13] = (pending >> 64) as i64;
            words[14] = pending as i64;
            words[15] = 1;
        }
        words[16] = self.synced as i64;
        words
    }

    fn from_token(words: [i64; WORDS]) -> Option<Self> {
        if !(0..8).contains(&words[9]) || !(1..=2).contains(&words[10]) ||
            !(0..=1).contains(&words[15]) || !(0..=1).contains(&words[16]) { return None; }
        let mut delays = [0.0; 8];
        for (delay, word) in delays.iter_mut().zip(&words[1..9]) { *delay = f64::from_bits(*word as u64); }
        Some(Self { offset_ns: words[0], delays, delay_index: words[9] as usize, pick_left: words[10] as u32,
            pick_rtt: f64::from_bits(words[11] as u64), pick_offset: f64::from_bits(words[12] as u64),
            pending: (words[15] != 0).then_some(((words[13] as i128) << 64) | words[14] as u64 as i128),
            synced: words[16] != 0 })
    }
}

fn action(message: &[u8], from_peer: bool) -> i32 {
    if message.len() < PACKET_BYTES { return 0; }
    match message[1] { 210 => 1, 211 if from_peer => 2, _ => 0 }
}

fn header(kind: u8) -> [u8; PACKET_BYTES] {
    let mut packet = [0; PACKET_BYTES];
    packet[0..4].copy_from_slice(&[0x80, kind, 0, 7]);
    packet
}

fn ntp_from_nanos(ns: i64) -> i128 {
    ((ns.div_euclid(1_000_000_000) as i128) << 32) |
        ((ns.rem_euclid(1_000_000_000) as i128) << 32) / 1_000_000_000
}

fn java_round(value: f64) -> i64 {
    let floor = value.floor();
    (if value - floor >= 0.5 { floor + 1.0 } else { floor }) as i64
}

#[cfg(test)]
mod tests {
    use super::*;

    fn answer(request: &[u8; 32], skew: i128) -> [u8; 32] {
        let mut packet = header(211);
        let t1 = u64::from_be_bytes(request[24..32].try_into().unwrap());
        packet[8..16].copy_from_slice(&t1.to_be_bytes());
        let peer = (t1 as i128 + skew + (1i128 << 31)) as u64;
        packet[16..24].copy_from_slice(&peer.to_be_bytes());
        packet[24..32].copy_from_slice(&peer.to_be_bytes());
        packet
    }

    #[test] fn golden_packets_and_peer_policy() {
        let mut clock = TimingClock::new(0, 0);
        let request = clock.request(0);
        assert_eq!(&request[0..4], &[0x80, 210, 0, 7]);
        assert_eq!(&request[24..32], &0x83aa_7e80_0000_0000u64.to_be_bytes());
        let reply = clock.reply(&request, 0, 1_000_000_000);
        assert_eq!(&reply[0..4], &[0x80, 211, 0, 7]);
        assert_eq!(&reply[8..16], &request[24..32]);
        assert_eq!(action(&request, false), 1);
        assert_eq!(action(&reply, false), 0);
        assert_eq!(action(&reply, true), 2);
        assert_eq!(action(&reply[..31], true), 0);
    }

    #[test] fn matching_two_sample_step_then_slew_and_delay_rejection() {
        let mut clock = TimingClock::new(0, 0);
        for now in [0, 10_000_000_000] {
            let request = clock.request(now);
            clock.sample(&answer(&request, 5i128 << 32), now + 1_000_000_000);
        }
        assert!(clock.synced);
        assert_eq!(clock.offset_ns, 2_208_988_805_000_000_000);
        for now in [20_000_000_000, 30_000_000_000] {
            let request = clock.request(now);
            clock.sample(&answer(&request, 1i128 << 28), now + 1_000_000_000);
        }
        assert_eq!(clock.offset_ns, 2_208_988_805_007_812_500);
        let before = clock.offset_ns;
        for now in [40_000_000_000, 50_000_000_000] {
            let request = clock.request(now);
            clock.sample(&answer(&request, 20i128 << 32), now + 2_000_000_000);
        }
        assert_eq!(clock.offset_ns, before);
    }

    #[test] fn stale_negative_and_duplicate_samples_do_not_count() {
        let mut clock = TimingClock::new(0, 0);
        let old = clock.request(0);
        let current = clock.request(1_000_000_000);
        clock.sample(&answer(&old, 5i128 << 32), 2_000_000_000);
        assert_eq!(clock.pick_left, 2);
        let mut negative = answer(&current, 0);
        let t2 = u64::from_be_bytes(negative[16..24].try_into().unwrap());
        negative[24..32].copy_from_slice(&(t2 + (10u64 << 32)).to_be_bytes());
        clock.sample(&negative, 2_000_000_000);
        assert_eq!(clock.pick_left, 2);
        clock.sample(&answer(&current, 5i128 << 32), 2_000_000_000);
        assert_eq!(clock.pick_left, 2);
        assert!(clock.pending.is_none());
    }

    #[test] fn signed_time_wrapping_and_java_rounding_survive_the_value_token() {
        for wall in [i64::MIN, -1, 0, 1, 1_700_000_000_123, i64::MAX] {
            for mono in [i64::MIN, -1, 0, i64::MAX] {
                let mut clock = TimingClock::new(mono, wall);
                clock.request(mono);
                let restored = TimingClock::from_token(clock.token()).unwrap();
                assert_eq!(restored.token(), clock.token());
                assert_eq!(restored.synced_ntp(mono), clock.synced_ntp(mono));
            }
        }
        assert_eq!(ntp_from_nanos(-1), -5);
        assert_eq!(java_round(-0.5), 0);
        assert_eq!(java_round(0.49999999999999994), 0);
        assert_eq!(java_round(f64::INFINITY), i64::MAX);
        assert_eq!(java_round(f64::NAN), 0);
    }
}
