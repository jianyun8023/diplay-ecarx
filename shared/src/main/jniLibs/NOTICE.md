# Prebuilt native libraries — provenance

These `.so` files are **not** built here. They are extracted verbatim from the published
`DiPlay-0.2.15.apk` (see `适配文件/DiPlay-0.2.15.apk`).

## Why

`shared/src/main/jni/` holds the real sources (`local_hotspot_radio.c`, `speex_echo_jni.c`,
`linux_i2c_jni.c`, `speexdsp/`) and `Android.mk` / `Application.mk`; upstream builds them with
`ndkBuild`. This checkout lives under `C:\Users\啾啾\Desktop\共享\carplay`, and the NDK's GNU make
cannot cope with that non-ASCII path — it renders the NDK prefix as `C:/Users/��/` and the compile
step then fails with `fatal error: 'jni.h' file not found`. Switching to prebuilt libraries keeps
the build reproducible here without touching a single line of the C sources.

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

## libopus_encoder.so (built here, not extracted)

`libopus_encoder.so` is the exception: it is **compiled from source in this repo**, not extracted
from the published APK. It wraps libopus 1.5.2 (fixed-point) via `jni/opus_encoder_jni.c` to give
820A / Android 9 head units an in-process Opus **encoder** — Android's MediaCodec only ships the
Opus encoder on API 30+, so the CarPlay telephony uplink would otherwise go silent.

- Sources: `jni/opus/` (celt + silk + src), `jni/opus_encoder_jni.c`, `jni/Android.mk`,
  `jni/opus/Android.opus.mk`.
- Build (from `src/shared/src/main/jni`):
  `ndk-build APP_ABI=arm64-v8a APP_PLATFORM=android-25 NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=Android.mk`
  (`armeabi-v7a` needs `APP_PLATFORM=android-21`; NDK r28 removed the armeabi-v7a sysroot above 21.)
- `x86_64` has no `libopus_encoder.so` here: the r28c sysroot in this environment lacks the
  x86_64 crt/libc. The Kotlin shim guards `System.loadLibrary` with `runCatching`, so x86_64 falls
  back silently; 820A is arm64-v8a and is unaffected.
