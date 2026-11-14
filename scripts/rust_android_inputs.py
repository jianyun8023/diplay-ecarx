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
# Keep this list independent of Git so source archives follow the same contract.
# Do not broadly ignore hidden files: .cargo/config.toml is a real build input.
IGNORED_PARTS = {'target', '.git', '__pycache__', '.DS_Store', 'Thumbs.db'}
IGNORED_SUFFIXES = {'.pyc', '.pyo', '.swp', '.swo'}


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_fingerprints(root):
    paths = [p for p in (root / CRATE).rglob('*')
             if p.is_file() and not IGNORED_PARTS & set(p.relative_to(root / CRATE).parts)
             and p.suffix not in IGNORED_SUFFIXES and not p.name.endswith('~')]
    paths += [root / 'scripts/check_rust_android_native.sh', root / 'scripts/rust_android_inputs.py',
              root / 'shared/rust-android-inputs.gradle']
    return {path.relative_to(root).as_posix(): sha256(path) for path in sorted(paths)}


def fingerprint_digest(files):
    digest = hashlib.sha256()
    for path, checksum in sorted(files.items()):
        digest.update(path.encode() + b'\0')
        digest.update(bytes.fromhex(checksum))
    return digest.hexdigest()


def source_digest(root):
    return fingerprint_digest(source_fingerprints(root))


def first_source_difference(previous, current):
    for path in sorted(previous.keys() | current.keys()):
        if previous.get(path) != current.get(path):
            change = 'added' if path not in previous else 'removed' if path not in current else 'changed'
            return f' (first difference: {path} [{change}])'
    return ''


def check_elf(path, abi):
    data = path.read_bytes()[:20]
    cls, machine = ABIS[abi]
    if (len(data) < 20 or data[:4] != b'\x7fELF' or data[4] != cls or data[5] != 1
            or int.from_bytes(data[18:20], 'little') != machine):
        raise ValueError(f'{abi}: missing or wrong ELF architecture: {path}')
    if int.from_bytes(data[16:18], 'little') != 3:
        raise ValueError(f'{abi}: ELF is not an ET_DYN shared library: {path}')


def stamp(root, inputs, abi, expected_source):
    files = source_fingerprints(root)
    if fingerprint_digest(files) != expected_source:
        raise ValueError('Rust build inputs changed during compilation; rebuild from a stable source tree')
    library = inputs / abi / LIB
    check_elf(library, abi)
    receipt = dict(schema=2, abi=abi, source_sha256=expected_source, source_files=files,
                   library_sha256=sha256(library))
    (inputs / abi / 'provenance.json').write_text(json.dumps(receipt, sort_keys=True) + '\n')


def verify(root, inputs):
    files = source_fingerprints(root)
    source = fingerprint_digest(files)
    for abi in ABIS:
        library = inputs / abi / LIB
        receipt = json.loads((inputs / abi / 'provenance.json').read_text())
        if (not isinstance(receipt, dict) or receipt.get('schema') != 2
                or receipt.get('abi') != abi or not isinstance(receipt.get('source_files'), dict)):
            raise ValueError(f'{abi}: unsupported or invalid Rust provenance metadata')
        if receipt.get('source_sha256') != source:
            difference = first_source_difference(receipt['source_files'], files)
            raise ValueError(f'{abi}: Rust source inputs changed{difference}')
        library_digest = sha256(library)
        if receipt.get('library_sha256') != library_digest:
            raise ValueError(f'{abi}: Rust library bytes do not match provenance receipt: {library}')
        expected = dict(schema=2, abi=abi, source_sha256=source, source_files=files,
                        library_sha256=library_digest)
        if receipt != expected:
            raise ValueError(f'{abi}: inconsistent Rust provenance metadata')
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
