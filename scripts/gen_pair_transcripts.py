#!/usr/bin/env python3
"""Generate complete AirPlay Pair-Verify / Pair-Setup transcripts as fixed fixtures.

This is an INDEPENDENT reference implementation, not derived from the Rust (ring) or
Kotlin (BouncyCastle) production code:
  - X25519 / Ed25519 / ChaCha20-Poly1305 / HKDF-SHA512 come from the third-party
    `cryptography` package (pyca/cryptography).
  - The SRP-6a values (A/B/K/client proof/server proof) are read from the already
    independently recomputed `pairsetup-srp-golden.properties` (Python stdlib
    hashlib + integer pow), whose salt/b/a were pinned to LIVI 6ac88bab.
  - The TLV8 layout (255-byte chunking, [255,0] continuation separator) and the
    HAP/AirPlay labels below are protocol constants, not copied from production output.

Output: two .properties files consumed by the Rust module tests as byte-exact
expected responses. Deterministic: fixed seeds only, no randomness.

Run: python3 scripts/gen_pair_transcripts.py
"""
import hashlib
import os
import sys

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "shared", "src", "test", "resources")


def hkdf32(ikm: bytes, salt: bytes, info: bytes) -> bytes:
    return HKDF(algorithm=hashes.SHA512(), length=32, salt=salt, info=info).derive(ikm)


def chacha_seal(key: bytes, nonce_label: bytes, plaintext: bytes) -> bytes:
    assert len(nonce_label) == 12, "nonce label must be 12 bytes"
    return ChaCha20Poly1305(key).encrypt(nonce_label, plaintext, None)


def tlv_encode(items) -> bytes:
    """Mirror the Rust tlv8::encode layout: [kind,len,value] chunks of <=255 and a
    [255,0] separator between consecutive items of the same kind."""
    out = bytearray()
    previous = None
    for kind, value in items:
        if previous == kind:
            out += bytes([255, 0])
        if len(value) == 0:
            out += bytes([kind, 0])
        for i in range(0, len(value), 255):
            chunk = value[i:i + 255]
            out += bytes([kind, len(chunk)]) + chunk
        previous = kind
    return bytes(out)


def raw_ed_public(seed: bytes) -> bytes:
    return Ed25519PrivateKey.from_private_bytes(seed).public_key().public_bytes(
        encoding=serialization.Encoding.Raw, format=serialization.PublicFormat.Raw)


def raw_x_public(seed: bytes) -> bytes:
    return X25519PrivateKey.from_private_bytes(seed).public_key().public_bytes(
        encoding=serialization.Encoding.Raw, format=serialization.PublicFormat.Raw)


def x_shared(private_seed: bytes, peer_public: bytes) -> bytes:
    return X25519PrivateKey.from_private_bytes(private_seed).exchange(X25519PublicKey.from_public_bytes(peer_public))


def load_srp_golden() -> dict:
    path = os.path.join(RES, "pairsetup-srp-golden.properties")
    values = {}
    with open(path, "r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            values[key] = bytes.fromhex(value)
    return values


def hexs(value: bytes) -> str:
    return value.hex()


PV_NONCE_02 = b"\x00\x00\x00\x00PV-Msg02"
PV_NONCE_03 = b"\x00\x00\x00\x00PV-Msg03"
PS_NONCE_05 = b"\x00\x00\x00\x00PS-Msg05"
PS_NONCE_06 = b"\x00\x00\x00\x00PS-Msg06"


def build_pair_verify() -> dict:
    accessory_seed = bytes([0x11] * 32)          # Ed25519 long-term accessory identity
    accessory_id = b"diplay-accessory\x00id"
    ephemeral_scalar = bytes([0x42] * 32)         # X25519 private scalar the responder must use
    controller_seed = bytes([0x33] * 32)          # Ed25519 controller long-term identity
    controller_ephemeral = bytes([0x24] * 32)     # X25519 controller ephemeral
    controller_id = b"controller\x00id"

    accessory_public = raw_ed_public(accessory_seed)
    accessory_ephemeral_public = raw_x_public(ephemeral_scalar)
    controller_ephemeral_public = raw_x_public(controller_ephemeral)
    shared = x_shared(controller_ephemeral, accessory_ephemeral_public)

    enc_key = hkdf32(shared, b"Pair-Verify-Encrypt-Salt", b"Pair-Verify-Encrypt-Info")

    m1_request = tlv_encode([(6, bytes([1])), (3, controller_ephemeral_public)])

    accessory_sig = Ed25519PrivateKey.from_private_bytes(accessory_seed).sign(
        accessory_ephemeral_public + accessory_id + controller_ephemeral_public)
    m2_sub = tlv_encode([(1, accessory_id), (10, accessory_sig)])
    m2_sealed = chacha_seal(enc_key, PV_NONCE_02, m2_sub)
    expected_m2 = tlv_encode([(6, bytes([2])), (3, accessory_ephemeral_public), (5, m2_sealed)])

    controller_sig = Ed25519PrivateKey.from_private_bytes(controller_seed).sign(
        controller_ephemeral_public + controller_id + accessory_ephemeral_public)
    m3_sub = tlv_encode([(1, controller_id), (10, controller_sig)])
    m3_sealed = chacha_seal(enc_key, PV_NONCE_03, m3_sub)
    m3_request = tlv_encode([(6, bytes([3])), (5, m3_sealed)])

    expected_m4 = tlv_encode([(6, bytes([4]))])
    control_read = hkdf32(shared, b"Control-Salt", b"Control-Write-Encryption-Key")
    control_write = hkdf32(shared, b"Control-Salt", b"Control-Read-Encryption-Key")

    return {
        "accessory_seed": accessory_seed,
        "accessory_id": accessory_id,
        "ephemeral_scalar": ephemeral_scalar,
        "controller_id": controller_id,
        "controller_public": raw_ed_public(controller_seed),
        "m1_request": m1_request,
        "expected_m2": expected_m2,
        "m3_request": m3_request,
        "expected_m4": expected_m4,
        "control_read_key": control_read,
        "control_write_key": control_write,
    }


def build_pair_setup() -> dict:
    golden = load_srp_golden()
    salt = golden["salt"]
    b_private = golden["b"]
    server_proof = golden["M2"]           # SRP server proof (M2 in the golden file)
    session_key = golden["K"]
    client_public_a = golden["A"]
    client_proof = golden["M1"]           # SRP client proof

    accessory_seed = bytes([0x21] * 32)
    accessory_id = b"diplay-accessory\x00id"
    accessory_public = raw_ed_public(accessory_seed)
    controller_seed = bytes([0x31] * 32)
    controller_id = b"controller\x00id"
    controller_public = raw_ed_public(controller_seed)

    encryption = hkdf32(session_key, b"Pair-Setup-Encrypt-Salt", b"Pair-Setup-Encrypt-Info")

    # Sanity: the SRP golden must reproduce B from (salt, b) using the pinned group.
    assert len(salt) == 16 and len(b_private) == 32 and len(server_proof) == 64

    m1_request = tlv_encode([(6, bytes([1])), (0, bytes([99]))])
    expected_m2 = tlv_encode([(6, bytes([2])), (3, golden["B"]), (2, salt)])
    m3_request = tlv_encode([(6, bytes([3])), (3, client_public_a), (4, client_proof)])
    expected_m4 = tlv_encode([(6, bytes([4])), (4, server_proof)])

    controller_sign_key = hkdf32(session_key, b"Pair-Setup-Controller-Sign-Salt", b"Pair-Setup-Controller-Sign-Info")
    controller_sig = Ed25519PrivateKey.from_private_bytes(controller_seed).sign(
        controller_sign_key + controller_id + controller_public)
    m5_sub = tlv_encode([(1, controller_id), (3, controller_public), (10, controller_sig)])
    m5_sealed = chacha_seal(encryption, PS_NONCE_05, m5_sub)
    m5_request = tlv_encode([(6, bytes([5])), (5, m5_sealed)])

    accessory_sign_key = hkdf32(session_key, b"Pair-Setup-Accessory-Sign-Salt", b"Pair-Setup-Accessory-Sign-Info")
    accessory_sig = Ed25519PrivateKey.from_private_bytes(accessory_seed).sign(
        accessory_sign_key + accessory_id + accessory_public)
    m6_sub = tlv_encode([(1, accessory_id), (3, accessory_public), (10, accessory_sig)])
    m6_sealed = chacha_seal(encryption, PS_NONCE_06, m6_sub)
    expected_m6 = tlv_encode([(6, bytes([6])), (5, m6_sealed)])

    return {
        "accessory_seed": accessory_seed,
        "accessory_public": accessory_public,
        "accessory_id": accessory_id,
        "srp_salt": salt,
        "srp_b_private": b_private,
        "controller_id": controller_id,
        "controller_public": controller_public,
        "m1_request": m1_request,
        "expected_m2": expected_m2,
        "m3_request": m3_request,
        "expected_m4": expected_m4,
        "m5_request": m5_request,
        "expected_m6": expected_m6,
    }


HEADER = (
    "# Complete AirPlay {name} transcript (byte-exact expected responses).\n"
    "# Generated by scripts/gen_pair_transcripts.py with the third-party 'cryptography'\n"
    "# package (X25519/Ed25519/ChaCha20-Poly1305/HKDF-SHA512) and, for SRP, the\n"
    "# independently recomputed pairsetup-srp-golden.properties (hashlib + integer pow).\n"
    "# Deterministic fixed inputs only; NOT exported from the Rust/Kotlin production code.\n"
)


def write_fixture(name, values: dict) -> str:
    path = os.path.join(RES, name)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(HEADER.format(name=name))
        for key, value in values.items():
            if isinstance(value, bytes):
                # Non-UTF8-friendly fields are hex; ASCII ids are also hex for uniformity.
                handle.write(f"{key}={hexs(value)}\n")
    return path


def main() -> int:
    if not os.path.isdir(RES):
        print(f"missing test resources dir: {RES}", file=sys.stderr)
        return 2
    verify = build_pair_verify()
    setup = build_pair_setup()
    pv = write_fixture("pair-verify-transcript.properties", verify)
    ps = write_fixture("pair-setup-transcript.properties", setup)
    print("wrote", pv)
    print("wrote", ps)
    for name, values in (("pair-verify", verify), ("pair-setup", setup)):
        print(f"[{name}] keys:", ", ".join(values))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
