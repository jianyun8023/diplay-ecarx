import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { cpSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import test from 'node:test';

// ast-grep's snippet tests exercise the matcher without applying its files/ignores.
test('architecture rules apply at their production source paths', t => {
  const root = mkdtempSync(join(tmpdir(), 'diplay-architecture-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  cpSync('sgconfig.yml', join(root, 'sgconfig.yml'));
  cpSync('config/architecture/rules', join(root, 'config/architecture/rules'), { recursive: true });

  const core = 'shared/src/main/rust/diplay-media/src';
  const binding = 'external fun read(): Long';
  const loader = 'fun load() = System.loadLibrary("diplay_media")';
  const oracle = 'import org.concentus.OpusDecoder';
  const pointer = 'unsafe fn read(p: *const u8) -> u8 { *p }';
  const close = 'fn read(fd: i32) { unsafe { libc::close(fd); } }';
  const schedule = 'fn retry() { std::thread::sleep(delay); }';
  const cases = [
    ['common/src/main/java/Host.kt', binding, 'dp-host-jni'],
    ['samples/maphost/src/release/kotlin/Map.kt', binding, 'dp-host-jni'],
    ['shared/src/main/java/Owner.kt', binding],
    ['mobile/src/debug/kotlin/Host.kt', loader, 'dp-core-loader'],
    ['shared/src/main/java/com/shihab/diplay/airplay/NativeMedia.kt', loader],
    ['common/src/main/kotlin/Host.kt', 'val hasCore = NativeMedia.available', 'dp-core-fallback'],
    ['shared/src/main/kotlin/Codec.kt', oracle, 'dp-test-oracle'],
    ['shared/src/testCore/kotlin/Codec.kt', oracle],
    ['common/src/test/kotlin/Host.kt', binding],
    [`${core}/lib.rs`, 'pub use media::audio;', 'dp-rust-crate-root'],
    [`${core}/vehicle/mod.rs`, '#[path = "../old.rs"] mod compat;', 'dp-rust-module-path'],
    [`${core}/protocols/rtsp/mod.rs`, pointer, 'dp-rust-protocol-unsafe'],
    [`${core}/protocols/rtsp/jni.rs`, pointer],
    [`${core}/protocols/iap2_body.rs`, pointer],
    [`${core}/protocols/mqtt/jni.rs`, 'extern "system" fn Java_example() { panic!(); }', 'dp-jni-panic'],
    [`${core}/session/controller/mod.rs`, schedule, 'dp-session-thread'],
    [`${core}/media/audio/receiver.rs`, close, 'dp-receiver-close'],
    [`${core}/platform/stream.rs`, close],
  ];

  const expected = [];
  for (const [file, source, rule] of cases) {
    mkdirSync(join(root, dirname(file)), { recursive: true });
    writeFileSync(join(root, file), source + '\n');
    if (rule) expected.push(`${file} ${rule}`);
  }
  const scan = spawnSync('ast-grep', ['scan', '--json=compact', 'shared', 'common', 'mobile', 'samples'], {
    cwd: root, encoding: 'utf8',
  });
  assert.ifError(scan.error);
  assert.equal(scan.status, 1, scan.stderr);
  const findings = JSON.parse(scan.stdout).map(({ file, ruleId }) => `${file} ${ruleId}`);
  assert.deepEqual(findings.sort(), expected.sort());
});
