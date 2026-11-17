//! Opaque GC-value diagnostics state; no PCM or endpoint data survives a level scan.
pub(super) const SLOTS: usize = 18;
pub(super) const SILENT: i64 = i64::MIN + 1;
pub(super) struct Stats(pub [i64; SLOTS]);

impl Stats {
    pub fn new(now: i64) -> Self {
        let mut result = Self([0; SLOTS]);
        result.0[0] = now;
        result.0[1] = now;
        result.0[11] = i64::MIN;
        result
    }

    pub fn event(&mut self, event: i32, value: i64, extra: i64, now: i64) {
        let s = &mut self.0;
        match event {
            0 => s[1] = now,
            1 => {
                s[3] = s[3].wrapping_add(1);
                s[6] = s[6].max(now.wrapping_sub(s[1]).max(0));
                if value > 0 {
                    s[2] = s[2].wrapping_add(value);
                } else if value == 0 {
                    s[4] = s[4].wrapping_add(1);
                } else {
                    s[5] = s[5].wrapping_add(1);
                }
            }
            2 => {
                s[7] = s[7].wrapping_add(value.max(0));
                s[8] = s[8].wrapping_add(extra.max(0));
            }
            3 => {
                if s[11] != i64::MIN {
                    s[12] = s[12].max(now.wrapping_sub(s[11]).max(0));
                }
                s[11] = now;
                s[9] = s[9].wrapping_add(1);
            }
            4 => s[10] = s[10].wrapping_add(1),
            5 => {
                let (read_start, last_sent) = (s[1], s[11]);
                *self = Self::new(now);
                self.0[1] = read_start;
                self.0[11] = last_sent;
            }
            _ => {}
        }
    }

    pub fn level(&mut self, pcm: &[u8]) {
        let s = &mut self.0;
        let mut squares = f64::from_bits(s[15] as u64);
        for pair in pcm.chunks_exact(2) {
            let sample = i16::from_le_bytes([pair[0], pair[1]]) as i32;
            s[13] = s[13].max(sample.abs() as i64);
            s[14] = s[14].wrapping_add(1);
            squares += (sample as f64) * (sample as f64);
            if sample == 0 {
                s[16] = s[16].wrapping_add(1);
            }
            if sample == i16::MIN as i32 || sample == i16::MAX as i32 {
                s[17] = s[17].wrapping_add(1);
            }
        }
        s[15] = squares.to_bits() as i64;
    }

    pub fn snapshot(&mut self, now: i64, ended: bool) -> Option<[i64; 15]> {
        if !ended && now.wrapping_sub(self.0[0]) < 5_000_000_000 {
            return None;
        }
        let s = self.0;
        let squares = f64::from_bits(s[15] as u64);
        let rms = if s[14] == 0 {
            i64::MIN
        } else if squares == 0.0 {
            SILENT
        } else {
            (10.0 * (squares / s[14] as f64 / (32768.0 * 32768.0)).log10() + 0.5).floor() as i64
        };
        let out = [
            s[2],
            s[3],
            s[4],
            s[5],
            s[6] / 1_000_000,
            s[7],
            s[8],
            s[9],
            s[10],
            s[12] / 1_000_000,
            s[13],
            peak_dbfs(s[13]),
            rms,
            if s[14] == 0 {
                -1
            } else {
                s[16].wrapping_mul(100) / s[14]
            },
            s[17],
        ];
        Some(out)
    }
}

pub(super) fn peak_dbfs(peak: i64) -> i64 {
    if peak <= 0 {
        SILENT
    } else {
        (20.0 * (peak as f64 / 32768.0).log10() + 0.5).floor() as i64
    }
}
