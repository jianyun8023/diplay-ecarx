# 文档索引

DiPlay 领克 01 / ECARX 753 移植版的全部文档。新增文档时在这里登记一行，并放进对应分组。
仓库维护长期说明和按版本标注的脱敏验证摘要；原始日志、APK、私有车机资料与认证资产留在本地工作区。当前验收状态集中在验收矩阵，历史实验保留各自的版本与测量边界。变更记录以仓库根目录的 [CHANGELOG.md](../CHANGELOG.md) 为准。

## 使用说明（车主与安装者）

- [车主使用说明](USER-GUIDE.zh-CN.md) — 连接、日常设置、排错与升级，含设置截图。
- [功能与使用说明](FEATURES.zh-CN.md) — 各功能的行为、前提、各地图在仪表上的差异与待实车验证项。
- [Install and connect](INSTALL.md) ／ [安裝與連線](INSTALL.zh-TW.md) — 安装与首次连接。
- [設定與疑難排解](SETTINGS.zh-TW.md) — 设置项与排错（繁体）。
- [Built-in car hotspot setup](CONNECTION_SETUP.md) — 车机热点连接方式。
- [Existing Wi-Fi / Same LAN](EXISTING_WIFI.md) — 现有 Wi-Fi 同网段连接。
- [Compatibility](COMPATIBILITY.md) — 车机与 iPhone 兼容范围。
- [CarPlay on your launcher's home screen](LAUNCHER_INTEGRATION.md) — 桌面集成。
- [Privacy and diagnostics](PRIVACY.md) ／ [隱私權與診斷](PRIVACY.zh-TW.md) — 隐私与诊断报告。

## 开发与构建

- [技术架构](ARCHITECTURE.zh-CN.md) — 当前模块、会话数据流、Rust 核心与 Kotlin 平台适配边界及代码归属。
- [Rust 核心验收矩阵](RUST_CORE_REMAINING.zh-CN.md) — 当前已验证范围、剩余项和历史迁移审计；已关闭的 issue 不作为当前待办清单。
- [协议样本与黄金向量](PROTOCOL_VECTORS.zh-CN.md) — 样本来源、独立预期、测试入口和外部证据缺口。
- [领克 01 / ECARX 753 平台笔记](ECARX_753_PLATFORM_NOTES.zh-CN.md) — 厂商接口、固件行为、权限、验证矩阵与证据索引，长期维护；新接口先登记再使用。
- [构建 DiPlay 领克 01 / ECARX 753 移植版](../BUILD.md) — 本移植版特有的签名与 MFi 身份；通用流程见下一条。
- [Build DiPlay](BUILD.md) ／ [從原始碼建置 DiPlay](BUILD.zh-TW.md) — 模块、Android Studio、单测与 lint 的通用流程。
- [753 车机权限与无 ADB 实施清单](ADB_AND_PLATFORM_PERMISSIONS.zh-CN.md) — 平台权限与不依赖 ADB 的实施、验收基准。
- [ECARX SDK 与麦克风 Policy 上下文](ECARX_SDK_CONTEXT.zh-CN.md) — 反射与动态代理访问厂商接口。
- [Test checklist](TESTING.md) — 测试要点与主机单测。
- [繁體中文翻譯維護](LOCALIZATION.zh-TW.md) — 本地化维护。
- [Credits and license notices](THIRD_PARTY_NOTICES.md)、[licenses/](licenses/) — 第三方许可。
- 代理与贡献者约定见根目录 [AGENTS.md](../AGENTS.md)。

## 功能与机制说明

连接与启动：

- [Android 9 Wi-Fi Direct](ANDROID9_WIFI_DIRECT.md)
- [RFCOMM 建链与 iAP2 bootstrap 局部恢复](RFCOMM_LOCAL_RECOVERY.zh-CN.md)
- [开机启动优先级与系统应用边界](STARTUP_PRIORITY_AND_SYSTEM_APP.zh-CN.md)
- [车机热点与现有 Wi-Fi：启动耗时采样](CARPLAY_STARTUP_SAMPLING.zh-CN.md)
- [Wireless startup diagnostics](WIRELESS_DIAGNOSTICS.md)

画面与布局：

- [CarPlay view areas](VIEW_AREAS.md) — dock 位置、分屏与旋转。
- [保留系统栏时的前台视频布局](FOREGROUND_VIDEO_LAYOUT.zh-CN.md)
- [Side panel next to CarPlay (experimental)](SIDE_PANEL.md)
- [高通私有低延迟解码实车验证](QUALCOMM_LOW_LATENCY.zh-CN.md)

音频与媒体：

- [CarPlay 期间仅停用原车蓝牙媒体](BLUETOOTH_MEDIA_YIELD.md)
- [Buffered music](BUFFERED_AUDIO.md)
- [车机媒体接管方（车机媒体集成）](MEDIACENTER_BACKENDS.md)
- [Navigation guidance volume keys](NAVIGATION_WHEEL_VOLUME.md)

导航与仪表：

- [753 导航接管路线决策（停包方案）](NAVIGATION_TAKEOVER_DECISION.zh-CN.md)
- [Cluster music card (experimental)](CLUSTER_MUSIC_CARD.md)

## 验证素材与基准

- [verification/navigation-v109](verification/navigation-v109/) — 导航接管决策引用的 v109 导航验证素材。
- [预编译 libopus 实测（2026-11-10）](benchmarks/native-opus-2026-11-10.md) — 当时选用原生 Opus 编码的依据；后续 Opus 统一为静态链接的 libopus，见技术架构。
- [原生媒体迁移历史实验（2026-11-12 起）](benchmarks/native-media-2026-11-12.md) — 各阶段 A/B/C/D 台架测量和后续迁移验证，含逐轮 CSV；待办与开关默认值按当时版本理解。
- [原生视频首帧与配置失败交错验证（2026-11-14）](benchmarks/native-video-startup-2026-11-14.md) — #113 已合并结果与后续失败交错修复分批登记，包含有效 red、本地检查和实机待验范围。
- [车速、挡位与恢复复核（2026-11-14）](benchmarks/vehicle-speed-2026-11-14.md) — Ultra 实车证据、HY DCYTools 同源挡位、固件单位与订阅行为、本地恢复回归及实车待测范围。
- [原生 USB NCM 台架验收记录（2026-11-13，已收尾）](benchmarks/native-usb-2026-11-13.md) — 拔插／重启／切换、156 秒有线采样及主动断开背景，保留版本和结论适用范围。
- [媒体流原生解密实测（2026-11-11）](benchmarks/native-chacha-2026-11-11.md) — 原生 ChaCha 的性能依据（现并入 `libdiplay_media.so`）。

## 发布

- [CHANGELOG.md](../CHANGELOG.md) — 本移植版的变更记录，当前版本以 GitHub Release 为准。
- [DiPlay ECARX release notes](RELEASE-NOTES-NEXT.md) — 面向发布页的英文摘要。
- 上游 DiPlay 的发布说明、验证与评审记录见[上游仓库](https://github.com/shihabal3amri/DiPlay)。
