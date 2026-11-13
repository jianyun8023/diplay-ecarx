# 753 车机权限与无 ADB 实施清单

更新：2026-11-02。需求与后续验收统一维护于 [issue #21](https://github.com/jianyun8023/diplay-ecarx/issues/21)。本轮从 `origin/dev` 的 `a7d93709` 新建独立 worktree 实施，随后 rebase 到包含 #19、#22 的 `af00f223`。ADB 移除测试包使用 versionCode **114**；另一会话的 v115 不属于本轮产物。

## 1. 正式环境与结论

正式环境只有 **2021 款领克 01／亿咖通 753／高通 820A／Android 9（API 28）**，使用本车现有平台签名。Android 10、13 等只可能用于开发调试，不作为正式兼容承诺。APK 只打包 `arm64-v8a`、`armeabi-v7a`；不再发布 x86／x86_64 原生库。

**本轮实现后，正式功能没有必须使用 ADB 的项目。** 应用内 ADB 客户端、密钥管理、5555 连接、ADB 授权流程及功能所用的 shell helper 均已删除。权限不足时明确失败、提供可用的系统设置入口或要求使用车机签名版本，不回退 ADB，也不要求先连接电脑或确认调试密钥。

2026-11-02 用户回传的实车权限检查截图已确认五项新增权限全部授予。下表区分 Manifest 授予、功能访问状态和实际系统调用效果；AppOps／无障碍启用、接口效果及完整重启仍需分别验收，见第 9 节。

## 2. 平台签名与 ADB 权限不能排成同一条大小关系

本车历史记录显示，DiPlay 的签名与 `android` 平台包匹配，历史应用 UID 为 **10125**；本次实车截图显示 UID **10105**，平台签名仍匹配。两次记录均不是 shell UID 2000 或 system UID 1000，不据 UID 变化推断安装过程。平台签名应用可以取得 shell 未必具备的签名权限；因此不能以普通第三方 APK 的权限上限判断本应用。

获得权限仍需要：Manifest 声明、签名与权限声明方匹配、安装时授予，以及固件允许该 UID 调用接口。AppOps、用户状态、组件状态、厂商 Binder 校验与 SELinux 仍可能影响调用结果。平台签名不是任意文件读写或任意命令执行授权。

Android 的签名权限规则见 [protectionLevel](https://developer.android.com/reference/android/R.attr#protectionLevel) 和 [WRITE_SECURE_SETTINGS](https://developer.android.com/reference/android/Manifest.permission#WRITE_SECURE_SETTINGS)。本轮使用 Android 9 framework 方法核对直接接口；实车运行时检查和调用后回读才是最终依据。

## 3. 哪些功能不用 ADB

| 功能 | 当前实现／权限 | 证据与限制 |
| --- | --- | --- |
| 保存“车机启动后打开” | 保存偏好；`RECEIVE_BOOT_COMPLETED` 与 `BootReceiver.startActivity` | v113 已包含删除 ADB 前置检查的修复；不需要新增本轮权限 |
| 开机后的热点与自动连接 | 各自读取设置，使用系统热点、蓝牙和连接 API | 自启、打开热点、连上 CarPlay 是三个不同验收阶段 |
| Android 9 车机热点开启／关闭、配置读写 | 现有 `NETWORK_SETTINGS`、`TETHER_PRIVILEGED`、`OVERRIDE_WIFI_CONFIG` 等平台权限及直接系统接口 | 历史实车已有授予；保留配置保护、状态回读、超时、取消与恢复；通用 ADB 兜底已删除 |
| USB 自动确认所需无障碍服务 | `WRITE_SECURE_SETTINGS`，直接写 `Settings.Secure` 并回读 | 本轮新增声明；保留其他服务及全局启用状态；权限不足不改写 |
| 方向盘按键服务启用、崩溃及开机后恢复 | 同上；保留崩溃绑定移除／等待／恢复和有上限的重试 | 仍以服务实际绑定状态判断可用；不是写设置成功就宣称按键已生效 |
| 地图卡片使用情况访问、悬浮窗配置 | 已有 `PACKAGE_USAGE_STATS`／`SYSTEM_ALERT_WINDOW`；需要补设时使用 `MANAGE_APP_OPS_MODES` 为自身 UID 设置 AppOps | 历史包已有前两项授予，本轮新增后一项声明；最终按权限状态回读 |
| Wi-Fi Direct 期间暂停／恢复自动扫描 | `CONNECTIVITY_INTERNAL`，在应用进程调用 Android 9 `WifiManager.enableWifiConnectivityManager` | 本轮新增声明；保留单一写入者、租约、持久恢复记录、失败重试及应用重开恢复；其他应用主动扫描不在控制范围 |
| 缺少 USB 系统授权页面时连接 iPhone | `MANAGE_USB`，调用 `UsbManager.grantPermission(device, ownUid)` 后检查 `hasPermission` | 本轮新增声明；只处理仍连接的 Apple 设备和自身 UID，不给任意包授权；普通系统授权页面存在时继续使用它 |
| 缺少 VPN 系统授权页面时连接 | `CONTROL_VPN`，调用 `VpnService.prepareAndAuthorize(context)` 后检查 `VpnService.prepare` | 本轮新增声明；只授权自身应用，失败不伪报连接就绪；正常系统授权页面仍优先使用 |
| HY 后台按键日志识别 | 应用进程直接读取 logcat，使用 `READ_LOGS` | 2026-11-01 实车记录已有授予；移除原来的 `pm grant` 按钮，缺失时提示安装车机签名版本 |
| 车机媒体、导航、DIM／HUD、日夜模式、关闭原厂 HiCar | 现有车机 SDK／Binder、相应车机签名权限及 `CHANGE_COMPONENT_ENABLED_STATE` | 不需要 ADB；每项效果仍受厂商接口、模式和实车验证范围限制 |
| 蓝牙控制及普通 CarPlay 音视频／触摸 | Android／车机接口及现有蓝牙、录音等权限 | 不需要 ADB；本轮不扩大功能范围 |
| 导出诊断报告 | 应用可访问的系统 API、权限状态与应用日志 | 不再连接 shell 获取额外系统信息；不可访问的信息不作为正常功能前置条件 |

新增的 `WRITE_SECURE_SETTINGS`、`MANAGE_APP_OPS_MODES`、`CONTROL_VPN`、`CONNECTIVITY_INTERNAL`、`MANAGE_USB` 均声明 `maxSdkVersion=28`。不会为了调试设备补做 Android 10／13 ADB 兼容路径。

车辆电量、轮速等历史设置文案曾写“需要 ADB”，不能作为接入证据。基线创建控制器时 `vehicleStatusProvider = null`，本轮不宣称这些未接通的数据已恢复。实际按当前车机接口是否有有效数据判断，不要求启用 ADB。

## 4. 哪些项目必须用 ADB

**正式功能：无。** 缺少平台权限时，该功能的系统操作不能完成；正确处理是核查签名、Manifest、安装授予和固件接口，不将 ADB 设为产品使用条件。

| 场景 | 是否属于正式功能 | 处理方式 |
| --- | --- | --- |
| 主机安装、抓取 `dumpsys`、检查 shell 文件访问、开发调试 | 否 | 开发者可自行使用外部 ADB；不是应用授权或日常运行步骤 |
| 必须复现 shell UID 本身的行为 | 否 | 应用 UID 不能假装 shell；作为独立开发诊断，不接回正式功能 |
| Android 10／13 测试设备缺少平台签名权限 | 否 | 使用系统授权页面、手动配置或跳过对应专用功能；不承诺与正式车机相同能力 |
| 历史 ADB helper、旧版 Android 13 热点自动加入修复 | 否，已删除 | 只作历史研究；不再作为当前包的功能或恢复入口 |

本轮不修改车机调试开关，也不修改旧版遗留的厂商系统配置。外部 ADB 工具是否可用不影响应用内是否存在 ADB 依赖。

## 5. 为什么以前开机自启会提示 ADB

旧版在启用自动车机热点、模式为 `MANUAL` 时，以 `adbToggle` 包装自启开关：先连接 ADB、设置 `SYSTEM_ALERT_WINDOW`，成功后才保存开关。`BOOT_LAUNCH` 对应悬浮窗 AppOp，不是开机广播权限，也不是厂商自启白名单。

提交 `1a5712bc` 已删除该前置检查，v113 的来源代码已包含修复。本轮基线 `a7d93709` 已经是直接保存设置的实现，本轮继续移除自动连接及辅助授权阶段剩下的 ADB 调用和误导文案。

```text
开启“车机启动后打开” → 保存设置
BOOT_COMPLETED → BootReceiver 读取设置 → 请求打开 DiPlay
应用打开后 → 按独立设置打开热点／连接 CarPlay
```

厂商能否发送开机广播、允许启动页面，以及休眠唤醒是否等于完整开机，都需要实车核对。不能把“开关保存成功”“启动调用返回”当成“实车重启后已自动连接”。

可从应用诊断报告读取：`received`、`launchEnabledAtBoot`、`launchResult`、`failureClass`。`startActivity-returned` 只表示调用返回，不能单独证明页面已显示。

## 6. 历史授予与本轮实车回读

| 权限 | 已有材料 | 本轮状态 |
| --- | --- | --- |
| `WRITE_SETTINGS`、`SYSTEM_ALERT_WINDOW`、`PACKAGE_USAGE_STATS` | v104 实车 `granted=true`；本次截图也均已授予 | 悬浮窗实际访问已授予；使用情况访问仍显示未授予，需单独检查 AppOps |
| `NETWORK_SETTINGS`、`TETHER_PRIVILEGED`、`OVERRIDE_WIFI_CONFIG` | v104 实车 `granted=true` | Android 9 热点直接路径已有基础 |
| `BLUETOOTH_PRIVILEGED`、`CHANGE_COMPONENT_ENABLED_STATE`、`MEDIA_CONTENT_CONTROL`、车机 DIM 权限 | 历史实车已有授予 | 不以 ADB 可用性判定这些能力 |
| `READ_LOGS` | 2026-11-01 实车记录 `granted=true` | 直接使用；缺失时提示签名版本 |
| `WRITE_SECURE_SETTINGS` | 车机权限表存在，`signature|privileged|development` | 本次实车截图已确认授予；设置写入与服务绑定效果待验证 |
| `MANAGE_APP_OPS_MODES` | 车机权限表存在，`signature|installer|verifier` | 本次实车截图已确认授予；AppOps 设置后的实际访问待验证 |
| `CONNECTIVITY_INTERNAL`、`MANAGE_USB`、`CONTROL_VPN` | 车机权限表存在，`signature|privileged` | 本次实车截图已确认全部授予；扫描、USB、VPN 接口效果待验证 |

历史材料来自本地工作区 `logs/cap-v104-20261031-session1/boot-audit/package-details.txt` 和 `docs/实车验证记录-2026-11-01.md`，不随公开仓库分发。不将完整车机 dump、个人日志或签名资产加入仓库。

### 现场检查入口

在 **设置 → 诊断 → 权限检查** 中，直接查看平台签名是否匹配、应用 UID、应用声明权限的实际授予，以及使用情况访问、悬浮窗、USB／方向盘无障碍授权和方向盘服务连接状态。未授予项目排在前面；“系统未提供”与“未授予”分开显示，避免把新 Android 权限误判成本车缺权。

“刷新”只重新读取状态，不授权、不修改设置；同一份结果会加入导出的诊断报告。签名匹配、Manifest 权限已授予、功能访问已允许、服务实际连接是不同状态，页面分别检查。厂商自启策略和接口实际效果仍须实车验收。

## 7. 实现与复核入口

- [权限检查快照](../common/src/main/java/com/shihab/diplay/PermissionDiagnosticSnapshot.kt)：权限页面与导出报告共用的只读检查。
- [公共 Manifest](../common/src/main/AndroidManifest.xml)、[共享 Manifest](../shared/src/main/AndroidManifest.xml)：权限声明。
- [权限配置](../common/src/main/java/com/shihab/diplay/UsbPermissionSetup.kt)、[方向盘服务](../common/src/main/java/com/shihab/diplay/WheelKeyService.kt)：自身授权、服务名单保护及恢复。
- [USB 原生授权](../shared/src/main/java/com/shihab/diplay/transport/IphoneUsbGrant.kt)、[VPN 原生授权](../common/src/main/java/com/shihab/diplay/VpnPreConsent.kt)：目标边界和写后验证。
- [Wi-Fi 扫描控制](../shared/src/main/java/com/shihab/diplay/network/WifiScanPause.kt)、[恢复状态](../shared/src/main/java/com/shihab/diplay/network/WifiScanPauseSession.kt)：暂停、租约、恢复责任。
- [系统热点](../shared/src/main/java/com/shihab/diplay/network/CarHotspotTethering.kt)、[开机诊断](../common/src/main/java/com/shihab/diplay/StartupDiagnosticSnapshot.kt)：自启与连接阶段分离。
- 根 `build.gradle.kts` 限制所有应用的 ABI，`shared/build.gradle` 限制预编译库；最终以 APK 的 `lib/` 内容复核，不能只看库模块配置。

## 8. 包体与验收记录

历史 v113 发布 APK 中 x86／x86_64 库条目合计 **104,300 字节**（约 102 KiB，不含 ZIP 对齐和目录开销）。因此删除确实能减小包体，但不能据此把本轮整体 APK 大小变化全部归因于 ABI：本轮同时删除 ADB 代码、资源并改动权限与文案。APK 前后须使用相同构建类型比较。

主机检查与 APK 内容核对结果记录在 issue #21；不把 Robolectric 或编译通过当成真机系统服务已授权。权限界面的 1920×1080 [自动化测试截图](screenshots/12-permission-check-android9-test.png)用于核对布局，其中 UID、签名和授权值属于测试环境。

本轮验证包括仓库规定的 shared／common／home 单元测试、mobile／home／maphost／siribridge 的 Lint 与 Debug 构建；同时静态检查 APK ABI、新增权限声明和 ADB 客户端缺失。已收到实车权限检查截图，完整重启与功能调用验收待进行。按用户要求，rebase 后只补跑 4 个测试类共 13 项，全部通过，未重复全量单测。

PR [#23](https://github.com/jianyun8023/diplay-ecarx/pull/23) 提交后，已从源码 `d18a1b58` 构建 **v114 Release**（`0.2-car-test-noadb-v114`，包名 `com.shihab.diplay`）。Release Lint、Android 9 验签、zipalign、ARM ABI、五项 API 28 权限声明及 ADB 客户端缺失检查通过，签名与认证输入均与 v113 一致。

- APK：`DiPlay-noadb-release-v114.apk`，17,909,257 字节；比本地 v113 小 218,522 字节，该整体变化不只来自 ABI 删除。
- SHA-256：`b22580ca8252cc226ddba0e82951bb1130e0bd45f46fcf0a3f99f05067c01d96`。
- 本地工作区产物、源码 ZIP 和验证记录：`artifacts/issue21-v114/`；签名与认证资产未进入源码归档。

待实车验收（安装本车平台签名的新包后，关闭网络调试；不先用电脑授权）：

- [x] 实车截图回读五项新增权限，记录可见的既有权限与功能访问状态。
- [ ] 完整重启：分别记录广播、DiPlay 页面、热点和 CarPlay 连接结果；另测休眠唤醒。
- [ ] USB／地图权限配置：首次启用、已有授权、缺失权限、取消，其他无障碍服务不丢失。
- [ ] 方向盘服务：首次启用、崩溃恢复、应用更新、关闭与开机后的实际绑定。
- [ ] Wi-Fi Direct：暂停、正常断开恢复、进程退出／重开恢复，不能只确认暂停成功。
- [ ] 缺少 USB／VPN 授权页面时，确认平台接口实际生效；普通页面存在时仍可授权。
- [ ] HY 后台日志按键与热点读写／启停，核对诊断报告，无调试授权提示。

## 9. 2026-11-02 实车截图反馈

证据来自用户提供的权限检查页面照片，仅转录可辨识状态。页面显示 UID **10105**、平台签名**匹配**；截图未显示应用版本、调试开关或具体启用操作的执行结果，不据此确认对应场景已经完成。

| 检查项 | 截图结果 | 当前结论 |
| --- | --- | --- |
| 五项新增权限 | `WRITE_SECURE_SETTINGS`、`MANAGE_APP_OPS_MODES`、`MANAGE_USB`、`CONTROL_VPN`、`CONNECTIVITY_INTERNAL` 全部已授予 | 新声明的安装授予已由实车确认，直接系统调用具备相应权限前提 |
| 热点、日志与车机权限 | `NETWORK_SETTINGS`、`OVERRIDE_WIFI_CONFIG`、`TETHER_PRIVILEGED`、`READ_LOGS`、DIM／媒体／导航等可见条目已授予 | 不应再按缺少这些 Manifest 权限要求 ADB |
| 使用情况访问 | `PACKAGE_USAGE_STATS` 已授予，功能访问仍显示未授予 | 当前检查依赖 `GET_USAGE_STATS` AppOp 为 `MODE_ALLOWED`；Manifest 授予不能替代该检查 |
| 无障碍与方向盘服务 | USB／方向盘无障碍授权显示未授予，方向盘服务未连接 | 仍须启用并确认实际绑定；用户不确定是否执行过启用，尚不能认定启用操作失败 |
| 悬浮窗 | `SYSTEM_ALERT_WINDOW` 与功能访问均已授予 | 此次可确认悬浮窗访问检查通过 |
| 存储 | `READ_EXTERNAL_STORAGE`、`WRITE_EXTERNAL_STORAGE` 未授予 | 与五项平台权限分开处理，按具体文件功能使用系统授权或文件选择器，不引入 ADB |
| 较新 Android 权限 | `ACCESS_LOCAL_NETWORK`、`BLUETOOTH_CONNECT`、前台服务细分权限、`NEARBY_WIFI_DEVICES`、`POST_NOTIFICATIONS` 等显示系统未提供 | 符合本车 Android 9 范围，不作为缺权故障补授 |

下一步使用应用现有入口验证：连接设置中的“设置 USB 与地图权限”，以及方向盘服务中的“启用方向盘按键服务”；随后回到权限检查点击“刷新”。若明确执行后仍未通过，记录页面提示并导出诊断报告，检查授权操作的 `verified`／`failed` 和方向盘服务绑定结果。权限检查页面本身只读，不会因打开或刷新自动启用功能。

本次只更新文档与 issue／PR 的实车证据，不改应用代码、不重跑单测，也不重新构建 APK。完整重启、关闭调试后的自动连接及各原生接口效果继续保留为待验收项。
