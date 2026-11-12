//! CarPlay control framing, preserving RtspMessage's wire behavior and receive limits.
use std::collections::HashMap;

pub const MAX_HEADER_BYTES: usize = 16 * 1024;
pub const MAX_BODY_BYTES: usize = 1024 * 1024;

#[derive(Debug, PartialEq)]
pub struct Request<'a> {
    pub method: String,
    pub path: String,
    pub protocol: String,
    pub headers: HashMap<String, String>,
    pub body: &'a [u8],
}

#[derive(Debug, PartialEq)]
pub struct Parsed<'a> { pub messages: Vec<Request<'a>>, pub rest: &'a [u8] }

fn trim(value: &str) -> &str {
    value.trim_matches(|c| matches!(c, '\u{0009}'..='\u{000d}' | '\u{001c}'..='\u{0020}'))
}

pub fn parse(buffer: &[u8]) -> Result<Parsed<'_>, String> {
    let mut messages = Vec::new();
    let mut offset = 0;
    while offset < buffer.len() {
        let remaining = &buffer[offset..];
        let end = remaining.windows(4).position(|part| part == b"\r\n\r\n");
        let Some(end) = end.filter(|&end| end <= MAX_HEADER_BYTES) else {
            if remaining.len() > MAX_HEADER_BYTES {
                return Err(format!("RTSP header over {MAX_HEADER_BYTES} bytes"));
            }
            break;
        };
        // Java US_ASCII replaces high bytes; masking their high bit would change methods/headers.
        let head: String = remaining[..end].iter().map(|&b| if b < 128 { b as char } else { '\u{fffd}' }).collect();
        let mut lines = head.split("\r\n");
        let mut request_line = lines.next().unwrap_or("").splitn(3, ' ');
        let method = request_line.next().unwrap_or("").to_string();
        let path = request_line.next().unwrap_or("").to_string();
        let protocol = request_line.next().unwrap_or("RTSP/1.0").to_string();
        let mut headers = HashMap::new();
        for line in lines {
            if let Some((key, value)) = line.split_once(':') {
                headers.insert(trim(key).to_ascii_lowercase(), trim(value).to_string());
            }
        }
        let length = headers.get("content-length").and_then(|s| s.parse::<i32>().ok()).unwrap_or(0).max(0) as usize;
        if length > MAX_BODY_BYTES { return Err(format!("RTSP body of {length} bytes over {MAX_BODY_BYTES}")); }
        let body_start = offset + end + 4;
        let body_end = body_start + length;
        if body_end > buffer.len() { break; }
        messages.push(Request { method, path, protocol, headers, body: &buffer[body_start..body_end] });
        offset = body_end;
    }
    Ok(Parsed { messages, rest: &buffer[offset..] })
}

pub fn response(protocol: &str, status: i32, reason: Option<&str>, cseq: Option<&str>,
    mut headers: Vec<(String, String)>, body: &[u8]) -> Vec<u8> {
    let reason = reason.unwrap_or(match status {
        400 => "Bad Request", 404 => "Not Found", 500 => "Internal Server Error", _ => "OK",
    });
    for (name, value) in cseq.map(|value| ("CSeq", value.to_string())).into_iter()
        .chain(std::iter::once(("Content-Length", body.len().to_string()))) {
        if let Some(entry) = headers.iter_mut().find(|(key, _)| key == name) { entry.1 = value; }
        else { headers.push((name.to_string(), value)); }
    }
    let mut head = format!("{protocol} {status} {reason}\r\n");
    for (key, value) in headers { head.push_str(&key); head.push_str(": "); head.push_str(&value); head.push_str("\r\n"); }
    head.push_str("\r\n");
    let mut wire: Vec<u8> = head.chars().map(|c| if c.is_ascii() { c as u8 } else { b'?' }).collect();
    wire.extend_from_slice(body);
    wire
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn merged_and_fragmented_requests_keep_the_exact_unfinished_remainder() {
        let first = b"POST /pair-verify RTSP/1.0\r\nCSeq: 3\r\nContent-Length: 4\r\n\r\nbody";
        let second = b"OPTIONS *\r\n\r\n";
        let wire = [first.as_slice(), second.as_slice(), b"GET /in"].concat();
        for split in 0..=first.len() {
            let partial = parse(&wire[..split]).unwrap();
            let next = [partial.rest, &wire[split..]].concat();
            let parsed = parse(&next).unwrap();
            assert_eq!(partial.messages.len() + parsed.messages.len(), 2);
            assert_eq!(parsed.rest, b"GET /in");
        }
        let parsed = parse(&wire).unwrap();
        assert_eq!(parsed.messages[0].body, b"body");
        assert_eq!(parsed.messages[0].headers["cseq"], "3");
        assert_eq!(parsed.messages[1].protocol, "RTSP/1.0");
    }

    #[test]
    fn current_header_duplicates_ascii_replacement_and_content_length_rules_are_preserved() {
        let wire = b"GET  /info RTSP/1.0\r\nX : old\r\nx:\x1cnew\x1f\r\n\xff: \xff\r\nContent-Length: 12abc\r\n\r\n";
        let parsed = parse(wire).unwrap();
        let req = &parsed.messages[0];
        assert_eq!(req.path, "");
        assert_eq!(req.protocol, "/info RTSP/1.0");
        assert_eq!(req.headers["x"], "new");
        assert_eq!(req.headers["\u{fffd}"], "\u{fffd}");
        assert!(req.body.is_empty());
        for length in ["-2", "18446744073709551615", "0x10"] {
            assert_eq!(parse(format!("POST / RTSP/1.0\r\nContent-Length: {length}\r\n\r\n").as_bytes()).unwrap().messages.len(), 1);
        }
        assert_eq!(parse(b"POST / RTSP/1.0\r\nContent-Length: +2\r\n\r\nab").unwrap().messages[0].body, b"ab");
    }

    #[test]
    fn peer_limits_are_checked_before_waiting_for_more_body_or_header_bytes() {
        assert!(parse(&vec![b'x'; MAX_HEADER_BYTES]).unwrap().messages.is_empty());
        assert!(parse(&vec![b'x'; MAX_HEADER_BYTES + 1]).is_err());
        assert!(parse(b"POST / RTSP/1.0\r\nContent-Length: 1500000000\r\n\r\n").is_err());
        assert!(parse(b"POST / RTSP/1.0\r\nContent-Length: 1048576\r\n\r\npartial").unwrap().messages.is_empty());
    }

    #[test]
    fn responses_keep_order_replace_exact_header_names_and_match_the_standard_golden_wire() {
        let headers = vec![("Content-Type".into(), "application/x-apple-binary-plist".into()), ("CSeq".into(), "0".into())];
        assert_eq!(response("RTSP/1.0", 200, None, Some("5"), headers, b"ab"),
            b"RTSP/1.0 200 OK\r\nContent-Type: application/x-apple-binary-plist\r\nCSeq: 5\r\nContent-Length: 2\r\n\r\nab");
        assert_eq!(response("RTSP/1.0", 500, None, Some("5"), Vec::new(), b""),
            b"RTSP/1.0 500 Internal Server Error\r\nCSeq: 5\r\nContent-Length: 0\r\n\r\n");
        assert_eq!(response("HTTP/1.1", 470, Some("测试😀"), None, vec![("a".into(), "\0".into())], b"\xff"),
            b"HTTP/1.1 470 ???\r\na: \0\r\nContent-Length: 1\r\n\r\n\xff");
    }
}
