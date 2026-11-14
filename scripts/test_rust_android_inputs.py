import json
from pathlib import Path
import tempfile
import unittest

import rust_android_inputs as native


class RustAndroidInputsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.inputs = self.root / '.native/rust-android'
        self.output = self.root / 'generated'
        self.source = self.root / native.CRATE / 'src/lib.rs'
        self.source.parent.mkdir(parents=True)
        self.source.write_text('current source')
        (self.root / 'scripts').mkdir()
        for name in ['check_rust_android_native.sh', 'rust_android_inputs.py']:
            (self.root / 'scripts' / name).write_text(name)
        (self.root / 'shared/rust-android-inputs.gradle').write_text('Gradle JNI staging')
        for abi, (cls, machine) in native.ABIS.items():
            directory = self.inputs / abi
            directory.mkdir(parents=True)
            data = bytearray(64)
            data[:6] = b'\x7fELF' + bytes([cls, 1])
            data[16:18] = (3).to_bytes(2, 'little')
            data[18:20] = machine.to_bytes(2, 'little')
            (directory / native.LIB).write_bytes(data)
            native.stamp(self.root, self.inputs, abi, native.source_digest(self.root))

    def test_complete_current_set_stages_no_checked_in_rust(self):
        for abi in native.ABIS:
            old = self.root / 'shared/src/main/jniLibs' / abi
            old.mkdir(parents=True)
            (old / native.LIB).write_bytes(b'obsolete')
            (old / 'libplatform.so').write_bytes(b'C prebuilt')
        native.stage(self.root, self.inputs, self.output)
        for abi in native.ABIS:
            self.assertEqual((self.output / abi / native.LIB).read_bytes(),
                             (self.inputs / abi / native.LIB).read_bytes())
            self.assertEqual((self.output / abi / 'libplatform.so').read_bytes(), b'C prebuilt')

    def test_missing_emulator_library_fails(self):
        (self.inputs / 'x86_64' / native.LIB).unlink()
        with self.assertRaises(OSError):
            native.verify(self.root, self.inputs)

    def test_missing_receipt_fails_even_when_library_exists(self):
        (self.inputs / 'arm64-v8a/provenance.json').unlink()
        with self.assertRaises(OSError):
            native.verify(self.root, self.inputs)

    def test_dirty_source_fails_without_git_revision_change(self):
        self.source.write_text('edited source')
        with self.assertRaisesRegex(ValueError, r'Rust source inputs changed.*src/lib.rs \[changed\]'):
            native.verify(self.root, self.inputs)

    def test_build_script_change_invalidates_receipts(self):
        (self.root / 'scripts/check_rust_android_native.sh').write_text('changed flags')
        with self.assertRaisesRegex(ValueError, 'Rust source inputs changed.*check_rust_android_native.sh'):
            native.verify(self.root, self.inputs)

    def test_gradle_staging_change_invalidates_receipts(self):
        (self.root / 'shared/rust-android-inputs.gradle').write_text('changed staging')
        with self.assertRaisesRegex(ValueError, 'Rust source inputs changed.*rust-android-inputs.gradle'):
            native.verify(self.root, self.inputs)

    def test_added_source_is_identified(self):
        (self.source.parent / 'new.rs').write_text('new source')
        with self.assertRaisesRegex(ValueError, r'src/new.rs \[added\]'):
            native.verify(self.root, self.inputs)

    def test_removed_source_is_identified(self):
        self.source.unlink()
        with self.assertRaisesRegex(ValueError, r'src/lib.rs \[removed\]'):
            native.verify(self.root, self.inputs)

    def test_tampered_library_fails(self):
        with (self.inputs / 'armeabi-v7a' / native.LIB).open('ab') as out:
            out.write(b'tampered')
        with self.assertRaisesRegex(ValueError, 'armeabi-v7a: Rust library bytes do not match'):
            native.verify(self.root, self.inputs)

    def test_wrong_abi_cannot_be_stamped(self):
        (self.inputs / 'x86_64' / native.LIB).write_bytes(
            (self.inputs / 'arm64-v8a' / native.LIB).read_bytes())
        with self.assertRaisesRegex(ValueError, 'ELF'):
            native.stamp(self.root, self.inputs, 'x86_64', native.source_digest(self.root))

    def test_non_shared_elf_cannot_be_stamped(self):
        for abi in native.ABIS:
            for elf_type in [0, 1, 2]:
                with self.subTest(abi=abi, elf_type=elf_type):
                    library = self.inputs / abi / native.LIB
                    data = bytearray(library.read_bytes())
                    data[16:18] = elf_type.to_bytes(2, 'little')
                    library.write_bytes(data)
                    with self.assertRaisesRegex(ValueError, 'ET_DYN'):
                        native.stamp(self.root, self.inputs, abi, native.source_digest(self.root))

    def test_non_shared_elf_fails_verification_even_with_matching_checksum(self):
        library = self.inputs / 'x86_64' / native.LIB
        data = bytearray(library.read_bytes())
        data[16:18] = (2).to_bytes(2, 'little')
        library.write_bytes(data)
        receipt_path = self.inputs / 'x86_64/provenance.json'
        receipt = json.loads(receipt_path.read_text())
        receipt['library_sha256'] = native.sha256(library)
        receipt_path.write_text(json.dumps(receipt))
        with self.assertRaisesRegex(ValueError, 'x86_64: ELF is not an ET_DYN'):
            native.verify(self.root, self.inputs)

    def test_old_receipt_requires_rebuild(self):
        receipt_path = self.inputs / 'arm64-v8a/provenance.json'
        receipt = json.loads(receipt_path.read_text())
        receipt['schema'] = 1
        del receipt['source_files']
        receipt_path.write_text(json.dumps(receipt))
        with self.assertRaisesRegex(ValueError, 'unsupported or invalid Rust provenance metadata'):
            native.verify(self.root, self.inputs)

    def test_inconsistent_file_fingerprints_fail(self):
        receipt_path = self.inputs / 'arm64-v8a/provenance.json'
        receipt = json.loads(receipt_path.read_text())
        receipt['source_files'][f'{native.CRATE}/src/lib.rs'] = '0' * 64
        receipt_path.write_text(json.dumps(receipt))
        with self.assertRaisesRegex(ValueError, 'inconsistent Rust provenance metadata'):
            native.verify(self.root, self.inputs)

    def test_source_change_during_build_cannot_be_stamped(self):
        before = native.source_digest(self.root)
        self.source.write_text('changed during build')
        with self.assertRaisesRegex(ValueError, 'during compilation'):
            native.stamp(self.root, self.inputs, 'arm64-v8a', before)

    def test_partial_rebuild_does_not_bless_other_abis(self):
        self.source.write_text('new source')
        native.stamp(self.root, self.inputs, 'arm64-v8a', native.source_digest(self.root))
        with self.assertRaisesRegex(ValueError, 'armeabi-v7a'):
            native.verify(self.root, self.inputs)

    def test_generated_cargo_outputs_do_not_change_source_digest(self):
        before = native.source_digest(self.root)
        generated = self.root / native.CRATE / 'target/debug/output'
        generated.parent.mkdir(parents=True)
        generated.write_text('build output')
        self.assertEqual(before, native.source_digest(self.root))

    def test_stray_files_do_not_invalidate_receipts_without_git(self):
        before = native.source_digest(self.root)
        self.assertFalse((self.root / '.git').exists())
        for name in ['.DS_Store', 'Thumbs.db', 'helper.pyc', 'helper.pyo', '.lib.rs.swp',
                     '.lib.rs.swo', 'lib.rs~', '__pycache__/helper.pyc', '.git/index']:
            with self.subTest(name=name):
                stray = self.source.parent / name
                stray.parent.mkdir(parents=True, exist_ok=True)
                stray.write_text('local tooling output')
                self.assertEqual(before, native.source_digest(self.root))
                native.verify(self.root, self.inputs)

    def test_hidden_cargo_configuration_is_still_a_build_input(self):
        config = self.root / native.CRATE / '.cargo/config.toml'
        config.parent.mkdir()
        config.write_text('[build]\nrustflags = ["-C", "opt-level=2"]\n')
        with self.assertRaisesRegex(ValueError, r'.cargo/config.toml \[added\]'):
            native.verify(self.root, self.inputs)

    def test_failed_verification_preserves_previous_staging(self):
        self.output.mkdir()
        marker = self.output / 'marker'
        marker.write_text('previous successful output')
        self.source.write_text('changed')
        with self.assertRaises(ValueError):
            native.stage(self.root, self.inputs, self.output)
        self.assertTrue(marker.exists())


if __name__ == '__main__':
    unittest.main()
