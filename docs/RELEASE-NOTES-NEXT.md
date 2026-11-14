# DiPlay ECARX release notes

The current ECARX release is **[v150](https://github.com/jianyun8023/diplay-ecarx/releases/tag/v150)** (2026-11-14), based on `dev` and upstream DiPlay 0.2.22, with Android versionCode 150. The full record of changes is the [changelog](../CHANGELOG.md) (Simplified Chinese); this page is the English summary.

**The currently adapted Qualcomm 820A head unit is ARMv8 (ARM64 / `arm64-v8a`). Download only [DiPlay-v150-arm64-v8a-release.apk](https://github.com/jianyun8023/diplay-ecarx/releases/download/v150/DiPlay-v150-arm64-v8a-release.apk). Do not download the ARMv7 (`armeabi-v7a`) or x86_64 APK for this head unit.**

The release retains a separate ARMv7 fallback package and an x86_64 emulator-test package; neither is recommended for the currently adapted 820A unit. `update.json` lists the packages by ABI and keeps ARM64 as its default, so an ARM64 installation updates to the ARM64 package.

- **v150**: includes the post-v148 Rust core/media migration and native Opus path, wired reconnect and resource-lifetime fixes, artwork-content deduplication, native startup/codec accounting hardening, bounded AAudio playback-head mapping, and recovery of failed audio renderers with live microphone echo-reference rebinding. Boot startup, automatic car hotspot and direct video default to enabled only when no choice has been saved; explicit disabled preferences remain unchanged. Automated/host checks are not combined vehicle acceptance; pending hardware and acoustic checks remain tracked in issues #96, #99 and #110.

- **v146**: release builds optimized with R8 while keeping class and method names readable in crash reports; each Release also carries `DiPlay-v<versionCode>-mapping.txt`.
- **v145**: the "HY proxy" media mode renamed "Android standard" with a switch for the HY cover broadcast, a centred and larger ECARX cluster safe area, the live CarPlay map in the sample launcher on Android 9, versioned APK file names, one USB request per wired connection, and a repeated iPhone SETUP no longer dropping the session.
- **v143–v144**: in-app updates from this repository's Releases (download-source choice, SHA-256 and signature checks before installing), MFi identity kept on disk and importable from Settings, release notes shown as plain text in About, reconnect after the car drops Bluetooth and hotspot when the driver returns, fewer day/night choices with a correct cold start, two music-pause switches, and more screen, car-power and media diagnostics.
- **v136**: immediate day/night switching from the car signal, hardened ECARX reflection, vendor compile dependencies removed, licenses and source reachable from About.
- **v134**: navigation sound, automatic hotspot protection, boot service order and short-press Siri.

Guides:

- [Owner's guide](USER-GUIDE.zh-CN.md) (Simplified Chinese, v146): connection, everyday settings, troubleshooting and upgrades, with car screenshots.
- [Detailed feature guide](FEATURES.zh-CN.md) (v146): behavior, prerequisites, per-map cluster differences and remaining vehicle checks.

The instrument-cluster map requires **iOS 17.4 or later** on the iPhone: turn-by-turn, distance and lane prompts use the navigation-metadata channel added in iOS 17.4, while the cluster map picture itself is supported from iOS 16.4. **Amap additionally requires iOS 17.4 and iPhone 12 or later.**

Public release APKs are platform-signed and contain no MFi credentials. Update in place with the same package and signature; do not uninstall or clear app data first. A fresh install or cleared data needs credentials supplied separately or imported under Settings → Advanced. The application ID remains `com.shihab.diplay`, but internal component class names changed after v148: recreate old launcher shortcuts/navigation widgets and re-enable any previously enabled wheel-key or USB auto-confirm accessibility service once after upgrading.

Historical upstream changes are in the [upstream DiPlay releases](https://github.com/shihabal3amri/DiPlay/releases).
