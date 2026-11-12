//! The screen streams' access units: CarPlay sends 4-byte length-prefixed NAL units; the decoders
//! take Annex B. Mirrors `MediaCodecSupport.toAnnexB` and `isRandomAccess` in Kotlin.

/// Bits of the flags a video packet carries to Kotlin (`MediaPacket.flags`).
pub const FLAG_ANNEX_B: i32 = 1;
pub const FLAG_RANDOM_ACCESS_H264: i32 = 2;
pub const FLAG_RANDOM_ACCESS_H265: i32 = 4;

pub fn starts_with_start_code(bytes: &[u8]) -> bool {
    bytes.len() >= 4 && bytes[..4] == [0, 0, 0, 1]
}

/// Rewrites each NAL length as a start code in place. False, with the bytes untouched, when the
/// unit is neither Annex B already nor a complete chain of non-empty length-prefixed NAL units.
pub fn to_annex_b_in_place(bytes: &mut [u8]) -> bool {
    if starts_with_start_code(bytes) { return true; }
    let mut offset = 0usize;
    let mut units = 0;
    while offset + 4 <= bytes.len() {
        let length = u32::from_be_bytes([bytes[offset], bytes[offset + 1], bytes[offset + 2], bytes[offset + 3]]) as usize;
        offset += 4;
        if length == 0 || length > i32::MAX as usize || length > bytes.len() - offset { return false; }
        offset += length;
        units += 1;
    }
    if units == 0 || offset != bytes.len() { return false; }
    offset = 0;
    while offset + 4 <= bytes.len() {
        let length = u32::from_be_bytes([bytes[offset], bytes[offset + 1], bytes[offset + 2], bytes[offset + 3]]) as usize;
        bytes[offset..offset + 4].copy_from_slice(&[0, 0, 0, 1]);
        offset += 4 + length;
    }
    true
}

/// [FLAG_RANDOM_ACCESS_H264] when an IDR starts here, [FLAG_RANDOM_ACCESS_H265] for an IRAP.
pub fn random_access_flags(annex_b: &[u8]) -> i32 {
    let mut flags = 0;
    let mut cursor = 0usize;
    while cursor + 3 < annex_b.len() {
        let prefix = if annex_b[cursor] != 0 || annex_b[cursor + 1] != 0 {
            0
        } else if annex_b[cursor + 2] == 1 {
            3
        } else if annex_b[cursor + 2] == 0 && annex_b[cursor + 3] == 1 {
            4
        } else {
            0
        };
        if prefix > 0 && cursor + prefix < annex_b.len() {
            let header = annex_b[cursor + prefix];
            if header & 31 == 5 { flags |= FLAG_RANDOM_ACCESS_H264; }
            if cursor + prefix + 1 < annex_b.len() && (16..=21).contains(&((header >> 1) & 63)) {
                flags |= FLAG_RANDOM_ACCESS_H265;
            }
            if flags == FLAG_RANDOM_ACCESS_H264 | FLAG_RANDOM_ACCESS_H265 { break; }
            cursor += prefix;
        } else {
            cursor += 1;
        }
    }
    flags
}

/// The frame time in a screen header: bytes 8..15, a little-endian NTP 32.32 value, in nanoseconds.
pub fn sender_nanos(header: &[u8]) -> i64 {
    if header.len() < 16 { return 0; }
    let raw = u64::from_le_bytes(header[8..16].try_into().unwrap());
    ((raw >> 32) as i64).wrapping_mul(1_000_000_000) + (((raw & 0xffff_ffff) * 1_000_000_000) >> 32) as i64
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn length_prefixed_units_become_annex_b() {
        let mut unit = vec![0, 0, 0, 2, 0x65, 0xaa, 0, 0, 0, 1, 0x41];
        assert!(to_annex_b_in_place(&mut unit));
        assert_eq!(unit, vec![0, 0, 0, 1, 0x65, 0xaa, 0, 0, 0, 1, 0x41]);
        assert_eq!(random_access_flags(&unit) & FLAG_RANDOM_ACCESS_H264, FLAG_RANDOM_ACCESS_H264);
    }

    #[test]
    fn a_broken_chain_is_left_alone() {
        let original = vec![0, 0, 0, 9, 0x65, 0xaa];
        let mut unit = original.clone();
        assert!(!to_annex_b_in_place(&mut unit));
        assert_eq!(unit, original);
        assert!(!to_annex_b_in_place(&mut [1, 2]));
        assert!(!to_annex_b_in_place(&mut [0, 0, 0, 0]));
    }

    #[test]
    fn hevc_irap_and_avc_non_idr() {
        // HEVC IDR_W_RADL: type 19 -> header byte 0x26.
        assert_eq!(random_access_flags(&[0, 0, 0, 1, 0x26, 0x01, 0xaf]) & FLAG_RANDOM_ACCESS_H265, FLAG_RANDOM_ACCESS_H265);
        assert_eq!(random_access_flags(&[0, 0, 0, 1, 0x41, 0x9a]) & FLAG_RANDOM_ACCESS_H264, 0);
    }

    #[test]
    fn sender_time_matches_the_kotlin_conversion() {
        let mut header = [0u8; 16];
        header[8..16].copy_from_slice(&((3u64 << 32) | 0x8000_0000).to_le_bytes());
        assert_eq!(sender_nanos(&header), 3_500_000_000);
    }
}
