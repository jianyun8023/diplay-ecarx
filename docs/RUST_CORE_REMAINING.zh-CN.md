# Rust 核心验收状态与历史审计

- **输入小批迁移（HID/Siri/事件分派）**：`vehicle::hid::index_press_release` 提供媒体/电话键"按下(index)/释放(0)"字节对；`protocols::airplay` 操作 18 返回 Siri 请求/释放动作序列 (2,3)；`session::airplay::event_command` 判断事件通道请求是否为 `/command`。Kotlin 仅执行发送。Rust 单测 + Kotlin native 契约测试；cargo 323、shared 2117、三 ABI 原生 631、设备 androidTest 190/190。
- **PairVerify/PairSetup 完整 transcript（2026-11-17）**：为关闭「外部认证向量缺完整 transcript」缺口，用独立参考实现（[`scripts/gen_pair_transcripts.py`](../scripts/gen_pair_transcripts.py)，Python `cryptography` + 已独立复算的 SRP 数学）生成固定 M1–M4／M1–M6 原始字节，固化为 `pair-verify-transcript.properties`／`pair-setup-transcript.properties`。Rust 只增加 `#[cfg(test)]` 的确定性 seam（`PairVerify::use_ephemeral_seed`、`PairSetup::use_srp_secret`，生产仍 `SystemRandom`），对 M2／M4／M6 与派生 control key 逐字节断言。cargo 325 通过；三 ABI 原生 631 JNI／来源校验通过。**来源为独立参考实现，非第三方抓包**（见[向量清单](PROTOCOL_VECTORS.zh-CN.md)）。
- **蓝牙守卫决策下沉（2026-11-17）**：新增 Rust `session::bt_profile_guard`，承接 yield 模式/延迟/skip、pausedByUs、restore 阶段、启动恢复门槛、boot-radio 观察与 adapter-ready 拒绝等纯策略；`CarBluetoothProfileGuard` 保留 `BluetoothAdapter`/profile-proxy 执行、广播、重试线程与持久化 journal，`testCoreShadows` 提供独立 Kotlin 参考供 Robolectric。Rust 7 项单测 + Kotlin 独立 JVM 决策测试；cargo 332、shared JVM 2122 全通过，三 ABI 638 JNI 校验，小米 8 真机无线会话复验（`BT guard armed mode=media delayMs=0`，连接/首帧/音频正常）。**审计结论**：更新管理属应用层（`protocols/session/media/vehicle` 之外，且不影响 CarPlay 正确性）、热点决策已在 `session::hotspot_recovery`／`p2p_startup`／`network_address`——二者不再下沉。

## 当前状态与验收矩阵（2026-11-15）

**源码层面的完整 Rust 核心迁移已由 [PR #97](https://github.com/jianyun8023/diplay-ecarx/pull/97) 合入 `dev`。** 当前工作是具体缺陷修复与按版本补验，不再沿用旧迁移分支。本文是当前剩余项的仓库入口；已关闭的 [#96](https://github.com/jianyun8023/diplay-ecarx/issues/96)、[#98](https://github.com/jianyun8023/diplay-ecarx/issues/98)、[#99](https://github.com/jianyun8023/diplay-ecarx/issues/99) 保留历史证据，其正文中的旧清单和关闭状态都不能单独作为当前验收结论。

**2026-11-14 用户确认收尾**：真实 SDK 接口、189.6 秒启动记录、导航音量键、日夜初始化、蓝牙媒体让位、HUD／车道、双屏生命周期、#111 echo 引用切换及 v148／v150 性能记录均已确认 OK，不再安排重复核验、追加回放或故障注入。此处记录用户的最新验收结论；历史日志范围和条件跳过结果保持原样，不改写成新执行的测试。此前单列的 PASCD 动态数据／真实服务恢复及配置失败交错 ARM64 green 不在这次收尾列表内。

**本次媒体状态归属补齐**：在 #114 的 `f689c722` 上继续将视频配置／关键帧、封面恢复／队列、MediaCenter 发布判定、录音恢复与导航提示音状态交给 Rust；平台资源及动作顺序保留。边界见[架构说明](ARCHITECTURE.zh-CN.md#当前-rust-核心与平台边界)。本次 JVM 3600 通过／2 环境跳过、Rust 235 通过，三 ABI 的 510 JNI／来源校验、四应用 lint／debug／R8 和设备测试 APK 构建均通过，详见[本批验证记录](benchmarks/media-state-ownership-2026-11-14.md)。不改写下表历史测试数字，也不重开用户已确认收尾的旧批次。小米 8／API 35／ARM64 设备自动化共 39 项通过、0 跳过；测试包、源码与范围见上述记录。同批 Release 的 iPhone 起播、切歌、暂停恢复、Siri 与导航已有日志核验及用户正常确认；ECARX 专有 Binder／目标车机音路仍待验证。

| 范围 | 已有证据 | 当前边界与下一步 |
| --- | --- | --- |
| AAudio 关闭 | v149 含 `d16ed0a1` 已在 ECARX／API 28 实车通过：音乐和导航使用 AAudio，正常拆除，未复现 PAUSING flush 错误。见 [#96 实车审计](https://github.com/jianyun8023/diplay-ecarx/issues/96#issuecomment-6841704520)。 | 已验证，不因版本变动笼统重开已通过项目。#111 亦按本页用户确认收尾。 |
| Siri、通话、echo 恢复 | 小米 8＋真实 iPhone 已通过 Siri 语音／播放恢复和约 16 秒运营商通话；见 [#96 勘误](https://github.com/jianyun8023/diplay-ecarx/issues/96#issuecomment-6842219271)。#111 同格式 renderer 失败后的 echo reference 切换有 60 项定向 JVM 与完整 Checks／CI。 | #111 echo 引用切换已由用户确认 OK，本轮收尾，不再安排输出失败注入。历史通话轨 312 **采样帧**（48 kHz 约 6.5 ms）保留为观察记录，不是 312 次错误；方控 Siri 属原先单列场景。 |
| 原生 USB NCM／重连 | 小米 8 原工具日志确认 4 次主动拔插通过；#104 同逻辑包 3 轮重启、最终包起播及有线／无线切换通过；CSV 有 156 秒有线采样，Harness 补齐该窗错误查询结果。20:57 断开经用户确认为主动操作，21:17 切无线也有明确指令，不计自然掉线。见[USB 验收记录（已收尾）](benchmarks/native-usb-2026-11-13.md)，按不同 APK、计时起点和采集范围登记。原生路径默认开启，保留显式关闭和启动失败回退。 | **本轮已收尾（2026-11-14）**：用户确认当前状态 OK，停止 USB 分析与补测，移出当前待办。小米 8 的指定场景通过；长时运行、故障注入和目标固件的新组合仍是证据边界，不追认为全面通过，也不作为本轮阻塞项。 |
| PASCD 车速源／真实 SDK ABI | #101 已接生产；Ultra 有同一车速源的实车显示，旧系统日志另有非零回调；v150 有 8 次注册／即时回调路径。HY DCYTools 使用同一 VHAL 挡位属性，真实固件枚举与当前 P/R/N/D 映射一致。车速相关 17 项＋共享替身关联 7 项定向测试通过；完整 Checks 通过（3595 通过／2 条件跳过），见[代码与日志复核](benchmarks/vehicle-speed-2026-11-14.md)。 | 单位转换、数据源、映射与默认订阅行为已有依据。缺当前包非零速度／时间戳／挡位到 PASCD 的端到端记录，以及 AdaptAPI、VHAL 真实服务中断恢复。未 drain 批次清空和 VHAL 重新取初值／订阅的本地回归已补齐通过；SDK 接口已由用户确认 OK，不再追加核验。应用重启不等于服务断线验收。 |
| 视频首包／配置顺序（#113） | [#113](https://github.com/jianyun8023/diplay-ecarx/pull/113) 已合并；小米 8 的 16 项设备回归、同 APK 原生库交替对照和 300.334 秒会话观察已完成。详见[验证摘要](benchmarks/native-video-startup-2026-11-14.md)。 | 该批通过数、APK 身份和设备恢复记录固定属于 #113；未测物理显示延迟、目标 ECARX／API 28 硬解和全部 ABI 运行时，不替代后续批次验收。 |
| 配置失败交错（本批） | 以 `eeca03ae` 为基线，小米 8／API 35 两项新增回归均有效 red（10.815 秒）。生产修复已实现：4 文件、17 增／11 删，以锁内 0／1 hold 判断保留后续配置的帧。本地完整 Checks／四应用 R8 通过；JVM 3593 通过＋2 环境跳过，Rust 226 通过，三 ABI 的 494 JNI＋JNI_OnLoad／来源校验通过。详见[本批记录](benchmarks/native-video-startup-2026-11-14.md)。 | 小米 8／API 35／ARM64 两项修复后回归已通过，属于首轮 33 项设备自动化；不替代真实 iPhone 会话或目标车机验收。 |
| 导航音量键 | 11 月 14 日 17:25 历史诊断报告记录服务未启用；相关本地测试已有覆盖。用户随后明确确认此项 OK。 | 已收尾，不再把历史未启用快照或此前复测计划列为当前待办。 |
| CarPlay 导航音量键（车机握手） | 独立开关默认关闭。通过 alternate 探测后启用 route 0 与导航焦点；任一可写组为 0 或不可读时跳过厂商标记。alternate 随单次播报释放，探测失败沿用 DiPlay 路由、焦点与压低。 | [实车清单](NAVIGATION_WHEEL_VOLUME.md#real-car-acceptance)包括冷启动、按键选组、0 级与用户调音、停车警报、断连恢复及固件退场。 |
| 双屏／HUD／生命周期与长测 | 历史 v149 实车有 cluster 首帧，#98 小米 8 台架有 overlay 地图实际渲染；#113 新会话记录了两路接收。 | HUD／车道、双屏生命周期及已有性能记录已由用户确认 OK，不再追加回放、实体显示核验或长测。原记录覆盖范围保持不变。 |
| v149 观察项与 QTI low-latency | [#99 v150 实车评论](https://github.com/jianyun8023/diplay-ecarx/issues/99#issuecomment-6872722131)已验证低延迟开关下发／撤销、正常起播；延迟对照无可测收益，驱动未回报实际生效，保持默认关闭。 | #99 的 08:24:18–08:24:38 Host 暂停，用户已确认记不清操作背景，**仅保留观察，不认定故障或当前回归**。3.2 秒音频到达间隙／stale backlog、熄火后状态也先核操作背景和当前包复现，再决定是否处理。等待数据的 `readsOver250Ms` 不等于处理耗时。 |
| 独立协议样本 | 保留数学参考、旧实现差分及已登记的外部向量。 | PairVerify／PairSetup 已补**完整 transcript**（独立参考实现固定字节 + Rust 逐字节断言，2026-11-17）；HUD／车道已确认 OK，不再要求补固定输入输出回放。来源为独立参考实现而非第三方抓包；如需第三方互通证据仍应补外部发布向量或已知密钥真实抓包。 |

当前平台状态另见 [ECARX 验收表](ECARX_753_PLATFORM_NOTES.zh-CN.md#7-验证矩阵)。新增结果须注明源码／包、环境、场景和未覆盖范围；历史实车通过、最新变更未补验与确实未覆盖的目标硬件分别记录。原始日志、认证资产和设备标识留在本地，不提交仓库。

## 宿主重试预算归属（2026-11-16）

基于 `89ef31ab`，先在旧生产实现补齐宿主合同，再将启动五次预算、整栈退避计数及其重置／延迟规则接入
Rust `session::connection_recovery`。Kotlin 保留 session 对象身份、首帧时钟采样、Handler／Runnable 和
生命周期门禁；独立旧 Kotlin 算法仅留在 `testCore`。两套预算与 run 内恢复仍独立，调度即消耗且取消不退还，
各重置入口和混合失败序列见[架构说明](ARCHITECTURE.zh-CN.md#8-新代码放在哪)。

| 验证 | 结果 |
| --- | --- |
| 迁移前合同 | common 重试相关 40 项通过；补强稳定 60 秒不清整栈计数的断言后，宿主合同 32 项再次通过 |
| 迁移后 JVM | common 71 项、shared 4 项通过，包含 API 28／33 的 transport 清理回归和独立旧算法对照；最终窗口常量归属及旧 JNI 清理后，common 重试相关 41 项、shared 13 项通过 |
| Rust | 固定 Rust 1.97.1 的 session 测试 42 项通过；最终非法 selector 合同加入后，connection recovery 专项 4 项通过 |
| R8 | mobile／maphost 的 `minifyReleaseWithR8` 通过，新增入口沿用 `NativeConnectionRecovery` 的保留规则 |
| Android ARM64 | 最终源码以 Rust 1.97.1／NDK 28.2.13676358／API 28 编译通过，577 个 Java JNI 导出及 `JNI_OnLoad`、ELF 和来源记录校验通过 |

本批未构建 ARMv7／x86_64、完整 APK，也未运行设备或实车验证；ARM64 编译及主机测试不代表设备端回调时序验收。
本地原始日志为 `/tmp/host-retry-{old,old-frame,final,cargo-197,window-final-cargo,window-final-gradle,arm64-final}.log`。
不改写以下历史测试与设备验收数字。

## 连接恢复后的重建取消判断（2026-11-16）

基于 `99b67ef0`，将宿主的 control-running 计数、后续 loss 标志及排队快照比较迁入
`session::connection_recovery`。Kotlin 只保存 opaque 状态和快照，保留原事件位置、Handler 清理顺序、
owner／session 身份及代际门禁。菜单暂缓与后台接管仅补合同，不迁移策略；已有重试预算与退避不变。

迁移前新增合同 42 项通过；随后补强旧代际 reporter 不能污染当前判断的合同，旧宿主 39 项通过。
菜单打开期间收到 Running 的合同也在旧宿主单项及迁移后 39 项宿主测试中通过。迁移后定向检查：

| 检查 | 结果 |
| --- | --- |
| common／shared JVM | 82／5 项通过，覆盖恢复后跳过、再次丢失、session active 区别、当前／非当前 session、旧代际、菜单定时器及后台握手失败停止；独立旧 Kotlin 算法只在 testCore |
| Rust 1.97.1 recovery | 5 项通过，包含 Int wrapping、snapshot 只比较计数和未知事件安全返回 |
| R8 | mobile 检查通过，maphost 任务为 UP-TO-DATE；新增 JNI 沿用现有整类保留规则 |
| Android ARM64 | Rust 1.97.1／NDK 28.2.13676358／API 28 编译及来源校验通过；579 个 Java JNI 导出及 `JNI_OnLoad`、ELF 验证通过 |

本地日志：`/tmp/host-running-old.log`、`/tmp/host-control-{old-generation,old-menu,final-menu,final-gradle,final-cargo,arm64}.log`。
本批未运行 ARMv7／x86_64 构建、完整 APK 或设备／实车验证；不改写上一批及历史验收范围。

## 菜单暂缓与退出恢复决策（2026-11-16）

基于 `b23584ae`，先补菜单行为合同，再将失败保留／停止动作及退出恢复选择交给
`session::connection_recovery` 的纯函数。完整失败对象、待办标记、设置校验／保存／回滚、菜单与 Handler
仍由 Kotlin 持有；没有增加 native 菜单状态或句柄。首个 manual 失败锁定、STARTUP_STOP 不锁定、
有效传输方式、重报去重后不回退重建及原预算行为均保持。

| 检查 | 结果 |
| --- | --- |
| 迁移前合同 | 新增 12 项菜单合同在旧宿主通过；同批既有 startup／bootstrap 39／6 项通过。复核补强了 USB 接管后的有效有线、Wi-Fi 重报优先拆除及有 controller 无待办不重建 |
| 迁移后 common／shared | 94／6 项通过，包含 4 组保留条件和 512 组退出事实经真实 JNI 与独立旧 Kotlin if 链对照 |
| Rust 1.97.1 recovery | 6 项通过；菜单入口无堆状态、无 panic 分支 |
| R8 | mobile 检查通过，maphost 任务为 UP-TO-DATE；沿用 NativeConnectionRecovery 整类 native 保留规则 |
| Android ARM64 | Rust 1.97.1／NDK 28.2.13676358／API 28 编译及来源校验通过；581 个 Java JNI 导出及 JNI_OnLoad、ELF 验证通过 |

本地日志：`/tmp/host-menu-{old,old-review,final-gradle,final-cargo,arm64}.log`。最终调用改为具名参数后
仅执行 `git diff --check`，不改变已验证逻辑。本批未运行其他 ABI 构建、完整 APK 或设备／实车验证。

## 后台握手保留状态（2026-11-16）

基于 `cdc54e3d`，先在旧宿主补齐 14 项后台握手合同，再将保留标记、当前 Failed 的一次性消费以及
返回前台／当前 session-active 的清除转移交给 `session::connection_recovery`。
Kotlin 不保存镜像标记，继续保留导航与能力查询短路、owner／shutdown／代际门禁、资源及监听器引用、
Handler 和实际停止／接管。取消或无操作导航不额外清标记，onStop 不设置标记，新 Host 不继承旧标记。

| 检查 | 结果 |
| --- | --- |
| 旧实现合同 | 14 项通过，含真实 onStart／onStop、旧代与当前 session-active、owner／shutdown 非对称、Failed 消费不 fallthrough、真实 shutdown 使重复失败失效、新 Host 接管和能力查询短路 |
| common／shared JVM | 104／7 项通过，含预算、菜单、控制恢复、transport 清理回归及独立旧 Kotlin 状态参考经真实 JNI 对照 |
| Rust 1.97.1 recovery | 7 项通过；opaque value 不分配堆句柄，合法重复失败不重复消费 |
| R8 | mobile 检查通过，maphost 为 UP-TO-DATE；新增 JNI 由现有整类 native 规则保留 |
| Android ARM64 | Rust 1.97.1／NDK 28.2.13676358／API 28 编译和来源校验通过；583 个 Java JNI 导出及 JNI_OnLoad、ELF 校验通过 |

本地日志：`/tmp/host-background-{old,final-gradle,final-cargo,arm64}.log`。本批未运行其他 ABI 构建、
完整 APK 或设备／实车验证，不改写前面各批次的验收范围。

## 宿主停止重试与失败去重状态（2026-11-16）

基于 `a2eb630e`，先在旧宿主补齐行为合同，再将全局停止重试、启动失败同代去重、USB 配对停止代际
迁入 `session::connection_recovery`。`HostFailureState` 只携带两个 opaque 值，不分配原生堆句柄，
也不在 Kotlin 镜像保存三种状态。三者的独立含义及重置规则见[架构说明](ARCHITECTURE.zh-CN.md#8-新代码放在哪)。

重试按钮／设置重连仅解除停止，USB 回退额外清除 USB 代际，均保留 startup 去重；session-active
不解除停止。USB 屏蔽仍在 reporter 首个门禁、后台标记消费之前；startup 去重仍在原分支、实际重试
门禁之前记录。预算、退避、UI、Handler、当前代际来源及事件处理顺序保持不变。

| 检查 | 结果 |
| --- | --- |
| 旧实现合同 | 81 项通过：新增三状态合同 9 项、后台 15 项、菜单 12 项、startup 39 项、bootstrap 6 项；固定重试／保存后的代际断言，避免旧代被忽略造成假阳性 |
| common／shared JVM | 124／8 项通过，含 API 28／33 transport 回归、LastConnectionProblem，以及独立旧 Kotlin 状态经真实 JNI 对照；覆盖初始 startup=-1、USB 空值与完整 Int 边界 |
| Rust 1.97.1 recovery | 8 项通过，覆盖独立停止／恢复、同代重复、USB presence 和代际边界 |
| R8 | mobile 检查通过，maphost 为 UP-TO-DATE；4 个新增 JNI 沿用已有整类 native 保留规则 |
| Android ARM64 | Rust 1.97.1／NDK 28.2.13676358／API 28 编译和来源校验通过；587 个 Java JNI 导出及 JNI_OnLoad、ELF 校验通过 |

本地日志：`/tmp/host-latch-{old,final-gradle,final-cargo,arm64}.log`。本批未运行其他 ABI 构建、完整 APK
或设备／实车验证，不改写既有批次的验收范围。

## 宿主自动播放决策与等待预算（2026-11-16）

基于 `ed01bc2f`，先补齐旧宿主行为合同，再将重复激活决策、蓝牙等待计数及等待／发送／放弃规则
交给 `session::playback`。`AutoPlayRecovery` 携带 opaque Long，不创建原生堆句柄或 session 映射。
Kotlin 保留最近 session 引用与身份比较、模式和蓝牙 SDK 查询、Handler、取消入口、诊断序号及实际发送。

新 session 先占用再读设置；重复激活只抑制 Play，不阻止前面的蓝牙让位。模式关闭、发送被拒、等待耗尽
和取消均不为同一最近 session 重新排队，A→B→A 仍可重新占用。首次 3 秒、五次相对 2 秒延期不变，
第六次检查蓝牙关闭仍可发送，开启则放弃；没有引入 13 秒硬截止。后台接管仅占用引用，不额外播放。

| 检查 | 结果 |
| --- | --- |
| 旧实现合同 | 14 项通过；补强耗尽后先关闭蓝牙再验证不重排、设置读取时点、真实 session-ended 取消、A→B→A、onStop 与 shutdown、重复 session 仍执行蓝牙让位；第 13 秒边界以 999 ms 未发送／再 1 ms 发送复核 |
| common／shared JVM | 38／6 项通过：common 自动播放 14、Smooth lifecycle 9、后台握手 15；shared 独立旧算法经真实 JNI 对照 1、既有 PlaybackStatus 5 |
| Rust 1.97.1 playback | 7 项通过，包含等待计数、相对延期校准和 Int wrapping |
| R8 | mobile 实际执行通过，maphost 为 UP-TO-DATE；3 个新增 JNI 沿用 NativePlaybackCore 整类保留规则 |
| Android ARM64 | Rust 1.97.1／NDK 28.2.13676358／API 28 编译和来源校验通过；590 个 Java JNI 导出及 JNI_OnLoad、ELF 校验通过 |

shutdown 合同在真实 shutdown 后恢复 fixture controller 引用，验证回调不读取播放快照、不发送且记录取消，
避免 controller 已被清空掩盖门禁缺失。本地日志：`/tmp/autoplay-{old,old-boundary,final-gradle,final-cargo,arm64}.log`。
验证后仅移除一条随常量迁移而悬空的宿主注释，并通过 diff-check；本批未运行其他 ABI 构建、完整 APK
或设备／实车验证，不改写历史验收。

## USB 接管与拔线回退决策（2026-11-16）

基于 `5cbbe6fa`，先补旧宿主 USB 行为合同，再将接管选择、Host 重枚举状态、相对等待与回退动作
交给 `session::connection_recovery`。`HostUsbRecovery` 仅携带 opaque 值，不保存共享 `usbTakeover`
或创建原生堆句柄。Kotlin 继续负责共享接管事实、设置读取、设备枚举、Handler、菜单事务及实际重建。

等待仍为普通 2 秒／重枚举 10 秒，每次重排重新起算；任意 Apple USB 设备在场即保留接管，不自动重排。
接管拒绝的热入口也先取消回调，attach 不清重枚举；RunningControl 清状态并取消，Failed 清状态并改排
普通等待，RunningWireless 不执行这些动作。回退保留菜单草稿，采用到期时读取的保存偏好，清停止／USB
代际及首帧观察而不重置计数或 startup 去重。未增加 owner／代际门禁，菜单取消后的 controller 仍实时读取。

| 检查 | 结果 |
| --- | --- |
| 旧实现合同 | 首轮 69 项通过：transport API 28／33 共 42、policy 6、failure latch 9、menu 12；补强 RunningControl 清状态、暂存失败和首帧观察清理后，旧宿主 transport 42 项再次通过 |
| common／shared JVM | 126／9 项通过：common transport 42、policy 6、failure latch 9、menu 12、startup 39、budget 3、后台握手 15；shared 包含 16 组选择事实及 8 组回退事实经真实 JNI 与独立旧 Kotlin 算法对照 |
| Rust 1.97.1 recovery | 9 项通过，包含重枚举转移、等待校准、接管与回退动作 |
| R8 | mobile 实际执行通过，maphost 为 UP-TO-DATE；5 个新增 JNI 沿用 NativeConnectionRecovery 整类 native 保留规则 |
| Android ARM64 | Rust 1.97.1／NDK 28.2.13676358／API 28 编译与来源校验通过；595 个 Java JNI 导出及 JNI_OnLoad、ELF 校验通过 |

合同通过空枚举隔离非 Apple 过滤，使用真实 reporter 驱动 9999 ms／1 ms 重枚举边界，并以 generation
推进及 teardown 提交见证实际重建入口；等待中关闭无线偏好仍须完成状态清理、保留 controller 和预算。
本地日志：`/tmp/usb-recovery-{old,old-transport,final-gradle,final-cargo,arm64}.log`。验证后仅修正原策略注释
中无线配置事实的说明，并通过 diff-check。本批未运行其他 ABI 构建、完整 APK 或设备／实车验证。

## 配对消费与 Siri 纯时间规则（2026-11-16）

基于 `d05467de`，先在旧实现补齐合同，再将 `WheelKeyPresses` 的消费／提交规则与 `WheelSiriKey`
单击／双击时间状态交给 `vehicle::wheel_keys`。每个消费者继续独立持有 opaque 值，Kotlin 保留 Any 键
映射、原 equals/hashCode、`decide()` 调用与时间采样。正常返回后才提交，保留原 Action、异常不提交、
同键重入后外层最终写入；已放行记录不等于不存在。单／双击共享同实例可空时间戳，400 ms、180 ms
及配置上限含等号、拒绝后更新起点、确认后清空、Long wrapping 与非法上限不提交均保持。

本批不改变 Host／服务的 pending、事件指纹、Handler、取消入口、1500 ms 第二次释放窗口或 VoiceKeyPair。
Kotlin 不再保留纯时间算法；独立旧算法仅在 testCore。Siri token 使用 GC 数组保留完整 Long 与空值，
无原生堆句柄、全局键注册表、隐式 session 重置或新锁。

| 检查 | 结果 |
| --- | --- |
| 旧实现合同 | 25 项通过：配对 2、Siri 6、WheelZoom 14、键码 3；覆盖交错键／equals、记录 PASS、孤立 repeat／UP、callback 异常和重入，以及窗口边界、回拨、共享起点和 wrapping |
| common／shared JVM | 89／2 项通过：common 为配对 2、Siri 6、WheelZoom 14、键码 3、service 24、joystick 7、HY 5、ECARX 28；shared 经真实 JNI 对照独立旧实现 |
| Rust 1.97.1 wheel_keys | 2 项通过；JNI 位于 owner 私有模块，数组／参数失败抛异常而不提交新值 |
| R8 | mobile 实际执行通过，maphost 为 UP-TO-DATE；新增 NativeWheelInput native 方法保留规则及 host shadow 注册 |
| Android ARM64 | Rust 1.97.1／NDK 28.2.13676358／API 28 编译与来源校验通过；602 个 Java JNI 导出及 JNI_OnLoad、ELF 校验通过 |

本地日志：`/tmp/wheel-core-{old,final-gradle,final-cargo,arm64}.log`。本批未运行其他 ABI 构建、完整 APK
或设备／实车验证；既有 ECARX 广播合同不替代尚未迁移的 Host／服务 pending 路径验收。

## 媒体会话隔离与后续状态收敛（2026-11-16）

以 `5cbbe6fa` 作历史对照，相关 AirPlay 媒体文件直到按键批 `f1956345` 均无变化。本轮先保持结构修复
session 隔离：普通音频 feedback 只选请求 session 的 metadata，关闭只清该 session 的 metadata、
pending microphone 和 capture。旧 session 关闭不停止仍被另一 session 注册的同 ID 麦克风；capture
从表中移除后再关闭。残留表项也按各自所属 session 清理，不依赖 streams 表仍有对应条目。
未改变原生媒体直连路径、buffered 退休屏障、资源锁或 onTeardown 的编排，未迁移 owner。

| 检查 | 结果 |
| --- | --- |
| 修复前复现 | API 28 Robolectric 的 4 项新增合同均失败：跨 session feedback 混入、新 silent microphone 被取消、同 ID 麦克风错误 stop、新 capture 不可写；原有 5 项通过 |
| 修复后 shared JVM | 49 项通过：隔离 10（含新增 API 28 合同 5 项）、engine 7、route feedback 3、microphone start 4、app microphone 7、buffered reply 2、独立 buffered reference 9、screen listener 7 |
| Android 测试源码 | `:shared:compileDebugAndroidTestKotlin` 通过；只编译，未在设备执行 |
| R8 | mobile 实际执行通过，maphost 为 UP-TO-DATE |
| Rust／JNI／ABI | 本批未修改 Rust、JNI 声明或原生构建输入，未重建三 ABI；不将按键批的 602 JNI 或历史设备结果记作本批新验收 |

本地日志：`/tmp/media-isolation-{red,red-api28,mic-red,green,final}.log`。四个失败均有反馈内容、
真实 scheduler／sink 回调或采集文件写入见证，额外合同验证缺少 stream 条目时仍清理旧 metadata／capture。
未运行完整 APK 或设备／实车验证，主机反馈与资源合同不等同于实际共享音路交接验收。

重评后的后续三批保持独立：

1. 缓冲音频 SETUP 的 shk／AAC／ct／spf 和控制请求 rate／rtpTime／flushUntilTS 仍在 Kotlin 解释；
   扩展现有 `protocols::airplay::setup` 和 buffered owner，保留 Java Number 转换、缺省、拒绝顺序及回复布局。
   现有 Rust 队列、锚点、控制状态与回复编码继续复用，不重新建立协议实现。
2. 仪表内容分为 Controller 接受的跨 session 选择与 session 已交付内容。命令编码和 stream 代际已在
   Rust；剩余是 requested／displayed／shown 及发送结果提交。迁前补 live session 的部分发送失败与排队
   zoom 失效合同，showUI、关键帧各自结果及 stream 代际必须参与提交。
3. 共享输出归属需要引擎寿命状态；bufferedOwner、退休集合和候选安装不能放进单 session owner。
   在现有媒体 namespace／owner 内收敛，Kotlin 保留实际关闭、清理完成事实、资源锁与释放屏障。

四种寿命及平台职责见[架构边界](ARCHITECTURE.zh-CN.md#8-新代码放在哪)；本轮仅实施第一批隔离，
其余三批是后续方案，不表示已完成迁移。

## 缓冲音频 SETUP 与控制请求规则（2026-11-16）

基于已提交的隔离修复 `04887377`，先在旧宿主补合同，再扩展现有 Rust owner：
`protocols::airplay::setup` 解释 shk／audioFormat／ct／spf 并生成 type 103 回复；现有
`media::audio::buffered` 解释控制 method、rate、rtpTime、flushUntilTS，复用原锚点、队列与回复状态。
NativeAirPlayProtocol 和 buffered facade 各一次转为具名计划，Kotlin 保留流查找、peer、创建／绑定端口、
时钟采样及平台执行。共享输出 owner、资源锁、退休屏障和原生数据直连不在本批迁移范围内。

Java Number 的 longValue／intValue／doubleValue、缺省、拒绝顺序和异常身份保持；ct／spf 缺失或 null
仍接受，截断数值按旧行为判断，rate 的 -0.0 仍等于 0，NaN／非 0 或 1 被拒。拒绝 SETUP 不退休健康流。
控制先找到所属 session 流，再读 rtpTime、rate；仅 FLUSH 继续读 flushUntilTS，只有合法 SETRATE 才读取
NTP。RTP 以 presence 加完整 Long 跨 JNI，由 Rust 归一到 U32，缺失与 -1／MIN／MAX 分别处理。
SETUP 回复保持 type／dataPort／audioBufferSize 的 Int 字段顺序，不新增 streamConnectionID；8 MiB
取现有 buffered 容量常量，原 Kotlin 重复常量已删除。anchor／feedback 的类型和线格式继续沿用原 owner。

| 检查 | 结果 |
| --- | --- |
| 旧实现合同 | 首轮 Host 5、reply 2、独立 buffered reference 9 共 16 项通过；补强缺省、锚点不变、FLUSH 实际调用与异常短路后，旧 Host API 28 的 6 项通过 |
| 迁移后 shared JVM | 44 项通过：Host 6、独立字段参考 2、reply 2、旧 buffered reference 9、engine 7、session isolation 10、route feedback 3、NativeAirPlayProtocol 5；字段参考含 10 组 SETUP 与 240 组控制组合，经真实 host JNI 对照 |
| Rust 1.97.1 | buffered 专项 6 项、setup 专项 8 项通过，其中 1 项重合，共 13 个不同测试 |
| Android 测试源码 | `:shared:compileDebugAndroidTestKotlin` 通过，仪器测试未在设备执行 |
| R8 | mobile 实际执行通过，maphost 为 UP-TO-DATE；两处新增 JNI 沿用已有 native 整类保留规则 |
| 三 ABI | Rust 1.97.1／NDK 28.2.13676358／API 28 的 ARM64、ARMv7、x86_64 均编译通过；各 604 个 Java JNI 导出及 JNI_OnLoad 一致，ELF／来源校验通过；原 bufferedControl 签名含 presence，声明、调用和 host shadow 同步 |

另修复 AudioRouteFeedbackEntryTest 的独立运行前置条件：显式加载 mandatory native，避免依赖其他测试
先触发 lazy load；不是本批媒体策略回归。本地日志：`/tmp/buffered-contract-{old,old-api28}.log`、
`/tmp/buffered-final-gradle.log`、`/tmp/buffered-u32-cargo.log`、`/tmp/buffered-final-setup-cargo.log`、
`/tmp/buffered-protocol-abi.log`。当前未发现可执行 Android 测试的在线设备；本批未构建完整 APK，
未完成设备／实车验收，不将 API 28 Robolectric 与主机 JNI 结果记作设备端 native TCP／共享音路验收。

## 仪表内容选择、发送计划与提交（2026-11-16）

基于缓冲音频批 `97e6b7ed`，先补旧行为合同，再按两种寿命迁移：
`session::controller::dashboard` 保存跨 session 已接受选择、暂停／物理可见性及 wrapping 路由计数；
`session::airplay::cluster_content` 保存单 session 的 retained／requested／displayed／shown 与发送、
提交规则。命令线格式和协商 stream 代际复用原 owner，不合并成单一 AirPlayCore。

Kotlin 保留锁、调度与实际发送，将 opaque 内容快照和平台身份原子发布；路由读取不等待网络写锁。
URL 以 UTF-16 保真。showUI 与关键帧各自结果参与提交，live 部分失败和迟到成功可使显示未知；
失败的新选择不覆盖 Controller 已接受选择，暂停选择仍可跨 session 恢复。各 teardown 清除入口
均调用内容 clear，不额外推进 negotiation 代际。Controller close 只清物理可见性，不额外推进 epoch。

| 检查 | 结果 |
| --- | --- |
| 旧实现合同 | ClusterContent 17、ControllerFirstFrame 6 共 23 项通过；包括 live 部分失败、paused accepted 选择在失败后跨 session 恢复、排队 zoom 的路线不变正例及内容／可见性／stream 改变负例 |
| 迁移后 shared／common JVM | 30／47 项通过；shared 为原 23、Session 独立对照 2、Controller 独立对照 3、地图资格 2；common 为 DashboardContentDelivery 18、ECARX first frame 15、WheelZoom 14 |
| 独立参考 | Session 2250 步旧 Kotlin 对照包含 null／空串／NUL／孤立代理项／负 stream；Controller 参考独立于 native token，覆盖接受／失败、跨 session、可见性及 epoch |
| Rust 1.97.1 | Session cluster_content 3 项、Controller dashboard 2 项通过 |
| Android 测试源码／R8 | Android 测试源码编译通过；mobile R8 实际执行通过，maphost 为 UP-TO-DATE；新 native 类保留与真实 host shadow 已接入 |
| 三 ABI | ARM64／ARMv7／x86_64 均以 Rust 1.97.1、NDK 28.2.13676358、API 28 编译；各 613 个 Java JNI 导出及 JNI_OnLoad、ELF／来源校验通过 |

本地日志：`/tmp/cluster-old-contract{,-positive}.log`、`/tmp/cluster-final-gradle.log`、`/tmp/cluster-abi.log`。
首轮组合编译发现 teardown 后部旧 null 赋值遗漏，已补成 opaque clear 并重跑通过；Rust 来源未因此改变。
本批未运行设备／实车或完整 APK 验收，主机对照不替代真实仪表发送与共享音路交接。

## buffered 共享输出归属与替换屏障（2026-11-16）

在第三批验收通过后继续第四批，先验证现有 Kotlin engine 合同，再将引擎寿命的归属规则收进
`media::audio::buffered_state::engine`，JNI 沿用 `media::audio::buffered`。单流队列、时钟、锚点和
原生 packet 路径保持原 owner；普通音频和 cluster 资源路径独立，不纳入此 buffered 协调器。

Rust 保存 idle／installing／cancelled／retirement-only 与 eligibility，决定准入、取消、安装及屏障处置。
Kotlin 继续持有实际 owner／transition／stream 引用及 WeakHashMap 的 session 身份，value 是 opaque
资格值，不保留 retired/cancelled 布尔策略。没有新增 native handle、全局 session ID 或累积退休集合。
原 ownership lock 内提交状态和修改表，close／start／control 继续在锁外；按原时点采样清理完成事实。

缺少旧流只在安装时算可继续；preflight／finishRetire 释放屏障须实际存在 retiring 且已完成。
close 正常返回与清理完成分传：close 抛错但清理已完成，本次仍拒绝，下次 preflight 才释放。
取消不提前清 installing 屏障或恢复旧 session 已退休资格；普通 TEARDOWN 不永久退休 session。
只有匹配 transition 才能提交或释放。callback 重入不需要等待自己，也不反向获取 stream lock。

| 检查 | 结果 |
| --- | --- |
| 旧实现合同 | API 28 engine 4、既有 buffered Host 6 共 10 项通过：取消后旧 session 仍退休、pending TEARDOWN、主动 TEARDOWN 重入、close 抛错但 cleanup 已完成 |
| 独立对照 | 4 项 core 合同含 10,000 步旧 nullable Transition 模型、完成事实组合、engine 隔离与非法 JNI；不解读 native phase，device 入口复用同一合同 |
| 最终组合 JVM | shared 82、common 47 共 129 项通过，0 skipped／failures／errors；包含第三批仪表、第四批共享输出、SETUP/control、会话隔离、反馈、原 buffered 参考及 UI／方控回归 |
| Rust 1.97.1 | 第四批 engine 专项 4 项通过；第三批 Session 3／Controller 2 项已有独立通过记录，三处新增规则共 9 项 |
| Android 测试源码／R8 | 两批新增 device 入口与已有 Android 测试源码编译通过；mobile R8 实际执行通过，maphost 为 UP-TO-DATE |
| 最终三 ABI | ARM64／ARMv7／x86_64 均以 Rust 1.97.1、NDK 28.2.13676358、API 28 编译；各 615 个 Java JNI 导出及 JNI_OnLoad、ELF／来源校验通过 |

本地日志：`/tmp/buffered-engine-old-contract.log`、`/tmp/buffered-engine-rust.log`、
`/tmp/media-ownership-final-gradle.log`、`/tmp/media-ownership-abi.log`。旧 Host 合同的 close hook 使用
Robolectric directlyOn 执行真实 close，仅控制阻塞或清理后的异常，不将它记作 native TCP 验收。
当前 ADB 只有 Linux 宿主在线，Android 地址 offline；仪器测试未执行，未构建完整 APK 或完成设备／实车验收。
既有 BufferedAudioSessionTest 的真实接收／释放屏障场景仍需 Android 设备运行，不以主机状态对照替代。

## 非 buffered 清理与 duck 隔离（2026-11-16）

第三、第四批已合并为 `0319d86a` 并推到 `dev`。随后按用户选择，先修复旧会话清理与 duck
相互影响，再将这部分动作准入规则收进原有 Rust owner；不将普通流并入 buffered 协调器。

- 音频／麦克风 TEARDOWN 按其他 session 的同 `AudioStreamId` 注册决定是否通知共享 sink 停止。
- 主屏／仪表 TEARDOWN、session close 与 EOF 按其他 session 的同 type 注册决定是否通知 inactive；
  EOF 仍先要求实际 `remove(key, screen)` 成功，不能仅用 cluster owner CAS 结果替代。
- A duck → B duck → A unduck 不再释放 B 的提示音；B unduck 或关闭仍释放。无 owner 的重复 unduck
  仍到达 sink；关闭非 owner 的 session 不额外发送 unduck。

`media::audio::route` 决定音频／麦克风释放，`media::video::flow` 决定屏幕释放，
`media::audio::scheduler` 决定 duck 通知。Kotlin 在原 sink 调用点重新读取注册事实，继续负责
对象身份、表操作、实际 CAS／remove 结果及平台动作。没有新增 native 句柄、全局 session ID 或锁；
单会话调用顺序、最后一条流的清理和原 disconnect 恢复路径保持不变。

| 检查 | 结果 |
| --- | --- |
| 缺陷合同 | API 28 Robolectric 的 10 项真实 Engine 回调合同在旧实现中 6 项失败、4 项通过；最小 Kotlin 修复后 10 项通过，迁到 Rust 后仍全部通过。EOF 合同同时验证当前流最后关闭时恰好通知一次 inactive |
| 最终定向 JVM | shared 56、common 15 共 71 项通过，0 failures／errors／skipped；包含新增 10 项、独立参考、会话隔离、screen listener、duck、route、buffered 屏障及仪表首帧回归 |
| 独立参考 | shared 专用 `testCoreInternal` 保留迁移前最小 Kotlin 修复参考，真实 host JNI 对照全部 20 组布尔输入：audio 2、screen 2、duck 16；生命周期由 Engine 合同另行验证 |
| Rust 1.97.1 | 三个 owner 的新增 `shared_output_` 定向测试 3 项通过；未将其记作全库测试 |
| Android 测试源码／R8 | `:shared:compileDebugAndroidTestKotlin` 通过；mobile R8 实际执行通过，maphost 为 UP-TO-DATE。三个新增 JNI 方法均在 mobile keep seeds 中，沿用已有整类 native 规则 |
| 最终三 ABI | ARM64／ARMv7／x86_64 均以 Rust 1.97.1、NDK 28.2.13676358、API 28 编译；各 618 个 Java JNI 导出及 JNI_OnLoad、ELF／来源校验通过 |

本地日志：`/tmp/nonbuffered-cleanup-{old-red,kotlin-green,native-green,rust,final-gradle,abi}.log`，
旧失败 XML 为 `/tmp/nonbuffered-cleanup-old-red.xml`。独立复核确认 fresh 注册查询、EOF remove 前置条件、
duck 实际 CAS 及原调用顺序。本批仅验证顺序交接合同；注册查询到 sink 调用之间未增加原子资源锁，
不据此宣称解决所有并发交错。未运行 Android 仪器测试、完整 APK 或设备／实车验收；API 28
Robolectric 与 host JNI 不代替 native TCP、真实 renderer／AudioRecord 的跨会话交接验收。

## 剩余协议规则与交接边界复核（2026-11-16）

以下是本批清理修复后的源码链路分析，尚未新增对应运行复现合同。按失败影响及可独立交付程度排序，
不把代码可达路径、历史设备通过与本批已测事实混算。

| 优先级／候选 | 具体触发与当前边界 | 后续归属与合同 |
| --- | --- | --- |
| ✅ 已完成：普通音频输出退休与恢复 | 已区分"被替换的永久退休"与"渲染器故障的可恢复 detach"：`AudioRoute` 增 `retired` 状态，`deliver` 对被替换 route 直接 `Closed` 丢弃（不再回退 Kotlin），仅故障 `disconnect` 仍回退 `onAudioPacket` 供恢复；`AndroidMediaSink.onNativeAudioRoute` 替换同 id 旧 route 时改 `retire()`。Rust 单测覆盖 retire-vs-disconnect（`966f2017`） | 完成；A→B 被替换流不再投递到 B renderer；同流 renderer 恢复仍经 disconnect 保留 |
| ✅ 已完成：麦克风实际发送配置交接 | `startMicrophoneCapture` 不再 `computeIfAbsent` 复用旧 uplink；Rust `NativeMicrophone.uplinkConfig*` 按已提交有效配置决定复用/替换，新 sender 实际 `start()` 成功后才提交；Kotlin 关旧建新、执行录音器与平台资源（`8ae0e2da`）。Rust 单测 + Kotlin native 契约测试 | 完成；同 id 不同 host／port／key／format 会替换 sender |
| 高：视频配置交付资格 | `video/receiver.rs:97` 回调 config；`CarPlayMediaEngine.kt:217` 仅检查各自 session key，旧 A 仍可经 sink 配置共享 type decoder。native 帧则不同：`NativeVideoRoute.kt:10` disconnect 后为空 targets，`vpipe.rs:368` 消费并释放，不据此声称已证实 native 混帧 | 沿 `media::video::flow`／既有 Route 明确 config 的交付资格，先补新旧配置交错及最后 owner 正例；Kotlin 保留 stream／decoder 身份与平台配置 |
| 高：iAP tunnel 发布与执行寿命 | Controller 的 `startWirelessTunnelControl` 在 2920 行发布 B，但 2929 行仍排入单线程 executor；A 的 NO_TIMEOUT loop 可继续运行，回调主要按 run 判断。发布的 channel 与实际运行的 channel 可能不同 | 沿 `session::wireless_state`／controller 补重复 tunnel 的接受、替换与迟到回调合同，再决定提交点；不能未经合同直接增加拒绝门禁。metadata 属于 iAP channel 寿命，不按每次 AirPlay session 更换统一清空 |
| 中：排队命令与旧 session UI 回调 | Controller 727／738 行捕获 session 后排队，执行时没有相邻 knob／viewArea 路径那样的当前身份检查；415 行旧 `requestUI` 回调也未按 active session 限定 | 沿现有 controller owner 定义来源准入；Kotlin 保留 Executor、session 身份和实际发送。补 A 排队→B 激活→A 仍开放的合同，不将迟到 A 命令改投 B |
| ✅ 已完成：一般命令包络与 SETUP 整体形状 | 已迁入 `protocols::airplay`：`NativeAirPlayProtocol.setupPlan`（streams-vs-peer 信封）与 `remoteCommandPlan`（remote 信封）在 Rust 判定（`363b614c`）；控制命令解释（noisy／duck／ui／navigationEntity）在 `control` op15 且有单测（`57db7caa`）。Kotlin 仅消费具名计划、解码与回调 | 完成；保留类型／缺省／转换／拒绝行为，解码对象与实际 callback 留 Kotlin，未新增 negotiated-stream 或 active-session 门禁 |
| ✅ 已完成：视频 URL 可播放规则 | 已迁 `protocols::airplay::video` op10（`playable_url`）；`VideoInCar.isPlayableUrl` 与 `parseItem` 改调 Rust 并删除 Kotlin scheme 常量；Rust 单测 + Kotlin 契约测试覆盖 | 完成；http／https 与 app-scheme 规则、本地／FairPlay 排除均在 Rust |

下一批优先普通音频 route 的退休／恢复区分，再处理麦克风 sender 和视频 config 资格。
Controller tunnel 与排队命令另成行为合同批次，避免与媒体资源交接同时改变。仍保留以下提交时点：
媒体 SETUP 返回非空结果后立即记录 accepted stream；控制响应写入后安装 cipher；每个已 flush 的读取批次
执行原 setup-response 通知。协议 route、streamAction、features、teardown、night/navigation/availability
及已迁移的 playback 判断已有 Rust owner，不因外围仍有 Kotlin 回调重复迁移。

## G/H 本次改动的验证边界（2026-11-15）

本次代码将 AAC 输入组装／调度与麦克风分帧、echo lease、统计、编码发送收敛到 Rust/C；Speex 静态链接到核心，按用户要求删除 Kotlin 数据／Java socket 后备。小米 8／API 35 同一最终测试 APK 在 ARM64、ARMv7 各通过 22 项、0 失败、0 跳过，包含真实 AudioRecord 经 Speex／PCM／Opus 到加密 UDP、关闭重开和真实 AAC 解码输出。见[本批设备验证](benchmarks/native-aac-capture-2026-11-15.md)与[当前架构](ARCHITECTURE.zh-CN.md)。本批未做真实 iPhone 通话、实车音路、声学 AEC 或性能验证，不重开下面已收尾的历史场景。

## 2026-11-15 dump 派生四项收敛

用户确认继续实施的 ECARX MQTT、HUD 发布策略、控制通道缓冲与下行音频控制链均已接入生产 Rust owner，详见[当前架构](ARCHITECTURE.zh-CN.md)及[本轮实施／验证记录](benchmarks/native-platform-boundaries-2026-11-15.md)。Kotlin 保留 Socket／线程／锁、DIM SDK 与持久设置、MediaCodec 配置／关闭、AudioTrack／焦点／路由和诊断；没有新增 Kotlin 算法后备。

小米 ARM64／ARMv7 各通过 8 项合同测试；同批 ARM64 Release 另完成真实 iPhone 无线连接、MediaSession 媒体键播放／暂停／切歌和正常断开重连，收到 AAC／Opus 原生 PCM，用户确认画面和声音正常。MQTT broker／DIM 真车发布、实车音路与长时间稳定性仍未由本轮证明；不根据代码行数或原生缓冲归属宣称性能改善。USB／TUN 和已收尾项目保持原验收范围。

## 当前剩余项的处理范围（2026-11-14 澄清）

- **本批手机验收已完成**：39 项 ARM64 自动化及同批 Release 的切歌封面、暂停恢复、Siri／导航与听感均通过。ECARX 专有 Binder／目标车机音路仍需实车验证。
- **此前保留的实车项**：PASCD 动态数据到发送的对应记录／真实服务恢复；与本次收尾列表无关的方控 Siri、熄火唤醒场景仍按各自原记录管理。已确认 OK 的导航音量键、#111、HUD 和双屏生命周期不再重复列入。
- **仅观察**：约 20 秒 Host 暂停、音频到达间隙及相关历史现象。未明确操作背景或复现前，不当作已确认缺陷。
- **独立协议资料**：PairVerify／PairSetup 外部 transcript 的来源缺口保留为资料记录，不阻塞本轮视频修复；SDK 接口和 HUD／车道不再追加核验。
- **性能记录已收尾**：v148／v150 短测及每版 10 分钟、8 次重连已有工作区报告，用户确认 OK。保留原测量范围，不追加长测，也不用于替代配置失败交错修复的设备 green。

USB 本轮已收尾；#113 首帧修复及历史 AAudio 关闭修复已有验收结果，不重复列入本轮待执行项。

**Java MediaCodec 备用循环清理（2026-11-14）**：已从生产删除，视频统一通过 Rust／NDK；API < 28 或原生循环无法创建时明确失败。软硬件 codec 回退、厂商参数降级、Surface／镜像能力保留。v150 原始日志有 18 次原生首输入／18 次原生首渲染；这是已有路径证据；本次变更后小米 8／API 35／ARM64 设备自动化已通过，见[验证记录](benchmarks/native-video-startup-2026-11-14.md#java-mediacodec-备用循环清理)。本批完整 Checks 已通过：JVM 3597 通过、2 环境跳过、0 失败，四应用 lint／debug 构建及设备测试 APK 通过；同批 Release 的真实 iPhone 会话已完成上述指定场景；目标车机仍需验证。

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
| 通话／导航路由、far-end PCM、采集时钟／分帧／echo lease／统计／编码发送 | `media::audio::{routing, echo, capture, uplink}`，Speex C 静态连接 | Android 音路能力、AudioTrack／AudioRecord／timestamp、平台 AEC／effects、关闭执行与诊断文字 |
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
| 更多真实 CSM／HUD 固定输入输出；PairVerify／PairSetup 的第三方互通证据 | PairVerify／PairSetup 完整 transcript 已用独立参考实现固定（2026-11-17），非第三方抓包；仍需更多真实 CSM 帧样本，以及取得可公开登记的第三方发布向量或已知密钥真实抓包以补互通证据 |

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

### 2026-11-15：默认诊断与小米 8 无线复测

诊断实现 `80499891` 完成 Rust 汇总/限频、输出采样、默认日志去重、报告身份及写入健康、平台操作结果和退出/恢复独立记录。ARM64 与 ARMv7 各 23 项设备合同通过；小米 8 / API 35 完成两轮无线连接、MediaSession 播放/暂停/恢复/切歌、前后台和正常退出，用户确认音画正常。记录见[默认诊断小米 8 实测](benchmarks/default-diagnostics-mi8-2026-11-15.md)。默认日志短时约 3.28 KB/min，健康摘要约每分钟一条。被测包版本号仍为 150，151 为后续交付版本号；不改写历史验收。实车 ECARX 服务/导航/蓝牙恢复效果、AEC/通话、长期运行及冷启动矩阵仍待目标硬件验收。

## 核心职责重构分支（2026-11-17，rust-carplay-control-ownership）

基于 `origin/dev@9ae06f13` 的重构分支，按功能分独立 commit（非最小化改动）：

- **Area 5c**：`session::controller` 的 `header: [i64; 14]` 改为具名 `ControllerState` 字段，`Vpn`/`Proof` 位标志改具名 bool，事件/决策/key/VPN/proof/dashboard 码改枚举，仅在 JNI 边界转换一次；14 字 + 键向量、`[active,generation]`、`[generation,flags]` 三处 wire 布局与 `valid_header` 区间、可观测结果逐字节不变（`23ce566e`）。附带：音频焦点 token 具名化（`7b754547`）；删除无调用者的 `NativeMedia.openRtp` 并对齐平台态（`632a3557`、`d77bb4a9`）。
- **Area 4**：删除只做取反的伪 JNI `audioOutputMayStop`/`screenOutputMayStop`/`usbRestore`，连同其 JNI 导出、Kotlin 声明与测试影子；USB 回退改为直接判断平台 Apple 设备事实。
- **Area 3**：新增 Rust `media::output_lease` 拥有非缓冲流身份与输出共享租约（键含引擎 epoch／kind／stream type／audioType／持有会话），仅某输出身份最后释放才允许停止；Kotlin 在注册点 acquire、在 teardown／会话关闭／对端 EOF／替换点 release 并据返回值决定停止，多会话共存由 `CarPlaySharedOutputCleanupTest` 守护（`84f25f2b`）。
- **Area 1**：控制加密启用时机迁入 `session::airplay::after_response`（`3ebff66e`）；wired 配对／CarKit 重试与终端结果分类迁入 `session::wired_stack`（`4f8ef73e`、`7134ed6b`）。wired 的 USBMUX／Lockdown／NCM／VPN I/O 序列仍由 Kotlin 执行，符合既有边界“Rust 决定、Kotlin 执行平台动作并回报”。

| 验证 | 结果 |
| --- | --- |
| Rust `cargo test --locked --lib` | 307 passed / 0 failed / 0 warnings |
| `:shared:testDebugUnitTest` | 2101 passed / 0 failed |
| `:common:testDebugUnitTest` | 1659 passed / 0 failed |
| Android 原生（本机） | Rust 1.97.1／NDK 28.2.13676358／API 28 构建三 ABI 通过：ARM64／ARMv7／x86_64 各 619 个 Java JNI 导出 + `JNI_OnLoad`，ELF/架构与跨 ABI 导出一致性校验通过；覆盖新增 `outputLeaseAcquire/Release`、`afterResponse`、`wired_stack.carkitErrorAction/terminalOutcome` |
| APK 构建／lint（本机） | `:mobile:assembleDebug :maphost:assembleDebug :mobile:lintDebug :maphost:lintDebug` 通过（`DIPLAY_JNI_LIBS_DIR` 指向本机三 ABI 构建产物，`stageRustAndroidInputs` 来源校验通过） |
| 未在本机执行 | 真机 wired 验证（需 USB 连接的 iPhone）；本机设备为 Android／无线场景 |

wired `runStack` 的 bring-up 顺序已由 `session::wired_stack::advance(action, approved)` 驱动：核心发出 USBMUX／配对／CarKit／CSM／VPN／endpoint／control 的下一步或中止分支，Kotlin 执行每步 I/O 并回报 gate fact（I/O 体、资源所有权与 try/finally 原样保留）。wired 真机回归仍待 USB 连接的 iPhone。

## “CarPlay 全在 Rust”收敛核对（2026-11-17）

逐项核对"剩余非平台逻辑"，结论与对应 commit：

- **时序/阈值（④）**：iAP2 引导阶段预算单源 `session::watchdog::budget`（`6e36ee37`）；wired 启动看门狗时限入 `session::controller::timing_policy` 索引 14/15（`3f480e18`）。Kotlin 剩余超时为平台 socket/无线电窗口，按边界保留。
- **状态语义（②）**：恢复动作（`ConnectionRecovery.action`）、每代一次失败（`WirelessRunState.fail`）、失败分类（`WirelessRecoveryCore.classify`）均 Rust；Kotlin 仅 `CarPlayStatus` DTO 与 `debugLogMessage` 文案（展示/日志，属平台）。已满足，无需改。
- **重试原因结构化（⑤）**：CarKit `StartService` 拒绝由 Rust `flow::rejection_reason` 返回结构化码（1=无效配对记录，2=无效 host id，`87b8949d`），Kotlin 抛 `IphoneUsbException.CarkitRejected(reason)` 直接读码；残余 `.contains` 仅平台事实（蓝牙设备名/errno 文本）。
- **共享输出两套 owner（③）**：核实为**按输出域分离**——非缓冲 screen/audio/mic 的唯一“可停止”权威是 `media::output_lease`（`NativeAudioRoute`/`NativeVideoFlow` 仅渲染/输入，不做停止决策）；单条 type-103 buffered 渲染器由 `BufferedEngineCore`＋Kotlin `WeakHashMap` 弱资格＋退休屏障决定（AGENTS 明确弱资格须由 Kotlin 持有）。二者语义不同且都已在 Rust 决策，**合并会违背弱引用设计并有媒体回归风险，故按“不盲改”保留现状**。
- **共享输出释放顺序（③ 补强）**：`onTeardown` 与会话关闭（`onSessionClosed`）均统一为"先摘除并关闭源流，再按最后持有者释放并停止"；只对音频类型释放音频/麦克风 lease（屏幕 110/111、数据 130 的 teardown 不再误发 `onAudioStopped`）；`onAudio` 的同 session 替换按最后持有者门控停止。Rust `output_lease` 增单测（未知持有者释放不停共享输出），`CarPlaySharedOutputCleanupTest` 增 4 例（源先于 sink、屏幕/数据不误停、会话关闭顺序），shared 2114/0、cargo 318/0。
- **设备级原生契约测试（2026-11-17，emulator-5580 / API28 / arm64，`am instrument` 直跑）**：全部 40 个 `*DeviceTest` 类通过——覆盖 protocols::airplay/rtsp/bplist/control_cipher、pair_setup/pair_verify、iap2_body、media 音频/相机/播放/媒体策略/视频(vpipe·route)、buffered、cluster_content、vehicle(hid/identify/status/conversion/route_guidance/lane)、hud、mqtt、ntp timing、wireless state/recovery、hotspot address/recovery/p2p/bonjour、control_deadline、location 等。前置：`NativeVideoRouteDeviceTest` 的 `stats` 缓冲由 `LongArray(14)` 修正为 16（契约 6 核心＋`VideoInputStats[8]`＋codec/queue，`a0345bb2`，生产 `drainStats` 本就 16，非生产缺陷）；`NativeMicrophoneCaptureDeviceTest` 需先 `pm grant ... RECORD_AUDIO`（权限前置，模拟器麦克风输入可用）。整包 androidTest **190 项全部通过**。其中 `BufferedAudioSessionTest` 初期失败的根因是**测试 loopback 地址族不一致**（`InetAddress.getLoopbackAddress()` 在该模拟器解析为 IPv6 `::1`，而测试用 `Socket("127.0.0.1")`，`BufferedAudioStream.acceptPeer` 视其为异对端而断连，表现为 `Broken pipe`/超时）；已在测试中显式传入匹配的 `expectedPeer`，复跑 OK(10)，整包 OK(190)。非生产缺陷（生产 `expectedPeer` 取真实 remote 地址）。`--offline` 下 `connectedAndroidTest` 缺 UTP 依赖，故离线构建 androidTest APK + `am instrument`。
- **音频投递准入（③ 补）**：`media::output_lease` 决定"谁能停"，另补"谁能继续投递"——被替换的音频 route 在 `media::audio::route` 永久 `retire`（后续包在 Rust 内丢弃、不回退 Kotlin），仅渲染器故障用可恢复的 `disconnect`（回退 `onAudioPacket` 供恢复）。`AndroidMediaSink.onNativeAudioRoute` 替换同 id 的旧 route 时改为 `retire()`。Rust 单测（retire→Closed vs disconnect→Callback）；cargo 319、shared 2114、三 ABI 原生 624、`NativeAudioRoute/Out/State` 设备契约通过（`966f2017`）。
- **麦克风配置交接（② 补）**：同 mic id 新配置不再 `computeIfAbsent` 复用旧发送器。Rust `NativeMicrophone.uplinkConfig*` 按已提交的有效配置决定复用/替换，新发送器实际 `start()` 成功后才提交；Kotlin 关闭旧发送器并重建、执行录音器与平台资源。并区分同键/换键：同键不同配置的替换快照并承接旧发送器 ChaCha nonce（避免 nonce 重用），换键从 0 起。Rust 单测（复用矩阵 + nonce 承接）+ Kotlin native 契约测试；cargo 321、shared 2115、三 ABI 原生 629、`NativeMicrophoneCapture` 设备契约通过（提交见本项）。
- **wired 收尾/释放计划（①）**：release 顺序与“关 NCM / detach VPN”分支的决策已下沉 `session::wired_stack::teardownPlan(phase, facts)`（Rust 单测 + Kotlin 契约测试），Kotlin 仅按计划执行原有 close/detach 语句；wired 真机回归仍待 USB 连接的 iPhone。

结论：safely-doable 的非平台逻辑（④②⑤）已收敛；③ 经核实为有意分离（非缺陷）；① 的决策层已下沉，仅真机回归待 USB iPhone。
