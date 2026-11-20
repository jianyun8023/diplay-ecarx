# 文档索引

DiPlay 领克 01 / ECARX 753 移植版的全部文档。新增文档时在这里登记一行，并放进对应分组。
仓库维护长期说明和按版本标注的脱敏验证摘要；原始日志、APK、私有车机资料与认证资产留在本地工作区。当前验收状态集中在验收矩阵，历史实验保留各自的版本与测量边界。变更记录以仓库根目录的 [CHANGELOG.md](../CHANGELOG.md) 为准。

## 使用说明（车主与安装者）

- [车主使用说明](USER-GUIDE.zh-CN.md) — 连接、日常设置、排错与升级，含设置截图。
- [功能与使用说明](FEATURES.zh-CN.md) — 各功能的行为、前提、各地图在仪表上的差异与待实车验证项。
- [Install and connect](INSTALL.md) — 英文安装与首次连接指南。
- [Built-in car hotspot setup](CONNECTION_SETUP.md) — 车机热点连接方式。
- [Existing Wi-Fi / Same LAN](EXISTING_WIFI.md) — 现有 Wi-Fi 同网段连接。
- [Compatibility](COMPATIBILITY.md) — 车机与 iPhone 兼容范围。
- [CarPlay on your launcher's home screen](LAUNCHER_INTEGRATION.md) — 桌面集成。
- [Privacy and diagnostics](PRIVACY.md) — 隐私与诊断报告。

## 开发与构建

- [技术架构](ARCHITECTURE.zh-CN.md) — 当前模块、会话数据流、Rust 核心与 Kotlin 平台适配边界及代码归属。
- [可执行的架构规则](ARCHITECTURE.zh-CN.md#可执行的架构规则) — 本地检查命令、CI 门禁和各条规则的适用范围。
- [Rust 核心验收矩阵](RUST_CORE_REMAINING.zh-CN.md) — 当前已验证范围与剩余待验项；已关闭的 issue 不作为当前待办清单。
- [协议样本与黄金向量](PROTOCOL_VECTORS.zh-CN.md) — 样本来源、独立预期、测试入口和外部证据缺口。
- [领克 01 / ECARX 753 平台笔记](ECARX_753_PLATFORM_NOTES.zh-CN.md) — 厂商接口、固件行为、权限、验证矩阵与证据索引，长期维护；新接口先登记再使用。
- [分支模型](BRANCHING.md) — 主干 `dev`、标签发版与分支收敛建议。
- [构建 DiPlay 领克 01 / ECARX 753 移植版](../BUILD.md) — 本移植版特有的平台签名、`.env` 与 MFi 身份；通用构建流程见下一条。
- [Build DiPlay](BUILD.md) — 模块、工具链、Rust 原生构建、Android Studio、单测与 lint 的通用流程。
- [753 车机权限与无 ADB 实施清单](ADB_AND_PLATFORM_PERMISSIONS.zh-CN.md) — 平台权限与不依赖 ADB 的实施、验收基准。
- [ECARX SDK 与麦克风 Policy 上下文](ECARX_SDK_CONTEXT.zh-CN.md) — 反射与动态代理访问厂商接口。
- [Test checklist](TESTING.md) — 测试要点与主机单测。
- [Credits and license notices](THIRD_PARTY_NOTICES.md)、[licenses/](licenses/) — 第三方许可。
- 代理与贡献者约定见根目录 [AGENTS.md](../AGENTS.md)，按归属域拆分任务指南：[会话与传输](agent-guides/SESSION_AND_TRANSPORT.md)、[媒体](agent-guides/MEDIA.md)、[协议](agent-guides/PROTOCOLS.md)、[车辆与仪表](agent-guides/VEHICLE.md)、[界面与设置](agent-guides/UI_AND_SETTINGS.md)。

## 功能与机制说明

连接与启动：

- [Android 9 Wi-Fi Direct](ANDROID9_WIFI_DIRECT.md)
- [RFCOMM 建链与 iAP2 bootstrap 局部恢复](RFCOMM_LOCAL_RECOVERY.zh-CN.md)
- [开机启动优先级与系统应用边界](STARTUP_PRIORITY_AND_SYSTEM_APP.zh-CN.md)
- [车机热点与现有 Wi-Fi：启动耗时采样](CARPLAY_STARTUP_SAMPLING.zh-CN.md)
- [Wireless startup diagnostics](WIRELESS_DIAGNOSTICS.md)
- [默认模式诊断与日志完整性](DIAGNOSTICS_DEFAULT_MODE.zh-CN.md) — 日志预算、报告身份、退出统计与采集缺口。

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
- [ECARX 导航与仪表功能](FEATURES.zh-CN.md) — 当前提示、车道、地图和显隐行为。

## 验证素材与基准

- [verification/navigation-v109](verification/navigation-v109/) — 导航接管决策引用的 v109 导航验证素材。
- [预编译 libopus 实测（2026-11-10）](benchmarks/native-opus-2026-11-10.md) — 选用原生 Opus 编码的性能依据（后续统一为静态链接 libopus，见技术架构）。
- [媒体流原生解密实测（2026-11-11）](benchmarks/native-chacha-2026-11-11.md) — 原生 ChaCha 解密的性能依据（现并入 `libdiplay_media.so`）。
- [车速、挡位与恢复复核（2026-11-14）](benchmarks/vehicle-speed-2026-11-14.md) — Ultra 实车证据、HY DCYTools 同源挡位、固件单位与订阅行为、本地恢复回归及实车待测范围。
- [默认模式诊断手机实测（2026-11-15）](benchmarks/default-diagnostics-mi8-2026-11-15.md) — 两轮无线连接、日志导出和最终统计，不替代实车验收。

## 发布

- [CHANGELOG.md](../CHANGELOG.md) — 本移植版的完整变更记录；公开发布包见 [GitHub Releases](https://github.com/jianyun8023/diplay-ecarx/releases)。
- 上游 DiPlay 的发布说明、验证与评审记录见[上游仓库](https://github.com/shihabal3amri/DiPlay)。

## 文档维护

当前行为写入对应功能说明，职责归属写入架构，验收结论写入矩阵；不要将已完成的任务方案继续列为待办。一次性方案、交接、原始日志和机器路径留在本地工作区，仓库只保留可公开复核的摘要与必要素材。

删除过时文档时同步处理入站链接；已有有效基准、导航决策依据和第三方许可不因日期较早而删除。历史发布说明保留版本标记，当前发行以 Release 为准。说明书中的界面基线与源码版本、被测包、公开发行版本分别标注。
