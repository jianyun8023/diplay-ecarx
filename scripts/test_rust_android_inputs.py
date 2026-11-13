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
        for abi, (cls, machine) in native.ABIS.items():
            directory = self.inputs / abi
            directory.mkdir(parents=True)
            data = bytearray(64)
            data[:6] = b'\x7fELF' + bytes([cls, 1])
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
        with self.assertRaisesRegex(ValueError, 'provenance'):
            native.verify(self.root, self.inputs)

    def test_build_script_change_invalidates_receipts(self):
        (self.root / 'scripts/check_rust_android_native.sh').write_text('changed flags')
        with self.assertRaisesRegex(ValueError, 'provenance'):
            native.verify(self.root, self.inputs)

    def test_tampered_library_fails(self):
        with (self.inputs / 'armeabi-v7a' / native.LIB).open('ab') as out:
            out.write(b'tampered')
        with self.assertRaisesRegex(ValueError, 'provenance'):
            native.verify(self.root, self.inputs)

    def test_wrong_abi_cannot_be_stamped(self):
        (self.inputs / 'x86_64' / native.LIB).write_bytes(
            (self.inputs / 'arm64-v8a' / native.LIB).read_bytes())
        with self.assertRaisesRegex(ValueError, 'ELF'):
            native.stamp(self.root, self.inputs, 'x86_64', native.source_digest(self.root))

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
