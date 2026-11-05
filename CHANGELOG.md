# DiPlay ECARX 0.2 — 未发布

基线升级至上游 [DiPlay v0.2.22](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.22)，
`versionCode 122`。保留 ECARX 的应用标识、可选本地认证资产和签名配置。
合并取舍与验收项见 [升级记录](docs/UPSTREAM-0.2.22-INTEGRATION.md)。

使用手册正文已跟进到本段实现；页面截图仍是 v106 实车画面，各节标注了后续版本的实测状态。

## RFCOMM 连接与网络准备并行（无线连接提速，待实车验证）

- 选定配对 iPhone 后立即开始 RFCOMM socket 连接，与 AirPlay listener attach、Bonjour 启动、endpoint 构造并行执行。v124 实车日志中这两段原本串行：bt/select 之后空等约 235ms 才发起连接，而连接本身约 259ms；并行后两段等待重叠，预期节约 ≈235ms（待实车 A/B 验证）。
- 只并行 socket connect：socket 创建时即在资源锁下登记到既有清理路径，所有权不变；采纳预连接 socket 或重抛其失败都发生在 `validateInfrastructure()`（含 Bonjour 校验）通过之后，CSM 与 bootstrap 时序不变，未放宽任何校验。
- 预连接失败按该次尝试的 connect 失败处理，保留 #36 局部恢复的分类与预算；预连接只被第一次尝试消费一次，退避重试与备用 iPhone 仍按原路径串行建链，不会出现两个 socket 同时连接同一部手机。
- 采纳等待上限为 connect 8s 预算加 2s 轮询余量：预连接卡死时以 IOException 超时进入既有重试，而不是退回串行再开第二个 socket。
- `RFCOMM_CONNECTED` 启动时间线采样移到预连接线程 connect 真正完成时刻，时间线不再把「等网络准备」计入连接耗时。

## 热点就绪分级确认（无线连接提速）

- 热点检测改为分级确认：首个采样已显示 AP 已开、接口已在 `getTetheredIfaces()` 中且地址可用时，稳定采样由 3 次缩短为 2 次（少等一个 250ms 轮询）；热点刚启动、接口或地址发生变化时仍保留 3 次完整确认。
- `validateReady()` 仍每次重新读取接口，但只在接口/地址变化或校验失败时输出候选网卡明细，消除单次连接最多 4 轮重复的网卡枚举日志。
- 不跨热点开关周期缓存 `ifindex` 或 IPv6 `scopeId`；每次采样都重新读取，`wlan0` 的 index 在 13/14 间变化也能正确识别为变更并重新确认。
## 无线关闭不再被 Bonjour 阻塞

- 拆分无线栈关闭的同步与异步路径：同步路径只做必须与建链保持顺序的工作（失效当前 generation、解绑 AirPlay、关闭 iAP2 隧道与 RFCOMM bootstrap、释放热点），Bonjour 的 JmDNS 关闭、MulticastLock 释放与工作线程回收改到独立守护线程执行。
- 关闭 JmDNS 期间不再持有全局资源锁。实车日志中单次 `teardown resource=Bonjour` 曾达到 22 秒、多次 4~5 秒，期间整个无线 teardown 与下一次重连都被阻塞；现在资源锁只覆盖瞬时操作。
- AirPlay 服务解绑提到 Bonjour 之前，先停止广播与接收连接，不用排在可能阻塞的 JmDNS 关闭之后。
- 异步清理只关闭关闭时捕获的旧 generation 实例，新会话使用自己的 `CarPlayBonjour`，旧 generation 的迟到清理不会影响新会话。
## 网卡识别：不再把只有 link-local IPv6 的以太网口误判为热点

- 自动推断热点承载接口（`hotspotCarrierCandidates`）时，非无线的有线/VLAN 接口现在必须发布可用的 IPv4 地址才进入候选，纯 `fe80::` 不再作为热点归属证据，修复 `eth0`/`tam0` 等内部网卡被误判为热点的问题。
- 平台明确报告的 tethered 接口与显式配置的接口仍保留原有 IPv6 兼容策略，仍可承载 IPv6 热点；本次未全局禁用 IPv6。

## iAP2 握手进度看门狗（无线 CarPlay）

- 无线 CarPlay 的蓝牙握手新增独立的进度看门狗，按阶段分别计时，不再只靠 AirPlay TCP 的 `FirstTcpWatchdog` 或一个统一的控制环超时：
  - RFCOMM 已连接 → CSM 已建立（15 s）：等了 CSM 仍没起来，判定为「蓝牙链路故障」。
  - CSM 已建立 → MFi 认证完成（30 s）：链路通了但手机不应答识别/MFi，判定为「手机未响应」。
  - MFi 认证完成 → WirelessCarPlayAvailable／TransportIdentifier／StartSession（65 s，为 60 s 传输等待之上的兜底；部分进度后 45 s）：认证完成却不再推进，判定为手机停在握手中。
  - StartSession 已发送之后交给 `FirstTcpWatchdog` 与无线交接看门狗；Tunnel 建立是本看门狗的终止点，不再重复计时，避免与既有超时互相打断。
- 新增四种失败原因并给出各自的处置建议：蓝牙链路故障（`WIRELESS_BLUETOOTH_LINK_FAILED`）、手机未响应（`WIRELESS_PHONE_NOT_RESPONDING`）、MFi 认证失败（`WIRELESS_AUTHENTICATION_FAILED`）、无线隧道未建立（`WIRELESS_TUNNEL_NOT_STARTED`）。此前这些情况要么一直停在「正在连接」，要么只能等到五分钟总超时。
- 计时预算按实车日志校准：RFCOMM 连接约 1–2 s，CSM＋识别＋MFi 均在 0.5 s 内，各阶段预算都远大于实测值，只在手机真正卡住时触发。
- 每个阶段都会输出 `bootstrap phase=… sincePreviousMs=…` 进度日志，失败时输出 `bootstrap stall reached=… missing=… waitedMs=…`，方便区分是蓝牙、手机还是认证的问题。

## 首次 TCP 超时不再盲翻地址族

- `FIRST_TCP_TIMEOUT` 此前直接调用 `flipWirelessAddressFamily()` 持久化切换 IPv4/IPv6 偏好。当 iPhone 根本没接入热点（例如还在屋里、蓝牙/Wi-Fi 未就绪）时，这是把「没连上」误判成「地址族不对」，会污染下一次健康连接。
- 现在只有同时满足两个条件才翻转：其一，本轮观察到 iPhone 的 Bonjour `_airplay._tcp` 通告，证明手机确实接入了热点并在应答 mDNS；其二，当前地址族已出现过可达信号（Bonjour 解析、iAP2 隧道建立或 AirPlay 会话），证明该地址族本身并非未检验。
- 任一条件不满足时保留原偏好，仅记录 `wireless address family flip withheld: <reason>` 日志。判定逻辑抽为 `AddressFamilyFlipGate` 并附单元测试。
- 每次 `startWireless` 重置本轮可达标记，避免上一轮证据泄漏到新一轮。

## 冷启动画布尺寸立即应用

- 首次取得有效显示尺寸时立即应用，不再等待 500ms 防抖，减少冷启动时 CarPlay 建栈前的固定等待。
- 后续尺寸变化（系统栏显隐、旋转、分屏、相机窗口等）仍保留 500ms 防抖，避免瞬时窗口抖动触发多次重建。

## USB 插入不再打断无线 CarPlay

- 新增「允许 USB 插入自动接管无线 CarPlay」开关，默认关闭。关闭时，已配置无线 CarPlay 的车机插 USB 充电只记录设备，不再切换到有线、不重建会话、也不请求 VPN 授权；「通过 USB 连接」仍按原方式主动使用有线。
- 冷启动与热插拔使用同一判定：直接读取已保存的无线偏好与手机配置，避免 `onCreate` 早于设置加载时误判为有线。
- 判定还会尊重「默认连接方式 = USB」，并且只保护真正配置过无线（已选手机）的用户，全新用户插 USB 仍能直接启动有线 CarPlay。
- 本次未改动 AirPlay 协议、CH341/MFi 设备路径与系统 USB 权限弹窗；取消授权后的自动回退与迟到回调仍留待后续。

## RFCOMM 与 iAP2 局部恢复（待实车验证）

- 与 #38 的 iAP2 阶段看门狗整合为一套超时来源：RFCOMM 建链 8 秒；CSM 就绪由看门狗 45 秒预算主导（覆盖实测 34.6 秒长尾，硬等待 50 秒兜底）；identification/MFi/订阅由 CSM→MFi 30 秒主导、硬兜底 35 秒，不再让握手借用长期控制循环的预算，也不让两套计时器同时关闭同一传输。
- 临时建链失败用新 socket 对同一目标重试一次（400ms 退避），仍失败才尝试唯一已连接的备用 iPhone；CSM／bootstrap 临时失败关闭旧 CSM 与 RFCOMM 后最多重建一次（800ms 退避）。三个恢复预算在本轮连接内共享，不按手机重置，最多创建 4 个 bootstrap socket。
- MFi 等待超时即使被 I/O／传输层包装，也沿 cause 链识别为可恢复并消耗一次局部重建；明确的手机认证拒绝（`MfiAuthenticationFailedException` / `retryable=false`）仍不重试。
- 每次尝试复核原热点、AirPlay listener 和 Bonjour；蓝牙关闭、权限缺失、目标解绑、明确协议／认证拒绝与无效蓝牙实现不盲目重试。局部恢复不重启热点或 Bonjour，不重置 first-TCP 计时；StartSession 发送或 Wi-Fi handoff 开始后不再局部重建。
- 为 bootstrap 增加独立 attempt token，屏蔽旧握手、状态、封面与假蓝牙探测回调；连接取消及时关闭 socket，迟到成功不得复活旧连接。预算与车测清单见 [RFCOMM 局部恢复](docs/RFCOMM_LOCAL_RECOVERY.zh-CN.md)。

## Release 自动构建

- 发布 GitHub Release 后先运行仓库检查，再使用 Actions Secrets 中的现有平台密钥签名，上传 APK 与 SHA-256 校验文件；手动触发时提供 Actions 构建产物。
- 自动发布包不包含 MFi 认证文件。覆盖安装可复用应用私有目录中的有效认证；全新安装或清除数据后需另行提供认证。版本号升至 122，避免低于已安装的 v121。

## 上游更新检查

- 暂停上游 DiPlay 版本检查：隐藏手动检查与后台开关、停止显示缓存的更新提示，并取消已排队的后台检查，避免本分支提示安装上游 APK。

## 移除 Compose 依赖

- 全项目唯一使用 Compose 的是板级 I2C 诊断页 `MainActivity`（`exported=false`，无任何入口可到达）。该页改为与其他页面一致的手写 View 实现，配色复用 `DiPlayPalette`，昼夜跟随 `AppAppearanceRuntime`。
- `common`、`mobile`、`automotive` 移除 Compose 编译插件、`buildFeatures.compose` 与 Runtime/UI/Material3 依赖，`ui/theme/` 模板包删除。
- Release 构建未启用 R8 裁剪，Compose 此前约占 APK 内 dex 类数与代码量的三成；移除后 APK 显著减小、编译期少一个编译器插件，车机安装与 dexopt 时间同步下降。

## 导航诊断：MQTT 观测（#24）

- 诊断报告新增导航 MQTT 观测：用独立只读会话订阅 12 个 `dim/*` 与 `ihu/*` 导航主题，按主题记录条数、距上次的毫秒数、是否 retained 与最后一条摘要。它只订阅、不发任何导航命令，用来区分「原厂服务还在写」和「本端没发出去」。
- 逐条 MQTT 日志与导航明细跟随调试开关，并对日志量设上限；普通模式只保留每主题汇总，报告不会被逐帧日志撑大。

## 无 ADB 与平台权限（v114，2026-11-02，待实车验收）

- 正式功能不再需要 ADB：应用内 ADB 客户端、密钥管理、5555 连接与授权流程，以及功能所用的 shell helper 全部删除。开机打开应用、热点读写与启停、日志读取、方向盘按键、USB 与地图权限改为平台权限、系统设置入口或明确失败，不回退 ADB，也不要求先连电脑。
- Manifest 新增 `WRITE_SECURE_SETTINGS`、`MANAGE_APP_OPS_MODES`、`MANAGE_USB`、`CONTROL_VPN`、`CONNECTIVITY_INTERNAL` 五项；实车截图已回读全部授予，接口实际效果与完整重启仍待验收。
- 设置中新增只读「权限检查」页，启动时记录授予结果；`GET_USAGE_STATS` 这类 AppOp 仍如实显示未授予，不代替用户在系统里开关。
- 已从 `d18a1b58` 构建 v114 Release（`0.2-car-test-noadb-v114`），Release Lint、Android 9 验签、zipalign、ARM ABI、五项权限声明及 ADB 客户端缺失检查通过。清单与待验收项见[权限与无 ADB 实施清单](docs/ADB_AND_PLATFORM_PERMISSIONS.zh-CN.md)。

## 导航输出生命周期与 ETA（#22／#26）

- 路线归属与已发布输出拆成两个状态：仪表地图显隐、HUD 提示开关、原厂服务恢复各自判断，关掉某一路输出不再被当成整条路线结束。
- 地图显隐与引导解耦：路线进行中只撤下地图源，导航提示与车道继续；OEM 在显隐回调里改主意时保留独立地图，不整条撤销。
- ETA 改为剩余秒数。此前按 epoch 秒下发，车机 `updateEta` 再叠加当前时刻，仪表显示的时间不可用；未知 ETA 不再发 0 冒充有效值。
- 恢复「HUD／仪表导航提示」与「开始导航时自动显示仪表地图」两个开关。后者只决定每条新路线的初始显隐，仪表上的显隐操作优先，更改从下一条路线生效。
- 计划性恢复改为绕过 SDK 去重缓存重放当前转向。本项取代 #25 引入的 DIM 直连重放，该路径已在 #26 revert。

## 仪表地图与显隐（#14／#16／#27，2026-11-01 至 11-03，待实车验证）

- 路线初始隐藏地图时，延迟到首次显示请求才创建仪表窗口、绑定视频 Surface 并启动地图绘制，避免关闭自动显示后仍提前出现地图；HUD 提示继续输出，首次展开仍等待地图首帧。
- 仪表显隐选择在同一次 DiPlay 运行内跨路线保留；退出应用后，下次启动重新按保存的初始地图开关决定。
- 冷启动默认允许投送，之后跟随仪表／三指显隐；页面重建与同进程重连保留本次意图。
- 仪表退出接入手机 TurnByTurn 仲裁，等手机回报路线结束后再清原厂导航与车道，不再按本地超时直接清场。
- 仪表布局变化或 OEM 可见性回调后重新取回地图窗口，一次显隐回调不再丢掉整条路线的独立地图。
- 三指飞屏按原厂投影选项的实际取值判断，不再假设固定方向。

## 蓝牙恢复与导航事件（#17／#18／#19）

- 蓝牙媒体让位按手机地址归一大小写后单独跟踪，重连后恢复当前手机的优先级，不再回落到上一台手机。
- 导航事件跨投送重建恢复引导，手机明确终态时关闭已结束的路线，避免重建后停在过期转向。
- 麦克风 GKUI Policy 生命周期补齐，见下节。

## 热点权限与标准连接（2026-11-02，待实车验证）

- 开机打开应用无需 ADB；手动与自动热点统一识别已有系统热点权限。
- 删除“自动加入热点”实验修复、专用 ADB helper 及资源，使用系统热点接口与标准连接流程。

## 麦克风 Policy 生命周期补充（待实车验证）

- 通话录音补齐 GKUI Policy 初始化、服务就绪、授权回调、撤回/恢复和取消释放；Siri 保持原采集入口。
- SDK 不可用、拒绝或初始化/首次授权超时时保留直接录音回退。本车 dump 的 OpenAPI 音频策略服务明确不支持麦克风仲裁，不能把客户端流程补齐视为本车仲裁已可用；依据和 PR #13 前后行为见 [SDK 上下文](docs/ECARX_SDK_CONTEXT.zh-CN.md)。

## #14 本地实现（2026-11-02，待实车验证）

- 每次进程冷启动默认允许仪表地图投送，之后跟随仪表／三指显隐；页面重建与同进程重连保留本次意图。旧「仪表投送」开关退役，改为「开始导航时自动显示仪表地图」，只管每条新路线的初始显隐。
- 导航与车道按有效会话自动输出，忽略旧版关闭偏好；亿咖通导航卡片仅保留桌面地图选项，安全区校准移至高级「仪表显示」。（#26 起提示开关恢复为可见设置，沿用旧 key `dim_navigation_enabled`。）
- 地图关闭后通过 SDK 公开接口以原静音值重申当前导航状态，并补发有效车道；不使用结束再开始导航的方式修复显隐。
- 「整屏镜像到仪表」位于高级设置，默认关闭，使用主视频流的独立镜像输出，重新连接后生效；普通导航继续使用独立仪表地图。
- USB 权限测试使用独立外部包身份发送广播，验证 iPhone 与 CH341 两种接收器均拒绝无权限发送者。

## 新增

- **CarPlay 日夜模式「跟随车辆」**：优先使用车机 AdaptAPI `IDayNightMode` 日夜推送
  （含原厂自动模式下的光感结果），不可用时跟随车机版高德的日夜广播，再退回 Android 系统。
- **仪表流随会话存活**：集群画面移入 retained AirPlay session，Activity 后台或销毁不再带走仪表。
- **启动陈旧投屏恢复**：冷启动和进入设置时释放上一次遗留的仪表投屏。
- **热点**：手动热点控制与 Android 9 热点状态检查；自动启动优先 5 GHz（写 `apBand` 并回读校验，
  保留 SSID、密码与加密方式，尊重显式选择的 2.4 GHz）。
- **媒体后端可选**：权限门控的 ECARX MediaCenter 传输，UNI / DIM / 标准 HY 三种后端。
- **仪表内容**：车道引导、封面卡片、可编辑可见区。
- **车道引导**：按指导索引缓存提前到达的数据，并按手机显示状态隐藏或恢复；实车 v108 仅验证隐藏路径，显示恢复及多分支高亮仍待验证。
- **诊断**：ACL 唤醒源与重试决策追踪、麦克风真实来源与采集统计、无 ADB 车测报告、
  设置操作与车机能力记录。
- **HY 后台 Siri 授权**：方向盘按键的后台监听依赖 `READ_LOGS`，本应用为平台签名，安装即授予；授权状态可在**设置 → 诊断 → 权限检查**确认，重新连接 CarPlay 后生效。原先通过本机 ADB 尝试授权的入口已随无 ADB 改造移除。
- 可选的导航提示音焦点、通话采样率和桌面任务兼容设置，默认保持关闭。

## 变更（含破坏性）

- **简化媒体与 Siri 设置**：音频页统一选择车机媒体集成，移除独立仪表媒体开关；车辆页新增方向盘唤醒 Siri 总开关，直接接管模式选择短按／长按，HY 模式选择 155/156 或 312/303。保留显式 Siri 事件和桥接入口，不受按键总开关影响。学习和双击参数不再显示，蓝牙音频排错选项归入声音异常排查。

- **默认媒体后端改为直连 ECARX 接管**。此前默认走 HY 代理；需要沿用 HY 代理或标准后端的，
  请在设置里显式选择。
- **应用语言收敛为英文 / 简体中文 / 繁体中文**。`values-ar`、`values-ja`、`values-ko`、
  `values-ru`、`values-uk`、`values-es` 等目录已删除，其他语言设备回落到英文；
  要恢复某语言需从上游重新并入，并补 ECARX 新增文案。
- **导航接管改为临时停用 `ecarx.naviservice`**，结束后恢复原包状态并启动原厂服务；移除 MQTT 距离纠偏及其兜底路径。停包可能删除原厂桌面的导航卡片绑定，恢复后需手工重加；停包或恢复失败须保留诊断。取舍与验收见 [导航接管决策](docs/NAVIGATION_TAKEOVER_DECISION.zh-CN.md)。
- **无线握手失败不再等满 5 分钟**：iPhone 拒绝 Wi-Fi 传输时 1 分钟内判定，并区分
  「iPhone 说不可用」与「iPhone 始终未发起传输」两种原因；只对可自动恢复的原因重试。
- **新增的「主画面镜像」通路未交付**：10-30 引入后当日移除（未通过验证）。
  它和设置里原有的「整屏镜像到仪表」不是一回事——后者仍然存在，关闭时不遮挡仪表箭头与媒体卡片。
  曾用过当日构建的，升级后请确认仪表不再出现多余的镜像页。
- `versionCode 101 → 114`。

## 修复

- 上游 v0.2.22 合并项：音乐时钟与缓冲、USB PCM 录音分包（每包不超链路 MTU）、有线重连、
  Android 8/9 无线断线检测、连接失败原因与首页最近一次问题、异步日志、报告脱敏、
  协议输入边界检查（AirPlay 接收缓冲上限、Bonjour 探测行数、bplist 越界、NCM NTB 解析边界）、
  USB 权限接收器、MFi 远端明文、data socket peer 校验。
- 上游安全项：备份服务、环境光锁文件、外部 intent extras、日志不写热点 SSID、脱敏遗漏、
  sample launcher 固定包名、BYD classpath 引用、map embed service、更新流程、usbmux 日志洪泛。
- ECARX 侧：导航时仪表距离被原厂改成 1 m；ETA 按 epoch 秒下发导致仪表时间错误；
  后台或切换应用后仪表黑屏；冷启动后仪表仍挂着上一次投屏；启动后蓝牙让位未恢复；
  手机换机或地址大小写变化后蓝牙让位回落到上一台手机；复合左掉头丢失车道；
  媒体有曲目无封面、同尺寸封面不刷新、自动 Play 重复触发；系统栏显示时画面被裁切或跳动；
  热点恢复后状态一直失败、过期配置回调覆盖新状态；USB 失败会话未关闭、插拔后无线偏好被改写。

## 保留与不做

- 保留 ECARX 仪表与多角度车道引导、UNI/DIM/标准媒体后端、蓝牙让位和 820A 麦克风仲裁。
- 保持英、简中、繁中范围；不恢复已删除的 BYD 功能，也不接入其他 Geely 机型的仪表路线。
- 每个适配功能的开启位置、前提、验证方法与已知边界，见
  [功能与使用说明](docs/FEATURES.zh-CN.md)。

## 发布状态

- **v114 Release**（`0.2-car-test-noadb-v114`，源码 `d18a1b58`）已构建，Release Lint、Android 9 验签、zipalign、ARM ABI、五项新增权限声明及 ADB 客户端缺失检查通过。APK 17,909,257 字节，SHA-256 `b22580ca8252cc226ddba0e82951bb1130e0bd45f46fcf0a3f99f05067c01d96`；包体与验收记录见[权限清单第 8 节](docs/ADB_AND_PLATFORM_PERMISSIONS.zh-CN.md#8-包体与验收记录)。
- 实车已回传权限检查截图（UID 10105、平台签名匹配，五项新增权限全部授予）。截图未显示应用版本与调试开关，完整重启、USB／地图权限、方向盘服务、Wi-Fi Direct 与热点调用仍待验收。
- 历史构建：v108 车测版（车道隐藏路径有实车证据）、v105 发布构建、`DiPlay-DEV-20261031-cartest-r2`。导航 v109 的回放记录在 [verification](docs/verification/navigation-v109/)。

---

# DiPlay ECARX 0.1 — 2026-10-24

领克 05 / 亿咖通 ECARX 移植版首版，基于上游 DiPlay **0.2.21** 源码。版本号 `0.1`
（versionCode **100**，高于上游同包名的 40，保证能覆盖升级）。

**发布的 APK 不含 MFi 身份**，装上能打开但连不上 iPhone；实车测试请自行把身份打进包，
步骤见 [README](README.md#如何把-mfi-身份打进-apk)。

## 仪表导航（本次核心）

- 仪表盘改用亿咖通 **DIM** 通道推 CarPlay 的转向箭头、剩余距离、路名与预计到达时间，替换上游的
  BYD HUD / DiLink 集群实现。箭头 id 映射来自 Apple 自有类型表与在本车上对 101–143 的实测。
- 仪表画面不再依赖车机镜像：本项目在 display 1 自建 `Presentation`，装 CarPlay **原生集群流**
  （1920×720，8:3），完美贴合异形仪表区域。
- **修正仪表投屏开关的语义**：这个开关不是"镜像开关"，而是"把 display 1 接到仪表"的通路——
  关掉会把 display 1 从仪表上摘掉（车机立刻盖回自己的仪表页），打开才显示该屏上的窗口。
  因此改为：导航开始时先上窗、再接通路；结束时先摘通路、再关窗；导航开始**不再抢仪表**，
  启动时也不恢复这个开关。
- 由此修掉三个连锁现象：导航开始仪表闪一下、必须手动开"仪表投屏"才显示、导航开始时 HUD 与
  仪表卡片的箭头/距离消失。
- 箭头改走整型 id 通道（`updateTurnByTurnArrow` 返回 `void`，调用成功不等于画出来了）。

## 仪表媒体卡片

- 曲名、歌手、专辑、播放状态推到仪表盘，与导航开关相互独立。
- 补上 `updateMediaSourceTypeList` 与 `updateMediaMuteState`：只调 `updateCurrentSourceType`
  会让车机"选中但不登记音乐源"，卡片一直是"无媒体"。

## 移除与清理

- **删除全部比亚迪专用代码**（BYD HUD 桥、DiLink 集群节点、BYD 车机专用分支），移植到此结束。
- **删除全部取证/探针代码**：MQTT 全量观察者（`ihu/#` 订阅与回显）、display 逐块转储、
  DIM surface/cevt 反射转储、媒体读次数计数、投屏主题对照观察。发布包里不再有这些字符串与线程。
- 投屏包名参数归零为 `""`：平台实现会丢弃前三个参数，传什么名字都无效，相关开关已删。
- **源码注释去掉对外部工具/本地文件的引用**：不再出现第三方工具 APK 的包名与 smali 路径、
  本地分析文档路径、机器上的绝对路径。取证结论本身保留，出处改写成「车机自带的亿咖通投屏工具」。
  纯注释改动，`classdiff` 复核 `removed=0 added=0`。

## 其它

- 车机蓝牙让位：CarPlay 期间切断 iPhone 与车机的经典蓝牙，结束后自动恢复（否则音频停在蓝牙通路）。
- 方向盘按键学习（媒体键 / 语音键 / 接听挂断）。
- 诊断报告保留仪表关键行：`Cluster panel: showing on …`、`DIM mirror left to the platform …`、
  `DIM guidance started arrowId=…` / `DIM arrow=… arrowId=…`、`presentation switch -> …`、
  `media counters media=… sourceType=… pushed=…`。

## 验证状态

实车通过（2026-10-24，领克 05 实车）：无线连接与投屏、仪表导航（箭头/距离/路名/ETA）、HUD、
仪表媒体卡片、导航结束交还仪表、蓝牙让位与恢复。

**未验证、不保证**：USB 有线连接、麦克风上行音质、方向盘按键逐键行为、其它亿咖通车型、iOS 版本跨度。

---

> 以下为上游 DiPlay 的变更历史，本移植版基于 **0.2.21**。上游面向比亚迪 DiLink，
> 其中的 BYD 相关条目（HUD、DiLink 集群、车机热点等）在本移植版里已删除或替换。

# DiPlay 0.2.21 — 2026-10-17

- Fix the 0.2.20 music lag by counting the music still waiting to be decoded in the clock adjustment, and bring music back after an interruption when the iPhone plays again (#814, #804).
- Let wired CarPlay recover by itself: retry when the iPhone never opens its session, stop waiting forever for an ignored mode request, keep an attempt that recovered before its retry, and lower navigation guidance in the audio itself (#907, #841, #876).
- Stop DiPlay closing when the software Opus encoder fails a check, name the wireless startup failure and refused permissions on the CarPlay screen, stop re-asking about the car hotspot on Android 13, find car hotspots on VLAN interfaces, wait for Bluetooth that is turning on, and save the CarPlay menu settings when an unused hotspot is empty (#866, #824, #795, #893, #885, #822, #853).
- On BYD head units, hand the dashboard back to the car when CarPlay ends, survive the DiYou desktop card, warn again about a car hotspot that stays off, open BYD's own screens on DiLink 1.0, hide the call popup on Android 10+, clarify the call key setting and offer DiLink 7.0's split screen (#909, #827, #794, #845, #842, #875).
- Install on Android 7.0, add Italian, and add options that are off by default: temporary system bars, black rounded corners, a play/pause wheel key, double press for Siri, car hotspot import, a home-screen bubble, the car's own music rhythm and music data for it (#847, #863, #818, #811, #823, #813, #769, #844, #695, #908).
- Record more in the diagnostic report: DiPlay's own frames in the crash record, the USB devices Android lists while waiting for the iPhone, a wheel-speed line each minute, and the USB network traffic when the iPhone never opens CarPlay; note screenshots under screen turning (#827, #830, #862, #907, #864).

See [0.2.21 release notes](docs/RELEASE-NOTES-0.2.21.md) for contribution links and limits, and [validation](docs/VALIDATION.md) for checks. Full-release vehicle acceptance is not claimed.

# DiPlay 0.2.20 — 2026-10-15

- Keep music playing when a head unit's audio clock drifts by adjusting its playback rate, run DiPlay's audio threads at audio priority, and keep separate music buffers for wireless and USB (#788, #740, #746).
- Play calls that a head unit sends to its earpiece on the loudspeaker, add Calls on the loudspeaker for phone and tablet head units, open the microphone for app voice messages, open Siri once per press of BYD's voice key, and record calls in the car's phone audio state from the voice-recognition source (#797, #704, #698, #774, #786, #770).
- Start wired CarPlay when a USB audio driver holds the iPhone, within seconds after Save and reconnect, after a VERSION reply that follows a reply trailer, and on Android 17 (#699, #714, #744, #708).
- Turn the car hotspot on through the system shell, or without ADB on head units that aren't BYD on Android 8.0–10; let Connect in the "Car hotspot is off" dialog connect; add a switch for the Wi-Fi Direct scan pause; and skip 5 GHz Wi-Fi Direct channels on head units without that band (#699, #748, #775, #777, #789, #768, #740).
- Find the dashboard cluster on controller 13 and 17 firmware and the Leopard 5 Ultra, hide the DiLink 4 cluster routes on DiLink 3, fall back from HEVC to H.264 without a hardware decoder, show CarPlay with FFmpeg-backed decoders, and group the settings menu inside CarPlay by goal (#714, #734, #707, #710).
- Record DiPlay's last crash, the wheel and media keys it received, the car Bluetooth pause, app-call vocoder info, music rebuffers and the USB devices listed while waiting for a CH341 adapter in the diagnostic report (#700, #702, #788, #740, #759, #793).

See [0.2.20 release notes](docs/RELEASE-NOTES-0.2.20.md) for contribution links and limits, and [validation](docs/VALIDATION.md) for checks. Full-release vehicle acceptance is not claimed.

# DiPlay 0.2.19 — 2026-10-12

- Start wired CarPlay several seconds sooner by not waiting 2 s on each refused USB network write, ask for CarPlay mode first on iOS 26, grant USB access through approved ADB on Android 7.1–9 head units without a USB permission page, and let Auto-confirm USB permission find the system dialog (#644, #689).
- Report the playback position of 16 kHz calls, Siri and navigation prompts at their own rate instead of three times too fast, restart a microphone whose first read fails, and stop background music underruns from a 0.5 s audio wait (#644).
- Keep CarPlay from restarting at the first touch with the DiLink 4 dashboard map on and from staying scaled down after the reversing camera, show the last picture when a SurfaceView output comes back, and redesign the in-CarPlay settings menu (#663, #674, #669, #642).
- Japanese, complete Traditional Chinese with guides, and 67 more Arabic strings (#646, #691, #682, #681, #631).
- Download the diagnostic report on a phone by QR code or a typed address and code, keep the phone page answering behind idle connections, show connection recovery tips and a Bluetooth-off message, and fix wireless CarPlay on MIUI 13 (#686, #687, #685, #677, #679, #673).
- Set BYD navigation audio defaults on a first setup, add an optional album-artwork card on the dashboard map, record why the car hotspot could not be started, and fall back to the default Bluetooth adapter (#656, #660, #667, #641).

See [0.2.19 release notes](docs/RELEASE-NOTES-0.2.19.md) for contribution links and limits, and [validation](docs/VALIDATION.md) for checks. Full-release vehicle acceptance is not claimed.

# DiPlay 0.2.18 — 2026-10-11

- Keep wired sessions running when a USB read finishes as DiPlay stops waiting for it, start wired CarPlay on Android 7.1 head units, when a head-unit USB audio driver holds the iPhone, and without a VPN dialog over approved network ADB (#625, #614, #618).
- Stop discarding music packets after a 10 ms wait, and log the /feedback position against the received audio (#618, #625).
- Show the real connection stage in every app language, stop with a clear cause when a head unit's Bluetooth cannot reach the iPhone, and find the connected iPhone through sink-role Bluetooth profiles (#624, #590, #585).
- Wheel next/previous on the CarPlay screen without the wheel-key service, Geely/Lynk `geely_fx11` wheel keys and Siri, wheel-key service restore retries, and next/previous to answer or decline with CarPlay call keys (#600, #626, #622, #625).
- Settings backup and restore from a phone, an installer choice for updates, and About inside Settings (#598, #593, #597).
- Experimental, off-by-default options: Harman full screen with 32-bit x86 libraries, square corners, clearer navigation voice over USB, MediaTek low-latency decoding, a media-key audio focus opt-out, car phone audio for calls, a cluster speedometer and FCE cluster guidance (#613, #596, #622, #608, #615, #595, #607, #599).

See [0.2.18 release notes](docs/RELEASE-NOTES-0.2.18.md) for contribution links and limits, and [validation](docs/VALIDATION.md) for checks. Full-release vehicle acceptance is not claimed.

# DiPlay 0.2.17 — 2026-10-10

- Keep wired sessions running after a damaged NCM block, retry identification without unsupported CarPlay messages for older iOS, recognize the Android 10+ and Chinese USB permission prompts, explain refused wired mode switches and retry NCM interface claims (#575).
- Add an iPhone charging choice for wired USB: Normal 2.4 A, Reduced 1.5 A or Low 0.5 A (#583).
- Recommend the built-in car hotspot, and pause the car's Wi-Fi network search during Wi-Fi Direct on Android 7.1 and later when network ADB is approved (#570).
- Record the head-unit microphone for app voice notes and clock Opus 16 kHz microphone packets at 16 kHz (#548, #547).
- Check daily for updates with an Update available notice, recognize the DiLink generation from the controller version, and save Android 9 reports to Download/DiPlay (#488, #531, #541).
- Add album-cover ambient lighting and fix saving ambient settings on hotfix firmware; add experimental BYD call popup hiding and external controller keys (#537, #550, #539, #549).

See [0.2.17 release notes](docs/RELEASE-NOTES-0.2.17.md) for contribution links and limits, and [validation](docs/VALIDATION.md) for checks. Full-release vehicle acceptance is not claimed.

# DiPlay 0.2.16 — 2026-10-09

- Send the Siri and call microphone on Android 7.1–9 head units without an Opus encoder through a bundled software Opus encoder, and offer Opus only when it can be encoded; accepted on a BOS Mini A1 (Android 9) with an iPhone 12 on iOS 27 (#468, #483).
- Fix wired NCM receive framing and Android 8 USB reads, and retry rejected large USB reads at smaller sizes (#478, #495).
- Refresh hotspot addresses after a first wireless timeout, request Android 17 local-network permission, handle missing VPN authorization screens and add WPA3 car-hotspot security (#474, #465, #517, #501).
- Add experimental Low-latency decoding and Direct video output, an FPS counter, a hardware low-latency Smooth video decoder and bounded video backlog recovery (#496, #456, #347).
- Add inline Settings search, confirm before the quick menu discards staged changes, allow turning off the swipe-down gesture, and refine Settings layout and reconnect prompts (#450, #500, #487, #486, #482, #490, #491).
- Place the dashboard car marker with 1% sliders, give the small-window turn card its own placement, theme the cluster waiting screen, show the full DiLink 3 arrival time, declare initial appearance and add side-panel resizing (#494, #493, #481, #458, #346, #480).
- Add experimental, off-by-default car Bluetooth pause, music-following ambient lighting, navigation wheel volume and a Platform 21 instrument route; refine call echo alignment, buffered-audio pacing and audio diagnostics (#307, #345, #344, #348, #421, #484, #499).

See [0.2.16 release notes](docs/RELEASE-NOTES-0.2.16.md) for contribution links and limits, and [validation](docs/VALIDATION.md) for checks. Full-release vehicle acceptance is not claimed.

# DiPlay 0.2.15 — 2026-10-08

- Lower the minimum to Android 7.1/API 25 with compatibility fallbacks; Android 7.1–8.1 vehicle validation remains pending (#407).
- Add the first-launch DiLink setup guide and manual GitHub update checks in About (#416, #413).
- Add Light/Dark/Auto app appearance and refine compact layouts, fullscreen handling, Language and About navigation (#422, #420).
- Recover the music prebuffer after underruns, including corrected retained-audio accounting, and supply a main-decoder operating-rate hint with fallback (#401, #417).
- Add Wi-Fi Direct automatic band choices and optional selected-iPhone Bluetooth launch; experimental Bluetooth audio remains off by default (#432, #439).
- Correct square-canvas physical dimensions, refine turn-card placement to 1% steps and add optional small-window marker layouts with experimental Auto following (#404, #440, #438).
- Add explicit experimental ADB boot-start repair and correct Ukrainian wording (#441, #399).

See [0.2.15 release notes](docs/RELEASE-NOTES-0.2.15.md) for contribution links and limits, and [validation](docs/VALIDATION.md) for checks. Full-release vehicle acceptance is not claimed.

# DiPlay 0.2.14 — 2026-10-07

- Group Settings by driver goal with search, adaptive layouts, quick controls and reconnect notices (#369).
- Add an independent Interface size control, preserving CarPlay geometry and following real density/window changes (#378).
- Add a default automatic-connection choice, scheduled day/night mode and launcher return to active CarPlay (#373, #365, #376).
- Improve custom car-button image selection and clarify the main app's source-build instructions (#364, #377, #374).
- Repair the captured USBMUX diagnostic trailer, wake drained Bluetooth receive queues and retry explicitly rejected large USB reads once at 16 KiB; improve stream diagnostics (#362).
- Select compatible video output without window hardware acceleration; use Android video decryption when available and retain a fallback (#362, #332).
- Add default-off experimental Smooth video pacing with explicit latency/picture-adjustment limits (#367).
- Preserve usable hotspot interface addresses and authenticated Bluetooth control on the observed rendered-session handoff fallback (#394, #396).
- Add configurable steering-wheel Siri, bounded microphone-source fallback and optional audio-focus handling (#360, #371, #339).
- Repair BYD call-watcher/audio-status behavior and add opt-in, default-off experimental software call echo cancellation and voice filtering (#370).
- Preserve artwork proportions, correct system-bar-aware split/rotation geometry and recognize the specifically observed 1280×480 DiLink 3 cluster surface (#375, #389, #388).

See [0.2.14 release notes](docs/RELEASE-NOTES-0.2.14.md) for all 20 contribution links, credits and limitations. Android 9/API 28 remains required. See [validation](docs/VALIDATION.md) for measured checks; complete-release vehicle acceptance is not claimed.

# DiPlay 0.2.13 — 2026-10-06

- Enable the legacy Android 9 Wi-Fi Direct group path with generated credentials and serialized ownership/cleanup; requested frequency remains unverified on Android 9 (#282).
- Prefer current IPv4 hotspot endpoints, preserve scoped IPv6 fallback, and refine Auto channel priorities beside a 5 GHz station without guaranteeing a band (#283, #309).
- Avoid unused Android NSD and USB-service dependencies for wireless startup; retain only rendered sessions on the guarded handoff fallback (#313, #300, #258).
- Add explicit experimental hotspot join Check/Apply/Restore on eligible Android 13+, user-configured 5 GHz hotspots, with complete private recovery state and no automatic mutation (#251).
- Switch wireless to USB within the host activity, verify requested permissions independently, preserve other accessibility services and cancel stale permission work (#268).
- Preserve validated USBMUX payload replies with narrow four-byte trailer recovery (#298).
- Add optional live dock/split-screen areas, square-canvas screen rotation and the selected-decoder square check; add a default-off experimental side panel (#246, #277, #284, #245).
- Preserve DiLink 4 native casting mode, offer pre-connection calibration and apply live cluster picture adjustments (#260, #265).
- Retain a recent dashboard turn card only across wireless replacement within its stale window; add a Smaller map choice (125%) and the checked DiLink 3 full-then-half projection sequence with compensation (#304, #306, #296).
- Restore battery reads when only sys.car.protocol is populated and recover eligible unbound wheel services using already-authorized ADB (#285, #297).
- Add independent default-off experimental DiLink 3 call keys and dashboard calls, with initialized watcher readiness, unique ownership, pristine-safe cancellation and retryable dirty cleanup; target-car acceptance remains requested (#243).
- Correct the observed 24 kHz Siri microphone RTP clock while retaining 48 kHz for telephony/unobserved formats; add bounded, default-off experimental AAC-LC buffered music and single-session renderer ownership (#295, #308).
- Set TCP_NODELAY on the touch event channel; contributor latency observations remain device-specific (#311).
- Improve full-size multi-window home/settings appearance, ambient-setting visibility and shared menu persistence; add main-settings car-button customization (#252, #239, #281, #302).
- Add light waiting/cluster placeholders and a 300 ms cluster fade; the main waiting screen follows CarPlay day/night mode (#305, #317).
- Add Traditional Chinese (Taiwan) as the seventh app language, preserve explicit script selection and correct Simplified Chinese hotspot wording (#314, #286). The release website also gains a Traditional Chinese edition.
- Retain the multilingual website groundwork, add the smooth-wireless guide and make buffered-ownership tests deterministic without runtime/API changes (#240, #310, #312).

See [0.2.13 release notes](docs/RELEASE-NOTES-0.2.13.md) for all 35 contribution links, credits, experimental settings, compatibility limits and diagnostic export steps. Final exact-release validation is recorded in [VALIDATION](docs/VALIDATION.md). This remains a public preview; no fresh complete-release vehicle test is claimed.

# DiPlay 0.2.12 — 2026-10-04

- Add Existing Wi-Fi / Same LAN wireless CarPlay with scoped IPv4/IPv6 discovery and network-change cleanup (#223).
- Wait for a stable car-hotspot interface and recover bounded wireless attempts when no AirPlay TCP follows StartSession (#229); add observed-state, authorized-ADB hotspot fallback on firmware exposing supported commands (#235).
- Improve Apple USB attach matching and narrowly scoped optional USB-prompt assistance (#170, #224).
- Pause Android 10 station scans during eligible hotspot/P2P sessions, preserving Same LAN, with controller leases and durable retryable restoration (#225).
- Improve split-screen, launcher cards, short-screen preparation and virtual cluster/floating-map geometry (#171, #172, #181).
- Add independent system-bar controls and correct in-session save/cancel and Local/USB-CH341 authentication selection (#191, #194).
- Add system, light-sensor, day and night CarPlay appearance modes, richer custom turn cards, and live main-video picture controls (#178, #193, #211).
- Offer custom integer resolution from 30% to 160%, with shared limits, correct 30%/160% labels and decoder/canvas capability fallback; refresh connection settings on resume (#179, #230, #196).
- Reconcile opt-in DiLink 4 cluster routing/calibration into one decoder owner, retain verified HUD gates, and journal exact stock-map holds and recovery (#213, #187).
- Add DiLink 3 guidance text and projection-display support with committed recovery before mutation, partial-setup compensation and retryable stock restoration (#182).
- Add opt-in wheel map zoom and main-screen joystick while preserving press/release and call behavior; reject stale queued work across phone/screen changes (#214, #231).
- Switch supported dashboard contents live using actual delivery and safely retained paused choices; preserve selection across stream/phone replacement (#232).
- Add a five-second dashboard-song-on-change window with timer invalidation, and retain album art while the next transfer is pending (#215, #228).
- Export reports through Downloads, document picker, app-external or private fallback storage, with explicit View/Share actions (#185, #219).

See [0.2.12 release notes](docs/RELEASE-NOTES-0.2.12.md) for the complete corrections, hardware evidence and issue-reporting steps. This remains a public preview; no fresh end-to-end vehicle test of the complete repaired release is claimed.

# DiPlay 0.2.11 — 2026-10-03

- Add preferred Wi-Fi Direct channel selection for the next connection; Auto remains the default, and manual channel rejection/mismatch reports an error (#175).
- Add a movable custom dashboard turn card with 2% position steps; leave unknown arrows blank and clear expired guidance (#155).
- Offer two-, three- or four-finger settings swipes, keeping three as the default (#156).
- Add opt-in read-only legacy vehicle-data detection while preserving default DiLink 5.0 mode; reject unaccepted/stale probe publication and fix accepted-battery lock ordering (#158, integrated through #173).
- Keep wireless location/vehicle data on its runtime Wi-Fi link and defer parked-video decisions until SETUP/event readiness (#157).
- Add optional automatic car-hotspot startup, off by default, with verified own-package authorization and unified vehicle settings (#164/#173).
- Support Android TV/remote controls while preserving head-unit touchscreen Back and absolute knob X/Y; keep its workflow source-only (#146).
- Retain artists across partial title updates and publish song metadata/artwork only when changed (#161, #162).
- Correct Android 9 audio API use, release failed codecs, reconnect with settled geometry/readiness, and require own-app VPN scope (#168 and local corrections).
- Add one guarded Auto-mode API 29 P2P recovery for the exact reported pre-create Builder error, plus bounded wireless/media/theme/own-app-exit diagnostics without payload recording or automatic uploads.

See [0.2.11 release notes](docs/RELEASE-NOTES-0.2.11.md) for requirements, device-test limits and fresh-report guidance. Preferred channel selection does not establish stutter as fixed; Qin Plus, Siri, iOS 15 and day/night reports remain under investigation. Android 9 is still the minimum; Wi-Fi Direct needs Android 10+.

# DiPlay 0.2.10 — 2026-10-03

- Publish CarPlay song metadata, position and artwork to Android media sessions; bound artwork queues and reject stale work across sessions (#82).
- Preserve normal USBMUX frames while handling narrowly validated handshake padding (#114); let USB connect without saved wireless-hotspot credentials (#130).
- Handle unknown reported Wi-Fi Direct security types, retry busy channels and allow bounded 5 GHz fallback (#121).
- Select an available AirPlay port and advertise it over Bonjour and wired/wireless iAP2; close sockets on failed setup/notification (#143).
- Enable available platform echo cancellation and noise suppression for calls, restoring the previous mode afterward (#116).
- Detect BYD CAN/CANFD battery protocols and clear unsupported/stale readings (#123).
- Add a saved show/hide setting for the home-screen dashboard-map mirror (#133).
- Improve optional parked video with seeking and ten-second skip controls; validate media URLs and redirects (#129).
- Extend Ukrainian translations, including the new map-mirror setting (#128 and release localization).
- Add bounded anonymous Bluetooth/USB/boot and microphone capture/encode/send diagnostics to exported reports; omit audio and packet contents.

See [0.2.10 release notes](docs/RELEASE-NOTES-0.2.10.md) for contributor credits, requirements and validation limits. Android 9 remains the minimum supported version.

# DiPlay 0.2.9 — 2026-10-02

- Follow BYD head-unit day/night changes while CarPlay is visible, including firmware that does not reliably deliver Android configuration callbacks.
- Restore media and navigation audio stream selection to 0–20 and inherit older saved navigation settings when no new selection exists. Vendor-specific outputs depend on head-unit support.
- Keep CarPlay connected through normal surround-view window changes, preserving video proportions and touch alignment. A connection started in a narrow camera window reconnects once when the window grows to restore the full-screen canvas.
- Add Ukrainian to the app language picker, Android app-language settings, and website. Correct its audio help to describe streams 1–20.
- Add an optional CarPlay song title, artist, and play/pause display on the BYD instrument cluster, using the existing network ADB connection.
- Add a CarPlay navigation widget for launchers that host standard Android widgets, with the next turn, road, distance, arrival information, and song. Clear expired guidance and explicitly cleared song titles.
- Add an optional floating copy of the dashboard map on the centre screen, with drag, pinch-to-resize, and tap-to-open controls. Requires permission to draw over other apps; Usage Access restricts it to home screens.
- Fix floating-map resizing on head units that ignore small pinch gestures.
- Let compatible launchers embed the live dashboard map on Android 11 and newer. Sharing is off by default; turning it off closes existing shared map views.
- Add map-host and DiPlay Home sample apps for developers. DiPlay Home combines the live map, standard Android widgets, a clock, and an app list; sample builds, lint, and Home back-navigation tests are checked in CI.
- Leave the GPS course empty when its direction is unknown, instead of reporting north. Valid GPS directions are preserved.
- Thanks to @lpcheng1208 for PRs [#71](https://github.com/shihabal3amri/DiPlay/pull/71), [#88](https://github.com/shihabal3amri/DiPlay/pull/88), and [#89](https://github.com/shihabal3amri/DiPlay/pull/89).
- Thanks to @romanchukg-cloud for PRs [#93](https://github.com/shihabal3amri/DiPlay/pull/93), [#101](https://github.com/shihabal3amri/DiPlay/pull/101), [#105](https://github.com/shihabal3amri/DiPlay/pull/105), [#106](https://github.com/shihabal3amri/DiPlay/pull/106), [#107](https://github.com/shihabal3amri/DiPlay/pull/107), [#108](https://github.com/shihabal3amri/DiPlay/pull/108), and [#109](https://github.com/shihabal3amri/DiPlay/pull/109).

See [0.2.9 release notes](docs/RELEASE-NOTES-0.2.9.md) for the merged changes and validation limits.

# DiPlay 0.2.8 — 2026-09-30

- Keep iPhone location reporting active across the wireless Bluetooth-to-Wi-Fi CarPlay handoff; limit location updates to one per second on wireless and USB.
- Add optional ADB wheel-speed and gear reporting for iPhone dead reckoning when GPS is unavailable. Tunnel use has not yet been verified.
- Add optional iOS 27 video playback on the car screen while parked, with iPhone, touchscreen and steering-wheel controls; close playback when leaving P.
- Explain unsupported DRM-protected video such as Apple TV+, which requires a licensed FairPlay receiver.
- Improve playback error reporting and preserve CarPlay when the head unit cannot play a video.

# DiPlay 0.2.7 — 2026-09-29

- App interface in English, Simplified Chinese, Arabic, Russian and Spanish; synchronized Android app-language settings.
- Steering-wheel media controls and long-press Siri on supported BYD firmware while CarPlay is on screen.
- Dashboard display choices: map, turn card, or both; corrected dashboard keyframe recovery.
- Optional ADB feature on supported DiLink 5.0: pause the dashboard map stream when its display mode hides the map.
- Optional ADB battery reporting for Apple Maps, with warning threshold, charging-connector selection and a checked reconnect action.
- Audio playback reliability fixes and clearer dashboard settings.
- Clarify the BYD-only support scope on the README and all five website editions.

# 0.2.0 — BYD navigation and connection improvements

- Standalone windshield HUD arrows, distance and street names on the verified DiLink5.1 firmware; no ADB, root or computer helper.
- Retain contributor cluster/SOME-IP navigation, route parsing, BYD CarPlay icon and display-size presets.
- Fix Car hotspot startup by using scoped IPv6 when available and binding discovery/probing to the AP interface. Physically confirmed on the development car.
- Drain asynchronously decoded audio during packet gaps and rebuild the music buffer after starvation. Wi-Fi Direct is much better in the user retest; occasional audio cutouts remain for a later version.
- Preserve bounded music-buffer choices, USB read improvements and decoder recovery; fix USB request/close races and keep vendor output outside phone callbacks.
- Save audio/video/receive timing and discovery diagnostics without road names or protocol payloads.
- HUD cleanup on normal end/disconnect/off/stale input; interrupted sessions recover on the next app launch. Force-stop may leave guidance visible until reopening.
- Thanks to @romanchukg-cloud and @georgiyrr for PR #3 and vehicle testing.

# 0.1.0 release restored — 2026-09-25

- Rebuilt and signed the APK locally with explicitly supplied runtime authentication assets.
- Restored release downloads; no app behavior or version-code change from 0.1.0.
- Accessory identity remains in the APK only. No credential files enter Git or the source archive.
- Retained generated test identities and public-source credential checks.
- Source/CI builds omit runtime identity assets by default; local packaging requires an explicit external directory.

# Source reset — 2026-09-25

- Withdrew the 0.1.0 APK and removed its release tag.
- Reset the public branch after preserving restricted local incident records.
- Removed static synthetic test private keys; generate test identities at runtime.
- Removed automatic private-asset packaging and disabled the old release build script.
- Added a build guard rejecting credential asset files.
- Replaced the download site with a five-language suspension notice.

The APK was subsequently rebuilt and restored as described above. Existing copies cannot be recalled by a Git history reset.
