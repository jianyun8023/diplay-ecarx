//! Downlink codec dispatch, PCM storage and processing. The output worker owns this state.
mod jni;
use crate::media::audio::pcm::{self, VoiceFilter, DEFAULT_CUTOFF_HZ};
use libopus_sys::{opus_decode, opus_decoder_create, opus_decoder_destroy, OpusDecoder, OPUS_OK};

const OPUS_PRE_SKIP: usize = 312;
const DETECT_AUDIBLE: i32 = 1;
const ATTENUATE: i32 = 2;
const FADE_IN: i32 = 4;
#[derive(Clone, Copy, PartialEq)]
enum Backend {
    Lpcm,
    Aac,
    Opus,
    SdkOpus,
}
struct Renderer {
    sample_rate: i32,
    wire_channels: i32,
    channels: usize,
    filter: Option<VoiceFilter>,
    backend: Backend,
    opus: Option<*mut OpusDecoder>,
    pre_skip_left: usize,
    decoded: Vec<i16>,
    pcm: Vec<u8>,
}
impl Renderer {
    fn new(rate: i32, wire_channels: i32, voice_filter: bool, codec: i32) -> Option<Self> {
        if rate <= 0 {
            return None;
        }
        let channels = wire_channels.clamp(1, 2) as usize;
        let mut state = Self {
            sample_rate: rate,
            wire_channels,
            channels,
            filter: voice_filter.then(|| VoiceFilter::new(rate, channels, DEFAULT_CUTOFF_HZ)),
            backend: match codec {
                0 => Backend::Lpcm,
                1 => Backend::Aac,
                2 => Backend::SdkOpus,
                _ => return None,
            },
            opus: None,
            pre_skip_left: 0,
            decoded: Vec::new(),
            pcm: vec![0; 64 * 1024],
        };
        if codec == 2 {
            let mut error = 0;
            let decoder = unsafe { opus_decoder_create(rate, channels as i32, &mut error) };
            if !decoder.is_null() && error == OPUS_OK as i32 {
                state.backend = Backend::Opus;
                state.opus = Some(decoder);
                state.pre_skip_left = OPUS_PRE_SKIP * rate as usize / 48_000;
                state.decoded = vec![0; rate as usize * 120 / 1000 * channels];
            } else if !decoder.is_null() {
                unsafe { opus_decoder_destroy(decoder) };
            }
        }
        Some(state)
    }
    fn configuration(&self) -> Option<(&'static str, bool, Vec<Vec<u8>>)> {
        match self.backend {
            Backend::Aac => Some((
                "audio/mp4a-latm",
                true,
                vec![
                    super::aac::audio_specific_config(self.sample_rate, self.wire_channels)
                        .to_vec(),
                ],
            )),
            Backend::SdkOpus => {
                let mut head = vec![0; 19];
                head[..8].copy_from_slice(b"OpusHead");
                head[8] = 1;
                head[9] = self.wire_channels as u8;
                head[10..12].copy_from_slice(&312u16.to_le_bytes());
                head[12..16].copy_from_slice(&self.sample_rate.to_le_bytes());
                Some((
                    "audio/opus",
                    false,
                    vec![
                        head,
                        6_500_000i64.to_le_bytes().to_vec(),
                        80_000_000i64.to_le_bytes().to_vec(),
                    ],
                ))
            }
            _ => None,
        }
    }
    fn decode(&mut self, source: &[u8]) -> i32 {
        if self.backend == Backend::Lpcm {
            self.pcm.resize(source.len(), 0);
            return pcm::copy_swapped(source, &mut self.pcm) as i32;
        }
        let Some(decoder) = self.opus else {
            return -100;
        };
        let frames = unsafe {
            opus_decode(
                decoder,
                source.as_ptr(),
                source.len() as i32,
                self.decoded.as_mut_ptr(),
                (self.decoded.len() / self.channels) as i32,
                0,
            )
        };
        if frames < 0 {
            return -1;
        }
        let frames = frames as usize;
        let skip = self.pre_skip_left.min(frames);
        self.pre_skip_left -= skip;
        let decoded = &self.decoded[skip * self.channels..frames * self.channels];
        self.pcm.resize(decoded.len() * 2, 0);
        for (dst, sample) in self.pcm.chunks_exact_mut(2).zip(decoded) {
            dst.copy_from_slice(&sample.to_le_bytes());
        }
        self.pcm.len() as i32
    }
}
impl Drop for Renderer {
    fn drop(&mut self) {
        if let Some(opus) = self.opus {
            unsafe { opus_decoder_destroy(opus) };
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn lpcm_buffer_grows_and_keeps_odd_tail_out_of_the_written_length() {
        let mut state = Renderer::new(48000, 2, false, 0).unwrap();
        let mut source = vec![0; 100001];
        source[0..4].copy_from_slice(&[0x12, 0x34, 0x56, 0x78]);
        assert_eq!(state.decode(&source), 100000);
        assert_eq!(&state.pcm[..4], &[0x34, 0x12, 0x78, 0x56]);
        assert_eq!(state.decode(&[1, 2]), 2);
        assert_eq!(state.pcm, [2, 1]);
    }
    #[test]
    fn sdk_capability_plan_preserves_aac_and_unsupported_native_opus_configuration() {
        let aac = Renderer::new(44100, 2, false, 1)
            .unwrap()
            .configuration()
            .unwrap();
        assert_eq!(aac.0, "audio/mp4a-latm");
        assert!(aac.1);
        assert_eq!(aac.2, [vec![0x12, 0x10]]);
        assert!(Renderer::new(48000, 1, false, 2)
            .unwrap()
            .configuration()
            .is_none());
        let opus = Renderer::new(44100, 1, false, 2)
            .unwrap()
            .configuration()
            .unwrap();
        assert_eq!(opus.0, "audio/opus");
        assert!(!opus.1);
        assert_eq!(opus.2[0].len(), 19);
        assert_eq!(&opus.2[0][10..16], &[0x38, 1, 0x44, 0xac, 0, 0]);
        assert_eq!(opus.2[1], 6_500_000i64.to_le_bytes());
    }
}
