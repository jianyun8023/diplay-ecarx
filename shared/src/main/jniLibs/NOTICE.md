# Prebuilt native libraries — provenance

APK builds consume these `.so` files without compiling native code. All four libraries are rebuilt
from the sources kept in this repository: the three C libraries from `shared/src/main/jni`
(`local_hotspot_radio`, `speex_echo`, `diplay_i2c`) with NDK `28.2.13676358`, and
`libdiplay_media.so` from the Rust source under `shared/src/main/rust/diplay-media`.

## Why the C libraries are checked in

`shared/src/main/jni/` holds the real sources (`local_hotspot_radio.c`, `speex_echo_jni.c`,
`linux_i2c_jni.c`, `speexdsp/`) and `Android.mk` / `Application.mk`; upstream builds them with
`ndkBuild`. Ordinary Gradle APK builds do not run `ndk-build`, so the compiled libraries are kept
under `jniLibs/` as prebuilt binaries. They were rebuilt from these sources on 2026-11-13 with NDK
`28.2.13676358` at API 28 from an ASCII-only checkout path. Rebuild with:

```sh
$ANDROID_NDK_HOME/ndk-build APP_ABI="arm64-v8a armeabi-v7a x86_64" \
  NDK_PROJECT_PATH=shared/src/main/jni \
  APP_BUILD_SCRIPT=shared/src/main/jni/Android.mk \
  NDK_APPLICATION_MK=shared/src/main/jni/Application.mk
# then copy libs/<abi>/*.so into shared/src/main/jniLibs/<abi>/
```

The NDK's GNU make cannot handle a checkout path containing non-ASCII characters; keep the build
path ASCII-only. Alternatively the `externalNativeBuild` block can be put back in
`shared/build.gradle` and the C sources compiled during the APK build.

## Integrity

Rebuilt from the retained sources on 2026-11-13 with NDK `28.2.13676358` (production ABI filters are
ARM64 and ARMv7; x86_64 files remain for the source tree / emulator):

| ABI | File | Bytes | SHA-256 |
|---|---|---:|---|
| arm64-v8a | liblocal_hotspot_radio.so | 4,880 | `7d226e17f474678b1c0c8cb8de203b5f15df46e8939303443fc1df01fd925c70` |
| arm64-v8a | libspeex_echo.so | 56,856 | `fadea4a56f1593d14d2bee1a7401441d14c4fddaf863d694c7de1591bbb67ab3` |
| arm64-v8a | libdiplay_i2c.so | 6,728 | `3c804d5fde1ab20407d1a56df3e324efa5a7de555db5e613b9918ccb296f2fcd` |
| armeabi-v7a | liblocal_hotspot_radio.so | 3,532 | `2f927a99d46ace2fa7ddc51261d57ee71e9601a499cb1ef97ffc440c72235f7f` |
| armeabi-v7a | libspeex_echo.so | 33,580 | `a2aada56c83ba5d89a6d8696bbd61b0ff564fb1602784c27dc852834c845bb04` |
| armeabi-v7a | libdiplay_i2c.so | 4,660 | `0c61f8374ae442043481116d6fb4333b4ed0b76cc95d5364e9e5488b8d9fdf69` |
| x86_64 | liblocal_hotspot_radio.so | 4,848 | `cb4e90c3c6e0b8e8bb19fd562c5b6be417e2e54c7f5480d7a7b77b7700e50031` |
| x86_64 | libspeex_echo.so | 72,712 | `541b9bd49607619182edc5ddc264837151c15e5375d57eb79987de515c5ec0cb` |
| x86_64 | libdiplay_i2c.so | 6,616 | `a6fa3e8603b87547f3d503e2e40c912794f253d4104f0465f24f071001ac0cbc` |

`libandroidx.graphics.path.so` is **not** copied: it ships inside the
`androidx.graphics:graphics-path` AAR and is packaged by Gradle.

## Source and distribution boundary

The table covers the nine native binaries rebuilt from the retained C sources. Their bytes and hashes
above match the repository's current files. GPL compliance does not require byte-for-byte
reproducible builds; these binaries are built from the in-repo sources listed above with the pinned
NDK, so they are the corresponding source-built artifacts (they are no longer byte-identical to the
old `DiPlay-0.2.15.apk` copies).

`Android.mk` still declares only `diplay_i2c`, `local_hotspot_radio` and `speex_echo`.

SpeexDSP's BSD-style notices remain in `../jni/speexdsp/COPYING` and its source headers.
The production APK ABI filters select ARM64 and ARMv7; x86_64 files remain in the source tree.
Keep the applicable licenses and corresponding source when redistributing native binaries.

## Rust CarPlay core

`libdiplay_media.so` contains the migrated CarPlay core (see `docs/ARCHITECTURE.zh-CN.md`): it receives and opens the CarPlay
screen and audio streams, owns compressed audio queues, buffered-music anchors/controls/backpressure and music clock trim,
runs the video decode loop, decodes and encodes Opus, processes PCM, plays through AAudio,
frames/encodes RTSP and binary plists, owns PairVerify/PairSetup/SRP/MFiSAP, control/event encryption, NTP and route guidance,
controller/VPN/wireless generations, recovery, watchdogs, AirPlay negotiation and iAP2 handshake/reporting state,
USBMUX/Lockdown, iAP2 framing/body/link/file transfer, type130/tunnel queues and the default NCM core.
It owns audio receiver-to-renderer routing, AAC/PCM processing, output scheduling, shared call PCM/capture clocks,
video timing/queues/codec policy, incremental playback state, hotspot address/readiness and Wi-Fi Direct retry policy,
AirPlay info/control/video field schemas and Bonjour publication policy, HID/touch slots,
EV/identification/PASCD/NMEA and existing HUD conversions. Platform SDK actions, hardware keys,
Calendar/Formatter and storage remain Kotlin. The experimental native NCM USB/TUN FD fast path is attempted by default unless explicitly disabled; if it cannot start, the Java I/O adapter still uses the Rust NCM core. It is built from
`../rust/diplay-media` (our code plus [ring 0.17.14](https://crates.io/crates/ring/0.17.14), whose ChaCha20
and Poly1305 come from BoringSSL's assembly; [libopus](https://github.com/xiph/opus) 1.5 series, vendored through [libopus_sys 0.3.3](https://crates.io/crates/libopus_sys/0.3.3) and compiled statically with its NEON/SSE assembly; libopus_sys (MIT), jni-sys and libc).
The C libopus is compiled into this same `.so` from vendored sources; no separate `libopus.so` is shipped. `Cargo.lock` pins every crate.
Build it from the repository root with
`ANDROID_NDK_HOME=/path/to/ndk/28.2.13676358 shared/src/main/rust/diplay-media/build.sh`;
it needs rustup targets `aarch64-linux-android` and `armv7-linux-androideabi`. Gradle APK builds still package
the checked-in Android libraries. The Android checks workflow runs `scripts/check_rust_android_native.sh`
on every pull request and `main`／`dev` push: it cross-compiles both ABIs with the pinned Rust／NDK versions,
checks ELF architecture, the 494 Java JNI exports, `JNI_OnLoad` and identical JNI export sets, then stages
the freshly built libraries for Debug／Release APK packaging. The pair and source revision are retained as
an Actions artifact. checked in hashes below identify the repository copies, not necessarily the CI-built
APK payload; byte-level equality across build hosts is not required. `cargo test` in this directory runs the host tests. Both shipped ABIs were rebuilt on
2026-11-13 with Rust `1.97.1 (8bab26f4f 2026-07-14)`, NDK `28.2.13676358` and API 28. The source is the
#100 package-rename batch based on `70733a9b`; that base commit alone does not contain this batch. Its
source manifest SHA-256 is
`5c68950c734d95a97e8c0c1c7c81e29ea0bfd810c7cc5e91dbda7e9d8b3dad27`.
The digest is SHA-256 of the sorted per-file `shasum -a 256` output, including repository-relative paths,
for the crate's Rust sources, manifests/lockfile, toolchain pin, build script and any crate Cargo configuration:

```sh
rg --files --hidden shared/src/main/rust/diplay-media \
  -g '*.rs' -g 'Cargo.toml' -g 'Cargo.lock' -g 'build.sh' \
  -g 'rust-toolchain.toml' -g '**/.cargo/config*' -g '*.cmake' -g '!**/target/**' |
  LC_ALL=C sort | xargs shasum -a 256 | shasum -a 256
```

The source manifest was unchanged before and after rebuilding. The hashes below identify that build;
each ABI exports the same 494 Java JNI methods plus JNI_OnLoad. ELF headers identify AArch64/ARM respectively,
and both Android identification notes record API 28 (`0x1c`) and NDK r28c build `13676358`.
A required-core load failure is explicit. Real SDK capability fallback, such as AudioTrack when AAudio cannot open,
remains platform behavior; there is no production Kotlin protocol/DSP substitute.

| ABI | Library | Bytes | SHA-256 |
|---|---|---:|---|
| arm64-v8a | libdiplay_media.so | 1696096 | `283b0ea9efc16f3eed378d7deeaef2e328c656506078f52ce85214fd3fdb524f` |
| armeabi-v7a | libdiplay_media.so | 1181188 | `66c3316a366d968ca203c54885284cf784ae9099c5a87faf0c79fb7cea05a13c` |

The crate's own code is covered by this repository's GPL license. The licenses of the linked crates are in
[the Rust crate licenses](../../../../docs/licenses/dependencies/Rust-crates-LICENSES.txt).
