//! Lockdown protocol owner. TLS, certificate material and actual stream effects stay on Android.
//! Compared with LIVI 6ac88bab7351a39b0cffac20c5dcf92ca848319b iap2-wired/carkit.rs:
//! that implementation delegates XML/session/pairing to idevice and Tokio; none is copied here.
mod flow;
mod jni;
mod xml;

type Text = Vec<u16>;
fn text(s: &str) -> Text {
    s.encode_utf16().collect()
}
#[derive(Clone)]
enum Value {
    Dict(Vec<(Text, Value)>),
    Text(Text),
    Int(i64),
    Bool(bool),
    Data(Vec<u8>),
}
impl Value {
    fn get(&self, key: &str) -> Option<&Value> {
        let key = text(key);
        match self {
            Self::Dict(items) => items.iter().find(|(k, _)| *k == key).map(|(_, v)| v),
            _ => None,
        }
    }
}
fn dict(items: Vec<(&str, Value)>) -> Value {
    Value::Dict(items.into_iter().map(|(k, v)| (text(k), v)).collect())
}
fn string(s: &str) -> Value {
    Value::Text(text(s))
}
fn remaining(deadline: i64, now: i64) -> Option<i64> {
    let n = deadline.wrapping_sub(now);
    (n > 0).then(|| (n + 999_999) / 1_000_000)
}
fn timeout(value: i64) -> Result<(), &'static str> {
    if (1..=300_000).contains(&value) {
        Ok(())
    } else {
        Err("timeoutMillis must be between 1 and 300000")
    }
}
fn error_code(value: &Value) -> Result<Option<Text>, ()> {
    Ok(match value.get("Error") {
        None => None,
        Some(Value::Text(v)) => Some(v.clone()),
        Some(Value::Int(v)) => Some(match value.get("ErrorString") {
            Some(Value::Text(t)) => t.clone(),
            _ => text(&v.to_string()),
        }),
        _ => return Err(()),
    })
}
fn base64(input: &[u8]) -> Text {
    const A: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = Vec::with_capacity(input.len().div_ceil(3) * 4);
    for chunk in input.chunks(3) {
        let n = ((chunk[0] as u32) << 16)
            | ((chunk.get(1).copied().unwrap_or(0) as u32) << 8)
            | chunk.get(2).copied().unwrap_or(0) as u32;
        out.extend([
            A[(n >> 18) as usize & 63] as u16,
            A[(n >> 12) as usize & 63] as u16,
            if chunk.len() > 1 {
                A[(n >> 6) as usize & 63] as u16
            } else {
                61
            },
            if chunk.len() > 2 {
                A[n as usize & 63] as u16
            } else {
                61
            },
        ]);
    }
    out
}
fn unbase64(input: &[u16]) -> Result<Vec<u8>, ()> {
    let mut out = Vec::with_capacity(input.len() / 4 * 3 + 2);
    let (mut bits, mut count, mut index) = (0u32, 0, 0);
    while index < input.len() {
        let c = input[index];
        index += 1;
        if c == 61 {
            if !(count == 3
                || (count == 2 && index < input.len() && {
                    let same = input[index] == 61;
                    index += 1;
                    same
                }))
                || index != input.len()
            {
                return Err(());
            }
            break;
        }
        let v = match c {
            65..=90 => c - 65,
            97..=122 => c - 97 + 26,
            48..=57 => c - 48 + 52,
            43 => 62,
            47 => 63,
            _ => return Err(()),
        };
        bits = (bits << 6) | v as u32;
        count += 1;
        if count == 4 {
            out.extend([(bits >> 16) as u8, (bits >> 8) as u8, bits as u8]);
            bits = 0;
            count = 0;
        }
    }
    match count {
        1 => return Err(()),
        2 => out.push((bits >> 4) as u8),
        3 => out.extend([(bits >> 10) as u8, (bits >> 2) as u8]),
        _ => {}
    }
    Ok(out)
}
