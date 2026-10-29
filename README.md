# DiPlay · 领克 01 CarPlay

[简体中文](README.zh-CN.md) · [繁體中文](README.zh-TW.md) · [English](README.md#english)

DiPlay 让你在领克车机上使用 iPhone 的导航、音乐、Siri 和通话，目前主要适配 **2021 款领克 01**。

| 功能 | 需要开启的设置 |
| --- | --- |
| 仪表和 HUD 导航提示 | **设置 → 导航 → 亿咖通导航**，开启「仪表导航」 |
| 车道指引 | 同一页面开启「仪表车道指引」 |
| 歌曲信息和封面 | 同一页面开启「仪表媒体卡片」 |
| 音乐走 CarPlay、保留蓝牙电话 | **设置 → 高级 → 视频与音频**，开启「CarPlay 期间停用蓝牙媒体」，关闭「通过车辆蓝牙播放 CarPlay 音频」 |
| 无线连接 | 在连接设置中选择车机热点、Wi-Fi Direct 或已有 Wi-Fi，按提示完成配置 |
| 开机自动连接 | **设置 → 连接 → 自动连接**，开启「车机启动后打开」和「打开 DiPlay 时连接」 |
| 自定义方向盘按键 | **设置 → 车辆 → 方向盘按键**，开启按键服务并学习需要的按键 |

**使用 HY 时，请关闭 HY 的方控接管。**

**Siri**：可通过 CarPlay 界面唤起；此版本暂不包含方向盘语音键短按唤起功能。

只显示导航提示和歌曲信息时，保持「仪表投屏」关闭。设置修改后，按界面提示重新连接。

目前 USB 连接仍有问题。遇到异常，请到 **设置 → 诊断 → 保存诊断报告**。

基于 [DiPlay](https://github.com/shihabal3amri/DiPlay)、[DiPlay-ECARX](https://github.com/jiopia/DiPlay-ECARX) 和 [xcertplay](https://github.com/shilapi/xcertplay)，部分界面改编自 [DiAuto](https://github.com/shihabal3amri/DiAuto)。许可与第三方声明见 [LICENSE](LICENSE) 和 [相关说明](docs/THIRD_PARTY_NOTICES.md)。

CarPlay 及相关标识属于 Apple；本项目与 Apple、领克、亿咖通无隶属或背书关系。

## English

DiPlay brings iPhone navigation, music, Siri and calls to the **2021 Lynk & Co 01** head unit.

| Feature | Settings |
| --- | --- |
| Cluster and HUD navigation | **Settings → Navigation → ECARX navigation**: enable “Navigation on the instrument cluster” |
| Lane guidance | On the same page, enable “Cluster lane guidance” |
| Song information and artwork | On the same page, enable “Cluster media card” |
| Music through CarPlay, keep Bluetooth calls | **Settings → Advanced → Video and audio**: enable “Pause Bluetooth media for CarPlay” and disable “CarPlay audio over the car's Bluetooth” |
| Wireless connection | Choose the car hotspot, Wi-Fi Direct or existing Wi-Fi in connection setup, then follow the prompts |
| Automatic connection at startup | **Settings → Connection → Automatic connection**: enable “Open after the car starts” and “Connect when DiPlay opens” |
| Custom steering-wheel controls | **Settings → Vehicle → Wheel keys**: enable the key service and learn the buttons you need |

**If you use HY, disable its steering-wheel control takeover.**

**Siri:** use the CarPlay interface. Short-press steering-wheel voice-button activation is not included in this release.

Keep full-screen cluster mirroring off when you only want navigation and song information. After changing settings, reconnect when prompted.

USB connection issues remain unresolved. To report a problem, choose **Settings → Diagnostics → Save diagnostic report**.
