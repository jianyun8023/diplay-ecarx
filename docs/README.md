# 文档索引

DiPlay 领克 01 / ECARX 753 移植版的全部文档。新增文档时在这里登记一行，并放进对应分组。
仓库只收随代码长期成立的说明；按日期或版本的试车清单、实车证据、评审和合并记录放在本地工作区，结论写进平台笔记的验证矩阵。变更记录以仓库根目录的 [CHANGELOG.md](../CHANGELOG.md) 为准。

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

- [技术架构](ARCHITECTURE.zh-CN.md) — 模块与包分层、会话组成、控制面（Kotlin）与数据面（Rust）的分工、媒体数据面迁移的目标设计与分阶段验收。
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
- [预编译 libopus 实测（2026-11-10）](benchmarks/native-opus-2026-11-10.md) — `shared/src/main/jniLibs` 原生库的性能依据。

## 发布

- [CHANGELOG.md](../CHANGELOG.md) — 本移植版的变更记录，当前版本以 GitHub Release 为准。
- [DiPlay ECARX release notes](RELEASE-NOTES-NEXT.md) — 面向发布页的英文摘要。
- 上游 DiPlay 的发布说明、验证与评审记录见[上游仓库](https://github.com/shihabal3amri/DiPlay)。
