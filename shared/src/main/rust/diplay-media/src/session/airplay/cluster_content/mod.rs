//! Requested and delivered cluster content, independent of platform route identities and I/O.
mod jni;

type Url = Vec<u16>;

#[derive(Clone, Debug, PartialEq)]
struct Content {
    stream: i32,
    requested: Option<Url>,
    displayed: Option<Url>,
    shown: bool,
}

#[derive(Clone, Debug, PartialEq)]
struct State {
    retained: Option<Url>,
    content: Option<Content>,
}

struct Change {
    state: State,
    accepted: bool,
    send_url: Option<Url>,
    need_key_frame: bool,
    rotate_route_token: bool,
}

impl State {
    fn new(initial: Option<Url>) -> Self {
        Self {
            retained: initial,
            content: None,
        }
    }

    fn token(&self) -> Vec<i64> {
        let mut words = vec![
            1,
            self.content.is_some() as i64,
            self.content.as_ref().map_or(0, |c| c.stream as i64),
            self.content.as_ref().is_some_and(|c| c.shown) as i64,
        ];
        for url in [
            self.retained.as_ref(),
            self.content.as_ref().and_then(|c| c.requested.as_ref()),
            self.content.as_ref().and_then(|c| c.displayed.as_ref()),
        ] {
            words.push(url.as_ref().map_or(-1, |s| s.len() as i64));
            if let Some(url) = url {
                words.extend(url.iter().map(|&unit| unit as i64));
            }
        }
        words
    }

    fn from_token(words: &[i64]) -> Option<Self> {
        let [1, present @ 0..=1, stream, shown @ 0..=1, rest @ ..] = words else {
            return None;
        };
        let stream = i32::try_from(*stream).ok()?;
        let mut rest = rest;
        let mut urls = Vec::with_capacity(3);
        for _ in 0..3 {
            let (&length, tail) = rest.split_first()?;
            rest = tail;
            if length == -1 {
                urls.push(None);
                continue;
            }
            let length = usize::try_from(length).ok()?;
            if length > rest.len() {
                return None;
            }
            urls.push(Some(
                rest[..length]
                    .iter()
                    .map(|&v| u16::try_from(v))
                    .collect::<Result<Url, _>>()
                    .ok()?,
            ));
            rest = &rest[length..];
        }
        if !rest.is_empty() {
            return None;
        }
        let mut urls = urls.into_iter();
        let retained = urls.next()?;
        let requested = urls.next()?;
        let displayed = urls.next()?;
        let content = if *present == 1 {
            Some(Content {
                stream,
                requested,
                displayed,
                shown: *shown == 1,
            })
        } else {
            if stream != 0 || *shown != 0 || requested.is_some() || displayed.is_some() {
                return None;
            }
            None
        };
        Some(Self { retained, content })
    }

    fn change(
        mut self,
        event: i32,
        stream: i32,
        url: Option<Url>,
        flags: i32,
        expected: i32,
    ) -> Change {
        let closed = flags & 1 != 0;
        let configured = flags & 2 != 0;
        let send = flags & 4 != 0;
        let ui_sent = flags & 8 != 0;
        let frame_sent = flags & 16 != 0;
        let mut accepted = false;
        let mut send_url = None;
        let mut need_key_frame = false;
        let mut rotate_route_token = false;
        match event {
            1 => {
                self.content = Some(Content {
                    stream,
                    requested: self.retained.clone(),
                    displayed: url,
                    shown: true,
                });
                rotate_route_token = true;
                accepted = true;
            }
            2 => {
                self.content = None;
                accepted = true;
            }
            3 | 4 => {
                let current = self.content.as_ref();
                if !closed
                    && configured
                    && (event == 4 || current.is_some_and(|c| c.stream == stream))
                {
                    self.retained = url.clone();
                    if let Some(current) = self.content.as_mut() {
                        current.requested = url.clone();
                        if send && (event == 3 || !current.shown || current.displayed != url) {
                            send_url = url;
                        }
                    }
                    accepted = true;
                }
            }
            5 | 6 => {
                if !closed && stream > 0 {
                    if let Some(current) = &self.content {
                        if event == 6 {
                            accepted = true;
                        } else {
                            send_url = current.requested.clone();
                            accepted = send_url.is_some();
                        }
                    }
                }
            }
            7 => {
                if let Some(current) = &self.content {
                    if current.shown && current.requested != current.displayed {
                        send_url = current.requested.clone();
                    }
                }
                accepted = send_url.is_some();
            }
            8 | 9 => {
                accepted = !closed && self.content.as_ref().is_some_and(|c| c.stream == stream);
                need_key_frame = event == 9 && ui_sent && accepted;
            }
            10 | 11 => {
                if let Some(current) = self.content.as_mut() {
                    let late =
                        closed || current.stream != expected || (event == 10 && stream != expected);
                    if late {
                        if ui_sent {
                            current.displayed = None;
                            if event == 11 {
                                current.shown = false;
                            }
                            rotate_route_token = true;
                        }
                    } else if event == 11 {
                        if ui_sent {
                            current.shown = false;
                        }
                        accepted = ui_sent;
                    } else {
                        if ui_sent {
                            current.displayed = if frame_sent { url } else { None };
                            current.shown = true;
                            rotate_route_token = true;
                        }
                        accepted = frame_sent;
                    }
                }
            }
            _ => unreachable!("validated JNI operation"),
        }
        Change {
            state: self,
            accepted,
            send_url,
            need_key_frame,
            rotate_route_token,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn url(text: &str) -> Option<Url> {
        Some(text.encode_utf16().collect())
    }

    #[test]
    fn retained_selection_and_partial_commit() {
        let state = State::new(url("initial"))
            .change(4, 0, url("map"), 2, 0)
            .state;
        let state = state.change(1, 1, url("initial"), 0, 0).state;
        assert_eq!(state.clone().change(7, 0, None, 0, 0).send_url, url("map"));
        let failed = state.clone().change(10, 1, url("map"), 8, 1);
        assert!(!failed.accepted);
        assert!(failed.rotate_route_token);
        assert_eq!(failed.state.content.as_ref().unwrap().displayed, None);
        assert!(failed.state.content.as_ref().unwrap().shown);
        let unsent = state.clone().change(10, 1, url("map"), 0, 1);
        assert_eq!(unsent.state, state);
        assert!(!unsent.rotate_route_token);
        let state = failed
            .state
            .change(2, 0, None, 0, 0)
            .state
            .change(1, 2, url("initial"), 0, 0)
            .state;
        assert_eq!(state.content.unwrap().requested, url("map"));
    }

    #[test]
    fn late_commands_invalidate_new_stream_and_stop_preserves_normal_identity() {
        let state = State::new(url("map")).change(1, 2, url("map"), 0, 0).state;
        let stopped = state.clone().change(11, 0, None, 8, 2);
        assert!(stopped.accepted);
        assert!(!stopped.rotate_route_token);
        assert_eq!(
            stopped.state.content.as_ref().unwrap().displayed,
            url("map")
        );
        let late_show = stopped.state.change(10, 2, url("map"), 8 | 16, 1);
        assert!(!late_show.accepted);
        assert!(late_show.rotate_route_token);
        assert!(!late_show.state.content.unwrap().shown);
        let late_stop = state.change(11, 0, None, 8, 1);
        assert!(!late_stop.accepted);
        assert!(late_stop.rotate_route_token);
        assert_eq!(late_stop.state.content.as_ref().unwrap().displayed, None);
        assert!(!late_stop.state.content.unwrap().shown);
    }

    #[test]
    fn opaque_snapshots_keep_all_java_utf16_units_and_reject_malformed_values() {
        let state = State::new(Some(vec![0, 0xd800, 0xdc00, 0xffff]))
            .change(1, 1, Some(vec![]), 0, 0)
            .state;
        assert_eq!(State::from_token(&state.token()), Some(state));
        assert!(State::from_token(&[1, 0, 0, 0, i64::MAX, -1, -1]).is_none());
        assert!(State::from_token(&[1, 0, 0, 0, 1, 65536, -1, -1]).is_none());
    }
}
