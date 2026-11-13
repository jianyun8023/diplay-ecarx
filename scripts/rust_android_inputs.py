#!/usr/bin/env python3
"""Content-bound Rust Android build receipts and the sole Gradle JNI staging path.

Receipts prevent accidental stale/mixed artifacts, not malicious artifact forgery.
No git revision is required: dirty trees and source archives use the same contract.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import sys

ABIS = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40), "x86_64": (2, 62)}
CRATE = "shared/src/main/rust/diplay-media"
LIB = "libdiplay_media.so"
REBUILD = "Run scripts/check_rust_android_native.sh for all three ABIs, then retry Gradle."


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_digest(root):
    paths = [p for p in (root / CRATE).rglob('*')
             if p.is_file() and not {'target', '.git'} & set(p.relative_to(root / CRATE).parts)]
    paths += [root / 'scripts/check_rust_android_native.sh', root / 'scripts/rust_android_inputs.py']
    digest = hashlib.sha256()
    for path in sorted(paths):
        digest.update(path.relative_to(root).as_posix().encode() + b'\0')
        digest.update(bytes.fromhex(sha256(path)))
    return digest.hexdigest()


def check_elf(path, abi):
    data = path.read_bytes()[:20]
    cls, machine = ABIS[abi]
    if (len(data) < 20 or data[:4] != b'\x7fELF' or data[4] != cls or data[5] != 1
            or int.from_bytes(data[18:20], 'little') != machine):
        raise ValueError(f'{abi}: missing or wrong ELF architecture: {path}')


def stamp(root, inputs, abi, expected_source):
    if source_digest(root) != expected_source:
        raise ValueError('Rust build inputs changed during compilation; rebuild from a stable source tree')
    library = inputs / abi / LIB
    check_elf(library, abi)
    receipt = dict(schema=1, abi=abi, source_sha256=expected_source, library_sha256=sha256(library))
    (inputs / abi / 'provenance.json').write_text(json.dumps(receipt, sort_keys=True) + '\n')


def verify(root, inputs):
    source = source_digest(root)
    for abi in ABIS:
        library = inputs / abi / LIB
        receipt = json.loads((inputs / abi / 'provenance.json').read_text())
        expected = dict(schema=1, abi=abi, source_sha256=source, library_sha256=sha256(library))
        if receipt != expected:
            raise ValueError(f'{abi}: Rust provenance does not match current sources or library bytes')
        check_elf(library, abi)


def stage(root, inputs, output):
    # Validate the entire set before replacing any previously staged output.
    verify(root, inputs)
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)
    for abi in ABIS:
        dest = output / abi
        dest.mkdir()
        # Retain existing C prebuilts, but never the obsolete checked-in Rust binary.
        for library in (root / 'shared/src/main/jniLibs' / abi).glob('*.so'):
            if library.name != LIB:
                shutil.copy2(library, dest / library.name)
        shutil.copy2(inputs / abi / LIB, dest / LIB)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['fingerprint', 'stamp', 'verify', 'stage'])
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--inputs', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--abi', choices=ABIS)
    parser.add_argument('--source')
    args = parser.parse_args()
    inputs = args.inputs or args.root / '.native/rust-android'
    try:
        if args.command == 'fingerprint':
            print(source_digest(args.root))
        elif args.command == 'stamp':
            if not args.abi or not args.source:
                parser.error('stamp requires --abi and --source')
            stamp(args.root, inputs, args.abi, args.source)
        elif args.command == 'verify':
            verify(args.root, inputs)
        else:
            if not args.output:
                parser.error('stage requires --output')
            stage(args.root, inputs, args.output)
    except (OSError, ValueError) as error:
        print(f'Invalid Rust Android inputs: {error}. {REBUILD}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
