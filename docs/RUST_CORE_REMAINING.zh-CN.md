# Rust 核心验收状态与历史审计

## 当前状态与验收矩阵（2026-11-14）

**源码层面的完整 Rust 核心迁移已由 [PR #97](https://github.com/jianyun8023/diplay-ecarx/pull/97) 合入 `dev`。** 当前工作是具体缺陷修复与按版本补验，不再沿用旧迁移分支。本文是当前剩余项的仓库入口；已关闭的 [#96](https://github.com/jianyun8023/diplay-ecarx/issues/96)、[#98](https://github.com/jianyun8023/diplay-ecarx/issues/98)、[#99](https://github.com/jianyun8023/diplay-ecarx/issues/99) 保留历史证据，其正文中的旧清单和关闭状态都不能单独作为当前验收结论。

| 范围 | 已有证据 | 当前边界与下一步 |
| --- | --- | --- |
| AAudio 关闭 | v149 含 `d16ed0a1` 已在 ECARX／API 28 实车通过：音乐和导航使用 AAudio，正常拆除，未复现 PAUSING flush 错误。见 [#96 实车审计](https://github.com/jianyun8023/diplay-ecarx/issues/96#issuecomment-6841704520)。 | 该关闭缺陷按此批实车证据已验证。当前音频缺口集中在下一行 #111 的输出失败恢复，不因版本变动笼统重开已通过项目。 |
| Siri、通话、echo 恢复 | 小米 8＋真实 iPhone 已通过 Siri 语音／播放恢复和约 16 秒运营商通话；见 [#96 勘误](https://github.com/jianyun8023/diplay-ecarx/issues/96#issuecomment-6842219271)。#111 同格式 renderer 失败后的 echo reference 切换有 60 项定向 JVM 与完整 Checks／CI。 | [#111 后续验收说明](https://github.com/jianyun8023/diplay-ecarx/issues/96#issuecomment-6871691681)仍要求 Android 9／ECARX 持续通话中注入输出失败，核验真实 Speex／平台 AEC 瞬态、焦点和麦克风。车机方控 Siri 未验；通话轨 312 **采样帧**（48 kHz 约 6.5 ms）欠载仍需分阶段复测，不是 312 次错误。 |
| 原生 USB NCM／重连 | 小米 8 原工具日志确认 4 次主动拔插通过；#104 同逻辑包 3 轮重启、最终包起播及有线／无线切换通过；CSV 有 156 秒有线采样，Harness 补齐该窗错误查询结果。20:57 断开经用户确认为主动操作，21:17 切无线也有明确指令，不计自然掉线。见[USB 验收记录（已收尾）](benchmarks/native-usb-2026-11-13.md)，按不同 APK、计时起点和采集范围登记。原生路径默认开启，保留显式关闭和启动失败回退。 | **本轮已收尾（2026-11-14）**：用户确认当前状态 OK，停止 USB 分析与补测，移出当前待办。小米 8 的指定场景通过；长时运行、故障注入和目标固件的新组合仍是证据边界，不追认为全面通过，也不作为本轮阻塞项。 |
| PASCD 车速源／真实 SDK ABI | #101 已接生产；Ultra 有同一车速源的实车显示，旧系统日志另有非零回调；v150 有 8 次注册／即时回调路径。HY DCYTools 使用同一 VHAL 挡位属性，真实固件枚举与当前 P/R/N/D 映射一致。车速相关 17 项＋共享替身关联 7 项定向测试通过；完整 Checks 通过（3595 通过／2 条件跳过），见[代码与日志复核](benchmarks/vehicle-speed-2026-11-14.md)。 | 单位转换、数据源、映射与默认订阅行为已有依据。缺当前包非零速度／时间戳／挡位到 PASCD 的端到端记录，以及 AdaptAPI、VHAL 真实服务中断恢复。未 drain 批次清空和 VHAL 重新取初值／订阅的本地回归已补齐通过；完整 SDK ABI 单列。应用重启不等于服务断线验收。 |
| 视频首包／配置顺序（#113） | [#113](https://github.com/jianyun8023/diplay-ecarx/pull/113) 已合并；小米 8 的 16 项设备回归、同 APK 原生库交替对照和 300.334 秒会话观察已完成。详见[验证摘要](benchmarks/native-video-startup-2026-11-14.md)。 | 该批通过数、APK 身份和设备恢复记录固定属于 #113；未测物理显示延迟、目标 ECARX／API 28 硬解和全部 ABI 运行时，不替代后续批次验收。 |
| 配置失败交错（本批） | 以 `eeca03ae` 为基线，小米 8／API 35 两项新增回归均有效 red（10.815 秒）。生产修复已实现：4 文件、17 增／11 删，以锁内 0／1 hold 判断保留后续配置的帧。本地完整 Checks／四应用 R8 通过；JVM 3593 通过＋2 环境跳过，Rust 226 通过，三 ABI 的 494 JNI＋JNI_OnLoad／来源校验通过。详见[本批记录](benchmarks/native-video-startup-2026-11-14.md)。 | 修复测试 APK 已构建签名，尚未安装／green。按用户要求等小米 8 其他任务结束、通知释放后优先执行 ARM64 回归；**green 待验，PR 尚未创建**。本批没有新增设备恢复／实机通过结论。 |
| 导航音量键 | 11 月 14 日 17:25 诊断报告（本地 `analysis/20261114-diplay-report-review/REPORT.md`）记录 `wanted=false allowedInSettings=false bound=false`，未记录 APK 身份；用户确认尚未完成开启相关设置及无障碍服务后的真车复测。 | 先确认服务已启用／绑定、音量键事件是否到达，再核验导航播报时实际调节效果。当前不能据未启用状态认定音量算法或 Rust 回归。 |
| 双屏／HUD／生命周期与长测 | 历史 v149 实车有 cluster 首帧，#98 小米 8 台架有 overlay 地图实际渲染；#113 新会话记录了两路接收。 | 最新版本的双屏销毁／重建、Surface 停放恢复、真实 HUD／HID、熄火唤醒和长时间音视频组合仍需单列验收。#113 的 5 分钟视频观察不包含真实音频路线，也不能证明无泄漏。 |
| v149 观察项与 QTI low-latency | [#99 v150 实车评论](https://github.com/jianyun8023/diplay-ecarx/issues/99#issuecomment-6872722131)已验证低延迟开关下发／撤销、正常起播；延迟对照无可测收益，驱动未回报实际生效，保持默认关闭。 | #99 的 08:24:18–08:24:38 Host 暂停，用户已确认记不清操作背景，**仅保留观察，不认定故障或当前回归**。3.2 秒音频到达间隙／stale backlog、熄火后状态也先核操作背景和当前包复现，再决定是否处理。等待数据的 `readsOver250Ms` 不等于处理耗时。 |
| 独立协议样本 | 保留数学参考、旧实现差分及已登记的外部向量。 | 完整 PairVerify／PairSetup transcript 和更多真实 CSM／HUD 固定输入输出仍需可公开登记来源的独立样本；自写 fixture 不作为独立证据。 |

当前平台状态另见 [ECARX 验收表](ECARX_753_PLATFORM_NOTES.zh-CN.md#7-验证矩阵)。新增结果须注明源码／包、环境、场景和未覆盖范围；历史实车通过、最新变更未补验与确实未覆盖的目标硬件分别记录。原始日志、认证资产和设备标识留在本地，不提交仓库。

## 当前剩余项的处理范围（2026-11-14 澄清）

- **明确待执行**：配置失败交错修复的 ARM64 实机 green，仍等用户释放小米 8；完成后提交该修复 PR。
- **待真车场景验证**：导航音量键开启设置／服务后的复测；#111 输出失败时的通话／AEC／焦点恢复；PASCD 动态数据到发送的对应记录／真实服务恢复；方控 Siri、实体 HUD 和显示／熄火组合。已有普通通话、Siri、音乐和导航证据保留，不列为“从未测试”。
- **仅观察**：约 20 秒 Host 暂停、音频到达间隙及相关历史现象。未明确操作背景或复现前，不当作已确认缺陷。
- **证据补全**：真实 SDK 完整 ABI、独立协议 transcript。它们不阻塞本轮局部视频修复 PR。
- **另批结果待归档**：v148／#113 分阶段性能与长稳由另一任务进行；结果按各自版本、场景登记，不以本轮短观察代替。

USB 本轮已收尾；#113 首帧修复及历史 AAudio 关闭修复已有验收结果，不重复列入本轮待执行项。

## 历史审计批次

以下保留原批次数字和判断。下文的“本轮”“当前”“暂挂”和“缺 iPhone”等均指对应历史采样阶段，**不覆盖上方最新矩阵**；尤其 iPhone 当时离场不代表后来未做真实会话，旧双 ABI／491 JNI／测试总数也不应改写成新批次数字。

基线：`feat/rust-protocol-media-all` 的 `0c4820ae`。对应 [issue #96](https://github.com/jianyun8023/diplay-ecarx/issues/96) 和 [PR #97](https://github.com/jianyun8023/diplay-ecarx/pull/97)。本轮按生产调用核查 Rust 核心与 Kotlin Android 薄宿主的差距，补齐可在源码及现有测试环境完成的部分；不把 Kotlin 文件行数作为完成标准。

### 基线已经具备的核心

基线已接入协议／密码、配对、iAP2、USBMUX／Lockdown、NCM、媒体队列／时钟／输出 scheduler、codec 选型、会话／controller／VPN 代际、watchdog、wireless recovery、导航／车辆转换等 Rust owner。旧文档中“controller 仍持有代际”“Lockdown 尚未集成”“UDP 音频仍逐包交 Kotlin”“所有核心需要 Kotlin fallback”等描述已不反映实际入口。

这轮没有重做上述迁移。检查 controller／WirelessRun／VPN 的实际调用后，保留平台资源句柄、监视器、SDK I/O、线程交接和异常对象引用；代际、有效性、预算、deadline、handoff 与拆除动作计划继续由现有 Rust owner 决定。

### 本轮发现并补齐的源码缺口

| 真实缺口 | 收敛到的 Rust owner | Kotlin 保留 |
| --- | --- | --- |
| AirPlay `/info` 能力声明、固定 event／command 请求体、VideoInCar 控制协议、Bonjour TXT／connect probe 编码及 probe 重试计划 | `protocols::airplay`，复用既有 bplist／RTSP／平台字符串桥 | 配置／DTO、socket／SDK 调用、平台文本转换和回调 |
| 通话／导航的音频路由映射、speaker fallback、far-end PCM ring 与 capture timestamp／clock | `media::audio::{routing, echo}` | Android 音路能力、AudioTrack／AudioRecord、AEC／Speex 调用、对象句柄和关闭执行 |
| BufferedAudioStream anchor／feedback map schema、NTP epoch／fraction 换算 | `media::audio::buffered` 的原生回复入口，复用 bplist 桥 | Java BigInteger／数值对象转换与平台提交 |
| iAP2 0x5001 NowPlaying 增量字段解析、保留及清空 | `session::playback` | 不可变 `CarPlayNowPlaying` 发布 DTO、平台 UTF-8 replacement／trim、监听器 |
| 导航 renderer lease、queued playback＋900 ms quiet tail、路由冲突和 priority voice 聚合 | `session::playback` | renderer token 对象和时钟；UI 只读取快照 |
| 热点 IPv4／IPv6 选择与 alternate、scoped IPv6 等待／过滤、family-flip 证据门槛；Bonjour endpoint 的独立地址优先级 | `session::network_address` | `InetAddress` 能力事实、接口扫描、取消、等待、日志及偏好存储 |
| 热点 2／3 稳定样本确认、首样本快速确认与变更降级、readiness deadline／250 ms polls | `session::hotspot_recovery` | AP／接口识别与平台对象身份比较、采样和 SDK 动作 |
| 热点 radio-return 的 bounded wait／500 ms polls、进程级 radio outage 时间窗 | `session::hotspot_recovery` | 系统时钟／线程取消、AP 状态查询、平台动作与日志 |
| Wi-Fi Direct channel plan、BUSY 的一次同频道重试、compatibility fallback 和已知不可恢复失败 | `session::p2p_startup`（本轮集成） | `WifiP2pManager` 请求、前置 SDK interlock、异常对象及回调 |
| iAP2 0x5204 lane update TLV／字段规则、排序及 unknown-angle 处理 | `vehicle::route_guidance::lane_update`，复用 `protocols::iap2_body::parse_parameter_list` | 不可变 lane DTO 组装和 HUD／仪表 SDK 输出 |
| 仅遗留在生产源码但无生产调用的旧 DSP 算法 | 移至 `testCore` 独立参考 | 测试对照，不进入生产 |
| CI 仅构建 host JNI、未执行 Rust 自身单测 | Android workflow 新增 `cargo test --locked --lib` | Gradle 继续执行 JVM／Android 模块检查 |

新增 GC opaque value owner 不引入 native heap close 生命周期；需要共享 PCM 引用的 echo owner 沿用明确引用所有权。Kotlin 不解释迁移状态的 token 编码，也不保留一份镜像核心状态。

### 明确保留的平台边界

- Activity／Service／视图／输入事件、Android 权限与焦点、设置、媒体会话发布、持久化、车机 SDK provider 和输出。
- `A2dpSinkPolicy`：Android 9 priority 与新系统 connectionPolicy 的隐藏 SDK 兼容、反射及 connect／disconnect 动作。
- `selectHotspotInterface`、`LocalOnlyHotspotInterfacePolicy`、`ExistingWifiManager`：识别 Android AP／station／tethered 接口、BSSID、ifindex、Network／LinkProperties 与可用能力；地址选择和稳定确认由 Rust 决定。
- `WirelessBluetoothConnection`：读取 bonded devices／实时 profile，挑选并验证平台 BluetoothDevice、执行 RFCOMM；run 重试次数、fallback 预算与终止分类由 Rust 决定。
- `CarWifiClientGuard`／OEM 热点控制：车机 Wi-Fi client 与 AP 的互斥、平台设置值／固件能力、实际 SDK 写入和恢复。
- Bonjour 的 TCP connect probe 次数／间隔和候选地址优先级属于互通策略，由原生 owner 决定；Android NSD 的 `FAILURE_ALREADY_ACTIVE` 解析重试、LocalOnly／P2P `awaitGroup` 等仍是 SDK 操作互斥／超时适配。保留这些等待不表示允许 Kotlin 再维护会话恢复预算。
- `WifiScanPauseSession`／journal、`P2pOwnership`、`HotspotHeadStart`／`RfcommPreconnect`：SDK 资源的恢复凭据、归属确认、单次接收与线程同步；不为移动这些平台句柄新增一套跨 JNI 锁。
- 平台 `String`／UTF-8 替换、`Calendar`／`Formatter`、JCA／PKIX 身份材料和硬件密钥；AirPlay owner 保留 Java BigInteger 极值序列化和 Regex 字符分类的本平台执行边界，协议 bit／pattern／布局仍由 Rust 定义。已经迁移的协议语义与密码算法不回到 Kotlin。
- MediaCodec／Surface／AudioTrack／AAudio／AudioRecord 及平台 AEC 的能力回退、设备校准和释放顺序；Java socket／USB I/O 仍执行 Rust 协议与媒体核心返回的请求。
- 诊断聚合、日志限流、UI 地图可见性和宿主的资源引用保留 Kotlin；不把它们当作会话核心未迁的证据。

### 本轮验证

本节登记本轮源码冻结阶段、下述 v149 Release 真机运行之前的执行结果；其中“未操作物理手机”仅指该阶段。历史云端构建、旧候选 APK 和此前设备数字不能充当新源码验收。

| 检查 | 本轮结果 |
| --- | --- |
| Rust session 定向 | `cargo test --locked ... session::`：30 passed，覆盖新增 playback／address／hotspot recovery；执行于本轮代码集成过程中 |
| Rust lane parser 定向 | `vehicle::route_guidance::tests::lane_updates...`：1 passed |
| 整批 locked Rust 单测 | 平台差异修复后的最终冻结源码 `cargo test --locked --lib`：212 passed、0 failed／ignored；本地日志 `build/task-validation/rust-final.log` |
| shared 定向 JVM／消费者回归 | 修复后 388 tests、0 failures／errors／skipped，按 XML 汇总；本地日志 `build/task-validation/targeted-recheck.log` |
| 测试 source-set 边界／Bonjour 补充定向 | common／home 测试 Kotlin 编译通过；新增 Bonjour＋NativeAirPlay＋HotspotAddress 定向通过；本地日志 `build/task-validation/boundary-recheck.log` |
| shared／common／home 全量 JVM | 最终 3546 tests：3544 passed、0 failures／errors、2 skipped；shared 1956 passed／2 skipped，common 1573 passed，home 15 passed。跳过项为主机端口行为和缺可加载的真实 SDK class JAR |
| 完整 CI／四应用 R8 | 最终同一轮 `BUILD SUCCESSFUL`，5 分 33 秒、432 tasks；三模块 JVM、四应用 lintDebug／assembleDebug／minifyReleaseWithR8 全部通过；本地日志 `build/task-validation/final-verification.log` |
| Android 测试 APK／产物入口审计 | 测试 APK 已构建并通过下列 49 项；最终 APK／JNI／R8 来源证明见产物段落 |
| 双 ABI 原生构建 | 平台合同修复后以 Rust 1.97.1／NDK 28.2.13676358／API 28 重建 ARM64／ARMv7，源 manifest 构建前后相同、两个 ABI 的 491 个 Java JNI 导出一致 |
| API 28 ARM64 模拟器真实 JNI 合同 | 平台差异修复后同一 49 项全部通过，11.405 秒；本地日志 `build/task-validation/api28-device-final.log`。未操作物理手机；模拟器不代表真实 USB、音路或车辆验收 |
| ECARX 真实 SDK ABI | 暂挂：本地候选 `ecarx.adapterapi.jar` 只有 `classes.dex`，没有 JVM `.class`；URLClassLoader 不能加载这些真实接口，fixture 不能提供真实 ABI 证据 |

新增 host／Android 共用独立旧实现合同覆盖 NowPlaying 的缺失／空／畸形字段和平台文本、导航 token／expiry／路由、地址选择／scope／取消／clock 顺序、readiness 样本序列、radio-return／outage 时间窗，以及 lane 截断／变异／重复字段。既有 controller／watchdog／handoff／恢复与 HUD／Glance 消费者测试继续作为回归入口。

这轮 native 产物来自基于 `0c4820ae` 的未提交迁移批，不能只用该基线 commit 作为产物源码标识。最终 Rust 源 manifest SHA-256 为 `da94e9f0fd3a6e2fc48fb7ad3a1513f291798a739db7c261f8f2f42b74f2774d`；ARM64 库 SHA-256 为 `25744c462ac3e43751264dd5a476cfe8fdb5ed220cac2f1e79e833b5a81bc228`，ARMv7 为 `130eeaef17712420726f5be4b99f6b8e4cefe7a24a49f3867a424ea11a4ec342`。计算规则与完整来源记录见 [native NOTICE](../shared/src/main/jniLibs/NOTICE.md)，本地逐文件 manifest、ELF／导出和二进制哈希保存于 `build/task-validation/native-*`。

最终 mobile Debug APK SHA-256 为 `6d7547079ea6c556899f382ad1fb3b4af4a521b837525c4d314450389059c2ab`。mobile 与 shared 测试 APK 的两 ABI 库均与对最终原库独立执行 `llvm-strip --strip-unneeded` 的结果逐字节一致。上述原库分别为 1,949,032／1,319,204 字节；APK 内 strip 后分别为 1,949,024／1,319,200 字节，SHA-256 分别为 `e7fbba01fef0d207ea92bed877c34d11cdc3668290c14ad01fc99a9cd70ed1dd`／`a2e47035a3732ec746e2fbb753d595e1d3699d9fc401099fd277082a5b34cfbb`，不能混用原库与 APK 内库的哈希。两包的 14 份 license／NOTICE／source-pointer 资源与仓库逐字节相同；home／maphost／siribridge 没有运行时 shared 依赖，不包含 core 符合产品边界。本地证据为 `build/task-validation/debug-apk-native-license-audit.log`。

最终四应用 R8 构建及 mapping／keep seeds 核对通过：mobile 的八个新增 facade、55 个 native 方法、NativeMedia 新增的两个入口、NowPlaying 八参构造及 decodeText 均保留，并与双 ABI 相同的 491 个 Java JNI 导出对齐；另外三个应用无需 shared-core 类。证据为 `build/task-validation/r8-jni-keep-audit.log`。公开树扫描覆盖 1665 个 tracked 与 59 个 untracked 文件并通过；Rust 目录 123 个文件（含源码、Cargo／toolchain 配置和构建脚本）的最终 manifest 与重建一致，证据为 `build/task-validation/public-tree-including-untracked.log`。上述 `build/task-validation/` 日志为本地 ignored 验证产物，不在公开源码树上传原始 dump／APK／日志。

首轮定向的 388 tests 中有 42 项失败，暴露新增 Robolectric 转发遗漏，以及 NowPlaying 内嵌 NUL／corner-mask 畸形嵌套的真实合同差异；修复后重跑同一集合全部通过。NowPlaying scalar string 原接口只去掉末尾 terminator，内嵌 NUL 仍保留；Rust 断言和 JVM／设备共用合同均覆盖该行为。新增 native facade 也接入现有 Robolectric `HostNative` 真 JNI 转发，DTO 在 sandbox 重建，不使用算法假实现绕过 JNI。

整批检查还暴露新 shared 内部合同被共享 source-set 编入 common／home 的编译错误。新 fixture 已隔离到 `shared/src/testCoreInternal/kotlin`，只供 shared JVM／Android 测试；通用旧参考与真 JNI shadows 继续共享。生产 `internal` 没有为测试改成 public。

API 28 首轮 49 项中两项暴露 Android／桌面平台差异：Regex 数字分类，以及 BigInteger 极值的 bit／byte 行为。修复保留本平台执行边界，Rust 仍定义协议 pattern／feature bits／布局；修复后相同 49 项全部通过，没有删除或放宽合同。

### 暂挂项与恢复条件

| 暂挂项 | 恢复条件 |
| --- | --- |
| ECARX 753／820A 的有线 USBMUX／Lockdown／NCM、拔插／重连、权限与固件行为 | 具备目标车机、真实 iPhone 和接口，使用本轮双 ABI 构建产物完成组合回归；运行和恢复不依赖 ADB |
| 实车音乐／导航／通话／Siri 音路、AEC 与 AudioRecord timestamp、focus／duck／route 校准 | 目标硬件实际播放与录音，覆盖主流使用路径和长会话，记录路由、听感、欠载和恢复；host／模拟器 PCM 合同不能替代 |
| 无线首次连接、radio 休眠／唤醒、热点／P2P／Bonjour 与 IPv4／IPv6 family recovery 的组合互通 | 实车真实 radio／AP 与 iPhone 重现连接、断开、回车唤醒与重试；保存包含版本和代际的日志 |
| 双屏／HUD／仪表／HID 与 Surface／codec 切换的实车组合验收 | 目标显示和输入设备就绪，完成实际输出及操作回归 |
| R8 优化发布包的实际运行（部分完成） | 小米 8 已完成下述 v149 正式 Release 包的无线会话／主屏／音频运行，AAudio 最小修复已完成独立设备验收；真实 CarPlay 修后重放、实车及目标 SDK／组合场景仍待验收 |
| PASCD 真实车速装配 | 已接入 ECARX `ISensor` 1048832（m/s）与 VHAL `GEAR_SELECTION`，`EcarxVehicleSpeedSource` 驱动既有 Rust PASCD 编码；接口／单位／权限已在平台笔记登记。仍需目标车实车验证订阅、权限、断线恢复与单位 |
| ECARX 真实 SDK 方法／签名 ABI | 提供含真实 JVM `.class`、可加载的 SDK class JAR 后运行现有 ABI 测试，或另行核验真实 DEX 的类型／方法签名；只有 `classes.dex` 的候选 JAR 不能直接用于 URLClassLoader 合同 |
| 外部完整 PairVerify／PairSetup transcript、更多真实 CSM／HUD 固定输入输出 | 取得可公开登记来源的独立固定样本；现有数学参考／旧实现差分继续使用，不虚称外部 golden |

上述源码冻结／模拟器验证阶段未操作物理手机或车机；后续小米 8 真机运行另记如下，车机仍未验收。上述项目是硬件／独立样本验收条件，不是继续保留第二套 Kotlin 核心算法的理由。

### 2026-11-13：小米 8 v149 Release 真机日志审计

本次在小米 8 上运行同一 v149 正式 Release／R8 包，APK SHA-256 为 `45fec9ddd6e1b5896a5a0f9e4b563f5a2a423d781cef299daf36b0bc53fa01d2`；该包构建及本次运行时，对应源码尚未提交。本节是后续真机审计，保留前述源码冻结验证及历史数字；不将 Release 真机运行记作 Debug 测试包验收。

本地 ignored 证据位于 `build/task-validation/mi8-v149-cold-start/`：`logcat-pid15208-final.txt`（07:22:16—07:29:30）、`session-pid15208.log`、`audio-flinger.txt` 和 `audio-flinger-followup.txt`。公开文档只登记摘要及本地路径，不上传原始日志、设备标识或认证信息。

用户随后澄清：iPhone 已拿走，当前缺少测试对端，因此暂挂真实 CarPlay 复测。此前网络原因的猜测已澄清，不登记为网络软件故障，也不据此增加网络修复。以下成功事实仅对应已采集的 07:22 会话；修复后的真实场景重放须待 iPhone 可用后再验收。

已确认的运行事实：

- 无线 gen 1／attempt 1 认证及会话成功。从进程首条日志 07:22:16.188 到会话成功 07:22:18.459 约 2.27 秒；主屏 HEVC 1920×1080 首帧于 07:22:19.278 渲染，约 3.09 秒。这是单次日志区间测量，不是多次启动性能结论。
- 已结束的四段 RTP 音乐／通话／导航提示音流均为 `seqForwardGaps=0`、`lateOrDuplicate=0`；不能据此推定全部播放阶段均无欠载或无听感问题。
- 麦克风统计的 `readErrors=0`、`sendErrors=0`；07:23:09 音频 mode 回到 0，音乐恢复。
- 当前音乐轨 459 在两份 AudioFlinger 快照中 underrun 均为 0，server frame 增长 938,880 帧，在 48 kHz 下对应 19.56 秒音频。
- type 101／default 音频映射为 NAVIGATION：07:27:14.814 导航提示音 focus duck 请求成功（granted=1），07:27:21.752 unduck 释放成功（result=1），持有约 6.938 秒。
- 约五分钟后仍有双路视频持续收包统计：07:27:18.687 累计 3,423 packets，07:27:19.193 累计 12,895 packets，均无接收序列缺口。这只证明持续收流，不证明副屏已渲染，也不是长期输出帧率验收。

#### 问题登记与修复验收

先登记以下问题，再逐项定位、修复和重验；本节登记本身不代表问题已解决。

| 问题／观察 | 当前证据与状态 | 后续动作及通过条件 |
| --- | --- | --- |
| 真实 CarPlay 修后复测缺少对端 | 验收暂挂。iPhone 已拿走，不能进行实连复测；此前网络猜测已澄清，不是已发现的软件故障 | iPhone 可用后重放真实场景；不能用此前成功会话替代修后验收，无需增加无证据的网络修复 |
| AAudio 在 PAUSING 状态下 flush 失败 | 已修复并通过独立设备验收。07:22:38.359、07:22:53.074、07:27:22.316 共三次，发生在 TEARDOWN 关闭 media／导航提示音输出；第三次属于导航关闭。旧路径随后 close 返回 0 并恢复正常。修复只删除 `AudioRenderer.releaseTrack` native 销毁分支的一行 flush，保留 pause→release；小米 8／API 28 各 3 项最终测试通过，各 6 次 AAudio close 返回 0，AAudio errors 与 PAUSING flush 均为 0 | 已完成下述独立设备验收；iPhone 可用后仍需重放真实音乐／导航／通话切换与 TEARDOWN，并验收听感。原告警与用户可闻故障的关系未作推定 |
| 通话轨累计 underrun | 现有日志调查完成，保留观察与复测。轨 457 的 AudioFlinger 终态 `Underruns=312`；该列按 AOSP `Tracks.cpp` 的 `getUnderrunFrames()` 口径为采样帧，不能解读为 312 次。在 48 kHz 下约 6.5 ms，发生点仍不明确，不据此猜改源码 | iPhone 可用后以分阶段计数复测，定位有效播放阶段是否产生欠载；不预判初期、DTX 或尾部，也不标记所有音频问题已解决 |
| 高通 codec 输入 buffer 配置警告 | 观察项。请求 8 MiB，只获得 7,077,888 字节；后续主屏正常输出首帧，尚未证明解码故障 | 核查能力与实际输入需求；只有重现配置失败、输入受限或输出异常，才作为故障修复，不单凭警告认定播放失败 |

通话项的只读分析：AudioFlinger 轨 457 属于 direct／VOIP_RX thread，Normal frame count 为 960、Total writes 为 418、Frames written 为 400,968，`960×418−400,968=312`，与终态累计欠载帧数一致，但日志没有欠载发生时刻，不能据此判定为启动、DTX 或拆除尾部问题。历史 perf 的有效 A1／B1／B2／A2 窗口中，首个 Opus 解码 PCM 输出均为 1,296 **字节**，应用 `underrunCount` 均为 0；这不支持“首包 1,296 字节本身必然故障”的推断。应用计数与 AudioFlinger 的采样帧累计口径不同，不能混算。相关历史对照只用于排除无证据的根因推断，不代替 v149 修后复测。已核对本地 workspace 根目录证据：`artifacts/perf-v148-vs-rust-20261112/{A1-old148,B1-rust,B2-rust,A2-old148}/logcat.txt`，四个目录均存在，不包含 invalid／setup 窗口。

AudioFlinger 计数口径的 primary 来源为 [AOSP Tracks.cpp 的 Track::appendDump](https://android.googlesource.com/platform/frameworks/av/+/9a375d710b/services/audioflinger/Tracks.cpp)：`Underruns` 对应 `mAudioTrackServerProxy->getUnderrunFrames()`。现有固定 `OPUS_PRE_SKIP=312` 解释首包解码后保留的 1,296 字节；它与上述欠载帧数数值相同，不构成两者存在因果关系的证据。

AAudio 的 PAUSING flush 警告在本地历史日志也可复现：workspace 根目录 `artifacts/issue96-mi8-qiphone-20261112-1925/device-logcat.txt` 的 3489、3985、5470、15238、15991 行，以及 `artifacts/perf-v148-vs-rust-20261112/B2-rust/logcat.txt` 的 4401 行。跨进程／重复采集记录不累计为独立验收轮次，也不与本次明确记录的三处时间点混算。这些路径只登记定位信息，原始日志不公开。

#### AAudio 最小修复阶段验证

生产修改已冻结：`AudioRenderer.releaseTrack` 的 native 销毁分支由 pause→flush→release 改为 pause→release。显式 flush／rebuffer、AudioTrack 分支和校准行为保持原合同。只处理已复现的销毁时状态错误，不据此调整通话 pre-skip 或网络策略。

| 检查 | 阶段结果 |
| --- | --- |
| 完整 checks／四应用 R8／Android 测试 APK | `BUILD SUCCESSFUL`，4 分 42 秒、465 tasks；本地日志 `build/task-validation/mi8-v149-cold-start/fix-ci.log` |
| 三模块 JVM XML | shared 1958 项＝1956 passed／2 skipped，common 1573 passed，home 15 passed；合计 3544 passed、2 skipped、0 failures／errors；汇总为同目录 `fix-jvm-summary.json`。这些是本次修复重跑结果，未改写前述迁移批的历史结果 |
| 小米 8／API 28 首轮设备测试 | 两端原有各 2 项均通过；新增用例仅写 960 帧，未填足设备播放缓冲（open 请求 4800 帧），playback head 为 0，首轮未通过，不能记为修复验收通过 |
| 设备 fixture 校正 | fixture 改为按 `bufferSizeInFrames×4` 字节填充；playback head、资源与三次重开断言保持原要求。仅重建测试 APK，生产／单测／CI 源码未再改变 |
| 最终同一测试 APK／独立设备验收 | 小米 8 API 35 ARM64：3／3 通过，2.781 秒；API 28 ARM64：3／3 通过，13.817 秒。新增测试经真实 `AudioRenderer.releaseTrack` 连续三次播放／关闭／重开，playback head 与资源断言均通过 |
| 最终独立进程日志审计 | 两端各 6 次 AAudio close 均返回 0（原两项测试的三条流＋新增测试三条流）；两端 AAudio errors＝0、PAUSING flush＝0。仅计最终测试进程日志，未混入首轮 fixture 失败或旧进程日志 |
| 真实 CarPlay 修后重放 | iPhone 已拿走，缺少对端，继续暂挂；设备独立音频合同不替代此项 |

最终证据均在本地 ignored 目录 `build/task-validation/mi8-v149-cold-start/`：`aaudio-mi8-final.log`、`aaudio-api28-final.log`、两端的 `aaudio-*-final-own-pid.log` 和 `aaudio-final-log-audit.json`。Rust 源码及双 ABI 库均未因本次 AAudio 修复改变，复用前述已重建并审计的库。已确认的 AAudio 销毁时 flush 问题在本阶段完成修复及独立设备验收；通话欠载观察、codec 警告和真实 CarPlay 听感／组合重放仍保留各自边界。

#### 本次验收边界

本次已补充正式 Release／R8 包在小米 8 上的实际无线认证、会话、主屏首帧和音频切换运行证据，并完成 AAudio 最小修复后的独立设备验收。详细 FPS、长期掉帧、听感及长会话尚未验收；副屏只有流接收证据，没有渲染证据。实车、真实 ECARX SDK ABI、USB NCM、车机音路／AEC 和组合恢复仍按前表暂挂条件恢复验收。修复后的真实 CarPlay／Release 场景重放仍待 iPhone 可用后进行，不以独立设备音频测试替代。

### 2026-11-13：NCM 原生路径默认选择

有线 `CarPlayVpnService.attach` 与设置／诊断的 `AirPlayPersistence.loadNativeWiredDataPath` 对同一 `native_wired_data_path` 偏好均默认读取为开启；已有显式关闭和备份恢复后的关闭选择继续有效，不新增偏好或迁移用户选择。

默认尝试 `NcmUsbBridge.startNative` 的 USB／TUN FD 原生转发；已有 Java 读取、FD 能力或引擎启动条件不满足时仍使用 Kotlin USB／TUN I/O 适配。两条路径的 NCM framing／路由核心都在 Rust，能力回退不恢复旧协议算法。真实 USB 主机拓扑、车机驱动、拔插／重连和性能仍须目标硬件验收，不以默认开启代替验收。

新增实际 attach 事务合同覆盖未保存偏好、显式关闭、原生启动失败后沿用同一 TUN，以及交接后失败的资源关闭；设置合同覆盖默认开启及显式关闭的备份恢复。执行结果待本批源码冻结后的统一验证登记，历史默认关闭的台架记录保持原样。

### 2026-11-13：PASCD 真实车速装配点

此前 PASCD 只有 Rust 编码入口（`vehicle::vehicle_speed`）而没有生产车速来源。本轮接入并登记了真实来源，不再用 GPS 速度或模拟值代替：

- 车速：ECARX AdaptAPI `Car.create(context)` → `ICar.getSensorManager()` → `ISensor` 的 `SENSOR_TYPE_CAR_SPEED = 1048832`；`isSensorSupported` 为 `active` 后读取 `getSensorLatestValue`，单位 m/s（SDK `SensorPaUtil` 将 `PaNaviVehSpeed` 原始值除以 100，合法 0–125 m/s），并 `registerListener(..., RATE_NORMAL=3)` 订阅变化。
- 挡位：Android VHAL `GEAR_SELECTION = 289408000`（global area 0、Integer、on-change），只接受 `CarPropertyValue.getStatus() == 0`；`CarSensorEvent` 的 P=4／R=2／N=1／D=8。AdaptAPI 的 `SENSOR_TYPE_GEAR = 2097664` 在本固件 `SensorImpl`／`SensorListener` 未实现，因此不作备用来源。
- 权限：声明并检查 `android.car.permission.CAR_POWERTRAIN`（平台签名）；接口／权限缺失时保留 Android 位置，不向 iPhone 声明 wheel-speed。

`EcarxVehicleSpeedSource`（`shared/.../hud`）把上述只读信号按挡位归集为采样，交给既有 `VehicleSpeedLocationProvider` → Rust `NativeVehicleSpeed` 的 `speedGear／speedSample／speedSnapshot`，仍由 Rust 编码 `$PASCD`。`CarPlayHostActivity` 仅在“向 iPhone 报告位置”开启且 `EcarxVehicleSpeedSource.supported()` 为真时装配；iPhone 停止请求车速时停止车辆信号订阅。服务断开时清空未发送批次，重连后恢复采样（不把旧值当作当前车况）。

验证：Rust `vehicle::vehicle_speed` 单测 5 项（批量、挡位不混淆、非法值／时钟、上限 512 对）；JVM `VehicleSpeedCoreTest`／`NativeVehicleConversionDeviceTest.realWheelSpeedBatchContract` 走真实 host JNI 覆盖 `speedGear／speedSample／speedSnapshot`；`EcarxVehicleSpeedSourceTest`（Robolectric）覆盖订阅、P/R/N/D 映射、未知／错误挡位丢弃、断线恢复与停用回退；双 ABI 均导出 494 个 Java JNI 方法（原 491 加新增 `speedGear／speedSample／speedSnapshot`）。真实车机的单位、权限、断线与组合场景仍属本表“PASCD 真实车速装配”的实车验收，不用台架结果替代。
