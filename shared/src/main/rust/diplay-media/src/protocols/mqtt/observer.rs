pub(super) const TOPICS: [&str; 12] = [
    "dim/actions/navigation/map",
    "dim/actions/navigation/fullscreenmap",
    "dim/actions/navigation/ongoing",
    "ihu/events/navigation/fullscreenmap",
    "ihu/events/navigation/ongoing",
    "ihu/info/navigation/turn-by-turn/next-turn",
    "ihu/info/navigation/turn-by-turn/icon",
    "ihu/info/navigation/turn-by-turn/lanesupport",
    "ihu/info/phoneprojection",
    "ihu/heartbeat/navigation",
    "ihu/events/navigation/distance",
    "ihu/events/navigation/ETA",
];
pub(super) const SLOTS: usize = 1 + TOPICS.len() * 5;
pub(super) const RETRY_MS: i64 = 5000;

pub(super) fn subscribe() -> Vec<u8> {
    let mut body = vec![0, 1];
    for topic in TOPICS {
        super::string(&mut body, topic.as_bytes());
        body.push(0);
    }
    super::frame(0x82, &body)
}
pub(super) fn suback(body: &[u8]) -> Result<(usize, String), &'static str> {
    if body.len() != TOPICS.len() + 2 || body[..2] != [0, 1] {
        return Err("suback");
    }
    let denied: Vec<&str> = TOPICS
        .iter()
        .zip(&body[2..])
        .filter_map(|(&topic, &code)| (code != 0).then_some(topic))
        .collect();
    Ok((
        TOPICS.len() - denied.len(),
        if denied.is_empty() {
            "none".into()
        } else {
            denied.join(",")
        },
    ))
}
pub(super) fn publication(
    header: i32,
    body: &[u8],
) -> Result<Option<(usize, &[u8])>, &'static str> {
    if header >> 4 != 3 || header & 6 != 0 || body.len() < 2 {
        return Err("unexpected-packet");
    }
    let end = 2 + u16::from_be_bytes([body[0], body[1]]) as usize;
    if end > body.len() {
        return Err("topic-length");
    }
    // Our exact ASCII subscriptions cannot match malformed UTF-8 or another topic.
    Ok(TOPICS
        .iter()
        .position(|topic| topic.as_bytes() == &body[2..end])
        .map(|i| (i, &body[end..])))
}
pub(super) fn key(topic: &str, summary: &str) -> String {
    if topic != TOPICS[4] {
        return summary.into();
    }
    summary
        .replace("naviEvent=firstFrameReady", "naviEvent=active")
        .replace("naviEvent=ongoingNavi", "naviEvent=active")
}
pub(super) fn blank(value: &[u16]) -> bool {
    value.iter().all(|&c| matches!(c, 9..=13 | 28..=32 | 0xa0 | 0x1680 | 0x2000..=0x200a | 0x2028 | 0x2029 | 0x202f | 0x205f | 0x3000))
}
pub(super) fn summary(
    topic: usize,
    mut value: impl FnMut(&str) -> Option<Vec<u16>>,
    periodicity: i32,
    lanes: usize,
) -> String {
    let enumeration = match topic {
        0 => Some(("statusType", &["always", "routeSet", "never"][..])),
        1 => Some(("action", &["switchMap"][..])),
        2 => Some((
            "action",
            &["iconLeftClicked", "iconCenterClicked", "iconRightClicked"][..],
        )),
        3 => Some(("mapRequest", &["showMap", "hideMap", "noRequest"][..])),
        4 => Some((
            "naviEvent",
            &["ongoingNavi", "idle", "firstFrameReady", "naviAppOff"][..],
        )),
        8 => Some((
            "statusType",
            &[
                "androidAutoNavigation",
                "carPlayNavigation",
                "noProjection",
                "androidAutoMedia",
                "carPlayMedia",
            ][..],
        )),
        _ => None,
    };
    if let Some((field, allowed)) = enumeration {
        let raw = String::from_utf16_lossy(&value(field).unwrap_or_default());
        let mut out = format!(
            "{field}={}",
            if allowed.contains(&raw.as_str()) {
                &raw
            } else {
                "unknown"
            }
        );
        if topic == 4 {
            out.push_str(&format!(
                " destinationPresent={}",
                value("destination").is_some_and(|v| !blank(&v))
            ));
        }
        return out;
    }
    let mut present = |field| value(field).is_some_and(|v| !blank(&v));
    match topic {
        5 => format!(
            "distancePresent={} roadPresent={}",
            present("distance"),
            present("address")
        ),
        6 => format!(
            "iconPresent={} imagePresent={}",
            present("iconId"),
            present("imageData")
        ),
        7 => format!("lanes={lanes}"),
        9 => format!(
            "timestampPresent={} periodicity={periodicity}",
            present("timestamp")
        ),
        10 => format!("distancePresent={}", present("distance")),
        11 => format!("etaPresent={}", present("destinationTime")),
        _ => unreachable!(),
    }
}
pub(super) fn distance(value: &[u16]) -> String {
    let s = String::from_utf16_lossy(value);
    let digits = s.bytes().take_while(u8::is_ascii_digit).count();
    if !(1..=9).contains(&digits) {
        return "unknown".into();
    }
    let mut rest = &s[digits..];
    if rest.starts_with(['.', ',']) {
        rest = &rest[1..];
        let decimals = rest.bytes().take_while(u8::is_ascii_digit).count();
        if !(1..=3).contains(&decimals) {
            return "unknown".into();
        }
        rest = &rest[decimals..];
    }
    if matches!(rest, "" | "m" | "km" | " m" | " km") {
        s
    } else {
        "unknown".into()
    }
}
pub(super) fn observe(
    state: &mut [i64; SLOTS],
    topic: usize,
    previous: Option<&str>,
    summary: &str,
    retained: bool,
    now: i64,
    debug: bool,
    detail: &str,
) -> Option<String> {
    state[0] = state[0].wrapping_add(1);
    let sequence = state[0];
    let s = &mut state[1 + topic * 5..][..5]; // count, seen, reported, reported count, retained
    let count = s[0].wrapping_add(1);
    let changed = previous.is_none_or(|p| key(TOPICS[topic], p) != key(TOPICS[topic], summary))
        || s[4] != retained as i64;
    let emit = changed || now.wrapping_sub(s[2]) >= if debug { 1000 } else { 300_000 };
    let line = emit.then(|| format!("mqtt rx={sequence} count={count} suppressed={} gapMs={} retained={retained} topic={} {summary}{detail}",
        count.wrapping_sub(s[3]).wrapping_sub(1), if previous.is_some() { now.wrapping_sub(s[1]) } else { -1 }, TOPICS[topic]));
    s[0] = count;
    s[1] = now;
    s[4] = retained as i64;
    if emit {
        s[2] = now;
        s[3] = count;
    }
    line
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn allowlisted_distance_and_summary_never_return_addresses_or_images() {
        for text in ["1", "123456789", "5,123 km", "3.4m"] {
            assert_eq!(distance(&text.encode_utf16().collect::<Vec<_>>()), text);
        }
        for text in [
            "1234567890",
            "3.1234km",
            "3 m address",
            "3.",
            "٣m",
            "3 ",
            " 3m",
        ] {
            assert_eq!(
                distance(&text.encode_utf16().collect::<Vec<_>>()),
                "unknown"
            );
        }
        for index in 0..TOPICS.len() {
            let result = summary(
                index,
                |_| Some("private-address-image".encode_utf16().collect()),
                -1,
                2,
            );
            assert!(!result.contains("private-address-image"));
        }
        assert!(blank(&[0xa0, 0x1c]));
        assert!(!blank(&[0x85]));
    }
    #[test]
    fn refreshes_coalesce_but_retained_and_idle_transitions_emit_immediately() {
        let mut state = [0; SLOTS];
        let a = "naviEvent=firstFrameReady destinationPresent=true";
        let b = "naviEvent=ongoingNavi destinationPresent=true";
        assert!(observe(&mut state, 4, None, a, false, 0, false, "").is_some());
        for i in 1..1000 {
            assert!(observe(&mut state, 4, Some(a), b, false, i, false, "").is_none());
        }
        let line = observe(&mut state, 4, Some(b), a, false, 300_000, false, "").unwrap();
        assert!(line.contains("count=1001 suppressed=999 gapMs=299001"));
        assert!(observe(&mut state, 4, Some(a), b, true, 300_001, false, "").is_some());
        assert!(observe(
            &mut state,
            4,
            Some(b),
            "naviEvent=idle destinationPresent=true",
            true,
            300_002,
            false,
            ""
        )
        .is_some());
        assert!(publication(0x32, &[0, 0]).is_err());
        assert!(publication(0x30, &[0, 10]).is_err());
        assert_eq!(suback(&[0, 2]), Err("suback"));
    }
}
