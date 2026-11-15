//! AirPlay capability declarations and video control messages, preserving the Kotlin wire contract.
mod bonjour;
mod control;
mod info;
mod jni;
mod setup;
mod video;

use super::bplist::Value;
type Result<T> = std::result::Result<T, &'static str>;

fn text(value: &str) -> Value {
    Value::String(value.encode_utf16().collect())
}
fn int(value: i32) -> Value {
    Value::Int32(value)
}
fn long(value: i64) -> Value {
    Value::Int(value as u64)
}
fn dict<const N: usize>(values: [(&str, Value); N]) -> Value {
    Value::Dict(
        values
            .into_iter()
            .map(|(key, value)| (key.encode_utf16().collect(), value))
            .collect(),
    )
}
fn array(values: impl IntoIterator<Item = Value>) -> Value {
    Value::Array(values.into_iter().collect())
}
fn put(value: &mut Value, key: &str, child: Value) {
    let Value::Dict(entries) = value else {
        unreachable!()
    };
    let key: Vec<u16> = key.encode_utf16().collect();
    if let Some((_, value)) = entries.iter_mut().find(|(name, _)| name == &key) {
        *value = child;
    } else {
        entries.push((key, child));
    }
}
fn get<'a>(value: &'a Value, key: &str) -> Option<&'a Value> {
    let Value::Dict(values) = value else {
        return None;
    };
    values
        .iter()
        .find(|(name, _)| name.iter().copied().eq(key.encode_utf16()))
        .map(|(_, value)| value)
}
fn required<'a>(value: &'a Value, key: &str) -> Result<&'a Value> {
    get(value, key).ok_or("Invalid AirPlay protocol facts")
}
fn number(value: &Value) -> Option<f64> {
    Some(match value {
        Value::Int32(n) => f64::from(*n),
        Value::Int(n) => *n as i64 as f64,
        Value::Float(n) => f64::from(*n),
        Value::Double(n) => *n,
        _ => return None,
    })
}
fn integer(value: &Value) -> Result<i32> {
    if let Value::Int32(n) = value {
        Ok(*n)
    } else {
        Err("Invalid AirPlay integer fact")
    }
}
fn boolean(value: &Value) -> Result<bool> {
    if let Value::Bool(n) = value {
        Ok(*n)
    } else {
        Err("Invalid AirPlay boolean fact")
    }
}
fn string(value: &Value) -> Result<Vec<u16>> {
    if let Value::String(n) = value {
        Ok(n.clone())
    } else {
        Err("Invalid AirPlay string fact")
    }
}
fn list(value: &Value) -> Result<&[Value]> {
    if let Value::Array(n) = value {
        Ok(n)
    } else {
        Err("Invalid AirPlay list fact")
    }
}
fn optional<T>(value: &Value, read: impl FnOnce(&Value) -> Result<T>) -> Result<Option<T>> {
    if matches!(value, Value::Null) {
        Ok(None)
    } else {
        read(value).map(Some)
    }
}
fn java_round(value: f64) -> i64 {
    let bits = value.to_bits();
    let shift = 1074 - ((bits >> 52) & 0x7ff) as i32;
    if (0..64).contains(&shift) {
        let significand = (bits & 0x000f_ffff_ffff_ffff) | 0x0010_0000_0000_0000;
        let signed = if bits >> 63 == 0 {
            significand as i64
        } else {
            -(significand as i64)
        };
        ((signed >> shift) + 1) >> 1
    } else {
        value as i64
    }
}
