# 文档索引

DiPlay 领克 01 / ECARX 753 移植版的全部文档。新增文档时在这里登记一行，并放进对应分组；
带日期或版本号的验证记录、评审和合并记录只追加、不改写。变更记录以仓库根目录的 [CHANGELOG.md](../CHANGELOG.md) 为准。

## 使用说明（车主与安装者）

- [车主使用说明](USER-GUIDE.zh-CN.md) — 连接、日常设置、排错与升级，含设置截图。
- [功能与使用说明](FEATURES.zh-CN.md) — 各功能的行为、前提、各地图在仪表上的差异与待实车验证项。
- [Install and connect](INSTALL.md) ／ [安裝與連線](INSTALL.zh-TW.md) — 安装与首次连接。
- [設定與疑難排解](SETTINGS.zh-TW.md) — 设置项与排错（繁体）。
- [Built-in car hotspot setup](CONNECTION_SETUP.md) — 车机热点连接方式。
- [Existing Wi-Fi / Same LAN](EXISTING_WIFI.md) — 现有 Wi-Fi 同网段连接。
- [Automatic joining of a CarPlay hotspot](WIRELESS_HOTSPOT_JOIN.md) — iPhone 自动加入热点的检查项。
- [Smooth wireless CarPlay](SMOOTH_WIRELESS.md) — 无线流畅度相关设置。
- [Compatibility](COMPATIBILITY.md) — 车机与 iPhone 兼容范围。
- [CarPlay on your launcher's home screen](LAUNCHER_INTEGRATION.md) — 桌面集成。
- [Privacy and diagnostics](PRIVACY.md) ／ [隱私權與診斷](PRIVACY.zh-TW.md) — 隐私与诊断报告。
- [用户手册截图来源](screenshots/USER-MANUAL-v134.md) — `screenshots/` 内各图的来源与版本。

## 开发与构建

- [领克 01 / ECARX 753 平台笔记](ECARX_753_PLATFORM_NOTES.zh-CN.md) — 厂商接口、固件行为、权限、验证矩阵与证据索引，长期维护；新接口先登记再使用。
- [2026-11-10 适配设计评审](ECARX_753_DESIGN_REVIEW_20261110.zh-CN.md) — 整体架构判断、按影响排序的发现与建议顺序。
- [构建 DiPlay 领克 01 / ECARX 753 移植版](../BUILD.md) — 本移植版特有的签名与 MFi 身份；通用流程见下一条。
- [Build DiPlay](BUILD.md) ／ [從原始碼建置 DiPlay](BUILD.zh-TW.md) — 模块、Android Studio、单测与 lint 的通用流程。
- [753 车机权限与无 ADB 实施清单](ADB_AND_PLATFORM_PERMISSIONS.zh-CN.md) — 平台权限与不依赖 ADB 的实施、验收基准。
- [ECARX SDK 与麦克风 Policy 上下文](ECARX_SDK_CONTEXT.zh-CN.md) — 反射与动态代理访问厂商接口。
- [Test checklist](TESTING.md) — 实车测试清单。
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
- [Platform 21 independent cluster task (experimental)](PLATFORM21_CLUSTER.md)

上游（比亚迪唐 21）遗留说明：

- [Initial UI and map appearance](TANG21_APPEARANCE.md)
- [Video backlog recovery contribution](TANG21_VIDEO_RECOVERY.md)

## 验证记录（按日期，只追加）

- [领克 01（2021 款）dev 验证范围](LYNK_01_PENDING_VALIDATION.md) — 待实车验收项。
- [领克 01（2021 款）已确认功能整理分支](LYNK_01_VERIFIED_BRANCH.md)
- [2026-10-30 导航音量映射复核](LYNK_NAVIGATION_VOLUME_EVIDENCE_20261030.md)
- [车机回归验证 2026-10-28](CAR_TEST_20261028.md)
- [2026-10-28 Release 合并范围](RELEASE-VERIFIED-20261028.md)
- [Manual car hotspot control](MANUAL_CAR_HOTSPOT_VALIDATION.md) — 待设备验证。
- [Connection reliability validation](CONNECTION_RELIABILITY.md)
- [Hotspot automatic-join repair validation](HOTSPOT_JOIN_VALIDATION.md) — 历史，功能已移除。
- [DiPlay 0.2.22 validation](VALIDATION.md) ／ [繁體中文支援驗證紀錄](VALIDATION.zh-TW.md)
- [verification/navigation-v109](verification/navigation-v109/) — v109 导航验证素材。

## 发布、评审与合并记录

- [CHANGELOG.md](../CHANGELOG.md) — 本移植版的变更记录，当前版本以 GitHub Release 为准。
- [DiPlay ECARX release notes](RELEASE-NOTES-NEXT.md) — 面向发布页的英文摘要。
- [v0.2.22 合并记录](UPSTREAM-0.2.22-INTEGRATION.md)
- [PR 158 and PR 164 integration rationale](PR158-INTEGRATION-2026-10-03.md)
- [Release review — 27 September 2026](PR3_REVIEW.md)
- [Complete issue audit — 2026-10-03](ISSUE-AUDIT-2026-10-03.md)
- [Issue and pull request safety review](SAFETY-REVIEW-2026-10-03.md)
- 上游 DiPlay 发布说明：
  [0.2.9](RELEASE-NOTES-0.2.9.md) · [0.2.10](RELEASE-NOTES-0.2.10.md) · [0.2.11](RELEASE-NOTES-0.2.11.md) ·
  [0.2.12](RELEASE-NOTES-0.2.12.md) · [0.2.13](RELEASE-NOTES-0.2.13.md) · [0.2.14](RELEASE-NOTES-0.2.14.md) ·
  [0.2.15](RELEASE-NOTES-0.2.15.md) · [0.2.16](RELEASE-NOTES-0.2.16.md) · [0.2.17](RELEASE-NOTES-0.2.17.md) ·
  [0.2.18](RELEASE-NOTES-0.2.18.md) · [0.2.19](RELEASE-NOTES-0.2.19.md) · [0.2.20](RELEASE-NOTES-0.2.20.md) ·
  [0.2.21](RELEASE-NOTES-0.2.21.md) · [0.2.22](RELEASE-NOTES-0.2.22.md)

## 上游归档（仅供溯源）

- [DiPlay README](UPSTREAM-DIPLAY-README.md) ／ [简体](UPSTREAM-DIPLAY-README.zh-CN.md) ／ [繁體](UPSTREAM-DIPLAY-README.zh-TW.md) — 上游 DiPlay（比亚迪）README。
- [xcertplay README](UPSTREAM-README.md) ／ [中文](UPSTREAM-README.zh-CN.md) — 上游 xcertplay README。
- [Standalone 5 GHz local hotspot](LOCAL_HOTSPOT_TEST.md) — 历史，本地热点已从应用移除。
