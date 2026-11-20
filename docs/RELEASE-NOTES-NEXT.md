# DiPlay ECARX release notes

This page summarizes **[v153](https://github.com/jianyun8023/diplay-ecarx/releases/tag/v153)** (2026-11-20, commit `c0731a91`, Android versionCode `153`) and earlier milestones, based on `dev` and upstream DiPlay 0.2.22. For the latest published release, see [latest Release](https://github.com/jianyun8023/diplay-ecarx/releases/latest). The full record—including post-v153 changes on `dev`—is in the [changelog](../CHANGELOG.md) (Simplified Chinese); this page is the English summary.

**Releases now ship ARM64 (`arm64-v8a`) only by default** for the adapted Qualcomm 820A / ECARX 753 head unit. ARMv7 (`armeabi-v7a`) and x86_64 (emulator) are opt-in local builds (`-PdiplayAbis=arm64-v8a,armeabi-v7a,x86_64`) and are no longer published with Releases.

- **Post-v153 on `dev` (unreleased)**: reverts the opt-in ECARX navigation-audio duck handshake from PR #126 (PR #131), defaults ECARX navigation audio routing to usage 12 / contentType 20 with standard 12/1 fallback and removes audio-channel previews (PR #132), switches the cluster arrival strip to 24-hour time (`78abe4d5`), and keeps the native video event callback under R8 (`db508105`).
- **v153 (2026-11-20)**:
  - **ARM64-only default**: build, CI and Release default to `arm64-v8a`; HEVC now defaults to off for broader head-unit compatibility.
  - **Cluster arrival strip** (PR #129): **Settings → Navigation → ECARX navigation** adds a live toggle (on by default) showing arrival time, remaining duration and distance in the measured free corner of the projected V3 cluster map, following the map's day/night palette.
  - **Cleaner post-call music recovery** (PR #127): stops and releases the call downlink track before tearing down the microphone and restoring normal audio mode, eliminating the underrun observed on the Xiaomi 8 bench.
  - **Architecture & stability hardening** (PR #128, #130 and prior commits): named video first-frame/sample events across JNI, single-point `duckAudio`/`unduckAudio` interpretation, named Lockdown JNI action adapter and 5-word state length validation, per-type screen config admission, deferred wireless iAP tunnel publication until control-loop takeover, unified `NativeMedia.requireAvailable()` exception chains, Rust ownership for HID/Siri/event dispatch and Bluetooth profile guard, full PairVerify/PairSetup reference transcripts, plus rustfmt/clippy/detekt/ast-grep architecture CI gates.
  - *(v152 was a local build with no public git tag; v153 also carried the opt-in PR #126 navigation-audio switch later reverted on `dev`.)*
- **v151 (2026-11-15)**: bounded default-mode diagnostics and session evidence persistence (PR #124), native AAC input and microphone pipeline (PR #122), MQTT/HUD/RTSP/audio native boundary convergence (PR #115–#123), video first-frame and interleaved config-failure fixes (PR #113, #114), and WorkManager update-worker recovery after the #100 rename (PR #112).
- **v150 (2026-11-14)**: post-v148 Rust core/media migration and native Opus path, wired reconnect and resource-lifetime fixes, artwork deduplication, bounded AAudio playback-head mapping, and failed audio renderer recovery with live microphone echo-reference rebinding. Automated/host checks are not combined vehicle acceptance; current hardware and acoustic acceptance is tracked in the [repository matrix](RUST_CORE_REMAINING.zh-CN.md).
- **v146**: release builds optimized with R8 while keeping class and method names readable in crash reports; each Release also carries `DiPlay-v<versionCode>-mapping.txt`.
- **v145**: the "HY proxy" media mode renamed "Android standard" with a switch for the HY cover broadcast, a centred and larger ECARX cluster safe area, live CarPlay map embedding on Android 9, versioned APK file names, one USB request per wired connection, and a repeated iPhone SETUP no longer dropping the session.
- **v143–v144**: in-app updates from this repository's Releases (download-source choice, SHA-256 and signature checks before installing), MFi identity kept on disk and importable from Settings, release notes shown as plain text in About, reconnect after the car drops Bluetooth and hotspot when the driver returns, fewer day/night choices with a correct cold start, two music-pause switches, and more screen, car-power and media diagnostics.
- **v136**: immediate day/night switching from the car signal, hardened ECARX reflection, vendor compile dependencies removed, licenses and source reachable from About.
- **v134**: navigation sound, automatic hotspot protection, boot service order and short-press Siri.

Guides:

- [Owner's guide](USER-GUIDE.zh-CN.md) (Simplified Chinese, v153+ text baseline): connection, everyday settings, troubleshooting and upgrades, with historical screenshot sources marked in the guide.
- [Detailed feature guide](FEATURES.zh-CN.md) (v153+ text baseline): behavior, prerequisites, per-map cluster differences and remaining vehicle checks.

The instrument-cluster map requires **iOS 17.4 or later** on the iPhone: turn-by-turn, distance and lane prompts use the navigation-metadata channel added in iOS 17.4, while the cluster map picture itself is supported from iOS 16.4. **Amap additionally requires iOS 17.4 and iPhone 12 or later.**

Public release APKs are platform-signed and contain no MFi credentials. Update in place with the same package and signature; do not uninstall or clear app data first. A fresh install or cleared data needs credentials supplied separately or imported under Settings → Advanced. The application ID remains `com.shihab.diplay`, but internal component class names changed after v148: recreate old launcher shortcuts/navigation widgets and re-enable any previously enabled wheel-key or USB auto-confirm accessibility service once after upgrading.

Historical upstream changes are in the [upstream DiPlay releases](https://github.com/shihabal3amri/DiPlay/releases).
