use super::*;

pub(super) const MAIN_UUID: &str = "b7e6c5a0-1111-4000-8000-000000000001";
const ALT_UUID: &str = "b7e6c5a0-2222-4000-8000-000000000002";
pub(super) fn features(disable_audio: bool) -> i64 {
    let all = 0x615653aee2i64;
    if disable_audio {
        all & !0x10004540a00
    } else {
        all
    }
}
pub(super) fn buffered_enabled(buffered: bool, disable_audio: bool) -> bool {
    buffered && !disable_audio
}

#[derive(Clone, Copy, Default)]
struct Insets {
    top: i32,
    bottom: i32,
    left: i32,
    right: i32,
}
impl Insets {
    fn read(value: &Value) -> Result<Self> {
        let values = list(value)?;
        let [top, bottom, left, right] = values else {
            return Err("Invalid AirPlay insets");
        };
        Ok(Self {
            top: integer(top)?,
            bottom: integer(bottom)?,
            left: integer(left)?,
            right: integer(right)?,
        })
    }
}
struct Area {
    width: i32,
    height: i32,
    x: i32,
    y: i32,
    dock: Option<i32>,
}
impl Area {
    fn read(value: &Value) -> Result<Self> {
        let [width, height, x, y, dock] = list(value)? else {
            return Err("Invalid AirPlay view area");
        };
        Ok(Self {
            width: integer(width)?,
            height: integer(height)?,
            x: integer(x)?,
            y: integer(y)?,
            dock: optional(dock, integer)?,
        })
    }
}
struct Display {
    width: i32,
    height: i32,
    width_mm: Option<i32>,
    height_mm: Option<i32>,
    fps: i32,
    input: i32,
    view: Option<Insets>,
    safe: Option<Insets>,
    draw_outside: Option<bool>,
    url: Option<Vec<u16>>,
    features: Option<i32>,
    areas: Option<Vec<Area>>,
    initial: i32,
    corner_masks: Option<bool>,
    disable_safe: bool,
}
impl Display {
    fn read(value: &Value) -> Result<Self> {
        Ok(Self {
            width: integer(required(value, "widthPixels")?)?,
            height: integer(required(value, "heightPixels")?)?,
            width_mm: optional(required(value, "widthPhysicalMm")?, integer)?,
            height_mm: optional(required(value, "heightPhysicalMm")?, integer)?,
            fps: integer(required(value, "fps")?)?,
            input: integer(required(value, "primaryInputDevice")?)?,
            view: optional(required(value, "viewArea")?, Insets::read)?,
            safe: optional(required(value, "safeArea")?, Insets::read)?,
            draw_outside: optional(required(value, "safeAreaDrawOutside")?, boolean)?,
            url: optional(required(value, "initialUrl")?, string)?,
            features: optional(required(value, "features")?, integer)?,
            areas: optional(required(value, "viewAreas")?, |v| {
                list(v)?.iter().map(Area::read).collect()
            })?,
            initial: integer(required(value, "initialViewArea")?)?,
            corner_masks: optional(required(value, "mainDisplayCornerMasks")?, boolean)?,
            disable_safe: boolean(required(value, "mainDisplayDisableSafeArea")?)?,
        })
    }
    fn area(&self, area: Option<&Area>, disable_safe: bool) -> Value {
        let view = area
            .map(|a| Insets {
                top: a.y,
                bottom: self.height.wrapping_sub(a.y).wrapping_sub(a.height),
                left: a.x,
                right: self.width.wrapping_sub(a.x).wrapping_sub(a.width),
            })
            .unwrap_or(self.view.unwrap_or_default());
        let rect = |insets: Insets| {
            dict([
                (
                    "widthPixels",
                    int(self
                        .width
                        .wrapping_sub(insets.left)
                        .wrapping_sub(insets.right)),
                ),
                (
                    "heightPixels",
                    int(self
                        .height
                        .wrapping_sub(insets.top)
                        .wrapping_sub(insets.bottom)),
                ),
                ("originXPixels", int(insets.left)),
                ("originYPixels", int(insets.top)),
            ])
        };
        let mut value = rect(view);
        if let Some(dock) = area.and_then(|a| a.dock) {
            put(&mut value, "viewAreaStatusBarEdge", int(dock));
        }
        if !disable_safe {
            let safe = self.safe.unwrap_or_default();
            let clipped = if area.is_none() {
                safe
            } else {
                Insets {
                    top: safe.top.max(view.top),
                    bottom: safe.bottom.max(view.bottom),
                    left: safe.left.max(view.left),
                    right: safe.right.max(view.right),
                }
            };
            let safe = if area.is_some()
                && (clipped.left.wrapping_add(clipped.right) >= self.width
                    || clipped.top.wrapping_add(clipped.bottom) >= self.height)
            {
                view
            } else {
                clipped
            };
            let mut safe = rect(safe);
            put(
                &mut safe,
                "drawUIOutsideSafeArea",
                Value::Bool(self.draw_outside.unwrap_or(true)),
            );
            put(&mut value, "safeArea", safe);
        }
        value
    }
    fn entry(&self, main: bool) -> Value {
        let width_mm = self.width_mm.unwrap_or(200).clamp(1, 2000);
        let height_mm = self
            .height_mm
            .unwrap_or_else(|| {
                (java_round(f64::from(width_mm) * f64::from(self.height) / f64::from(self.width)))
                    as i32
            })
            .clamp(1, 2000);
        let fps = ((self.fps.clamp(30, 60) - 30 + 2) / 5) * 5 + 30;
        let mut value = dict([
            ("uuid", text(if main { MAIN_UUID } else { ALT_UUID })),
            ("type", int(if main { 110 } else { 111 })),
            ("maxFPS", int(fps)),
            ("widthPixels", int(self.width)),
            ("heightPixels", int(self.height)),
            ("widthPhysical", int(width_mm)),
            ("heightPhysical", int(height_mm)),
            ("features", int(self.features.unwrap_or(0x0a))),
            ("primaryInputDevice", int(self.input)),
            ("uiAppearanceMode", int(0)),
            ("uiAppearanceSetting", int(0)),
            ("mapAppearanceMode", int(0)),
            ("mapAppearanceSetting", int(0)),
        ]);
        let areas = self.areas.as_deref().filter(|areas| !areas.is_empty());
        let disable_safe = main && self.disable_safe;
        put(
            &mut value,
            "viewAreas",
            match areas {
                Some(areas) => array(areas.iter().map(|area| self.area(Some(area), disable_safe))),
                None => array([self.area(None, disable_safe)]),
            },
        );
        put(
            &mut value,
            "initialViewArea",
            int(areas.map_or(0, |areas| self.initial.clamp(0, areas.len() as i32 - 1))),
        );
        if main {
            if let Some(corner) = self.corner_masks {
                put(&mut value, "cornerMasks", Value::Bool(corner));
            }
        }
        if areas.is_some_and(|areas| areas.len() > 1) {
            put(&mut value, "viewAreaTransitionControl", Value::Bool(true));
        }
        if let Some(url) = &self.url {
            put(&mut value, "initialURL", Value::String(url.clone()));
        }
        value
    }
}

pub(super) fn build(
    config: &Value,
    mut video_features: impl FnMut(i64) -> Result<Value>,
) -> Result<Value> {
    let main = Display::read(required(config, "main")?)?;
    let cluster = optional(required(config, "cluster")?, Display::read)?;
    let disable_audio = boolean(required(config, "disableAudioOutput")?)?;
    let rate = integer(required(config, "entertainmentSampleRate")?)?;
    let microphone = boolean(required(config, "microphone")?)?;
    let buffered = boolean(required(config, "mainBufferedAudio")?)?;
    let opus_input = boolean(required(config, "microphoneOpus")?)?;
    let speech_restricted = boolean(required(config, "speechWithout8And16Khz")?)?;
    let call_rate = boolean(required(config, "callAtCallRate")?)?;
    let hevc = boolean(required(config, "hevc")?)?;
    let video = boolean(required(config, "videoInCar")?)?;
    let allowed = boolean(required(config, "videoAllowed")?)?;
    let icons = list(required(config, "icons")?)?;
    let hid = list(required(config, "hidDevices")?)?;
    let resource = |id| {
        dict([
            ("resourceID", int(id)),
            ("transferType", int(1)),
            ("transferPriority", int(100)),
            ("takeConstraint", int(100)),
            ("borrowConstraint", int(100)),
            ("unborrowConstraint", int(100)),
        ])
    };
    let mut value = dict([
        (
            "sourceVersion",
            Value::String(string(required(config, "sourceVersion")?)?),
        ),
        ("features", long(features(disable_audio))),
        ("statusFlags", long(4)),
        ("model", Value::String(string(required(config, "model")?)?)),
        (
            "manufacturer",
            Value::String(string(required(config, "manufacturer")?)?),
        ),
        (
            "deviceID",
            Value::String(string(required(config, "deviceId")?)?),
        ),
        (
            "bluetoothIDs",
            array([Value::String(string(required(config, "btMac")?)?)]),
        ),
        (
            "name",
            Value::String(string(required(config, "deviceName")?)?),
        ),
        (
            "rightHandDrive",
            Value::Bool(boolean(required(config, "rightHandDrive")?)?),
        ),
        ("keepAliveLowPower", Value::Bool(false)),
        ("keepAliveSendStatsAsBody", Value::Bool(false)),
        (
            "modes",
            dict([
                ("resources", array([resource(1), resource(2)])),
                (
                    "appStates",
                    array([
                        dict([("appStateID", int(2)), ("state", Value::Bool(false))]),
                        dict([("appStateID", int(1)), ("speechMode", int(-1))]),
                        dict([("appStateID", int(3)), ("state", Value::Bool(false))]),
                    ]),
                ),
            ]),
        ),
    ]);
    if !disable_audio {
        let latency = |kind, name: Option<&str>| {
            let mut value = dict([
                ("type", int(kind)),
                ("inputLatencyMicros", long(0)),
                ("outputLatencyMicros", long(0)),
            ]);
            if let Some(name) = name {
                put(&mut value, "audioType", text(name));
            }
            value
        };
        put(
            &mut value,
            "audioLatencies",
            array(
                [
                    (100, None),
                    (100, Some("default")),
                    (100, Some("media")),
                    (100, Some("telephony")),
                    (100, Some("speechRecognition")),
                    (100, Some("alert")),
                    (101, None),
                    (101, Some("default")),
                    (102, Some("default")),
                ]
                .map(|(kind, name)| latency(kind, name)),
            ),
        );
        let pcm = 0x3fc | if rate == 48000 { 0xc000 } else { 0xc00 };
        let speech = if speech_restricted { pcm & !0x3c } else { pcm };
        let mono = 0x154 | if rate == 48000 { 0x4000 } else { 0x400 };
        let opus = 0x70000000;
        let aac = if rate == 48000 { 0x800000 } else { 0x400000 };
        let input = microphone.then_some(mono);
        let wireless_input = microphone.then_some(if opus_input { mono | opus } else { mono });
        let call_input = if call_rate && microphone {
            Some(0x154)
        } else {
            wireless_input
        };
        let format = |kind, name: &str, output, input: Option<i32>| {
            let mut value = dict([
                ("type", int(kind)),
                ("audioType", text(name)),
                ("audioOutputFormats", int(output)),
            ]);
            if let Some(input) = input {
                put(&mut value, "audioInputFormats", int(input));
            }
            value
        };
        let mut formats = vec![
            format(100, "compatibility", pcm, input),
            format(101, "compatibility", pcm, None),
            format(100, "default", speech | opus, wireless_input),
            format(100, "alert", speech | opus, None),
            format(100, "media", pcm, None),
            format(
                100,
                "telephony",
                if call_rate { 0x154 } else { mono | opus },
                call_input,
            ),
            format(100, "speechRecognition", mono | opus, wireless_input),
            format(101, "default", speech | opus, None),
            format(102, "media", aac, None),
        ];
        // iOS 27 opens buffered music with AAC-LC, not PCM or ALAC.
        if buffered {
            formats.push(format(103, "media", aac, None));
        }
        put(&mut value, "audioFormats", array(formats));
    }
    put(
        &mut value,
        "extendedFeatures",
        array([text("vocoderInfo"), text("enhancedRequestCarUI")]),
    );
    let mut displays = vec![main.entry(true)];
    if let Some(cluster) = cluster {
        displays.push(cluster.entry(false));
    }
    put(&mut value, "displays", array(displays));
    put(&mut value, "hidDevices", array(hid.iter().cloned()));
    if !icons.is_empty() {
        put(&mut value, "oemIconVisible", Value::Bool(true));
        put(
            &mut value,
            "oemIconLabel",
            Value::String(string(required(config, "oemLabel")?)?),
        );
        let icons = icons
            .iter()
            .map(|icon| -> Result<Value> {
                let data = required(icon, "data")?;
                if !matches!(data, Value::Data(_)) {
                    return Err("Invalid AirPlay icon data");
                }
                Ok(dict([
                    ("imageData", data.clone()),
                    ("widthPixels", int(integer(required(icon, "widthPixels")?)?)),
                    (
                        "heightPixels",
                        int(integer(required(icon, "heightPixels")?)?),
                    ),
                    ("prerendered", Value::Bool(true)),
                ]))
            })
            .collect::<Result<Vec<_>>>()?;
        put(&mut value, "oemIcons", array(icons));
    }
    if hevc {
        put(&mut value, "hevcInfo", dict([]));
    }
    if buffered_enabled(buffered, disable_audio) {
        put(&mut value, "mainBufferedInfo", dict([]));
    }
    if video {
        put(
            &mut value,
            "videoPlaybackInfo",
            super::video::info(video_features(features(disable_audio))?, allowed),
        );
    }
    Ok(value)
}

pub(super) fn view_area(index: i32, count: i32) -> Value {
    dict([
        ("type", text("updateViewArea")),
        (
            "params",
            dict([
                ("uuid", text(MAIN_UUID)),
                ("viewAreaIndex", int(index)),
                ("animationDurationMillis", int(300)),
                (
                    "adjacentViewAreas",
                    array((0..count).filter(|i| *i != index).map(int)),
                ),
            ]),
        ),
    ])
}
