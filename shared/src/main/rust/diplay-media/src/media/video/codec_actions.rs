//! Execution results, not planned output, determine render success. This small adapter boundary is
//! host-testable without linking Android's codec libraries.

pub(super) enum Action {
    Feed { index: usize, handle: i64, size: usize, presentation_us: i64 },
    Render { index: usize, at_ns: Option<i64>, shown: bool, bytes: i32 },
    Drop { index: usize },
}

pub(super) trait CodecIo {
    /// True means the buffer was returned empty because the packet exceeded its capacity.
    fn feed(&mut self, index: usize, handle: i64, size: usize, presentation_us: i64) -> Result<bool, String>;
    fn output(&mut self, index: usize, render: bool, at_ns: Option<i64>) -> Result<(), String>;
    fn release_packet(&mut self, handle: i64);
}

#[derive(Default)]
pub(super) struct Outcome {
    pub rendered: Vec<i32>,
    pub oversized: bool,
    pub failure: Option<String>,
}

pub(super) fn execute(codec: &mut impl CodecIo, actions: Vec<Action>) -> Outcome {
    let mut outcome = Outcome::default();
    for action in actions {
        // Once an operation fails, buffer ownership/state is uncertain. Leave remaining codec
        // buffers to codec teardown, but release every packet transferred into this batch.
        if outcome.failure.is_some() || outcome.oversized {
            if let Action::Feed { handle, .. } = action { codec.release_packet(handle); }
            continue;
        }
        let result = match action {
            Action::Feed { index, handle, size, presentation_us } => {
                let result = codec.feed(index, handle, size, presentation_us);
                codec.release_packet(handle);
                result.map(|oversized| outcome.oversized = oversized)
            }
            Action::Render { index, at_ns, shown, bytes } => {
                codec.output(index, true, at_ns).map(|()| {
                    if shown { outcome.rendered.push(bytes); }
                })
            }
            Action::Drop { index } => codec.output(index, false, None),
        };
        if let Err(error) = result { outcome.failure = Some(error); }
    }
    outcome
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Default)]
    struct FakeCodec {
        calls: Vec<String>,
        released: Vec<i64>,
        fail_at: Option<usize>,
        oversized: bool,
    }
    impl FakeCodec {
        fn call(&mut self, name: String) -> Result<(), String> {
            self.calls.push(name);
            if self.fail_at == Some(self.calls.len()) { Err("injected status=-10000".into()) } else { Ok(()) }
        }
    }
    impl CodecIo for FakeCodec {
        fn feed(&mut self, index: usize, _: i64, _: usize, _: i64) -> Result<bool, String> {
            self.call(format!("feed:{index}"))?;
            Ok(self.oversized)
        }
        fn output(&mut self, index: usize, render: bool, at_ns: Option<i64>) -> Result<(), String> {
            self.call(format!("output:{index}:{render}:{at_ns:?}"))
        }
        fn release_packet(&mut self, handle: i64) { self.released.push(handle); }
    }
    fn feed(handle: i64) -> Action { Action::Feed { index: 0, handle, size: 32, presentation_us: 1 } }
    fn render(at_ns: Option<i64>, shown: bool) -> Action { Action::Render { index: 1, at_ns, shown, bytes: 42 } }

    #[test]
    fn failed_queue_stops_batch_and_releases_all_packets() {
        let mut codec = FakeCodec { fail_at: Some(1), ..Default::default() };
        let result = execute(&mut codec, vec![feed(10), feed(11), render(None, true)]);
        assert_eq!(codec.calls, ["feed:0"]);
        assert_eq!(codec.released, [10, 11]);
        assert!(result.failure.is_some());
        assert!(result.rendered.is_empty());
    }

    #[test]
    fn failed_release_never_counts_render_for_immediate_or_timed_output() {
        for at in [None, Some(123)] {
            let mut codec = FakeCodec { fail_at: Some(1), ..Default::default() };
            let result = execute(&mut codec, vec![render(at, true), feed(12)]);
            assert!(result.failure.is_some());
            assert!(result.rendered.is_empty());
            assert_eq!(codec.calls.len(), 1);
            assert_eq!(codec.released, [12]);
        }
    }

    #[test]
    fn failed_drop_is_reported_without_rendering() {
        let mut codec = FakeCodec { fail_at: Some(1), ..Default::default() };
        let result = execute(&mut codec, vec![Action::Drop { index: 4 }]);
        assert_eq!(codec.calls, ["output:4:false:None"]);
        assert!(result.failure.is_some());
        assert!(result.rendered.is_empty());
    }

    #[test]
    fn success_before_later_failure_is_counted_but_hidden_output_is_not() {
        let mut codec = FakeCodec { fail_at: Some(3), ..Default::default() };
        let result = execute(&mut codec, vec![render(None, false), render(Some(123), true), render(None, true)]);
        assert_eq!(result.rendered, [42]);
        assert!(result.failure.is_some());
    }

    #[test]
    fn oversized_frame_does_not_feed_dependents_or_leak_packets() {
        let mut codec = FakeCodec { oversized: true, ..Default::default() };
        let result = execute(&mut codec, vec![feed(10), feed(11)]);
        assert!(result.oversized);
        assert!(result.failure.is_none());
        assert_eq!(codec.calls.len(), 1);
        assert_eq!(codec.released, [10, 11]);
    }
}
