//! The audio renderer's PCM work (Kotlin's `AudioRenderer.writePcm` before the track write): the call
//! high-pass, navigation sound detection, navigation attenuation and the first block's fade-in, all in
//! place on 16-bit little-endian interleaved PCM. Each step matches its Kotlin original sample for
//! sample: `VoiceFilter`, `isAudiblePcm16`, `NavigationAudioGain.scalePcm16`, `applyFadeIn`.

use std::f64::consts::PI;

const BUTTERWORTH_Q: [f64; 2] = [0.5411961, 1.3065630];
pub const DEFAULT_CUTOFF_HZ: f64 = 200.0;

#[derive(Clone, Copy)]
struct Biquad { b0: f64, b1: f64, b2: f64, a1: f64, a2: f64 }

impl Biquad {
    fn high_pass(rate: i32, cutoff: f64, q: f64) -> Biquad {
        let w = 2.0 * PI * cutoff / rate as f64;
        let alpha = w.sin() / (2.0 * q);
        let cos_w = w.cos();
        let a0 = 1.0 + alpha;
        Biquad {
            b0: (1.0 + cos_w) / 2.0 / a0,
            b1: -(1.0 + cos_w) / a0,
            b2: (1.0 + cos_w) / 2.0 / a0,
            a1: -2.0 * cos_w / a0,
            a2: (1.0 - alpha) / a0,
        }
    }
}

/// A fourth-order Butterworth high-pass as two biquads, direct form I, per channel.
pub struct VoiceFilter {
    stages: [Biquad; 2],
    channels: usize,
    /// x1, x2, y1, y2 per stage and channel.
    state: Vec<[f64; 4]>,
}

impl VoiceFilter {
    pub fn new(sample_rate: i32, channels: usize, cutoff_hz: f64) -> VoiceFilter {
        let stages = BUTTERWORTH_Q.map(|q| Biquad::high_pass(sample_rate, cutoff_hz, q));
        VoiceFilter { stages, channels, state: vec![[0.0; 4]; 2 * channels] }
    }

    pub fn process(&mut self, pcm: &mut [u8]) {
        let frame_bytes = 2 * self.channels;
        for frame in 0..pcm.len() / frame_bytes {
            for channel in 0..self.channels {
                let at = frame * frame_bytes + channel * 2;
                let mut x = i16::from_le_bytes([pcm[at], pcm[at + 1]]) as f64;
                for (index, stage) in self.stages.iter().enumerate() {
                    let h = &mut self.state[index * self.channels + channel];
                    let y = stage.b0 * x + stage.b1 * h[0] + stage.b2 * h[1] - stage.a1 * h[2] - stage.a2 * h[3];
                    h[1] = h[0];
                    h[0] = x;
                    h[3] = h[2];
                    h[2] = y;
                    x = y;
                }
                let sample = (x as i32).clamp(i16::MIN as i32, i16::MAX as i32) as i16;
                pcm[at..at + 2].copy_from_slice(&sample.to_le_bytes());
            }
        }
    }
}

/// True when any sample reaches `peak` in either direction.
pub fn is_audible(pcm: &[u8], peak: i32) -> bool {
    pcm.chunks_exact(2).any(|s| {
        let sample = i16::from_le_bytes([s[0], s[1]]) as i32;
        sample >= peak || sample <= -peak
    })
}

/// `NavigationAudioGain.linear`: an amplitude scale for `db` in -20..0.
pub fn linear_gain(db: i32) -> f32 {
    10f64.powf(db.clamp(-20, 0) as f64 / 20.0) as f32
}

/// Scales in place by `db`; 0 dB leaves the samples untouched.
pub fn attenuate(pcm: &mut [u8], db: i32) {
    let gain = linear_gain(db);
    if gain >= 1.0 { return; }
    for s in pcm.chunks_exact_mut(2) {
        let sample = i16::from_le_bytes([s[0], s[1]]) as f32;
        // Kotlin's roundToInt rounds half up.
        let scaled = (sample * gain + 0.5).floor() as i32 as i16;
        s.copy_from_slice(&scaled.to_le_bytes());
    }
}

/// Ramps the first 10 ms (in samples, as the Kotlin original counts them) up from silence.
pub fn fade_in(pcm: &mut [u8], sample_rate: i32) {
    let samples = pcm.len() / 2;
    let fade = samples.min((sample_rate / 100).max(1) as usize);
    for index in 0..fade {
        let at = index * 2;
        let sample = i16::from_le_bytes([pcm[at], pcm[at + 1]]) as i64;
        let scaled = (sample * (index as i64 + 1) / fade as i64) as i16;
        pcm[at..at + 2].copy_from_slice(&scaled.to_le_bytes());
    }
}

/// Wired LPCM arrives big-endian: copies `source` into `out` with each sample's bytes swapped.
pub fn copy_swapped(source: &[u8], out: &mut [u8]) -> usize {
    let length = source.len().min(out.len()) & !1;
    for (dst, src) in out[..length].chunks_exact_mut(2).zip(source[..length].chunks_exact(2)) {
        dst[0] = src[1];
        dst[1] = src[0];
    }
    length
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pcm(samples: &[i16]) -> Vec<u8> { samples.iter().flat_map(|s| s.to_le_bytes()).collect() }
    fn samples(pcm: &[u8]) -> Vec<i16> { pcm.chunks_exact(2).map(|s| i16::from_le_bytes([s[0], s[1]])).collect() }

    #[test]
    fn attenuation_rounds_like_kotlin() {
        let mut data = pcm(&[1000, -1000, 5, -5, 0]);
        attenuate(&mut data, -20);
        // 0.1 as f32 times 1000 is 100.000001; -5 * 0.1 = -0.5 rounds half up to 0.
        assert_eq!(samples(&data), vec![100, -100, 1, 0, 0]);
        let mut untouched = pcm(&[1234]);
        attenuate(&mut untouched, 0);
        assert_eq!(samples(&untouched), vec![1234]);
    }

    #[test]
    fn fade_in_ramps_the_first_ten_milliseconds_of_samples() {
        let mut data = pcm(&[1000; 4]);
        fade_in(&mut data, 200); // two samples of fade at 200 Hz
        assert_eq!(samples(&data), vec![500, 1000, 1000, 1000]);
    }

    #[test]
    fn the_high_pass_removes_dc_and_keeps_a_voice_band_tone() {
        let mut filter = VoiceFilter::new(16_000, 1, DEFAULT_CUTOFF_HZ);
        let mut dc = pcm(&[8000; 4000]);
        filter.process(&mut dc);
        assert!(samples(&dc)[3000..].iter().all(|s| s.abs() < 50));
        let tone: Vec<i16> = (0..4000).map(|n| ((n as f64 * 2.0 * PI * 1000.0 / 16000.0).sin() * 8000.0) as i16).collect();
        let mut voice = pcm(&tone);
        VoiceFilter::new(16_000, 1, DEFAULT_CUTOFF_HZ).process(&mut voice);
        let peak = samples(&voice)[3000..].iter().map(|s| s.abs()).max().unwrap();
        assert!(peak > 7500, "{peak}");
    }

    #[test]
    fn audible_and_swap() {
        assert!(!is_audible(&pcm(&[63, -63]), 64));
        assert!(is_audible(&pcm(&[0, -64]), 64));
        let mut out = [0u8; 4];
        assert_eq!(copy_swapped(&[0x12, 0x34, 0x56, 0x78, 0x9a], &mut out), 4);
        assert_eq!(out, [0x34, 0x12, 0x78, 0x56]);
    }
}
