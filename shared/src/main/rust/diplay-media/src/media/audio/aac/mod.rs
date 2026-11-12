//! AAC-LC SDK framing owner (ADTS, ASC and RFC3640 single-AU extraction).
//! Compared with LIVI 6ac88bab7351a39b0cffac20c5dcf92ca848319b
//! livi-audio/src/stream.rs: same ASC field layout; preserve our extra 12/11.025/8/7.35kHz
//! indices and truncated-AU prefix behavior. No GStreamer/runtime/implementation imported.
mod jni;
const HEADER_BYTES: i32 = 7;

fn frequency_index(rate: i32) -> u8 {
    match rate {
        96000 => 0,
        88200 => 1,
        64000 => 2,
        48000 => 3,
        44100 => 4,
        32000 => 5,
        24000 => 6,
        22050 => 7,
        16000 => 8,
        12000 => 9,
        11025 => 10,
        8000 => 11,
        7350 => 12,
        _ => 3,
    }
}
fn header(access_unit_bytes: i32, rate: i32, channels: i32) -> [u8; 7] {
    let frequency = frequency_index(rate) as u32;
    let channels = channels.clamp(1, 7) as u32;
    // The public Kotlin API accepted every Int, including wrapping lengths and values outside
    // ADTS's 13-bit range. Keep its unsigned shifts/byte truncation rather than rejecting them.
    let length = access_unit_bytes.wrapping_add(HEADER_BYTES) as u32;
    [
        0xff,
        0xf1,
        (0x40 | (frequency << 2) | (channels >> 2)) as u8,
        (((channels & 3) << 6) | (length >> 11)) as u8,
        (length >> 3) as u8,
        (((length & 7) << 5) | 0x1f) as u8,
        0xfc,
    ]
}
fn audio_specific_config(rate: i32, channels: i32) -> [u8; 2] {
    let value =
        (2u16 << 11) | ((frequency_index(rate) as u16) << 7) | ((channels.clamp(1, 7) as u16) << 3);
    value.to_be_bytes()
}
fn access_unit_range(first: [u8; 4], total: usize) -> Option<std::ops::Range<usize>> {
    if total < 4 {
        return None;
    }
    let bits = u16::from_be_bytes([first[0], first[1]]) as usize;
    if bits < 16 || bits % 16 != 0 {
        return None;
    }
    let start = 2 + bits / 8;
    if start > total {
        return None;
    }
    let size = (u16::from_be_bytes([first[2], first[3]]) >> 3) as usize & 0x1fff;
    let end = (start + size).min(total);
    (end > start).then_some(start..end)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn asc_44100_stereo_matches_livi_primitive_vector() {
        assert_eq!(audio_specific_config(44100, 2), [0x12, 0x10]);
        assert_eq!(audio_specific_config(48000, 2), [0x11, 0x90]);
        assert_eq!(audio_specific_config(-1, 0), [0x11, 0x88]);
        assert_eq!(audio_specific_config(48000, 99), [0x11, 0xb8]);
    }
    #[test]
    fn all_existing_rates_and_default_are_preserved() {
        for (i, rate) in [
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350,
        ]
        .into_iter()
        .enumerate()
        {
            assert_eq!(frequency_index(rate), i as u8)
        }
        assert_eq!(frequency_index(i32::MIN), 3);
        assert_eq!(frequency_index(12345), 3);
    }
    #[test]
    fn adts_golden_clamp_and_wrapping_lengths() {
        assert_eq!(header(2, 44100, 2), [0xff, 0xf1, 0x50, 0x80, 1, 0x3f, 0xfc]);
        assert_eq!(
            header(-8, 44100, 2),
            [0xff, 0xf1, 0x50, 0xff, 0xff, 0xff, 0xfc]
        );
        assert_eq!(
            header(i32::MAX, 48000, 0),
            [0xff, 0xf1, 0x4c, 0x40, 0, 0xdf, 0xfc]
        );
    }
    #[test]
    fn first_au_only_and_truncated_data_still_returns_available_prefix() {
        assert_eq!(access_unit_range([0, 16, 0, 24], 8), Some(4..7));
        assert_eq!(access_unit_range([0, 16, 0, 24], 6), Some(4..6));
        assert_eq!(access_unit_range([0, 32, 0, 16], 9), Some(6..8));
        assert_eq!(access_unit_range([0, 16, 0, 0], 8), None);
        assert_eq!(access_unit_range([0, 17, 0, 24], 8), None);
        assert_eq!(access_unit_range([0, 32, 0, 24], 5), None);
    }
}
