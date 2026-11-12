use super::*;

pub(super) const FEATURE_BITS: [i32; 2] = [0, 64];

pub(super) fn features_ex(big_endian: &[u8]) -> Value {
    let mut bytes: Vec<u8> = big_endian.iter().rev().copied().collect();
    while bytes.last() == Some(&0) {
        bytes.pop();
    }
    const ALPHABET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut encoded = String::new();
    for chunk in bytes.chunks(3) {
        let bits = (u32::from(chunk[0]) << 16)
            | (u32::from(*chunk.get(1).unwrap_or(&0)) << 8)
            | u32::from(*chunk.get(2).unwrap_or(&0));
        encoded.push(ALPHABET[(bits >> 18 & 63) as usize] as char);
        encoded.push(ALPHABET[(bits >> 12 & 63) as usize] as char);
        encoded.push(if chunk.len() > 1 {
            ALPHABET[(bits >> 6 & 63) as usize] as char
        } else {
            '='
        });
        encoded.push(if chunk.len() > 2 {
            ALPHABET[(bits & 63) as usize] as char
        } else {
            '='
        });
    }
    text(&encoded)
}
pub(super) fn info(features: Value, allowed: bool) -> Value {
    dict([
        ("videoPlaybackAllowed", Value::Bool(allowed)),
        ("featuresEx", features),
        (
            "playbackCapabilities",
            dict([
                ("supportsOfflineHLS", Value::Bool(false)),
                ("supportsAirPlayVideoWithSharePlay", Value::Bool(false)),
                ("supportsInterstitials", Value::Bool(false)),
                ("supportsIntegratedTimeline", Value::Bool(false)),
                ("supportsAVMetrics", Value::Bool(false)),
                ("supportsUIForAudioOnlyContent", Value::Bool(true)),
                ("supportsFPSSecureStop", Value::Bool(false)),
            ]),
        ),
    ])
}
fn seconds(time: &Value) -> Option<f64> {
    let value = number(get(time, "value")?)?;
    let scale = number(get(time, "timescale")?)?;
    (scale > 0.0).then_some(value / scale)
}
fn millis(seconds: f64) -> i32 {
    ((seconds * 1000.0) as i64).clamp(0, i64::from(i32::MAX)) as i32
}
fn cm_time(seconds: f64) -> Value {
    dict([
        ("value", long(java_round(seconds * 1000.0))),
        ("timescale", long(1000)),
        ("flags", long(1)),
        ("epoch", long(0)),
    ])
}
fn range(start: f64, duration: f64) -> Value {
    dict([("start", cm_time(start)), ("duration", cm_time(duration))])
}
fn strip(value: Value) -> Value {
    match value {
        Value::Dict(entries) => Value::Dict(
            entries
                .into_iter()
                .filter(|(_, value)| !matches!(value, Value::Null))
                .map(|(key, value)| (key, strip(value)))
                .collect(),
        ),
        Value::Array(values) => array(
            values
                .into_iter()
                .filter(|value| !matches!(value, Value::Null))
                .map(strip),
        ),
        value => value,
    }
}
struct Player {
    prepared: bool,
    playing: bool,
    position: f64,
    duration: f64,
    buffered: f64,
}
impl Player {
    fn read(value: &Value) -> Result<Self> {
        let [prepared, playing, position, duration, buffered] = list(value)? else {
            return Err("Invalid AirPlay player facts");
        };
        Ok(Self {
            prepared: boolean(prepared)?,
            playing: boolean(playing)?,
            position: number(position).ok_or("Invalid AirPlay player position")?,
            duration: number(duration).ok_or("Invalid AirPlay player duration")?,
            buffered: number(buffered).ok_or("Invalid AirPlay player buffer")?,
        })
    }
}

pub(super) fn message(
    operation: i32,
    args: &Value,
    mut features: impl FnMut(i64) -> Result<Value>,
) -> Result<Value> {
    let args = list(args)?;
    Ok(match (operation, args) {
        (0, [legacy, allowed]) => info(
            features(match legacy {
                Value::Int(n) => *n as i64,
                _ => return Err("Invalid AirPlay feature bits"),
            })?,
            boolean(allowed)?,
        ),
        (1, [message, playable]) => {
            if !boolean(playable)? {
                return Ok(Value::Null);
            }
            let Some(item) = get(message, "item") else {
                return Ok(Value::Null);
            };
            let Some(url @ Value::String(_)) = get(item, "Content-Location") else {
                return Ok(Value::Null);
            };
            let start = get(item, "Start-Position-Seconds")
                .and_then(number)
                .or_else(|| get(item, "Start-Position").and_then(seconds));
            array([
                get(item, "uuid").cloned().unwrap_or(Value::Null),
                url.clone(),
                int(start.map_or(0, millis)),
            ])
        }
        (2, [message]) => get(message, "time")
            .and_then(seconds)
            .map(|n| int(millis(n)))
            .unwrap_or(Value::Null),
        (3, [id, uuid, state]) => {
            let state = optional(state, Player::read)?;
            let ready = state.as_ref().is_some_and(|s| s.prepared);
            let playing = state.as_ref().is_some_and(|s| s.playing);
            let mut info = dict([
                ("item", dict([("uuid", uuid.clone())])),
                ("rate", Value::Double(if playing { 1.0 } else { 0.0 })),
                ("readyToPlay", Value::Bool(ready)),
                ("playbackLikelyToKeepUp", Value::Bool(ready)),
            ]);
            if let Some(state) = state.as_ref().filter(|_| ready) {
                put(&mut info, "position", cm_time(state.position));
                put(&mut info, "duration", cm_time(state.duration));
                put(
                    &mut info,
                    "seekableTimeRanges",
                    array([range(0.0, state.duration)]),
                );
                put(
                    &mut info,
                    "loadedTimeRanges",
                    array([range(0.0, state.buffered)]),
                );
            }
            for name in [
                "droppedVideoFrames",
                "totalVideoFrames",
                "decodedFrameCount",
            ] {
                put(&mut info, name, long(0));
            }
            put(
                &mut info,
                "playbackState",
                text(if !ready {
                    "loading"
                } else if playing {
                    "playing"
                } else {
                    "paused"
                }),
            );
            put(&mut info, "interstitialInfo", dict([]));
            strip(dict([
                ("info", info),
                ("kind", text("response")),
                ("type", text("playbackInfo")),
                ("messageID", id.clone()),
            ]))
        }
        (4, [id]) => strip(dict([
            ("type", text("seek")),
            ("kind", text("response")),
            ("messageID", id.clone()),
        ])),
        (5, [id, key, state]) => {
            let state = optional(state, Player::read)?;
            let ready = state.as_ref().filter(|s| s.prepared);
            let value = if key == &text("hasEnabledAudio") {
                Value::Bool(true)
            } else if key == &text("muted") {
                Value::Bool(false)
            } else if key == &text("seekableTimeRanges") {
                ready
                    .map(|s| array([range(0.0, s.duration)]))
                    .unwrap_or(Value::Null)
            } else if key == &text("loadedTimeRanges") {
                ready
                    .map(|s| array([range(0.0, s.buffered)]))
                    .unwrap_or(Value::Null)
            } else {
                Value::Null
            };
            strip(dict([
                ("key", key.clone()),
                ("value", value),
                ("kind", text("response")),
                ("type", text("property")),
                ("messageID", id.clone()),
            ]))
        }
        (6, [uuid, code]) => strip(dict([
            ("type", text("error")),
            (
                "error",
                dict([
                    ("domain", text("")),
                    ("code", long(i64::from(integer(code)?))),
                ]),
            ),
            ("uuid", uuid.clone()),
        ])),
        (7, [playing, uuid]) => {
            if boolean(playing)? {
                strip(dict([
                    ("type", text("playbackState")),
                    ("name", text("playing")),
                    ("item", dict([("uuid", uuid.clone())])),
                ]))
            } else {
                dict([("type", text("playbackState")), ("name", text("paused"))])
            }
        }
        (8, [seconds]) => cm_time(number(seconds).ok_or("Invalid AirPlay CMTime")?),
        (9, [legacy]) => features(match legacy {
            Value::Int(n) => *n as i64,
            _ => return Err("Invalid AirPlay feature bits"),
        })?,
        _ => return Err("Invalid AirPlay video operation"),
    })
}

pub(super) fn availability(token: &mut [i64; 4], event: i32, value: bool) -> Result<i32> {
    let [desired, enabled, ready, last] = *token;
    if !(-1..=1).contains(&desired)
        || !(0..=1).contains(&enabled)
        || !(0..=1).contains(&ready)
        || !(-1..=1).contains(&last)
    {
        return Err("Invalid AirPlay availability token");
    }
    let value = i64::from(value);
    match event {
        0 => token[0] = value,
        1 | 2 => {
            let index = event as usize;
            if token[index] != value {
                token[index] = value;
                if value == 0 {
                    token[3] = -1;
                }
            }
        }
        3 => token[3] = value,
        _ => return Err("Invalid AirPlay availability event"),
    }
    Ok(if token[0] < 0 || token[1] == 0 || token[2] == 0 {
        -1
    } else if token[3] == token[0] {
        -2
    } else {
        token[0] as i32
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn capability_and_availability_contracts() {
        assert_eq!(
            features_ex(&[1, 0, 0, 0, 0x61, 0x56, 0x53, 0xae, 0xe3]),
            text("465TVmEAAAAB")
        );
        assert_eq!(features_ex(&[255]), text("/w=="));
        assert_eq!(
            features_ex(&[255, 0x7f, 255, 255, 255, 0, 0, 0, 1]),
            text("AQAAAP///3//")
        );
        assert_eq!(
            features_ex(&[0x80, 0, 0, 0, 0, 0, 0, 1]),
            text("AQAAAAAAAIA=")
        );
        let mut token = [-1, 0, 0, -1];
        assert_eq!(availability(&mut token, 0, true), Ok(-1));
        assert_eq!(availability(&mut token, 1, true), Ok(-1));
        assert_eq!(availability(&mut token, 2, true), Ok(1));
        assert_eq!(availability(&mut token, 3, true), Ok(-2));
        assert_eq!(availability(&mut token, 2, false), Ok(-1));
        assert_eq!(availability(&mut token, 2, true), Ok(1));
        assert_eq!(java_round(-0.5), 0);
        assert_eq!(java_round(-1.5), -1);
    }
}
