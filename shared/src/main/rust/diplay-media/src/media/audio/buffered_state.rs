//! Buffered music owns both queued and not-yet-audible packets. Android serializes output
//! callbacks; no core mutex is held while calling the platform (callbacks can pause/close).

use crate::media::audio::queue::AudioPacket;
use std::collections::VecDeque;

pub const BUFFER_BYTES: usize = 8 * 1024 * 1024;
pub const SAMPLES_PER_FRAME: i64 = 1024;
pub const START_LATENCY_MS: i64 = 400;

pub struct BufferedAudio {
    pub sample_rate: i64,
    pub queue: VecDeque<AudioPacket>,
    fed: VecDeque<AudioPacket>,
    pub bytes: usize,
    pub closed: bool,
    pub rate: i32,
    flush_until: Option<u32>,
    pub anchor_rtp: Option<u32>,
    pub anchor_ntp: Option<i128>,
    feed_start: Option<u32>,
    feed_start_ns: i64,
    fed_samples: i64,
    pub generation: i64,
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::media::packet::Pool;
    use std::sync::Arc;

    fn frame(pool: &Arc<Pool>, sample: u32, bytes: usize) -> AudioPacket {
        let mut packet = pool.take(bytes);
        unsafe { std::ptr::write_bytes(packet.spare(), 0, bytes); packet.set_len(bytes); }
        AudioPacket { handle: packet.into_handle(), sample, arrived_ns: 0 }
    }

    #[test]
    fn pause_resume_keeps_the_entire_inaudible_renderer_preload() {
        let pool = Pool::new();
        let mut stream = BufferedAudio::new(48_000);
        for i in 0..100 { stream.push(frame(&pool, 1000 + i * 1024, 13)); }
        assert!(!stream.set_rate(Some(1000), 1, 0, 0));
        let mut fed = 0;
        while let Some((packet, _, _, generation)) = stream.next(0) {
            assert!(stream.commit(generation));
            unsafe { crate::media::packet::release(packet); }
            fed += 1;
        }
        assert_eq!(fed, 47);
        assert!(stream.set_rate(None, 0, 0, 0));
        assert_eq!(stream.anchor_rtp, Some(1000));
        assert_eq!(stream.queue.len(), 100);
        assert_eq!(stream.bytes, 1300);
        assert!(stream.next(0).is_none());
        stream.set_rate(None, 1, 0, 0);
        let (packet, sample, first, _) = stream.next(0).unwrap();
        assert_eq!((sample, first), (1000, true));
        unsafe { crate::media::packet::release(packet); }
        stream.close();
        assert_eq!(pool.outstanding(), 0);
    }

    #[test]
    fn flush_and_reentrant_close_invalidate_delivery_but_not_the_inflight_reference() {
        let pool = Pool::new();
        let mut stream = BufferedAudio::new(48_000);
        for i in 0..3 { stream.push(frame(&pool, i * 1024, 13)); }
        stream.set_rate(Some(0), 1, 0, 0);
        let (packet, _, _, generation) = stream.next(0).unwrap();
        assert!(stream.flush(Some(2048)));
        assert!(!stream.commit(generation));
        assert!(!stream.accepts(1024));
        assert!(stream.accepts(2048));
        assert_eq!(stream.bytes, 13);
        stream.close();
        assert!(!stream.commit(generation));
        assert_eq!(pool.outstanding(), 1);
        unsafe { crate::media::packet::release(packet); }
        assert_eq!(pool.outstanding(), 0);
    }

    #[test]
    fn byte_and_frame_limits_include_fed_audio_and_pacing_begins_at_fifteen_seconds() {
        let pool = Pool::new();
        let mut stream = BufferedAudio::new(48_000);
        for i in 0..5625 {
            assert!(!stream.full(13));
            assert_eq!(stream.push(frame(&pool, i * 1024, 13)), if i < 702 { 0 } else { 5 });
        }
        assert!(stream.full(13));
        stream.set_rate(Some(0), 1, 0, 0);
        let (packet, _, _, _) = stream.next(0).unwrap();
        assert!(stream.full(13));
        unsafe { crate::media::packet::release(packet); }
        stream.close();
        let mut stream = BufferedAudio::new(48_000);
        while !stream.full(60_000) { stream.push(frame(&pool, 0, 60_000)); }
        assert!(stream.bytes <= BUFFER_BYTES);
        assert!(stream.bytes > BUFFER_BYTES - 60_000);
        stream.close();
        assert_eq!(pool.outstanding(), 0);
    }

    #[test]
    fn audible_retirement_rtp_wrap_and_ntp_fraction_carry_preserve_the_anchor() {
        let pool = Pool::new();
        let mut stream = BufferedAudio::new(48_000);
        stream.push(frame(&pool, 0xffff_fc00, 13));
        let ntp = (3_999_999_999i128 << 32) + 0xf000_0000;
        stream.set_rate(Some(0xffff_fc00), 1, ntp, 0);
        assert_eq!(stream.anchor_ntp, Some(ntp + ((400i128 << 32) / 1000)));
        let (packet, _, _, generation) = stream.next(0).unwrap();
        stream.commit(generation);
        unsafe { crate::media::packet::release(packet); }
        assert_eq!(stream.position(422_000_000), Some(32));
        assert!(stream.next(422_000_000).is_none());
        assert_eq!(stream.bytes, 0);
        assert_eq!(pool.outstanding(), 0);
        stream.set_rate(None, 0, ntp, 422_000_000);
        assert_eq!(stream.anchor_rtp, Some(32));
        assert_eq!(stream.anchor_ntp, Some(ntp));
    }
}

pub fn before(a: u32, b: u32) -> bool { a.wrapping_sub(b) >= 0x8000_0000 }

impl BufferedAudio {
    pub fn new(sample_rate: i64) -> Self {
        Self { sample_rate, queue: Default::default(), fed: Default::default(), bytes: 0,
            closed: false, rate: 0, flush_until: None, anchor_rtp: None, anchor_ntp: None,
            feed_start: None, feed_start_ns: 0, fed_samples: 0, generation: 0 }
    }

    pub fn accepts(&self, sample: u32) -> bool {
        !self.closed && !self.flush_until.is_some_and(|until| before(sample, until))
    }

    pub fn full(&self, bytes: usize) -> bool {
        self.bytes + bytes > BUFFER_BYTES || self.queue.len() + self.fed.len() >= (self.sample_rate * 120 / SAMPLES_PER_FRAME) as usize
    }

    /// Delay after an accepted frame; the first fifteen seconds still fill at TCP speed.
    pub fn push(&mut self, packet: AudioPacket) -> i64 {
        self.bytes += packet.size();
        self.queue.push_back(packet);
        if self.queue.len() + self.fed.len() >= (self.sample_rate * 15 / SAMPLES_PER_FRAME) as usize {
            (SAMPLES_PER_FRAME * 1000 / (self.sample_rate * 4)).max(1)
        } else { 0 }
    }

    pub fn position(&self, now_ns: i64) -> Option<u32> {
        if let Some(start) = self.feed_start.filter(|_| self.rate > 0) {
            let played_ms = ((now_ns - self.feed_start_ns) / 1_000_000 - START_LATENCY_MS).max(0);
            Some(start.wrapping_add((played_ms * self.sample_rate / 1000) as u32))
        } else { self.anchor_rtp }
    }

    pub fn next(&mut self, now_ns: i64) -> Option<(i64, u32, bool, i64)> {
        if let Some(position) = self.position(now_ns) {
            while self.fed.front().is_some_and(|packet| {
                let end = packet.sample.wrapping_add(SAMPLES_PER_FRAME as u32);
                end == position || before(end, position)
            }) {
                self.bytes -= self.fed.pop_front().unwrap().size();
            }
        }
        let lead_ms = if self.feed_start.is_some() {
            self.fed_samples * 1000 / self.sample_rate - (now_ns - self.feed_start_ns) / 1_000_000
        } else { 0 };
        if self.closed || self.rate == 0 || lead_ms >= 1000 { return None; }
        let packet = self.queue.pop_front()?;
        let start = self.feed_start.is_none();
        if start {
            self.feed_start = Some(packet.sample);
            self.feed_start_ns = now_ns;
            self.fed_samples = 0;
        }
        let result = (packet.share(), packet.sample, start, self.generation);
        self.fed.push_back(packet);
        Some(result)
    }

    /// The platform may reenter SETRATE/FLUSH/close from its start callback.
    pub fn commit(&mut self, generation: i64) -> bool {
        if self.closed || self.rate == 0 || self.generation != generation { return false; }
        self.fed_samples += SAMPLES_PER_FRAME;
        true
    }

    fn restore(&mut self) {
        while let Some(packet) = self.fed.pop_back() { self.queue.push_front(packet); }
    }

    fn drop_before(&mut self, timestamp: u32) {
        self.flush_until = Some(timestamp);
        while self.queue.front().is_some_and(|packet| before(packet.sample, timestamp)) {
            self.bytes -= self.queue.pop_front().unwrap().size();
        }
    }

    fn restart(&mut self) -> bool {
        self.generation += 1;
        self.fed_samples = 0;
        self.feed_start.take().is_some()
    }

    pub fn set_rate(&mut self, rtp: Option<u32>, rate: i32, ntp: i128, now_ns: i64) -> bool {
        if self.closed { return false; }
        let start = if rate > 0 { rtp.or_else(|| self.position(now_ns)).or_else(|| self.queue.front().map(|p| p.sample)) }
            else { self.position(now_ns) };
        self.restore();
        if let Some(start) = start { self.drop_before(start); }
        if rate > 0 { self.anchor_rtp = start; }
        else if start.is_some() { self.anchor_rtp = start; }
        self.anchor_ntp = Some(ntp + if rate > 0 { ((START_LATENCY_MS as i128) << 32) / 1000 } else { 0 });
        let stop = self.restart();
        self.rate = rate.clamp(0, 1);
        stop
    }

    pub fn flush(&mut self, until: Option<u32>) -> bool {
        if self.closed { return false; }
        self.restore();
        if let Some(until) = until { self.drop_before(until); }
        self.restart()
    }

    pub fn close(&mut self) -> bool {
        self.closed = true;
        self.queue.clear();
        self.fed.clear();
        self.bytes = 0;
        self.rate = 0;
        self.restart()
    }
}
