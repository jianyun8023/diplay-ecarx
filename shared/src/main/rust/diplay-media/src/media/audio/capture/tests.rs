use super::*;

pub(super) fn capture(rate: i32, samples: usize) -> Capture {
    Capture {
        sender: Uplink::new(&[0; 32], 100, &[127, 0, 0, 1], 0, 9, 0, samples as i32, 0).unwrap(),
        frame: vec![0; samples * 2], filled: 0, rate, channels: 1, clock: [0; 3],
        speex: Speex::new(samples, rate), reference: vec![0; samples], echo: None, source: std::ptr::null_mut(),
    }
}

#[test]
fn fragmented_reads_keep_exact_residue_until_the_next_complete_frame() {
    let mut capture = capture(16_000, 320);
    let bytes: Vec<_> = (0..1937).map(|i| (i * 73) as u8).collect();
    let mut frames = Vec::new();
    for chunk in bytes.chunks(137) {
        let mut offset = 0;
        while offset < chunk.len() {
            offset += capture.fill(&chunk[offset..]);
            if capture.filled == capture.frame.len() {
                frames.extend_from_slice(&capture.frame);
                capture.filled = 0;
            }
        }
    }
    assert_eq!(frames, bytes[..1920]);
    assert_eq!(capture.filled, 17);
    assert_eq!(&capture.frame[..17], &bytes[1920..]);
}

#[test]
fn pcm16le_bridge_matches_the_previous_short_array_speex_path() {
    extern "C" {
        fn speex_echo_state_init(frame: i32, filter: i32) -> *mut c_void;
        fn speex_echo_state_destroy(state: *mut c_void);
        fn speex_echo_ctl(state: *mut c_void, request: i32, value: *mut c_void) -> i32;
        fn speex_echo_cancellation(state: *mut c_void, mic: *const i16, reference: *const i16, out: *mut i16);
        fn speex_preprocess_state_init(frame: i32, rate: i32) -> *mut c_void;
        fn speex_preprocess_state_destroy(state: *mut c_void);
        fn speex_preprocess_ctl(state: *mut c_void, request: i32, value: *mut c_void) -> i32;
        fn speex_preprocess_run(state: *mut c_void, samples: *mut i16) -> i32;
    }
    for rate in [16_000, 24_000, 48_000] {
        let samples = rate as usize / 50;
        let mut actual = Speex::new(samples, rate).unwrap();
        unsafe {
            let echo = speex_echo_state_init(samples as i32, rate / 4);
            let preprocess = speex_preprocess_state_init(samples as i32, rate);
            assert!(!echo.is_null() && !preprocess.is_null());
            let mut value = rate;
            speex_echo_ctl(echo, 24, (&mut value as *mut i32).cast());
            for (request, mut value) in [(0, 1), (2, 0), (4, 0), (8, 0), (20, -40), (22, -15)] {
                speex_preprocess_ctl(preprocess, request, (&mut value as *mut i32).cast());
            }
            speex_preprocess_ctl(preprocess, 24, echo);
            for step in 0..40 {
                let mic: Vec<i16> = (0..samples).map(|i| match i % 8 {
                    0 => i16::MIN, 1 => i16::MAX, 2 => 0, 3 => -1,
                    _ => (i * 721 + step * 913) as i16,
                }).collect();
                let reference: Vec<i16> = (0..samples).map(|i| (i * 389 + step * 577) as i16 / 2).collect();
                let mut expected = vec![0; samples];
                speex_echo_cancellation(echo, mic.as_ptr(), reference.as_ptr(), expected.as_mut_ptr());
                speex_preprocess_run(preprocess, expected.as_mut_ptr());
                let mut bytes: Vec<u8> = mic.iter().flat_map(|v| v.to_le_bytes()).collect();
                bytes.extend_from_slice(&[0xa5, 0x5a]);
                assert!(actual.process(&mut bytes, &reference));
                let expected: Vec<u8> = expected.iter().flat_map(|v| v.to_le_bytes()).collect();
                assert_eq!(&bytes[..samples * 2], expected, "rate={rate} step={step}");
                assert_eq!(&bytes[samples * 2..], &[0xa5, 0x5a]);
            }
            speex_preprocess_state_destroy(preprocess);
            speex_echo_state_destroy(echo);
        }
        let mut bytes = vec![0x5a; samples * 2];
        assert!(!actual.process(&mut bytes, &vec![0; samples - 1]));
        assert_eq!(bytes, vec![0x5a; samples * 2]);
        assert!(!actual.process(&mut bytes[..samples * 2 - 1], &vec![0; samples]));
        assert_eq!(bytes, vec![0x5a; samples * 2]);
    }
    assert!(Speex::new(0, 48_000).is_none());
    assert!(Speex::new(320, 0).is_none());
}
