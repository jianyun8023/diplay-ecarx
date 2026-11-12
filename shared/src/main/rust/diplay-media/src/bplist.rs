//! CarPlay's binary plist codec, ported from this project's Kotlin wire contract.
//! Java strings cross JNI as UTF-16 units, including NUL and malformed surrogates.

#[derive(Debug, PartialEq)]
pub enum Value {
    Null,
    Bool(bool),
    Int(u64),
    Float(f32),
    Double(f64),
    Date(i64),
    String(Vec<u16>),
    Data(Vec<u8>),
    Array(Vec<Value>),
    Dict(Vec<(Vec<u16>, Value)>),
}

pub const MAX_ENCODE_DEPTH: usize = 128;
pub const ENCODE_DEPTH_ERROR: &str = "bplist: nested too deep to encode";
type Result<T> = std::result::Result<T, String>;

fn require(condition: bool, message: &str) -> Result<()> {
    if condition {
        Ok(())
    } else {
        Err(message.into())
    }
}

struct Reader<'a> {
    bytes: &'a [u8],
    offsets: Vec<usize>,
    ref_size: usize,
    reads: i64,
    payload: i64,
}

fn be(bytes: &[u8], offset: usize, size: usize) -> Result<u64> {
    let data = bytes
        .get(offset..offset.saturating_add(size))
        .ok_or("bplist: value runs past the data")?;
    Ok(data
        .iter()
        .fold(0, |value, byte| (value << 8) | u64::from(*byte)))
}

impl Reader<'_> {
    fn charge(&mut self, size: usize) -> Result<()> {
        if size > 64 {
            self.payload -= size as i64;
            require(
                self.payload >= 0,
                "bplist: shared data is read more often than its size allows",
            )?;
        }
        Ok(())
    }

    fn reference(&self, offset: usize) -> Result<usize> {
        let reference = be(self.bytes, offset, self.ref_size)?;
        require(
            reference <= i32::MAX as u64,
            "bplist: reference out of range",
        )?;
        Ok(reference as usize)
    }

    fn object(&mut self, index: usize, depth: usize) -> Result<Value> {
        require(depth <= 32, "bplist: nested too deep")?;
        self.reads -= 1;
        require(
            self.reads >= 0,
            "bplist: expands to more objects than its size allows",
        )?;
        let mut position = *self
            .offsets
            .get(index)
            .ok_or("bplist: reference out of range")?;
        let marker = self.bytes[position];
        position += 1;
        let kind = marker >> 4;
        let nibble = marker & 15;
        let count = |position: &mut usize| -> Result<usize> {
            if nibble != 15 {
                return Ok(nibble as usize);
            }
            let size = 1 << (self.bytes[*position] & 15);
            *position += 1;
            require(size <= 8, "bplist: count wider than 8 bytes")?;
            let count = be(self.bytes, *position, size)? as i64;
            *position += size;
            require(
                count >= 0 && count as u64 <= (self.bytes.len() - *position) as u64,
                "bplist: count larger than the data",
            )?;
            Ok(count as usize)
        };
        match kind {
            0 => match nibble {
                0 => Ok(Value::Null),
                8 => Ok(Value::Bool(false)),
                9 => Ok(Value::Bool(true)),
                _ => Err(format!("bplist: unsupported primitive 0x0{nibble}")),
            },
            1 => {
                require(nibble <= 3, "bplist: integer wider than 8 bytes")?;
                Ok(Value::Int(be(self.bytes, position, 1 << nibble)?))
            }
            2 => match 1 << nibble {
                4 => Ok(Value::Float(f32::from_bits(
                    be(self.bytes, position, 4)? as u32
                ))),
                8 => Ok(Value::Double(f64::from_bits(be(self.bytes, position, 8)?))),
                size => Err(format!("bplist: unsupported real size {size}")),
            },
            // The old reader accepts every date nibble, and returns java.util.Date, not a real.
            3 => Ok(Value::Date(java_round(
                (f64::from_bits(be(self.bytes, position, 8)?) + 978_307_200.0) * 1000.0,
            ))),
            4 => {
                let count = count(&mut position)?;
                self.charge(count)?;
                Ok(Value::Data(self.bytes[position..position + count].to_vec()))
            }
            5 => {
                let count = count(&mut position)?;
                self.charge(count)?;
                Ok(Value::String(
                    self.bytes[position..position + count]
                        .iter()
                        .map(|byte| {
                            if *byte < 128 {
                                u16::from(*byte)
                            } else {
                                0xfffd
                            }
                        })
                        .collect(),
                ))
            }
            6 => {
                let count = count(&mut position)?;
                require(
                    count as u64 * 2 <= (self.bytes.len() - position) as u64,
                    "bplist: string longer than the data",
                )?;
                self.charge(count * 2)?;
                let units: Vec<_> = self.bytes[position..position + count * 2]
                    .chunks_exact(2)
                    .map(|unit| u16::from_be_bytes([unit[0], unit[1]]))
                    .collect();
                // The JVM boundary applies its platform's malformed UTF-16 replacement policy.
                Ok(Value::String(units))
            }
            8 => {
                require(nibble <= 7, "bplist: uid wider than 8 bytes")?;
                Ok(Value::Int(be(self.bytes, position, nibble as usize + 1)?))
            }
            10 | 12 => {
                let count = count(&mut position)?;
                require(
                    count as u64 * self.ref_size as u64 <= (self.bytes.len() - position) as u64,
                    "bplist: array larger than the data",
                )?;
                let mut values = Vec::with_capacity(count.min(64));
                for i in 0..count {
                    values.push(
                        self.object(self.reference(position + i * self.ref_size)?, depth + 1)?,
                    );
                }
                Ok(Value::Array(values))
            }
            13 => {
                let count = count(&mut position)?;
                require(
                    count as u64 * 2 * self.ref_size as u64 <= (self.bytes.len() - position) as u64,
                    "bplist: dictionary larger than the data",
                )?;
                let mut values = Vec::with_capacity(count.min(64));
                for i in 0..count {
                    let key_ref = self.reference(position + i * self.ref_size)?;
                    let value_ref = self.reference(position + (count + i) * self.ref_size)?;
                    let Value::String(key) = self.object(key_ref, depth + 1)? else {
                        return Err("bplist: dictionary key is not a string".into());
                    };
                    values.push((key, self.object(value_ref, depth + 1)?));
                }
                Ok(Value::Dict(values))
            }
            _ => Err(format!("bplist: unsupported object type 0x{kind:x}")),
        }
    }
}

// Math.round(double): ties go toward positive infinity, NaN is zero and overflow saturates.
// Adding 0.5 in floating point alone incorrectly rounds values immediately below a half.
fn java_round(value: f64) -> i64 {
    let bits = value.to_bits();
    let shift = 1074 - ((bits >> 52) & 0x7ff) as i32;
    if (0..64).contains(&shift) {
        let significand = ((bits & 0x000f_ffff_ffff_ffff) | 0x0010_0000_0000_0000) as i64;
        let signed = if (bits as i64) < 0 {
            -significand
        } else {
            significand
        };
        ((signed >> shift) + 1) >> 1
    } else {
        value as i64
    }
}

pub fn decode(bytes: &[u8]) -> Result<Value> {
    require(bytes.len() >= 40, "bplist: too short")?;
    require(&bytes[..8] == b"bplist00", "bplist: bad magic")?;
    let trailer = bytes.len() - 32;
    let offset_size = bytes[trailer + 6] as usize;
    let ref_size = bytes[trailer + 7] as usize;
    require(
        (1..=8).contains(&offset_size) && (1..=8).contains(&ref_size),
        "bplist: bad offset or reference size",
    )?;
    let declared = be(bytes, trailer + 8, 8)?;
    let top = be(bytes, trailer + 16, 8)?;
    let table = be(bytes, trailer + 24, 8)?;
    require(
        declared > 0 && declared <= trailer as u64 && top < declared,
        "bplist: bad object count",
    )?;
    require(
        table >= 8
            && table <= trailer as u64
            && table + declared * offset_size as u64 <= trailer as u64,
        "bplist: bad offset table",
    )?;
    let mut offsets = Vec::with_capacity(declared as usize);
    for i in 0..declared as usize {
        let offset = be(bytes, table as usize + i * offset_size, offset_size)?;
        require(
            offset >= 8 && offset < table,
            "bplist: object offset out of range",
        )?;
        offsets.push(offset as usize);
    }
    let allowance = bytes.len() as i64 * 4 + 1024;
    Reader {
        bytes,
        offsets,
        ref_size,
        reads: allowance.min(100_000),
        payload: allowance.min(4 * 1024 * 1024),
    }
    .object(top as usize, 0)
}

enum Node {
    Leaf(Vec<u8>),
    Container(Vec<u8>, Vec<usize>),
}

fn width(value: u64) -> (usize, u8) {
    if value > 0xffff_ffff {
        (8, 3)
    } else if value > 0xffff {
        (4, 2)
    } else if value > 0xff {
        (2, 1)
    } else {
        (1, 0)
    }
}

fn marker(kind: u8, count: usize) -> Vec<u8> {
    if count < 15 {
        return vec![(kind << 4) | count as u8];
    }
    let (size, log) = width(count as u64);
    let mut bytes = vec![(kind << 4) | 15, 0x10 | log];
    bytes.extend_from_slice(&(count as u64).to_be_bytes()[8 - size..]);
    bytes
}

fn string(value: &[u16]) -> Vec<u8> {
    let ascii = value.iter().all(|unit| *unit <= 127);
    let mut bytes = marker(if ascii { 5 } else { 6 }, value.len());
    if ascii {
        bytes.extend(value.iter().map(|unit| *unit as u8));
    } else {
        bytes.extend(value.iter().flat_map(|unit| unit.to_be_bytes()));
    }
    bytes
}

fn add(nodes: &mut Vec<Node>, value: &Value) -> Result<usize> {
    let index = nodes.len();
    nodes.push(Node::Leaf(Vec::new()));
    nodes[index] = match value {
        Value::Null => return Err("bplist: unsupported value null".into()),
        Value::Date(_) => return Err("bplist: unsupported value Date".into()),
        Value::Bool(value) => Node::Leaf(vec![if *value { 9 } else { 8 }]),
        Value::Int(value) => {
            let (size, log) = width(*value);
            let mut bytes = vec![0x10 | log];
            bytes.extend_from_slice(&value.to_be_bytes()[8 - size..]);
            Node::Leaf(bytes)
        }
        Value::Float(_) | Value::Double(_) => {
            let real = match value {
                Value::Float(value) => f64::from(*value),
                Value::Double(value) => *value,
                _ => unreachable!(),
            };
            let mut bytes = vec![0x23];
            bytes.extend_from_slice(&real.to_be_bytes());
            Node::Leaf(bytes)
        }
        Value::String(value) => Node::Leaf(string(value)),
        Value::Data(value) => {
            let mut bytes = marker(4, value.len());
            bytes.extend_from_slice(value);
            Node::Leaf(bytes)
        }
        Value::Array(values) => {
            let refs = values
                .iter()
                .map(|value| add(nodes, value))
                .collect::<Result<Vec<_>>>()?;
            Node::Container(marker(10, values.len()), refs)
        }
        Value::Dict(values) => {
            let mut refs = Vec::with_capacity(values.len() * 2);
            for (key, _) in values {
                refs.push(nodes.len());
                nodes.push(Node::Leaf(string(key)));
            }
            for (_, value) in values {
                refs.push(add(nodes, value)?);
            }
            Node::Container(marker(13, values.len()), refs)
        }
    };
    Ok(index)
}

/// The JNI input conversion owns validation, including the encoder depth boundary.
pub fn encode(value: &Value) -> Result<Vec<u8>> {
    let mut nodes = Vec::new();
    let top = add(&mut nodes, value)?;
    let ref_size = if nodes.len() > 0xffff {
        4
    } else if nodes.len() > 0xff {
        2
    } else {
        1
    };
    let mut out = b"bplist00".to_vec();
    let mut offsets = Vec::with_capacity(nodes.len());
    for node in nodes {
        offsets.push(out.len());
        match node {
            Node::Leaf(bytes) => out.extend(bytes),
            Node::Container(head, refs) => {
                out.extend(head);
                for reference in refs {
                    out.extend_from_slice(&(reference as u64).to_be_bytes()[8 - ref_size..]);
                }
            }
        }
    }
    let table = out.len();
    let offset_size = if table > 0xffff {
        4
    } else if table > 0xff {
        2
    } else {
        1
    };
    for offset in &offsets {
        out.extend_from_slice(&(*offset as u64).to_be_bytes()[8 - offset_size..]);
    }
    let mut trailer = [0; 32];
    trailer[6] = offset_size as u8;
    trailer[7] = ref_size as u8;
    trailer[8..16].copy_from_slice(&(offsets.len() as u64).to_be_bytes());
    trailer[16..24].copy_from_slice(&(top as u64).to_be_bytes());
    trailer[24..].copy_from_slice(&(table as u64).to_be_bytes());
    out.extend_from_slice(&trailer);
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn text(value: &str) -> Value {
        Value::String(value.encode_utf16().collect())
    }

    fn plist(objects: &[Vec<u8>]) -> Vec<u8> {
        let mut out = b"bplist00".to_vec();
        let mut offsets = Vec::new();
        for object in objects {
            offsets.push(out.len() as u32);
            out.extend(object);
        }
        let table = out.len();
        for offset in offsets {
            out.extend(offset.to_be_bytes());
        }
        let mut trailer = [0; 32];
        trailer[6] = 4;
        trailer[7] = 1;
        trailer[8..16].copy_from_slice(&(objects.len() as u64).to_be_bytes());
        trailer[24..32].copy_from_slice(&(table as u64).to_be_bytes());
        out.extend(trailer);
        out
    }

    fn counted(marker: u8, count: u32, payload: &[u8]) -> Vec<u8> {
        [
            vec![marker, 0x12],
            count.to_be_bytes().to_vec(),
            payload.to_vec(),
        ]
        .concat()
    }

    #[test]
    fn reference_wire_vector_matches() {
        // LIVI ts_vectors.json at 6ac88bab7351a39b0cffac20c5dcf92ca848319b:
        // shared wire fixture only; implementation follows this project's Kotlin contract.
        let entries = vec![
            ("type", text("hidSendReport")),
            ("uuid", text("2a2a2a2a")),
            ("hidReport", Value::Data(vec![0, 1, 2, 3])),
            ("n0", Value::Int(0)),
            ("n255", Value::Int(255)),
            ("n256", Value::Int(256)),
            ("n65536", Value::Int(65536)),
            ("big", Value::Int(1 << 40)),
            ("neg", Value::Double(-1.0)),
            ("frac", Value::Double(0.5)),
            ("t", Value::Bool(true)),
            ("f", Value::Bool(false)),
            ("uni", text("Bülli")),
            (
                "list",
                Value::Array(vec![
                    Value::Int(1),
                    text("two"),
                    Value::Array(vec![Value::Int(3)]),
                ]),
            ),
            ("empty", Value::Dict(vec![])),
            ("long", text(&"x".repeat(20))),
            ("many", Value::Array((0..20).map(Value::Int).collect())),
        ];
        let root = Value::Dict(
            entries
                .into_iter()
                .map(|(key, value)| (key.encode_utf16().collect(), value))
                .collect(),
        );
        let bytes = encode(&root).unwrap();
        assert_eq!(bytes.len(), 458);
        let sha = ring::digest::digest(&ring::digest::SHA256, &bytes)
            .as_ref()
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect::<String>();
        assert_eq!(
            sha,
            "b4e3e93557bb97d6207978c92e3a52f533a60cd3f0e9034a4253c080b83e872a"
        );
        assert_eq!(decode(&bytes), Ok(root));
    }

    #[test]
    fn unsigned_integer_widths_are_zero_padded_and_keep_all_64_bits() {
        for (value, body) in [
            (65536, vec![0x12, 0, 1, 0, 0]),
            (1 << 40, vec![0x13, 0, 0, 1, 0, 0, 0, 0, 0]),
            (1 << 63, vec![0x13, 0x80, 0, 0, 0, 0, 0, 0, 0]),
            (
                u64::MAX,
                vec![0x13, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff],
            ),
        ] {
            let bytes = encode(&Value::Int(value)).unwrap();
            assert_eq!(&bytes[8..8 + body.len()], body);
            assert_eq!(decode(&bytes), Ok(Value::Int(value)));
        }
    }

    #[test]
    fn null_uid_set_and_real_widths_keep_the_existing_decoded_types() {
        assert_eq!(decode(&plist(&[vec![0]])), Ok(Value::Null));
        assert_eq!(
            decode(&plist(&[vec![0x82, 0x80, 0, 1]])),
            Ok(Value::Int(0x80_0001))
        );
        assert_eq!(
            decode(&plist(&[vec![0xc2, 1, 1], vec![9]])),
            Ok(Value::Array(vec![Value::Bool(true), Value::Bool(true)]))
        );
        assert_eq!(
            decode(&plist(&[
                [vec![0x22], 1.25f32.to_be_bytes().to_vec()].concat()
            ])),
            Ok(Value::Float(1.25))
        );
        assert_eq!(
            decode(&plist(&[
                [vec![0x23], (-1.25f64).to_be_bytes().to_vec()].concat()
            ])),
            Ok(Value::Double(-1.25))
        );
        assert_eq!(
            decode(&encode(&Value::Float(1.25)).unwrap()),
            Ok(Value::Double(1.25))
        );
    }

    #[test]
    fn dates_use_java_rounding_including_half_ties_nan_and_overflow() {
        for (value, expected) in [
            (0.49999999999999994, 0),
            (0.5, 1),
            (-0.5, 0),
            (-1.5, -1),
            (f64::NAN, 0),
            (f64::INFINITY, i64::MAX),
            (f64::NEG_INFINITY, i64::MIN),
            (4503599627370495.0, 4503599627370495),
        ] {
            assert_eq!(java_round(value), expected);
        }
        for nibble in 0..16 {
            let bytes = plist(&[[vec![0x30 | nibble], 0f64.to_be_bytes().to_vec()].concat()]);
            assert_eq!(decode(&bytes), Ok(Value::Date(978_307_200_000)));
        }
    }

    #[test]
    fn text_keeps_nul_surrogate_pairs_bom_and_raw_malformed_utf16_for_the_jvm_boundary() {
        for value in ["a\0b", "Bülli😀", "\u{feff}a"] {
            let value = text(value);
            assert_eq!(decode(&encode(&value).unwrap()), Ok(value));
        }
        assert_eq!(
            decode(&plist(&[vec![0x53, 0, 0x80, 0xff]])),
            Ok(Value::String(vec![0, 0xfffd, 0xfffd]))
        );
        assert_eq!(
            decode(&plist(&[vec![0x62, 0xd8, 0, 0, 65]])),
            Ok(Value::String(vec![0xd800, 65]))
        );
    }

    #[test]
    fn container_reference_and_offset_widths_match_the_old_layout() {
        for (count, ref_size) in [(254, 1), (255, 2), (65535, 4)] {
            let bytes = encode(&Value::Array((0..count).map(|_| Value::Int(0)).collect())).unwrap();
            assert_eq!(bytes[bytes.len() - 25], ref_size);
            assert_eq!(
                decode(&bytes).unwrap(),
                Value::Array((0..count).map(|_| Value::Int(0)).collect())
            );
        }
        let bytes = encode(&Value::Data(vec![7; 500_000])).unwrap();
        assert_eq!(bytes[bytes.len() - 26], 4);
        assert_eq!(decode(&bytes), Ok(Value::Data(vec![7; 500_000])));
    }

    #[test]
    fn rejects_bad_headers_wide_numbers_and_non_string_keys() {
        assert_eq!(decode(b"short"), Err("bplist: too short".into()));
        for (object, message) in [
            (vec![0x1f], "integer wider than 8 bytes"),
            (vec![0x8f], "uid wider than 8 bytes"),
            (vec![0xaf, 0x1f], "count wider than 8 bytes"),
            (vec![0x21], "unsupported real size 2"),
            (vec![0x0a], "unsupported primitive 0x010"),
        ] {
            assert_eq!(decode(&plist(&[object])), Err(format!("bplist: {message}")));
        }
        assert_eq!(
            decode(&plist(&[vec![0xd1, 1, 2], vec![9], vec![8]])),
            Err("bplist: dictionary key is not a string".into())
        );
        let mut bytes = plist(&[vec![9]]);
        let end = bytes.len();
        bytes[end - 24..end - 16].copy_from_slice(&u64::MAX.to_be_bytes());
        assert_eq!(decode(&bytes), Err("bplist: bad object count".into()));
    }

    #[test]
    fn decoder_accepts_depth_32_and_rejects_depth_33_and_cycles() {
        for depth in [32, 33] {
            let mut root = Value::Bool(true);
            for _ in 0..depth {
                root = Value::Array(vec![root]);
            }
            let result = decode(&encode(&root).unwrap());
            if depth == 32 {
                assert_eq!(result, Ok(root));
            } else {
                assert_eq!(result, Err("bplist: nested too deep".into()));
            }
        }
        assert_eq!(
            decode(&plist(&[vec![0xa1, 0]])),
            Err("bplist: nested too deep".into())
        );
    }

    #[test]
    fn decoder_rejects_exponential_expansion_and_caps_total_object_reads() {
        let mut objects: Vec<_> = (0..30).map(|i| vec![0xa2, i + 1, i + 1]).collect();
        objects.push(vec![9]);
        assert_eq!(
            decode(&plist(&objects)),
            Err("bplist: expands to more objects than its size allows".into())
        );
        let root = counted(0xaf, 100_000, &vec![1; 100_000]);
        assert_eq!(
            decode(&plist(&[root, vec![0xa0]])),
            Err("bplist: expands to more objects than its size allows".into())
        );
        let root = counted(0xaf, 1_000_000, &vec![0; 1_000_000]);
        assert_eq!(
            decode(&plist(&[root])),
            Err("bplist: nested too deep".into())
        );
    }

    #[test]
    fn decoder_payload_budget_rejects_shared_blobs_but_exempts_small_strings() {
        let root = counted(0xaf, 20_000, &vec![1; 20_000]);
        let blob = counted(0x4f, 200_000, &vec![7; 200_000]);
        assert_eq!(
            decode(&plist(&[root, blob])),
            Err("bplist: shared data is read more often than its size allows".into())
        );
        let root = counted(0xaf, 1000, &vec![1; 1000]);
        let string = [vec![0x5f, 0x10, 20], vec![b'x'; 20]].concat();
        assert_eq!(
            decode(&plist(&[root, string])),
            Ok(Value::Array(
                (0..1000).map(|_| text(&"x".repeat(20))).collect()
            ))
        );
        let bytes = encode(&Value::Data(vec![0; 4 * 1024 * 1024 + 1])).unwrap();
        assert_eq!(
            decode(&bytes),
            Err("bplist: shared data is read more often than its size allows".into())
        );
    }

    #[test]
    fn malformed_wire_mutations_return_results_without_panicking() {
        let valid = encode(&Value::Array(vec![
            text("x"),
            Value::Int(65536),
            Value::Data(vec![1; 100]),
        ]))
        .unwrap();
        for index in 0..valid.len() {
            for byte in [0, 1, 8, 15, 31, 127, 128, 255] {
                let mut bytes = valid.clone();
                bytes[index] = byte;
                let _ = decode(&bytes);
            }
        }
        for marker in 0..=255 {
            let _ = decode(&plist(&[vec![marker]]));
        }
    }
}
