# AGENTS.md

Instructions for coding agents that work on DiPlay.

## Vehicle scope

Production targets Android 9 / API 28, ECARX 753 / Qualcomm 820A, with the vehicle platform signature. Ship the ARM64 (`arm64-v8a`) package by default. ARMv7 (`armeabi-v7a`) and x86_64 (emulator) are opt-in only: build and test them explicitly with `-PdiplayAbis=arm64-v8a,armeabi-v7a,x86_64` or `scripts/check_rust_android_native.sh armeabi-v7a x86_64`; they are not built or tested by default. The in-app updater downloads the ARM64 package. Other Android releases are debugging environments. App runtime, permission setup and recovery must not depend on ADB. Use [the permission checklist](docs/ADB_AND_PLATFORM_PERMISSIONS.zh-CN.md) and issue #21 as the implementation and validation reference. Vendor interfaces, firmware behavior and what has been verified on the car are recorded in [the platform notes](docs/ECARX_753_PLATFORM_NOTES.zh-CN.md); register a new interface, property id or broadcast there before using it.

## Checks

Match local validation to the actual change:

- Documentation, comments, credits and translation/string-only edits that do not change behavior, layout, resource keys/placeholders or build configuration need only `git diff --check` and a focused review of content, references, locale consistency and resource escaping. Do not launch full Gradle tests, lint, APK builds or native compilation for these changes; leave full validation to CI. Do not delay an explicitly requested commit or push while waiting for local full checks.
- For code, behavior or build changes, run relevant local checks. Run the full suite locally only when concrete integration risk requires it, when investigating a CI failure, or when the user requests it.
- If the user says to stop local checks or leave validation to CI, stop the checks and do not restart them unless asked. Report pending CI honestly; do not imply that uncompleted checks passed.

The full CI command from `.github/workflows/android.yml` is a reference, not a requirement for every delivery:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest \
  :mobile:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :maphost:assembleDebug
```

Set `ANDROID_HOME` or `local.properties` if Gradle cannot find the SDK.

## Task guides

Before editing, read the guide for the affected owner and its callers. These linked rules are required for the matching task; they are not loaded automatically just because the files exist. Read both sides of a cross-owner change. Start with the task row, then use the guide's headings and cross-links; unrelated guides need not be loaded.

| Task | Required guide |
| --- | --- |
| Connection lifecycle/recovery, Bluetooth/hotspot, USB/VPN, wired handshake, dashboard content | [Session and transport](docs/agent-guides/SESSION_AND_TRANSPORT.md) |
| Audio/video, codecs, microphone/echo, focus, NowPlaying/artwork/auto-play, shared outputs | [Media](docs/agent-guides/MEDIA.md) |
| RTSP/bplist, pairing/crypto, iAP2, AirPlay/SETUP, timing/screen framing, MQTT | [Protocols](docs/agent-guides/PROTOCOLS.md) |
| HUD/DIM, navigation/lanes, location/EV/speed, identification, HID/touch/wheel keys | [Vehicle](docs/agent-guides/VEHICLE.md) |
| Host views, settings composition/categories, controls, translations or layout | [UI and settings](docs/agent-guides/UI_AND_SETTINGS.md) |

Use the current checkout's files and the acceptance matrix linked below. Parent-workspace handoffs, old branches and dated reports are historical evidence, not current task gates. Locate moved files with `rg --files` or symbol search rather than assuming an old path still exists.

Keep this root entry below 8 KiB. Update detailed contracts in their owning guide and maintain this index when scope changes; do not append per-owner implementation history here.

## Source ownership

The module layout and current ownership are described in [the architecture notes](docs/ARCHITECTURE.zh-CN.md). The Rust core migration was merged in PR #97; its old work branches and closed issue #96 are historical records, not instructions to continue a migration. Rust owns protocols, session state, media queues and scheduling, recovery, codec/route decisions, navigation state, vehicle-data conversion and input protocols. Kotlin remains the Android host and platform adapter: UI, lifecycle, permissions, focus requests, Bluetooth/hotspot, USB/VPN, storage and vehicle SDK calls. Platform adapters report events/capabilities and execute core requests; they do not keep a second copy of core state.

The linked ownership guides describe production boundaries. Change one coherent owner and its callers/tests at a time; preserve Android 9 calibration, routing and recovery behavior, and update these rules when ownership changes. Reuse existing Rust media code. Before importing protocol code, compare the relevant [LIVI Rust crates](https://github.com/f-io/LIVI/tree/main/native/crates) and test vectors against our wire behavior; record the source revision, preserve required attribution, and check API 28 and all supported ABIs. Do not import its complete application/runtime merely to reuse a protocol module.

For behavior changes, run affected-module tests according to the Checks policy above. Track current acceptance and remaining work in [the acceptance matrix](docs/RUST_CORE_REMAINING.zh-CN.md), with links to supporting issues/PRs. Closed issues #96/#98/#99 preserve history; their closure does not establish every device/car acceptance item.

Keep shared-only internal-owner contracts in `shared/src/testCoreInternal/kotlin`, included by shared JVM/androidTest source sets only. General `testCore` references and `testCoreShadows` remain available to common tests; do not make production internal APIs public to compile fixtures in unrelated modules.

Migrated production core facades require native, including protocols, pairing, NTP, session state, media queues/clocks/DSP, navigation/HUD and vehicle conversion. Keep independent Kotlin test references and an explicit required-core load failure at startup. Bouncy Castle and Concentus are test-only; hardware/SDK capability fallbacks have separate platform requirements. Source implementation and necessary compilation do not establish combined device acceptance.

Rust sources live under `shared/src/main/rust/diplay-media/src/`: `protocols` (wire codecs/crypto/pairing/timing), `session` (lifecycle/recovery/deadlines/subscriptions), `media/audio` and `media/video` (media owners), `vehicle` (HID/vehicle/navigation/location), `platform` (JVM/NDK/shared stream lifetime), and `transport` (USBMUX/Lockdown/NCM). `lib.rs` only assembles these six namespaces. Keep an owner's private JNI module with that owner; do not add root compatibility re-exports, `#[path]` aliases, a catch-all utilities layer or split private state merely to relocate FFI. Preserve target-specific cfg, JNI/C ABI and existing public/private boundaries when moving code.
