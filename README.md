# DiPlay · 领克 01 CarPlay

[简体中文](README.zh-CN.md) · [繁體中文](README.zh-TW.md) · [English](README.md#english)

DiPlay 让你在领克车机上使用 iPhone 的导航、音乐、Siri 和通话。适配**亿咖通车机 753 版本**（领克高通 820A 平台），当前在 **2021 款领克 01** 上验证；同平台的 753 车机理论上通用。

每个功能的开启位置、前提条件、怎么确认生效，见 [功能与使用说明](docs/FEATURES.zh-CN.md)。

| 功能 | 需要开启的设置 |
| --- | --- |
| 仪表和 HUD 导航提示 | **设置 → 导航 → 亿咖通导航**，开启「仪表导航」 |
| 车道指引 | 同一页面开启「仪表车道指引」 |
| 歌曲信息和封面 | 同一页面开启「仪表媒体卡片」 |
| 日夜模式跟随车辆 | **设置 → 显示 → 显示与性能**，把「CarPlay 日夜模式」设为「跟随车辆」 |
| 音乐走 CarPlay、保留蓝牙电话 | **设置 → 音频 → 蓝牙音频**，开启「CarPlay 期间停用蓝牙媒体」，关闭「通过车辆蓝牙播放 CarPlay 音频」 |
| 无线连接 | 在连接设置中选择车机热点、Wi-Fi Direct 或已有 Wi-Fi，按提示完成配置 |
| 开机自动连接 | **设置 → 连接 → 自动连接**，开启「车机启动后打开」和「打开 DiPlay 时连接」 |
| 方向盘语音键叫 Siri | 无需设置：CarPlay 连上后短按即生效。**自定义按键本版未适配，请保持默认** |

**使用 HY 时，请关闭 HY 的方控接管。**

**方向盘语音键**：CarPlay 连上后自动接管**短按**，不需要开关、不需要学习；断开即解绑。生效需要同时满足：车机被识别为领克/吉利、媒体接管方为 ECARX、当前不在通话中。用 HY（媒体接管方 = Android）时按键交给 HY。自定义按键学习本版未适配。

只显示导航提示和歌曲信息时，保持「整屏镜像到仪表」关闭。设置修改后，按界面提示重新连接。

目前 USB 连接仍有问题；拔掉 USB 后会自动回到无线。遇到异常，请到 **设置 → 诊断 → 保存诊断报告**。

基于 [DiPlay](https://github.com/shihabal3amri/DiPlay)、[DiPlay-ECARX](https://github.com/jiopia/DiPlay-ECARX) 和 [xcertplay](https://github.com/shilapi/xcertplay)，部分界面改编自 [DiAuto](https://github.com/shihabal3amri/DiAuto)。许可与第三方声明见 [LICENSE](LICENSE) 和 [相关说明](docs/THIRD_PARTY_NOTICES.md)。

CarPlay 及相关标识属于 Apple；本项目与 Apple、领克、亿咖通无隶属或背书关系。

## English

DiPlay brings iPhone navigation, music, Siri and calls to the Lynk & Co head unit. Built for **ECARX system version 753** (Lynk Qualcomm 820A platform), validated on the **2021 Lynk & Co 01**; other 753 units on the same platform should work.

Where each feature is enabled, what it needs first, and how to tell it works: [feature and usage guide](docs/FEATURES.zh-CN.md) (Simplified Chinese).

| Feature | Settings |
| --- | --- |
| Cluster and HUD navigation | **Settings → Navigation → ECARX navigation**: enable “Navigation on the instrument cluster” |
| Lane guidance | On the same page, enable “Cluster lane guidance” |
| Song information and artwork | On the same page, enable “Cluster media card” |
| Day/night following the vehicle | **Settings → Display → Display and performance**: set “CarPlay day/night mode” to “Follow the vehicle” |
| Music through CarPlay, keep Bluetooth calls | **Settings → Audio → Bluetooth audio**: enable “Pause Bluetooth media for CarPlay” and disable “CarPlay audio over the car's Bluetooth” |
| Wireless connection | Choose the car hotspot, Wi-Fi Direct or existing Wi-Fi in connection setup, then follow the prompts |
| Automatic connection at startup | **Settings → Connection → Automatic connection**: enable “Open after the car starts” and “Connect when DiPlay opens” |
| Siri from the wheel | No setup: short-press works once CarPlay is connected. **Custom key learning is not supported in this version — leave it alone** |

**If you use HY, disable its steering-wheel control takeover.**

**Wheel voice key:** while CarPlay is connected DiPlay takes over the **short press** automatically — no switch, no learning; it unbinds when the session ends. It requires all of: the head unit identified as Lynk/Geely, media owner set to ECARX, and no call in progress. With HY (media owner = Android) the key goes to HY. Custom key learning is not supported in this version.

Keep “Mirror the full screen to the instrument cluster” off when you only want navigation and song information. After changing settings, reconnect when prompted.

USB connection issues remain unresolved; unplugging the cable now falls back to wireless automatically. To report a problem, choose **Settings → Diagnostics → Save diagnostic report**.
