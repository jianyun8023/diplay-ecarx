//! SETUP request interpretation, microphone capture parameters and reply dict layout.
//! These are the pure rules; the owner's private JNI reads the platform stream map lazily
//! (so scalar classes keep the old Kotlin unsigned semantics) and constructs the replies.

use super::Value;
use crate::media::audio::packetizer::{microphone_frame_millis, opus_bitrate, opus_clock};
use crate::media::audio::route::format_bits;
use crate::protocols::crypto::hkdf32;

/// The audio SETUP's type that may carry a microphone request.
const STREAM_TYPE_MAIN_AUDIO: i32 = 100;
const DATASTREAM_SALT: &str = "DataStream-Salt";
pub(super) const OUTPUT_KEY_LABEL: &str = "DataStream-Output-Encryption-Key";
pub(super) const INPUT_KEY_LABEL: &str = "DataStream-Input-Encryption-Key";

/// Reply layouts behind [super::jni]: one audio SETUP reply, a socketless remote-control
/// stream and a generic data-stream reply with its optional connection value.
pub(super) const REPLY_AUDIO: i32 = 0;
pub(super) const REPLY_REMOTE_CONTROL: i32 = 1;
pub(super) const REPLY_DATA: i32 = 2;

/// Calls and Siri always record. App audio ("default", e.g. a WhatsApp voice note) also carries
/// guidance prompts, so it records only when the iPhone asks for input: an explicit `input` flag,
/// or, without one, the uplink port the iPhone sends for a recording stream.
pub(super) fn wants_microphone(audio_type: &str, input: Option<bool>, data_port: Option<i32>) -> bool {
    match audio_type {
        "telephony" | "speechrecognition" => true,
        "default" | "compatibility" => match input {
            Some(explicit) => explicit,
            None => data_port.is_some_and(|port| (1..=65535).contains(&port)),
        },
        _ => false,
    }
}

/// Calls wait for their downlink: call capture uses the far-end audio as its echo reference.
/// Other eligible streams may start silently after SETUP.
pub(super) fn wait_downlink(audio_type: &str) -> bool {
    audio_type != "telephony"
}

/// One negotiated microphone stream's numeric plan; the platform DTO carries it to capture.
pub(super) struct MicrophonePlan {
    pub port: i32,
    pub frame_millis: i32,
    pub bitrate: Option<i32>,
    pub opus_clock_rate: i32,
    pub wait_downlink: bool,
}

/// The capture plan for one audio SETUP request, or None when the request records nothing:
/// the engine's switch is off, the stream is not main audio, the audio type never records,
/// or the uplink port is missing or out of range.
pub(super) fn microphone_plan(
    audio_type: &str,
    stream_type: i32,
    enabled: bool,
    format_bits_value: i64,
    frames_per_packet: i32,
    data_port: Option<i32>,
    input: Option<bool>,
) -> Option<MicrophonePlan> {
    if !enabled || stream_type != STREAM_TYPE_MAIN_AUDIO || !wants_microphone(audio_type, input, data_port) {
        return None;
    }
    let port = data_port?;
    if !(1..=65535).contains(&port) {
        return None;
    }
    let row = format_bits(format_bits_value as u64);
    let opus = row[0] == 1;
    Some(MicrophonePlan {
        port,
        frame_millis: microphone_frame_millis(opus, frames_per_packet, row[1], row[2]),
        bitrate: opus.then(|| opus_bitrate(format_bits_value)),
        opus_clock_rate: opus_clock(format_bits_value),
        wait_downlink: wait_downlink(audio_type),
    })
}

/// The DataStream key for one seed decimal and label: the salt is the US-ASCII bytes of
/// "DataStream-Salt" plus the unsigned decimal, exactly the bytes the old Kotlin assembled.
pub(super) fn data_stream_key(shared_secret: &[u8], seed: &str, label: &str) -> [u8; 32] {
    hkdf32(shared_secret, &data_stream_salt(seed), &ascii_bytes(label))
}

fn data_stream_salt(seed: &str) -> Vec<u8> {
    let mut salt = Vec::with_capacity(DATASTREAM_SALT.len() + seed.len());
    salt.extend_from_slice(DATASTREAM_SALT.as_bytes());
    salt.extend_from_slice(&ascii_bytes(seed));
    salt
}

/// The old US_ASCII encoding: the protocol's seeds and labels are decimal digits and names,
/// and any other character the platform replaced with '?'.
fn ascii_bytes(text: &str) -> Vec<u8> {
    text.chars().map(|c| if (c as u32) < 0x80 { c as u8 } else { b'?' }).collect()
}

/// A reply's connection slot after the scalar class decision.
#[derive(Debug, PartialEq)]
pub(super) enum ConnectionSlot {
    /// A plist value the reply dict carries inline.
    Inline(Value),
    /// The unsigned decimal the JNI bridge builds as a BigInteger.
    UnsignedDecimal(u64),
    /// The original scalar object crosses back unchanged.
    Original,
}

/// A Long scalar's reply slot: values that fit signed stay Long, the unsigned form becomes
/// the decimal BigInteger the platform previously built (18446744073709551615 for -1).
pub(super) fn long_connection(value: i64) -> ConnectionSlot {
    if value >= 0 {
        ConnectionSlot::Inline(Value::Int(value as u64))
    } else {
        ConnectionSlot::UnsignedDecimal(value as u64)
    }
}

/// An Integer scalar's reply slot: positive values stay Integer, negatives become their
/// 32-bit unsigned decimal.
pub(super) fn int_connection(value: i32) -> ConnectionSlot {
    if value >= 0 {
        ConnectionSlot::Inline(Value::Int32(value))
    } else {
        ConnectionSlot::UnsignedDecimal(value as u32 as u64)
    }
}

pub(super) fn unsigned_decimal_string(value: u64) -> String {
    value.to_string()
}

/// The audio SETUP reply: type, dataPort, controlPort, then the connection slot. A Null slot
/// carries an external Java object the JNI bridge puts back unchanged.
pub(super) fn audio_reply(stream_type: i32, data_port: i32, control_port: i32, connection: Value) -> Value {
    Value::Dict(vec![
        ("type".encode_utf16().collect(), Value::Int32(stream_type)),
        ("dataPort".encode_utf16().collect(), Value::Int32(data_port)),
        ("controlPort".encode_utf16().collect(), Value::Int32(control_port)),
        ("streamConnectionID".encode_utf16().collect(), connection),
    ])
}

/// A data-stream reply: type and streamID, then the optional port and connection slot in
/// the order the old replies appended them.
pub(super) fn data_reply(stream_type: i32, stream_id: i64, data_port: Option<i32>, connection: Option<Value>) -> Value {
    let mut fields = vec![
        ("type".encode_utf16().collect(), Value::Int32(stream_type)),
        ("streamID".encode_utf16().collect(), Value::Int(stream_id as u64)),
    ];
    if let Some(port) = data_port {
        fields.push(("dataPort".encode_utf16().collect(), Value::Int32(port)));
    }
    if let Some(connection) = connection {
        fields.push(("streamConnectionID".encode_utf16().collect(), connection));
    }
    Value::Dict(fields)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn microphone_requests_follow_the_audio_type_and_explicit_input() {
        // Calls and Siri always ask to record.
        assert!(wants_microphone("telephony", None, None));
        assert!(wants_microphone("speechrecognition", None, None));
        assert!(wants_microphone("telephony", Some(false), Some(0)));
        // App audio records only when the iPhone asks for input; the uplink port is the fallback.
        assert!(wants_microphone("default", None, Some(50_000)));
        assert!(wants_microphone("compatibility", None, Some(50_000)));
        assert!(wants_microphone("default", Some(true), None));
        // An explicit input wins over the port it arrives with.
        assert!(wants_microphone("default", Some(true), Some(0)));
        assert!(!wants_microphone("default", Some(false), Some(50_000)));
        assert!(!wants_microphone("default", Some(false), None));
        assert!(!wants_microphone("default", None, None));
        assert!(!wants_microphone("default", None, Some(0)));
        assert!(!wants_microphone("default", None, Some(65_536)));
        assert!(wants_microphone("default", None, Some(65_535)));
        // Media and alerts never record.
        assert!(!wants_microphone("media", Some(true), Some(50_000)));
        assert!(!wants_microphone("alert", Some(true), Some(50_000)));
    }

    #[test]
    fn calls_wait_for_downlink_and_other_streams_start_silently() {
        assert!(!wait_downlink("telephony"));
        assert!(wait_downlink("speechrecognition"));
        assert!(wait_downlink("default"));
        assert!(wait_downlink("media"));
    }

    #[test]
    fn microphone_parameters_follow_the_codec_and_packet_shape() {
        // Opus records 20 ms frames at the negotiated clock with the bitrate the bit names.
        let call = microphone_plan("telephony", 100, true, 0x4000_0000, 0, Some(50_000), None).unwrap();
        assert_eq!(call.frame_millis, 20);
        assert_eq!(call.bitrate, Some(96_000));
        assert_eq!(call.opus_clock_rate, 48_000);
        assert!(!call.wait_downlink);
        let siri = microphone_plan("default", 100, true, 0x2000_0000, 0, Some(50_000), Some(true)).unwrap();
        assert_eq!(siri.bitrate, Some(64_000));
        assert_eq!(siri.opus_clock_rate, 24_000);
        let quiet = microphone_plan("default", 100, true, 0x1000_0000, 0, Some(50_000), None).unwrap();
        assert_eq!(quiet.bitrate, Some(48_000));
        assert_eq!(quiet.opus_clock_rate, 16_000);
        // A named framesPerPacket rounds half-up into the 5..60 clamp.
        let pcm = microphone_plan("telephony", 100, true, 0x10, 160, Some(50_000), None).unwrap();
        assert_eq!(pcm.frame_millis, 10);
        assert_eq!(pcm.bitrate, None);
        assert_eq!(pcm.opus_clock_rate, 48_000);
        assert_eq!(microphone_plan("telephony", 100, true, 0x10, 1, Some(50_000), None).unwrap().frame_millis, 5);
        assert_eq!(microphone_plan("telephony", 100, true, 0x10, 2_000_000, Some(50_000), None).unwrap().frame_millis, 60);
        // Without framesPerPacket the MTU calibration names the longest fitting PCM packet.
        assert_eq!(microphone_plan("telephony", 100, true, 0x10, 0, Some(50_000), None).unwrap().frame_millis, 20);
        assert_eq!(microphone_plan("telephony", 100, true, 0x4000, 0, Some(50_000), None).unwrap().frame_millis, 10);
    }

    #[test]
    fn microphone_plans_refuse_disabled_or_out_of_range_setups() {
        assert!(microphone_plan("telephony", 100, false, 0, 0, Some(50_000), None).is_none());
        assert!(microphone_plan("telephony", 102, true, 0, 0, Some(50_000), None).is_none());
        assert!(microphone_plan("media", 100, true, 0, 0, Some(50_000), Some(true)).is_none());
        assert!(microphone_plan("telephony", 100, true, 0, 0, None, None).is_none());
        assert!(microphone_plan("telephony", 100, true, 0, 0, Some(0), None).is_none());
        assert!(microphone_plan("telephony", 100, true, 0, 0, Some(65_536), None).is_none());
        assert!(microphone_plan("telephony", 100, true, 0, 0, Some(1), None).is_some());
        assert!(microphone_plan("telephony", 100, true, 0, 0, Some(65_535), None).is_some());
    }

    #[test]
    fn the_data_stream_salt_and_label_assemble_byte_exact() {
        // The unsigned decimal travels as US-ASCII digits after the fixed salt prefix.
        assert_eq!(
            data_stream_salt("18446744073709551615"),
            b"DataStream-Salt18446744073709551615".to_vec()
        );
        assert_eq!(data_stream_salt("42"), b"DataStream-Salt42".to_vec());
        // Two derivations over the same secret, seed and label agree; a different seed does not.
        let key = data_stream_key(&[7u8; 32], "42", OUTPUT_KEY_LABEL);
        assert_eq!(key, data_stream_key(&[7u8; 32], "42", OUTPUT_KEY_LABEL));
        assert_ne!(key, data_stream_key(&[7u8; 32], "43", OUTPUT_KEY_LABEL));
        assert_ne!(key, data_stream_key(&[7u8; 32], "42", INPUT_KEY_LABEL));
    }

    #[test]
    fn replies_keep_the_field_order_and_slot_types() {
        let Value::Dict(audio) = audio_reply(100, 50_000, 50_001, Value::Int(42)) else { panic!() };
        let names = ["type", "dataPort", "controlPort", "streamConnectionID"]
            .map(|name| name.encode_utf16().collect::<Vec<_>>());
        assert_eq!(
            audio.iter().map(|(name, _)| name.clone()).collect::<Vec<_>>(),
            names.to_vec()
        );
        assert_eq!(audio[0].1, Value::Int32(100));
        assert_eq!(audio[1].1, Value::Int32(50_000));
        assert_eq!(audio[2].1, Value::Int32(50_001));
        assert_eq!(audio[3].1, Value::Int(42));

        let Value::Dict(remote) = data_reply(130, 3, None, None) else { panic!() };
        assert_eq!(remote.len(), 2);
        assert_eq!(remote[0].1, Value::Int32(130));
        assert_eq!(remote[1].1, Value::Int(3));

        let Value::Dict(data) = data_reply(130, 1, Some(50_000), Some(Value::Null)) else { panic!() };
        assert_eq!(data.len(), 4);
        assert_eq!(data[2].1, Value::Int32(50_000));
        assert_eq!(data[3].1, Value::Null, "an external object replaces the Null slot");
    }

    #[test]
    fn long_connections_stay_signed_until_the_unsigned_form_is_needed() {
        assert_eq!(long_connection(42), ConnectionSlot::Inline(Value::Int(42)));
        assert_eq!(long_connection(i64::MAX), ConnectionSlot::Inline(Value::Int(i64::MAX as u64)));
        assert_eq!(long_connection(-1), ConnectionSlot::UnsignedDecimal(u64::MAX));
        assert_eq!(long_connection(i64::MIN), ConnectionSlot::UnsignedDecimal(0x8000_0000_0000_0000));
        assert_eq!(int_connection(42), ConnectionSlot::Inline(Value::Int32(42)));
        assert_eq!(int_connection(-1), ConnectionSlot::UnsignedDecimal(4_294_967_295));
        assert_eq!(unsigned_decimal_string(u64::MAX), "18446744073709551615");
        assert_eq!(unsigned_decimal_string(4_294_967_295), "4294967295");
    }
}
