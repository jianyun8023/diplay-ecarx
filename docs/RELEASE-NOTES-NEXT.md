# DiPlay ECARX release notes

The current ECARX release is **[v136](https://github.com/jianyun8023/diplay-ecarx/releases/tag/v136)** (2026-11-08), on upstream DiPlay 0.2.22, with Android versionCode 136. The newest car-test package is **v142** (`0.2-car-test-music-pause-v142`, source `c023de23`): two music-pause switches, trimmed day/night choices following outside light, and a wireless restart that waits for the car's Bluetooth after the hotspot drops.

- [Owner's guide](USER-GUIDE.zh-CN.md) (Simplified Chinese): connection, everyday settings, troubleshooting and upgrades. Updated to the v142 wording — four day/night choices, the two music-pause switches, reconnect after a power cycle, which navigation app feeds which cluster content, and "About → licenses and source". Four v142 car screenshots and two v134 simulator screenshots.
- [Detailed feature guide](FEATURES.zh-CN.md): behavior, prerequisites, per-map cluster differences and remaining vehicle checks.
- [Changelog](../CHANGELOG.md): changes since v122, including navigation sound, automatic hotspot protection, startup, day/night, media pause and wireless recovery.

The instrument-cluster map requires **iOS 17.4 or later** on the iPhone: turn-by-turn, distance and lane prompts use the navigation-metadata channel added in iOS 17.4, while the cluster map picture itself is supported from iOS 16.4. **Amap additionally requires iOS 17.4 and iPhone 12 or later.** Navigation sound, hotspot protection, startup Siri and cluster visibility still need vehicle testing.

Public release APKs are platform-signed and contain no MFi credentials. Update in place with the same package and signature; a fresh install or cleared data needs credentials supplied separately.

Historical upstream changes are recorded in the [0.2.22 release notes](RELEASE-NOTES-0.2.22.md).
