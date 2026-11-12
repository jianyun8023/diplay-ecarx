use super::*;

fn hex_uid(uid: i32) -> String {
    if uid < 0 {
        format!("-{:x}", uid.unsigned_abs())
    } else {
        format!("{uid:x}")
    }
}

pub(super) fn command(operation: i32, arguments: &Value) -> Result<Value> {
    let args = list(arguments)?;
    let params = |kind: &str, params| dict([("type", text(kind)), ("params", params)]);
    Ok(match (operation, args) {
        (0, [allowed]) => params(
            "setVideoPlaybackAllowed",
            dict([("videoPlaybackAllowed", Value::Bool(boolean(allowed)?))]),
        ),
        (1, [uuid]) => params("stopUI", dict([("uuid", Value::String(string(uuid)?))])),
        (2, [uuid, url]) => params(
            "showUI",
            dict([
                ("uuid", Value::String(string(uuid)?)),
                ("url", Value::String(string(url)?)),
            ]),
        ),
        (3, [uuid]) => {
            if matches!(uuid, Value::Null) {
                dict([("type", text("forceKeyFrame"))])
            } else {
                params(
                    "forceKeyFrame",
                    dict([("uuid", Value::String(string(uuid)?))]),
                )
            }
        }
        (4, [zoom_in]) => params(
            "changeMapZoomLevel",
            dict([
                ("uuid", text("b7e6c5a0-2222-4000-8000-000000000002")),
                ("zoomDirection", int(if boolean(zoom_in)? { 0 } else { 1 })),
            ]),
        ),
        (5, [action]) => params("requestSiri", dict([("siriAction", int(integer(action)?))])),
        (6, [claim]) => {
            let claim = boolean(claim)?;
            params(
                "changeModes",
                dict([
                    (
                        "appStates",
                        array([dict([
                            ("appStateID", int(3)),
                            ("state", Value::Bool(claim)),
                        ])]),
                    ),
                    (
                        "reasonStr",
                        text(if claim {
                            "User requested navigation exit"
                        } else {
                            "Navigation exit arbitration complete"
                        }),
                    ),
                ]),
            )
        }
        (7, [data]) | (10, [data]) => {
            if !matches!(data, Value::Data(_)) {
                return Err("Invalid AirPlay command data");
            }
            if operation == 7 {
                params("iAPSendMessage", dict([("data", data.clone())]))
            } else {
                dict([("params", dict([("data", data.clone())]))])
            }
        }
        (8, [night]) => params(
            "setNightMode",
            dict([("nightMode", Value::Bool(boolean(night)?))]),
        ),
        (9, [uid, report]) => {
            let uid = integer(uid)?;
            if !matches!(report, Value::Data(_)) {
                return Err("Invalid AirPlay HID report");
            }
            let uid = hex_uid(uid);
            dict([
                ("type", text("hidSendReport")),
                ("uuid", text(&uid)),
                ("hidReport", report.clone()),
            ])
        }
        (11, [timing, event, keep_alive, features]) => {
            let mut response = dict([
                ("timingPort", int(integer(timing)?)),
                ("eventPort", int(integer(event)?)),
            ]);
            if let Some(port) = optional(keep_alive, integer)? {
                put(&mut response, "keepAlivePort", int(port));
            }
            put(
                &mut response,
                "enabledFeatures",
                array(list(features)?.iter().cloned()),
            );
            response
        }
        (12, [value]) => Value::Bool(value == &Value::Bool(true) || value == &long(1)),
        (13, [kind, port]) => dict([
            ("type", int(integer(kind)?)),
            ("dataPort", int(integer(port)?)),
        ]),
        (14, [streams]) => dict([("streams", array(list(streams)?.iter().cloned()))]),
        (15, [kind, params]) => {
            let kind = string(kind)?;
            let is = |name: &str| kind.iter().copied().eq(name.encode_utf16());
            let noisy = ["modesChanged", "duckAudio", "unduckAudio", "suggestUI"]
                .iter()
                .any(|name| is(name));
            let duck = if is("duckAudio") {
                Value::Bool(true)
            } else if is("unduckAudio") {
                Value::Bool(false)
            } else {
                Value::Null
            };
            let ui = if !is("requestUI") {
                0
            } else if get(params, "url") == Some(&text("videoplayback:")) {
                2
            } else {
                1
            };
            let to_int = |value: &Value| match value {
                Value::Int32(n) => Some(*n),
                Value::Int(n) => Some(*n as i64 as i32),
                Value::Float(n) => Some(*n as i32),
                Value::Double(n) => Some(*n as i32),
                _ => None,
            };
            let entity = if is("modesChanged") {
                get(params, "appStates")
                    .and_then(|value| {
                        if let Value::Array(values) = value {
                            Some(values)
                        } else {
                            None
                        }
                    })
                    .and_then(|values| {
                        values
                            .iter()
                            .find(|value| get(value, "appStateID").and_then(to_int) == Some(3))
                    })
                    .and_then(|value| get(value, "entity"))
                    .and_then(to_int)
            } else {
                None
            };
            array([
                Value::Bool(noisy),
                duck,
                int(ui),
                entity.map(int).unwrap_or(Value::Null),
            ])
        }
        (16, [uid, name, descriptor, display]) => {
            if !matches!(descriptor, Value::Data(_)) {
                return Err("Invalid AirPlay HID descriptor");
            }
            dict([
                ("hidProductID", long(1)),
                ("hidVendorID", long(2)),
                ("hidCountryCode", long(0)),
                ("uuid", text(&hex_uid(integer(uid)?))),
                ("name", Value::String(string(name)?)),
                ("displayUUID", Value::String(string(display)?)),
                ("hidDescriptor", descriptor.clone()),
            ])
        }
        (17, [sequence, size, stream]) => {
            let sequence = integer(sequence)?;
            let size = integer(size)?;
            let stream = optional(stream, |value| {
                if let Value::Int(n) = value {
                    Ok(*n as i64)
                } else {
                    Err("Invalid AirPlay stream id")
                }
            })?;
            let mut headers = Vec::new();
            if let Some(stream) = stream {
                headers.push(("X-Apple-StreamID".into(), stream.to_string()));
            }
            headers.extend([
                (
                    "Content-Type".into(),
                    "application/x-apple-binary-plist".into(),
                ),
                ("Content-Length".into(), size.to_string()),
                ("CSeq".into(), sequence.to_string()),
            ]);
            text(&crate::protocols::rtsp::header(
                "POST /command RTSP/1.0",
                &headers,
            ))
        }
        _ => return Err("Invalid AirPlay command operation"),
    })
}
