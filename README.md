# DiPlay · 领克 01 CarPlay

正式环境：Android 9（API 28）／753／820A，使用现有平台签名。应用内不使用 ADB，APK 仅含 ARM64／ARMv7；其他 Android 版本仅用于调试。见 [权限清单](docs/ADB_AND_PLATFORM_PERMISSIONS.zh-CN.md)。

[简体中文](README.zh-CN.md) · [繁體中文](README.zh-TW.md) · [English](README.md#english)

DiPlay 让你在领克车机上使用 iPhone 的导航、音乐、Siri 和通话。适配**亿咖通车机 753 版本**（领克高通 820A 平台），当前在 **2021 款领克 01** 上验证；同平台的 753 车机理论上通用。

连接、日常设置和排错，见 [车主使用说明（含设置图）](docs/USER-GUIDE.zh-CN.md)。功能细节和验证范围见 [详细说明](docs/FEATURES.zh-CN.md)。

各文档开头标注其适用版本：车主使用说明对齐 v142 车测包，详细说明以 v134 为基线。导航声音、自动热点保护、开机服务与短按 Siri 的新实现仍待实车复测。

| 功能 | 需要开启的设置 |
| --- | --- |
| 仪表和 HUD 导航提示 | **设置 → 导航 → 亿咖通导航**，开启「HUD／仪表导航提示」 |
| 车道指引 | 随「HUD／仪表导航提示」输出手机提供的有效车道，无需单独开关 |
| 歌曲信息和封面 | **设置 → 音频 → 车机媒体集成**，选择「DiPlay 直接接管」或「HY 代理」，歌曲信息随所选后端输出 |
| 日夜模式跟随车辆 | **设置 → 显示 → 显示与性能**，把「CarPlay 日夜模式」设为「跟随车辆」 |
| 音乐走 CarPlay、保留蓝牙电话 | **设置 → 音频 → 音频路由 → 声音异常排查**，开启「CarPlay 期间停用蓝牙媒体」，关闭「通过车辆蓝牙播放 CarPlay 音频」 |
| 导航压低音乐或共同播报 | **设置 → 音频 → 音频路由**，默认开启「导航声音跟随 iPhone 和车机」；手机地图选播报方式，车机调导航音量，改开关后重连 |
| 无线连接 | 在连接设置中选择车机热点、Wi-Fi Direct 或已有 Wi-Fi，按提示完成配置 |
| 开机自动连接 | **设置 → 连接 → 自动连接**，开启「车机启动后打开」「打开 DiPlay 时连接」，选无线和目标 iPhone；车机热点需保存配置并按需开启自动热点 |
| 自动热点保持稳定 | **设置 → 连接 → 连接设置**选择「车载热点（推荐）」，开启自动热点后默认「保持车机 Wi-Fi 关闭」；需要车机连接其他 Wi-Fi 时关闭此项 |
| 方向盘语音键叫 Siri | **设置 → 车辆 → 方向盘按键**，开启「方向盘唤醒 Siri」；直接接管模式选短按／长按，HY 代理模式选 155/156 或 312/303 |
| 在车机上接打电话 | 在 CarPlay 画面接听／挂断；声音走 CarPlay，车机电话界面和仪表卡片来自独立电话栈，通话时音乐与导航避让 |

**文中「HY」指 HYWebview（`com.hyphp.hywebview`），适配版本 3.6.1。** DiPlay 直接接管模式下，请关闭 HY 的媒体接管及同一语音键的方控接管；HY 代理模式请在两边选择相同的 Siri 模拟按键。

**方向盘 Siri**：在「设置 → 车辆 → 方向盘按键」用总开关开启或关闭。DiPlay 直接接管时选择短按／长按；HY 代理时选择 155/156 或 312/303，并在 HYWebview 的方向盘设置里配置相同模拟按键。原有 Siri 桥接入口继续可用，不受按键总开关影响。不提供按键学习；未连接 CarPlay 或通话中不接管。

只显示导航提示和歌曲信息时，保持「整屏镜像到仪表」关闭。设置修改后，按界面提示重新连接。

**仪表地图需要 iPhone 使用 iOS 17.4 或更新版本**：仪表上的箭头、距离、车道走 iOS 17.4 起的导航元数据通道；地图画面系统自 iOS 16.4 起支持，高德另要求 iOS 17.4 及以上、iPhone 12 或更新机型。

自动热点会关闭车机连接其他 Wi-Fi 的功能，iPhone 的 Wi-Fi 仍须开启。关闭保护项后，若 CarPlay 正使用热点，车机 Wi-Fi 延至下次车辆启动恢复。开机配置齐全时可直达 CarPlay，v134 会先等原车桌面服务，最多 5 秒。

建议首次使用无线配对。如果本车 CarPlay 记录首次通过 USB 建立，改无线前须在 iPhone 删除本车记录，再先无线配对。已配置无线时，插 USB 默认不接管；开关在 **连接 → 自动连接**。v122 的插线两条路径已实车确认，USB 卡顿和取消授权后的回退仍未解决。遇到异常，请到 **设置 → 诊断 → 保存诊断报告**。

同包名、同签名且版本号不低于已安装版本时覆盖升级，保留设置；Actions 公开发布包不内置 MFi 认证，全新安装或清除数据后需另行配置。不要先卸载。

开发参考：[文档索引](docs/README.md)、[构建说明](BUILD.md)、[753 车机 ADB 依赖与平台权限清单](docs/ADB_AND_PLATFORM_PERMISSIONS.zh-CN.md)。ECARX 接口通过运行时反射和动态代理访问，编译与单测不需要厂商 JAR；见 [SDK 适配说明](docs/ECARX_SDK_CONTEXT.zh-CN.md)。

基于 [DiPlay](https://github.com/shihabal3amri/DiPlay)、[DiPlay-ECARX](https://github.com/jiopia/DiPlay-ECARX) 和 [xcertplay](https://github.com/shilapi/xcertplay)，部分界面改编自 [DiAuto](https://github.com/shihabal3amri/DiAuto)。许可与第三方声明见 [LICENSE](LICENSE) 和 [相关说明](docs/THIRD_PARTY_NOTICES.md)。

Based on LIVI by Lasse Heitgres (https://github.com/f-io/LIVI)

CarPlay 及相关标识属于 Apple；本项目与 Apple、领克、亿咖通无隶属或背书关系。

## English

DiPlay brings iPhone navigation, music, Siri and calls to the Lynk & Co head unit. Built for **ECARX system version 753** (Lynk Qualcomm 820A platform), validated on the **2021 Lynk & Co 01**; other 753 units on the same platform should work.

For connection, everyday settings and troubleshooting, see the [owner's guide with settings screenshots](docs/USER-GUIDE.zh-CN.md). Behavior and validation limits are in the [detailed feature guide](docs/FEATURES.zh-CN.md). Both guides are in Simplified Chinese.

Each guide states the version it covers: the owner's guide follows the v142 car-test build, the feature guide the v134 baseline. The new navigation sound, hotspot protection, boot service order and short-press Siri behavior still need vehicle testing.

| Feature | Settings |
| --- | --- |
| Cluster and HUD navigation | **Settings → Navigation → ECARX navigation**: enable “HUD and cluster guidance” |
| Lane guidance | Follows “HUD and cluster guidance” when the iPhone supplies valid lanes; no separate switch |
| Song information and artwork | **Settings → Audio → Vehicle media integration**: choose “DiPlay direct” or “HY proxy”; song data follows the backend |
| Day/night following the vehicle | **Settings → Display → Display and performance**: set “CarPlay day/night mode” to “Follow the vehicle” |
| Music through CarPlay, keep Bluetooth calls | **Settings → Audio → Audio routing → Sound troubleshooting**: enable “Pause Bluetooth media for CarPlay” and disable “CarPlay audio over the car's Bluetooth” |
| Navigation over music or lowering it | **Settings → Audio → Audio routing**: “Navigation sound follows the iPhone and the car” is on by default on ECARX; choose the prompt mode on the iPhone and adjust navigation volume in the car. Reconnect after changing the switch |
| Wireless connection | Choose the car hotspot, Wi-Fi Direct or existing Wi-Fi in connection setup, then follow the prompts |
| Automatic connection at startup | **Settings → Connection → Automatic connection**: enable both startup switches, select wireless and an iPhone; save hotspot details and enable automatic hotspot startup if needed |
| Protect the automatic car hotspot | **Settings → Connection → Connection setup → Car hotspot**: automatic hotspot startup defaults to “Keep the car Wi-Fi off”; turn it off when the car needs to join other Wi-Fi networks |
| Siri from the wheel | **Settings → Vehicle → Steering-wheel keys**: enable “Wake Siri with the voice key”; direct mode picks short or long press, HY proxy picks 155/156 or 312/303 |
| Calls | Answer or end calls in CarPlay. Audio uses CarPlay; the car's phone screen and cluster card belong to its separate phone stack. Music and navigation yield during calls |

**“HY” in this document means HYWebview (`com.hyphp.hywebview`), version 3.6.1.** In direct mode, disable HY's media takeover and its mapping for the same voice key. In HY mode, select the same Siri simulated-key pair in both apps.

**Wheel Siri:** under **Settings → Vehicle → Steering-wheel keys**, the master switch controls key activation. Direct mode offers short/long press; HY mode offers 155/156 or 312/303, matching the simulated-key option in HYWebview 3.6.1. Siri shortcuts remain available with the key switch off. No key learning is offered. Keys are left alone without an active CarPlay session or during calls. Media integration is under **Settings → Audio → Vehicle media integration**.

Keep “Mirror the full screen to the instrument cluster” off when you only want navigation and song information. After changing settings, reconnect when prompted.

**The instrument-cluster map requires iOS 17.4 or later on the iPhone**: turn-by-turn, distance and lane prompts use the navigation-metadata channel added in iOS 17.4; the cluster map picture itself is supported from iOS 16.4, while Amap additionally requires iOS 17.4 and iPhone 12 or later.

Automatic hotspot startup keeps the car's Wi-Fi client off; leave iPhone Wi-Fi on. Turning protection off restores the car's previous Wi-Fi choice, at the next vehicle start if CarPlay is using the hotspot. A configured boot can open CarPlay directly; v134 first waits up to five seconds for the original launcher services.

Pair wirelessly first. If the car's record was first created over USB, remove it in iPhone CarPlay settings and pair wirelessly again. With wireless configured, USB insertion does not take over by default; the switch is under **Connection → Automatic connection**. Both USB insertion paths were tested on v122, but USB stutter and recovery after permission cancellation remain unresolved. To report a problem, choose **Settings → Diagnostics → Save diagnostic report**.

Update in place with the same package and signature and an equal or higher version code. Public Actions release APKs contain no MFi credentials; a fresh install or cleared data needs credentials supplied separately. Do not uninstall first.

For development, see [the build guide](docs/BUILD.md) and [SDK context](docs/ECARX_SDK_CONTEXT.zh-CN.md). The [docs index](docs/README.md) lists every document.
ECARX adapters use runtime reflection and dynamic proxies; builds and tests need no vendor JAR.
