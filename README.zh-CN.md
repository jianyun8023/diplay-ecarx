# DiPlay · 领克 01 CarPlay

正式环境：Android 9（API 28）／753／820A，使用现有平台签名。应用内不使用 ADB，APK 仅含 ARM64／ARMv7；其他 Android 版本仅用于调试。见 [权限清单](docs/ADB_AND_PLATFORM_PERMISSIONS.zh-CN.md)。

[简体中文](README.zh-CN.md) · [繁體中文](README.zh-TW.md) · [English](README.md#english)

DiPlay 让你在领克车机上使用 iPhone 的导航、音乐、Siri 和通话。适配**亿咖通车机 753 版本**（领克高通 820A 平台），当前在 **2021 款领克 01** 上验证；同平台的 753 车机理论上通用。

连接、日常设置和排错，见 [车主使用说明（含设置图）](docs/USER-GUIDE.zh-CN.md)。功能细节和验证范围见 [详细说明](docs/FEATURES.zh-CN.md)。

说明已对齐 **dev v134（2026-11-07，`7619ee32`）**，对照上一版用户手册 v122。导航声音、自动热点保护、开机服务与短按 Siri 的新实现仍待实车复测。

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

**文中「HY」指 HYWebview（`com.hyphp.hywebview`），适配版本 3.6.1。** DiPlay 直接接管模式下，请关闭 HY 的媒体接管及同一语音键的方控接管；HY 代理模式请在两边选择相同的 Siri 模拟按键。

**方向盘 Siri**：在「设置 → 车辆 → 方向盘按键」用总开关开启或关闭。DiPlay 直接接管时选择短按／长按；HY 代理时选择 155/156 或 312/303，并在 HYWebview 的方向盘设置里配置相同模拟按键。原有 Siri 桥接入口继续可用，不受按键总开关影响。不提供按键学习；未连接 CarPlay 或通话中不接管。

只显示导航提示和歌曲信息时，保持「整屏镜像到仪表」关闭。设置修改后，按界面提示重新连接。

**高德地图显示到仪表，需要 iPhone 使用 iOS 17.4 或更新版本。**

自动热点会关闭车机连接其他 Wi-Fi 的功能，iPhone 的 Wi-Fi 仍须开启。关闭保护项后，若 CarPlay 正使用热点，车机 Wi-Fi 延至下次车辆启动恢复。开机配置齐全时可直达 CarPlay，v134 会先等原车桌面服务，最多 5 秒。

建议首次使用无线配对。如果本车 CarPlay 记录首次通过 USB 建立，改无线前须在 iPhone 删除本车记录，再先无线配对。已配置无线时，插 USB 默认不接管；开关在 **连接 → 自动连接**。v122 的插线两条路径已实车确认，USB 卡顿和取消授权后的回退仍未解决。遇到异常，请到 **设置 → 诊断 → 保存诊断报告**。

同包名、同签名且版本号不低于已安装版本时覆盖升级，保留设置；Actions 公开发布包不内置 MFi 认证，全新安装或清除数据后需另行配置。不要先卸载。

开发参考：[构建说明](BUILD.md)、[753 车机 ADB 依赖与平台权限清单](docs/ADB_AND_PLATFORM_PERMISSIONS.zh-CN.md)。ECARX 接口通过运行时反射和动态代理访问，编译与单测不需要厂商 JAR；见 [SDK 适配说明](docs/ECARX_SDK_CONTEXT.zh-CN.md)。

基于 [DiPlay](https://github.com/shihabal3amri/DiPlay)、[DiPlay-ECARX](https://github.com/jiopia/DiPlay-ECARX) 和 [xcertplay](https://github.com/shilapi/xcertplay)，部分界面改编自 [DiAuto](https://github.com/shihabal3amri/DiAuto)。许可与第三方声明见 [LICENSE](LICENSE) 和 [相关说明](docs/THIRD_PARTY_NOTICES.md)。

CarPlay 及相关标识属于 Apple；本项目与 Apple、领克、亿咖通无隶属或背书关系。
