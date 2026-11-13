use super::*;

fn features_txt(features: i64) -> Value {
    let low = features as u32;
    let high = (features as u64) >> 32;
    text(&if high == 0 {
        format!("0x{low:x}")
    } else {
        format!("0x{low:x},0x{high:x}")
    })
}
pub(super) const STATUS_PATTERN: &std::ffi::CStr = c"^HTTP/\\d(?:\\.\\d)? (\\d{3})(?: |$)";

pub(super) fn values(
    operation: i32,
    arguments: &Value,
    mut status: impl FnMut(&[u16]) -> Result<Value>,
) -> Result<Value> {
    let args = list(arguments)?;
    Ok(match (operation, args) {
        (0, [Value::Int(features)]) => features_txt(*features as i64),
        (1, [device, disable_audio, model, version, pairing, key]) => dict([
            ("deviceid", Value::String(string(device)?)),
            (
                "features",
                features_txt(info::features(boolean(disable_audio)?)),
            ),
            ("flags", text("0x4")),
            ("model", Value::String(string(model)?)),
            ("srcvers", Value::String(string(version)?)),
            ("protovers", text("1.1")),
            ("pi", Value::String(string(pairing)?)),
            ("pk", Value::String(string(key)?)),
        ]),
        (2, [host, port, version, device]) => {
            let host = string(host)?;
            let port = integer(port)?;
            let version = string(version)?;
            let device = string(device)?;
            let host = if host.starts_with(&[91]) && host.ends_with(&[93]) && host.len() >= 2 {
                &host[1..host.len() - 1]
            } else {
                &host
            };
            let host = &host[..host.iter().position(|c| *c == 37).unwrap_or(host.len())];
            let blank = |c: &u16| matches!(*c, 9..=13 | 28..=32 | 160 | 5760 | 8192..=8202 | 8232 | 8233 | 8239 | 8287 | 12288);
            if host.iter().all(blank) {
                return Err("host must not be blank");
            }
            if !(1..=65535).contains(&port) {
                return Err("port must be in 1..65535");
            }
            if version.is_empty() {
                return Err("sourceVersion must not be empty");
            }
            let line_break = |value: &[u16]| value.iter().any(|c| matches!(*c, 10 | 13));
            if line_break(&version) {
                return Err("sourceVersion must not contain a line break");
            }
            if line_break(host) {
                return Err("host must not contain a line break");
            }
            let device: Vec<u16> = device.into_iter().filter(|c| *c != 58).collect();
            if device.is_empty() {
                return Err("deviceId must contain a hexadecimal value");
            }
            if line_break(&device) {
                return Err("deviceId must not contain a line break");
            }
            let mut request: Vec<u16> = "GET /ctrl-int/1/connect HTTP/1.1\r\nHost: "
                .encode_utf16()
                .collect();
            if host.contains(&58) {
                request.push(91);
                request.extend(host);
                request.push(93);
            } else {
                request.extend(host);
            }
            request.extend(format!(":{port}\r\nUser-Agent: AirPlay/").encode_utf16());
            request.extend(version);
            request.extend("\r\nAirPlay-Receiver-Device-ID: ".encode_utf16());
            request.extend(device);
            request.extend("\r\nConnection: close\r\n\r\n".encode_utf16());
            Value::String(request)
        }
        (3, [line]) => {
            let Some(Value::String(line)) = (!matches!(line, Value::Null)).then_some(line) else {
                return Ok(Value::Null);
            };
            status(line)?
        }
        _ => return Err("Invalid AirPlay Bonjour operation"),
    })
}

pub(super) struct ProbeLine {
    pub bytes: Vec<u8>,
}

pub(super) fn probe_retry(attempt: &mut i64, event: i32, closed: bool) -> Result<i64> {
    if !(0..=7).contains(attempt) {
        return Err("Invalid AirPlay probe retry token");
    }
    match event {
        0 => {
            if closed || *attempt == 7 {
                return Ok(0);
            }
            *attempt += 1;
            Ok(*attempt)
        }
        1 => Ok(if closed {
            -1
        } else if *attempt < 7 {
            1500
        } else {
            0
        }),
        _ => Err("Invalid AirPlay probe retry event"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn probe_attempts_cancel_and_exhaust_without_a_final_wait() {
        let mut state = 0;
        assert_eq!(probe_retry(&mut state, 0, true), Ok(0));
        for attempt in 1..=7 {
            assert_eq!(probe_retry(&mut state, 0, false), Ok(attempt));
            assert_eq!(
                probe_retry(&mut state, 1, false),
                Ok(if attempt < 7 { 1500 } else { 0 })
            );
        }
        assert_eq!(probe_retry(&mut state, 0, false), Ok(0));
        assert_eq!(probe_retry(&mut state, 1, true), Ok(-1));
    }
}
impl ProbeLine {
    pub fn feed(&mut self, byte: i32) -> std::result::Result<Option<Value>, &'static str> {
        if byte < 0 {
            return Ok(Some(array([if self.bytes.is_empty() {
                Value::Null
            } else {
                Value::Data(std::mem::take(&mut self.bytes))
            }])));
        }
        if byte == 10 {
            if self.bytes.last() == Some(&13) {
                self.bytes.pop();
            }
            return Ok(Some(array([Value::Data(std::mem::take(&mut self.bytes))])));
        }
        if self.bytes.len() >= 8192 {
            return Err("AirPlay control probe status line over 8192 bytes");
        }
        self.bytes.push(byte as u8);
        Ok(None)
    }
}
