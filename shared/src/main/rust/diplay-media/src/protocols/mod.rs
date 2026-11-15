//! Pairing, control-wire codecs and shared cryptography.

pub mod airplay;
pub mod auth_setup;
pub mod bplist;
pub mod control_cipher;
pub mod crypto;
pub mod data_stream;
pub mod iap2_body;
pub mod iap2_csm;
pub mod iap2_file_transfer;
pub mod iap2_link;
pub mod iap2_wire;
pub mod iap_stream_queue;
pub mod mqtt;
pub mod pair_setup;
pub mod pair_verify;
pub mod rtsp;
pub mod timing;
mod tlv8;
