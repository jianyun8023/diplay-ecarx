# Prebuilt native libraries — provenance

APK builds consume these `.so` files without compiling native code. The original three
libraries below come from `DiPlay-0.2.15.apk`; `libdiplay_media.so` is built from our Rust source (below).

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

## Media data plane

`libdiplay_media.so` is the media data plane (see `docs/ARCHITECTURE.zh-CN.md`): it receives and opens the CarPlay
screen and audio streams, runs the video decode loop, decodes and encodes Opus, processes PCM, plays through AAudio
and seals the microphone uplink. It also contains the optional, default-off USB NCM/tun bridge. It is built from
`../rust/diplay-media` (our code plus [ring 0.17.14](https://crates.io/crates/ring/0.17.14), whose ChaCha20
and Poly1305 come from BoringSSL's assembly; [opus-pure 0.2.3](https://crates.io/crates/opus-pure/0.2.3), a pure-Rust port of libopus; jni-sys and libc).
There is no prebuilt Opus library any more. `Cargo.lock` pins every crate.
Build it from the repository root with
`ANDROID_NDK_HOME=/path/to/ndk/28.2.13676358 shared/src/main/rust/diplay-media/build.sh`;
it needs rustup targets `aarch64-linux-android` and `armv7-linux-androideabi`. This script is not part
of Gradle or CI builds; `cargo test` in that directory runs the host tests. Both shipped ABIs were rebuilt from
the current source on 2026-11-12 with NDK `28.2.13676358`; the hashes below identify that build.
When the library cannot load, the Kotlin receivers and the platform cipher (Conscrypt) handle the same data.

| ABI | Library | Bytes | SHA-256 |
|---|---|---:|---|
| arm64-v8a | libdiplay_media.so | 1105888 | `6f0423223b2f839e5dda7115d215149bd87779cd4ab64e0b1a9f4cc4e24bd361` |
| armeabi-v7a | libdiplay_media.so | 757868 | `b89004174ded4d7d7e48cdc0340f0533e809bb31adc0f21326b34a669f55ba33` |

The crate's own code is covered by this repository's GPL license. The licenses of the linked crates are in
[the Rust crate licenses](../../../../docs/licenses/dependencies/Rust-crates-LICENSES.txt).
