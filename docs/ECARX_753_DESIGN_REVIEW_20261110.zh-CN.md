# 领克 01 / ECARX 753 适配设计评审（2026-11-10）

评审对象：`dev` 分支 `c55bef22` 及 PR #68 的代码与文档，面向唯一正式环境（领克 01 / 753 / Android 9）。
评审依据：仓库源码、docs/ 下的决策与验证文档、CHANGELOG v0.1 至 v144。没有新的实车证据；凡标注“待验”的功能以[平台笔记第 7 节](ECARX_753_PLATFORM_NOTES.zh-CN.md#7-验证矩阵)为准。
本文只追加，不改写；结论变化写入平台笔记。

## 1. 总体判断

适配的设计方向是对的：**运行时探测 + 反射 + 可检测失败**，不依赖 ADB，不打包厂商 JAR，每条厂商接口都能在反编译或实车日志里找到出处。
把原厂导航“停包”而不是在 MQTT 总线上纠偏、把麦克风仲裁按正确生命周期实现但明确记录服务端不支持、把“请求返回 true”和“车机实际变化”分开记录，这些取舍体现了一致的工程原则：宁要可检测的严重错误，不要静默失效。

主要风险不在单个功能，而在三处结构性问题：

1. **三个上帝类**承载了连接状态机、宿主窗口和设置页，拆分只能机械地剥外围（PR #66、#68 共减约 1,000 行），核心状态仍散在几十个字段里。
2. **启动链路由一串基于单车观察的时序经验拼成**（解锁即启、等桌面批次 5 s、等蓝牙 8 s、设置应用 6–10 s 后重置 Wi-Fi、热点与客户端互斥），每一环都对，合起来没有一个状态机和验收指标。
3. **待实车验证的功能持续堆叠**：v109 到 v144 的 CHANGELOG 中 15 个以上小节标“待实车验证”，而最近一次完整车测包是 v142，正式 Release 已到 v144。

## 2. 架构概览

```
iPhone ──iAP2/AirPlay──► shared/orchestration (CarPlayController, Wireless*, Iap2*)
                              │
                              ├─► shared/media  (AndroidMediaSink, AudioRenderer, MicrophoneUplink,
                              │                  NavigationPromptFocus, NavigationAudioRoute)
                              ├─► shared/hud    (NavigationOutputs → EcarxHudBridge → EcarxDimApi [反射]
                              │                  EcarxProjectionChannel/EcarxMqtt, EcarxDayNight,
                              │                  EcarxSwKeyIntercept, EcarxMicPolicy, EcarxFactoryNavigationRestore)
                              └─► shared/network (CarHotspot*, CarWifiClientGuard, GeelyHeadUnit)
common/  CarPlayHostActivity（投屏窗口、设置覆盖层）  DiPlayActivity（首页与设置）
         CarPlayBackgroundSession（跨 Activity 保活）  EcarxClusterSession（仪表窗口归后台会话）
         CarPlayMediaSession / HyMediaDisplay / EcarxMediaCenterTransport（媒体接管方）
         GeelyWheelKeys / WheelKeyService / HySiriKeys（三条方向盘键通道）
         BootReceiver / EcarxLauncherStartup（开机链路）
```

厂商面全部集中在 `shared/hud`、`common/mediacenter` 和少数 `network` 文件，入口统一经 `EcarxCapabilities` 探测。这是本项目最值得保持的边界。

## 3. 做得好的地方

- **能力探测分层**：车机识别（类 / Build / 包名）只决定显示卡片，每个接口调用前再探测一次，缺失即降级并写诊断。非 ECARX 设备零影响。
- **契约来自字节码而非文档**：箭头编号、DIM 调用序列与 1 s 节拍、方向盘广播、HY 协议都注明反编译出处，且箭头表与另一移植项目的实车扫描交叉核对。
- **厂商调用与协议线程隔离**：`NavigationOutputWorker` 让阻塞的车机服务不能拖住 iAP2 读线程。
- **失败可检测**：停包前后回读包状态；`automaticRetryAllowed` 区分需用户介入的失败；`requestMic` 返回 true 不开录音，等 `onResume`。
- **异常退出有恢复记录**：停包标记、蓝牙让位恢复记录、热点恢复都持久化并在下次启动检查。
- **诊断报告是唯一回传通道**，设计上围绕它：`EcarxReportSink` 把早期日志回放进报告，启动时间线、日夜来源、媒体命令来源都进报告。
- **无 ADB 原则落地**：正式功能无一依赖 ADB，五项平台权限用 `maxSdkVersion=28` 限定，不为调试机补路径。
- **更新链路安全**：`update.json` 带 SHA-256，安装前核对包名、签名、versionCode；镜像站只能提供字节，不能替换签名。
- **软件测试密度高**：3,289 个单测，ECARX 专项 60 余个测试类，Robolectric 截图固定 1920×1080。

## 4. 发现与建议

按影响排序。每条给出证据、风险和建议；“建议”不是承诺，取舍由维护者定。

### F1. 连接状态机没有边界（高）

`CarPlayController.runWireless` 648 行、`runStack` 237 行，二者与关闭、恢复、看门狗共享约 30 个字段（`wirelessGeneration`、`wirelessResourceLock`、`wirelessTunnelReady`、`wirelessHandoff*` 等）。局部恢复（`WirelessBootstrapRecovery`）、预连接（`RfcommPreconnect`）、看门狗（`Iap2BootstrapWatchdog`）都正确地做成了独立对象，但它们操作的状态仍在控制器里，正确性依赖“每次尝试前重新校验 run / 热点 / listener / Bonjour”的纪律。

建议：引入一个 `WirelessRun` 对象持有一代无线会话的全部可变状态和资源，`CarPlayController` 只负责创建、切换和销毁它。先做字段迁移，不改逻辑，用现有 1,719 个 shared 单测兜底。这比继续从宿主剥 UI 更有价值。

### F2. 启动链路缺少状态机与指标（高）

开机路径现在是：`LOCKED_BOOT_COMPLETED` 预热 → 已解锁即开 CarPlay → 识别为 ECARX 时等桌面批次（最多 5 s）→ 热点与蓝牙并行 → 蓝牙 `STATE_OFF` 等 8 s → 车机设置应用 6–10 s 后重置 Wi-Fi 由 `CarWifiClientGuard` 压住。每一步都有实车日志支撑，但：

- 多数标“待实车验证”（v134 的解锁启动、热点保护、导航声音并行等）。
- `CarPlayStartupTimeline` 已能记录阶段，但没有定义验收指标（例如整车冷启动到首帧 p50/p95），文档也明确“不承诺固定启动耗时”。
- 停包状态落盘后整机重启，开机广播拉起进程却不恢复原厂导航，要等用户打开 DiPlay（v109 发现，第 4 节“导航与仪表”）。这是开机链路上一个已知的用户可见缺陷。

建议：把开机流程画成一张状态图放进平台笔记；在 `BootReceiver.onBootCompleted` 中调用 `EcarxFactoryNavigationRestore` 的恢复检查；定义三条冷启动指标并在每个车测包记录。

### F3. 车测节奏落后于发布节奏（高）

v143、v144 已作为 GitHub Release 发布并开放应用内更新，而最后一次车测包是 v142；v143 的日夜精简、重连补全只在小米 8 台架验证。默认开启的“蓝牙媒体让位”在本车从未验证，唯一实机探测（BOOX Android 11）失败。

建议：Release 前至少跑一次平台笔记第 7 节中“日常使用基线”四项的车测并记录版本；未在本车验证的功能默认关闭或标 `(experimental)`；把“待实车验证”从 CHANGELOG 小节标题移到验证矩阵，CHANGELOG 只写行为。

### F4. 厂商契约漂移没有运行时护栏（中）

接口签名靠 2026-10-31 的 framework dump 核对，`EcarxAdaptapiAbiTest` 需要手工给 JAR 路径，CI 不跑。固件 OTA 后反射失败会进诊断报告，但不会阻止功能开启或提示用户。

建议：在诊断报告和“权限检查”页记录 AdaptAPI 运行库版本与 `EcarxCapabilities.describe()`；首次检测到接口集合变化时在首页给一条提示；发布流程里把 ABI 测试列为必跑项（本地有 JAR）。

### F5. 方向盘键有四条进入通道（中）

ECARX 拦截 API（200000 偏移键码）、Geely `InputService` 原始码、ECARX 语音/电话有序广播（`abortBroadcast`）、HY 模式读 logcat（`READ_LOGS`）。去重规则分散在 `GeelyWheelKeys`、`CarPlayMediaSession`、`MEDIACENTER_BACKENDS.md` 的文字里。其中 Geely 服务“导出且 `registerListener` 无权限检查”和“读 logcat 识别按键”都是靠固件疏漏，一次固件更新即失效。

建议：一张“键 → 通道 → 模式 → 去重规则”表放进平台笔记并对应到测试名；HY 模式的 logcat 通道标为临时方案，优先推动 HY 用 `:siribridge` 的显式 intent。

### F6. 音频通路决策依赖毫秒级时序（中）

导航播报走车机导航子通道还是混进音乐流，由 iPhone `duckAudio` 是否先于音频流 80–210 ms 到达决定；车机导航子通道的压低幅度 Android 不可控；`USAGE_ASSISTANT` 被车机吞掉。这些都是正确的观察，但决策点在“流打开瞬间”，抖动即走错通路，且 v134 的实现未实车复测。

建议：通路选择结果和依据写入每条播报的日志行（已有 `NavigationPromptFocus` 日志，补通路字段）；车测时专项记录 20 条播报的通路分布；考虑把“上一条播报的通路”作为本条的默认值以抵抗抖动。

### F7. 麦克风争用是已知无解项，需在用户文档明示（中）

GKUI 服务端对 `requestMic` 一律抛不支持，原厂助手与 CarPlay 争用硬件麦克风没有解；车机只为 `VOICE_COMMUNICATION` 源开麦。代码处理正确，但用户说明里只在 SDK 文档提及。

建议：FEATURES 第 9 节加一行“原厂语音助手与 Siri 同时工作时麦克风行为未定义”。

### F8. 依赖第三方应用内部行为（中）

HY 3.6.1：800 ms 轮询首个 active 会话、`dashboard` 广播协议、按键 logcat；`ecarx.xsf.mediacenter` 排在会话列表首位导致 HY 模式首次方控播放失效。HY 升级即可能失效。

建议：`HyMediaDisplay` 与 `HySiriKeys` 读取 HY 的 versionCode，非 28 时在诊断报告和设置卡片标“未验证版本”。

### F9. 平台签名应用的身份与数据脆弱性（中）

UID 在两次记录间从 10125 变为 10105，说明中途发生过卸载重装；MFi 身份存在 `no_backup/offline-mfi/`，卸载即丢。`persistent` 与 `sharedUserId` 两条“变系统应用”的路都已证明不可行。

建议：设置里的身份导入旁加“导出身份备份”；文档明确“不要卸载”已有，再加“升级前先导出”。

### F10. 宿主与设置页的 UI 构建方式阻碍拆分（中）

`DiPlayActivity` 的页面由私有 DSL 辅助函数（`button`/`label`/`row`/`card`…）直接读 Activity 字段构建；`CarPlayHostActivity` 的设置覆盖层同样。PR #68 只能剥出状态自洽的安全区编辑器。

建议：先把 DSL 做成独立的 `SettingsDsl(context, palette, theme)`，再按页面迁出；`SettingsLayoutSourceRulesTest` 的文件名单随之扩展。

### F11. 热点保护的副作用需要首次确认（低）

`CarWifiClientGuard` 持久写 `wifi_on=0`，车机此后不再自动连任何 Wi-Fi，默认开启。文档写明可关闭，但首次开启时没有确认对话。

建议：首次启用自动热点时弹一次说明。

### F12. 文档与证据的分布（低）

平台事实原先散在 ADB 清单、SDK 上下文、决策文档、FEATURES 第 9 节、CHANGELOG 和工作区 `WORKSPACE-GUIDE.md`。本次新增[平台笔记](ECARX_753_PLATFORM_NOTES.zh-CN.md)集中登记接口、固件行为、验证矩阵和证据索引。

建议：新接口、新属性 ID 先登记再使用；每个车测包更新验证矩阵；带日期的文档保持只追加。

## 5. 验证缺口（摘自平台笔记第 7 节）

实车从未验证、但已默认开启或已随 Release 发布的项：蓝牙媒体让位、日夜选项精简与冷启动推测、熄火开门后自动重连、导航声音跟随、开机热点保护、RFCOMM 并行与局部恢复。
实车只验证了一半的项：开机解锁即启动（批次等待有依据，整体验收未做）、方向盘语音键（短按有，后台 Siri 唤醒无）、仪表车道（隐藏有，显示恢复与多分支高亮无）。

## 6. 建议的下一步顺序

1. 用 v144 跑一轮基线车测并填验证矩阵（F3）。
2. `BootReceiver` 恢复原厂导航包；记录三条冷启动指标（F2）。
3. `WirelessRun` 状态对象迁移（F1）。
4. AdaptAPI 版本与接口集合进诊断报告，ABI 测试进发布清单（F4）。
5. 方向盘键通道表、音频通路日志字段、HY 版本检查（F5、F6、F8）。
6. 设置页 DSL 独立后继续拆 `DiPlayActivity`（F10）。
