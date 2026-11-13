# DiPlay ECARX release notes

The current ECARX release is **[v146](https://github.com/jianyun8023/diplay-ecarx/releases/tag/v146)** (2026-11-10), on upstream DiPlay 0.2.22, with Android versionCode 146. Each GitHub Release carries one APK per ABI (`DiPlay-v<versionCode>-arm64-v8a-release.apk` default, `-armeabi-v7a-` fallback, `-x86_64-` test-only) and an `update.json` that lists them in an `apks` map, so the installed app downloads the package for its own ABI. The full record of changes is the [changelog](../CHANGELOG.md) (Simplified Chinese); this page is the English summary.

- **v146**: release builds optimized with R8 while keeping class and method names readable in crash reports; each Release also carries `DiPlay-v<versionCode>-mapping.txt`.
- **v145**: the "HY proxy" media mode renamed "Android standard" with a switch for the HY cover broadcast, a centred and larger ECARX cluster safe area, the live CarPlay map in the sample launcher on Android 9, versioned APK file names, one USB request per wired connection, and a repeated iPhone SETUP no longer dropping the session.
- **v143–v144**: in-app updates from this repository's Releases (download-source choice, SHA-256 and signature checks before installing), MFi identity kept on disk and importable from Settings, release notes shown as plain text in About, reconnect after the car drops Bluetooth and hotspot when the driver returns, fewer day/night choices with a correct cold start, two music-pause switches, and more screen, car-power and media diagnostics.
- **v136**: immediate day/night switching from the car signal, hardened ECARX reflection, vendor compile dependencies removed, licenses and source reachable from About.
- **v134**: navigation sound, automatic hotspot protection, boot service order and short-press Siri.

Guides:

- [Owner's guide](USER-GUIDE.zh-CN.md) (Simplified Chinese, v146): connection, everyday settings, troubleshooting and upgrades, with car screenshots.
- [Detailed feature guide](FEATURES.zh-CN.md) (v146): behavior, prerequisites, per-map cluster differences and remaining vehicle checks.

The instrument-cluster map requires **iOS 17.4 or later** on the iPhone: turn-by-turn, distance and lane prompts use the navigation-metadata channel added in iOS 17.4, while the cluster map picture itself is supported from iOS 16.4. **Amap additionally requires iOS 17.4 and iPhone 12 or later.**

Public release APKs are platform-signed and contain no MFi credentials. Update in place with the same package and signature; a fresh install or cleared data needs credentials supplied separately or imported under Settings → Advanced.

Historical upstream changes are in the [upstream DiPlay releases](https://github.com/shihabal3amri/DiPlay/releases).
