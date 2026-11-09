# DiPlay ECARX release notes

The current ECARX release is **[v144](https://github.com/jianyun8023/diplay-ecarx/releases/tag/v144)** (2026-11-09), on upstream DiPlay 0.2.22, with Android versionCode 144. Each GitHub Release carries `DiPlay-v<versionCode>-release.apk` and an `update.json` that the installed app reads for in-app updates. The full record of changes is the [changelog](../CHANGELOG.md) (Simplified Chinese); this page is the English summary.

- **v143–v144**: in-app updates from this repository's Releases (download-source choice, SHA-256 and signature checks before installing), MFi identity kept on disk and importable from Settings, release notes shown as plain text in About, reconnect after the car drops Bluetooth and hotspot when the driver returns, fewer day/night choices with a correct cold start, two music-pause switches, and more screen, car-power and media diagnostics.
- **v136**: immediate day/night switching from the car signal, hardened ECARX reflection, vendor compile dependencies removed, licenses and source reachable from About.
- **v134**: navigation sound, automatic hotspot protection, boot service order and short-press Siri.

Guides:

- [Owner's guide](USER-GUIDE.zh-CN.md) (Simplified Chinese, follows the v142 car-test build): connection, everyday settings, troubleshooting and upgrades, with car screenshots.
- [Detailed feature guide](FEATURES.zh-CN.md) (v134 baseline): behavior, prerequisites, per-map cluster differences and remaining vehicle checks.

The instrument-cluster map requires **iOS 17.4 or later** on the iPhone: turn-by-turn, distance and lane prompts use the navigation-metadata channel added in iOS 17.4, while the cluster map picture itself is supported from iOS 16.4. **Amap additionally requires iOS 17.4 and iPhone 12 or later.**

Public release APKs are platform-signed and contain no MFi credentials. Update in place with the same package and signature; a fresh install or cleared data needs credentials supplied separately or imported under Settings → Advanced.

Historical upstream changes are recorded in the [0.2.22 release notes](RELEASE-NOTES-0.2.22.md).
