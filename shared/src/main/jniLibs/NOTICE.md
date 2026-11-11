# Prebuilt native libraries — provenance

APK builds consume these `.so` files without compiling native code. The original three
libraries below come from `DiPlay-0.2.15.apk`; the Opus libraries have separate provenance below.

## Why

`shared/src/main/jni/` holds the real sources (`local_hotspot_radio.c`, `speex_echo_jni.c`,
`linux_i2c_jni.c`, `speexdsp/`) and `Android.mk` / `Application.mk`; upstream builds them with
`ndkBuild`. The build was done on Windows from a checkout whose path contained non-ASCII
characters, and the NDK's GNU make cannot cope with that: it renders the NDK prefix with
replacement characters and the compile step then fails with
`fatal error: 'jni.h' file not found`. Switching to prebuilt libraries avoids rebuilding
the C sources during ordinary APK builds.

The C sources and the makefiles are left in place, so a build from an ASCII-only path reproduces
the upstream behaviour unchanged; only `shared/build.gradle` needs its `externalNativeBuild` block
put back.

## Integrity

Compare against the source APK (must match exactly):

| ABI | File | Bytes | SHA-256 (first 24) |
|---|---|---|---|
| arm64-v8a | liblocal_hotspot_radio.so | 4,896 | `5253167fd86de913a84e9163` |
| arm64-v8a | libspeex_echo.so | 56,856 | `84fca93c08fc3fc19c148da0` |
| arm64-v8a | libxcertplay_i2c.so | 6,760 | `e4a5921518f430dc58884a85` |
| armeabi-v7a | liblocal_hotspot_radio.so | 3,548 | `ddf3ef5f9345343f534bcc6b` |
| armeabi-v7a | libspeex_echo.so | 33,612 | `aef8140c154517a151840354` |
| armeabi-v7a | libxcertplay_i2c.so | 4,676 | `50696d4d2b181167bf6c2899` |
| x86_64 | liblocal_hotspot_radio.so | 4,864 | `4442b3796e5d56e807066da0` |
| x86_64 | libspeex_echo.so | 72,744 | `dff57dc476901931cfe5f1a0` |
| x86_64 | libxcertplay_i2c.so | 6,648 | `52bb21d092673f6ba0883289` |

`libandroidx.graphics.path.so` is **not** copied: it ships inside the
`androidx.graphics:graphics-path` AAR and is packaged by Gradle.

## Source and distribution boundary

The table covers the nine original native binaries. Their bytes and hash prefixes match the
repository's current files. This integrity check does not establish that a rebuild of the retained
sources corresponds to the extracted binaries; the exact upstream source revision and necessary build
scripts still need verification before relying on them as corresponding source. GPL compliance does not
require byte-for-byte reproducible builds; the unresolved point here is source correspondence.

`Android.mk` still declares only `xcertplay_i2c`, `local_hotspot_radio` and `speex_echo`.

SpeexDSP's BSD-style notices remain in `../jni/speexdsp/COPYING` and its source headers.
The production APK ABI filters select ARM64 and ARMv7; x86_64 files remain in the source tree.
Keep the applicable licenses and corresponding source when redistributing native binaries.

## Opus microphone encoder

`libopus.so` is copied **byte for byte**, without rebuilding Opus, from the `jni/<abi>/` entries in
[`io.voxkit:kopus-android:1.0.1`](https://repo.maven.apache.org/maven2/io/voxkit/kopus-android/1.0.1/kopus-android-1.0.1.aar).
The AAR SHA-256 is `3ba435a7219d2c030590b91bfc5c8e525a1d367dbdca7059f7729727faf75c1c`,
verified against its Maven Central `.aar.sha256`. Its exported version is `libopus 1.5.2` and its
ELF Android note targets API 24 / NDK r28c. It needs only Android's libc, libm and libdl.
The Kotlin wrapper and `libkopus.so` are not included: that wrapper does not expose the bitrate,
complexity and signal controls required by the microphone uplink.

`libopus_encoder.so` is our small JNI bridge, built once from `../jni/opus_encoder_jni.c` using
NDK `28.2.13676358`, API 24 and `-O2`. It links to the prebuilt `libopus.so`; no Opus source is compiled.
To regenerate just this bridge, run `ANDROID_NDK_HOME=/path/to/ndk/28.2.13676358 bash scripts/build_opus_bridge.sh`
from the repository root. This script is not part of Gradle or CI builds.

| ABI | Library | SHA-256 |
|---|---|---|
| arm64-v8a | libopus.so | `7a35e438c5e906f64f721d3f4c4c60d61a404a8b351ab6712520a6026a4f50b5` |
| armeabi-v7a | libopus.so | `debd799348d257081ac20493e2d61d4f3b3907faf6b76e3610059a86bfccdeb8` |
| arm64-v8a | libopus_encoder.so | `a9e5559dba940e9b4b2688fce59118b9b3f8f5a3c4fb116d50fb5a817f49741c` |
| armeabi-v7a | libopus_encoder.so | `6aa6745ffb695bf4b28ee9c5006ddebabe775fcddcbcd3024f7db07fff5ebe62` |

The matching public C headers and BSD-style license are retained under `../jni/opus/`, taken from
the [official Opus 1.5.2 source archive](https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz).
The JNI bridge is covered by this repository's GPL license. The original Java Concentus encoder
remains available when native loading or initialization fails. See the [third-party notices](../../../../docs/THIRD_PARTY_NOTICES.md).

## Media stream ChaCha20-Poly1305

`libdiplay_chacha.so` opens the ChaCha20-Poly1305 frames and packets of the CarPlay screen and audio
streams. It is built from `../rust/diplay-chacha` (our JNI glue plus [ring 0.17.14](https://crates.io/crates/ring/0.17.14),
whose ChaCha20 and Poly1305 come from BoringSSL's assembly). `Cargo.lock` pins every crate.
Build it from the repository root with
`ANDROID_NDK_HOME=/path/to/ndk/28.2.13676358 shared/src/main/rust/diplay-chacha/build.sh`;
it needs rustup targets `aarch64-linux-android` and `armv7-linux-androideabi`. This script is not part
of Gradle or CI builds. With rustc 1.97.1 and NDK `28.2.13676358` two clean builds give identical bytes.
When the library cannot load, the platform cipher (Conscrypt) opens the same data.

| ABI | Library | Bytes | SHA-256 |
|---|---|---:|---|
| arm64-v8a | libdiplay_chacha.so | 286992 | `5f2a90fc795114dd76062c3a8aa58bb2817813676d2e5bba9e0935d1e601d550` |
| armeabi-v7a | libdiplay_chacha.so | 214028 | `db325c2b5b8b2bbc1d798fa51022a5c6a471cd7ec89a4d8a53e498abac09b2b6` |

The JNI glue is covered by this repository's GPL license. The licenses of the linked crates are in
[the Rust crate licenses](../../../../docs/licenses/dependencies/Rust-crates-LICENSES.txt).
