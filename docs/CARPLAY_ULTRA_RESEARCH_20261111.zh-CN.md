# CarPlay Ultra：带着四项实车问题复查公开资料

检索与核对日期：2026-11-11。第一轮检索 GitHub 源码、issues/comments、X/Twitter、Reddit、4PDA、XDA、MacRumors 和 Ars 论坛，固定版本、文件 blob SHA 和当日 issue 状态见[取证清单](evidence/carplay-ultra-community-20261111.json)。本次显示专项继续读取项目源码、公开发布包和本地主题样本，新增结果见第 6 节及[显示取证清单](evidence/carplay-ultra-display-20261111.json)。

车辆范围：领克 05 2020 / ECARX 753 / Qualcomm 820A / Android 9，仪表 1920×720、中控 1920×1080。沿用用户已经确认的实车基线：已测空调/座椅操作成功，除 RPM 外当时已测车辆数据取得；测试时主驾有通风硬件但没有接入；仪表下方空白、中控上方 8:3 下方黑区仍存在。本轮未重新连车。

实现更新：本次已在 `exp/carplay-ultra-bench` 增加主驾 SeatFan 声明、ECARX 通风档位读写/回读、模拟台架和针对性测试。[接入与复测说明](CARPLAY_ULTRA_BENCH.zh-CN.md#主驾座椅通风2026-11-11)记录具体映射。下文的“缺项”描述固定基线 `2cd54650`；当前代码已补齐，iPhone 控件展示及实车执行仍未验收。

**座椅通风映射已实现；显示专项进一步找到可读主题脚本、UI Sync 区域数据处理和 CarCast 本地渲染宿主的证据。两块屏幕的留白仍没有找到可直接采用、已在本车验证的修复。**

| 本车问题 | 新找到的证据 | 能支持到哪一步 |
| --- | --- | --- |
| 主驾通风缺失 | iOS 有独立 `CAFSeatFan`、座椅风量特征和 Climate 按钮类；旧基线只声明座椅加热服务 | 本次已实现映射并通过自动测试；尚未证明当前主题显示按钮或本车写入成功 |
| 仪表下方空白 | xcertplay #59 的提问者也报告 Ultra 已开启、仪表未适配；尚无修复回复 | 说明开启 Ultra 与完成仪表是两回事；不是本车下方空白的同症状复现 |
| 中控只占 8:3 | CarCast 的 Ultra 模式文档明确使用 1920×720 screens；其一般显示设置另列 1080p | 没有证明 Ultra 的现成主题可铺满 1080p，也不能反推协议只支持 8:3 |
| RPM 无有效值 | 另一 ECARX Android 9 项目明确把 RPM 列为未收到/无效数据，并停止读取 | 提供同厂平台的相似失败实例；不是本车数据源缺失的证明，更不是可用补丁 |

## 官方架构与本车显示结论

- Apple [发布公告](https://www.apple.com/newsroom/2025/05/carplay-ultra-the-next-generation-of-carplay-begins-rolling-out-today/)要求 iPhone 12 或更新机型、iOS 18.5 或更新版本；不据第三方项目的兼容声明修改官方门槛。
- Apple [架构视频 5:54](https://developer.apple.com/videos/play/wwdc2024/10111/?time=354)说明仪表的本地渲染；Remote UI、Local UI、Punch-through UI 和原车 Overlay UI 是不同组成部分。手机地图视频不等于完整的仪表运行时。本分支的模拟 Canvas 仪表也不是通用主题渲染器。
- Apple [设计视频 11:41](https://developer.apple.com/videos/play/wwdc2024/10112/?time=701)及 [13:08](https://developer.apple.com/videos/play/wwdc2024/10112/?time=788)展示可调组件、宽屏及不规则屏幕。不能把 8:3 当作全部 Ultra 的协议限制，也不能据此保证现成车型主题会响应任意分辨率声明。
- 既有车测中，1920×1080 的解码画布被接受，但表面亮度探针的内容范围只到约 66%；另一次 1920×720 贴顶显示的截图下方 360 行全黑。两者是不同场次，亮度探针也不是原始视频逐像素测量。当前证据支持“已测主题/配置未适配 16:9 满屏”，不足以单独隔离手机布局、接收端变换及原车合成的责任。
- 本地核对过四个目录返回主题包，两块屏的声明均为 1920×720；范围仅限当时的查询条件，不是所有主题的全量枚举。主题包、目录响应和原始车测资料不随本仓库分发；本项是本地核对结果摘要，不能作为远端可独立复跑的包级证明。

## 1. 通风：找到了独立 SeatFan，不必猜邻近编号

GitHub 上的 iOS 18.2 / 22C152 反编译仓库，固定提交 `e26ed4563f78871c59d2d96856756a65d62517e5`，有以下可逐项核对的实现：

| 项目 | 代码中的值/行为 | 原始来源 |
| --- | --- | --- |
| Seat accessory | `0x01500001` | [CAFSeat.m，第 200 行](https://github.com/EthanArbuckle/iPhone17-1_18.2_22C152_Restore/blob/e26ed4563f78871c59d2d96856756a65d62517e5/System/Library/PrivateFrameworks/CarAccessoryFramework.framework/CAFSeat.m#L200) |
| SeatFan service | `0x22000002` | [CAFSeatFan.m，第 300 行](https://github.com/EthanArbuckle/iPhone17-1_18.2_22C152_Restore/blob/e26ed4563f78871c59d2d96856756a65d62517e5/System/Library/PrivateFrameworks/CarAccessoryFramework.framework/CAFSeatFan.m#L300) |
| 风量 | `0x31000012`，`CAFUInt8Characteristic`，有 `setFanLevel` 和范围读取 | [CAFSeatFan.m，第 160 行](https://github.com/EthanArbuckle/iPhone17-1_18.2_22C152_Restore/blob/e26ed4563f78871c59d2d96856756a65d62517e5/System/Library/PrivateFrameworks/CarAccessoryFramework.framework/CAFSeatFan.m#L160) |
| 开关 | `0x30000002`，Bool；存在 `hasOn` 检查 | [同文件第 90 行](https://github.com/EthanArbuckle/iPhone17-1_18.2_22C152_Restore/blob/e26ed4563f78871c59d2d96856756a65d62517e5/System/Library/PrivateFrameworks/CarAccessoryFramework.framework/CAFSeatFan.m#L90) |
| 座椅位置 | `vehicleLayoutKey`，`0x36000065`；Seat 按该字段整理风扇服务 | [CAFSeat.m，第 174 行](https://github.com/EthanArbuckle/iPhone17-1_18.2_22C152_Restore/blob/e26ed4563f78871c59d2d96856756a65d62517e5/System/Library/PrivateFrameworks/CarAccessoryFramework.framework/CAFSeat.m#L174) |
| iOS 界面 | `ClimateSeatFanServiceButton` 接收风量及开关更新 | [按钮类第 58 行](https://github.com/EthanArbuckle/iPhone17-1_18.2_22C152_Restore/blob/e26ed4563f78871c59d2d96856756a65d62517e5/Applications/Climate.app/ClimateSeatFanServiceButton.m#L58) |

同一仓库的 [CAFSeatHeatingCooling](https://github.com/EthanArbuckle/iPhone17-1_18.2_22C152_Restore/blob/e26ed4563f78871c59d2d96856756a65d62517e5/System/Library/PrivateFrameworks/CarAccessoryFramework.framework/CAFSeatHeatingCooling.m) 则是另一服务 `0x22000001`，`heatingCoolingLevel` 为 `0x31000021` / Int32。**不能只因名称含 Cooling，就认定把现有加热范围改成负数能打开座椅通风；SeatFan 是更直接的独立候选。** 这里没有从相邻编号推测字段。

对照本地实验提交 `2cd54650`：`EcarxVehicleState.kt:242` 的 seats 只含 `0x22000001`；`:379` 的 1026/1030 都绑定 ECARX 加热 `0x10050200`，没有 SeatFan。当前 LIVI 的 [body.rs](https://github.com/f-io/LIVI/blob/870d26441e073cdb802c427c5ae8833dba694951/native/crates/livi-cp/src/vehicle_state/body.rs#L7) 同样只描述两侧加热，[telemetry.rs](https://github.com/f-io/LIVI/blob/870d26441e073cdb802c427c5ae8833dba694951/native/crates/livi-core-proto/src/telemetry.rs#L352) 甚至用测试明确拒绝未知的 `seatCooling` 输入；因此直接升级或换成 LIVI 不等于补上通风。

车辆侧，本地 753 SDK 的 `IHvac.java` 第 159、194–198 行提供 `HVAC_FUNC_SEAT_VENTILATION=0x10050100`，关闭值为 0，1–3 档分别为功能值加 1–3。这与现有加热是不同车辆功能。厂商 SDK 不随仓库分发；[ABI 测试](../shared/src/test/java/com/shilapi/xcertplay/hud/EcarxAdaptapiAbiTest.kt)会使用外部提供的 SDK JAR 核对这些常量。

本次代码据此单独声明主驾 SeatFan，采用 service iid 1033、风量 iid 1034、位置 iid 1036；主驾 zone 1 的 0–3 档映射沿用现有支持检查、枚举转换及回读通路。类型编号与实例 iid 分开，现有加热 iid 保留。下一步需要确认当前 iPhone 是否生成/订阅控件，并核对实车支持状态、主驾区域、实际风量与回读。

证据边界：这是旧版 iOS 的公开反编译实现，不是当前 MFi 正式规范；类和编号可核验，但尚无本车当前 iOS/主题的端到端通过记录。量产端另有[作者亲测 DB12 Volante 的文章](https://www.slashgear.com/1919080/new-apple-carplay-ultra-hands-on-test-first-impressions/)记录通过 Ultra 使用座椅通风和 Focus 模式，只支持“Ultra 体验中可以有通风”，不证明领克映射或上述具体服务已适配。Ars 上的相似体验也是 Michael Teo Van Runkle 所写，不计作第二位独立测试者。

## 2. 仪表：有人卡在这里，但没有现成解法

[xcertplay #59](https://github.com/shilapi/xcertplay/issues/59) 由 `autunn` 于 2026-10-26 提出。其 [11 月 9 日回复](https://github.com/shilapi/xcertplay/issues/59#issuecomment-6746262514)说明 Ultra 已开启、使用了阿斯顿资源，但仪表适配未完成。11 月 11 日的最新回复仍是其他用户索要思路；本轮读取的三条评论中，没有维护者给出完成方案。

这个帖子与我们“主题可取到、车辆应用能跑、完整仪表还缺”的阶段有参考意义，但提问者的“似乎需要修改系统包”是猜测。帖子没有说明与本车相同的下方空白、没有同场次原始帧/合成层证据，不能证明我们应改系统包或固定偏移若干像素。

[CarCast 作者的 Reddit 发布帖](https://www.reddit.com/r/cafari/comments/1xbgdgm/introducing_carcast_for_android_a_carplay_screen/)描述由接收端绘制 Ultra 仪表。评论里有 Galaxy Tab 黑屏、Xiaomi Pad 横屏半屏报告，但缺少与本车相同的流尺寸及图层数据，不能归为同一故障。CarCast 可作完整仪表画面的对照对象；闭源实现不能直接补成本分支的主题运行时。

仪表下方空白仍须以同场次原始视频、全屏校准图与最终仪表照片来定位，操作入口见[仪表坐标校准](CARPLAY_ULTRA_BENCH.zh-CN.md#仪表坐标校准)。校准图也缺下方时优先追查窗口/合成；校准图完整而视频留空时，再查视频内容和映射。公开帖子没有替我们完成这一步。

## 3. 中控：没有找到现成主题响应式铺满 16:9 的复现

[CarCast 的 iCarCast 指南，第 154 行](https://github.com/rPlayAI/carcast-public/blob/1d08ba65c4704b5f525ca78e237738e817f008b0/docs/icarcast-user-guide.md#L154)将 Ultra 标为实验性、模拟车辆数据，使用 1920×720 screens。第 187 行一般显示设置另有 1920×1080；两段不能拼成“Ultra 主题支持 1080p 满屏”的证明。

[LIVI #281 的维护者回复](https://github.com/f-io/LIVI/issues/281#issuecomment-4750638827)说明 View Area 可为本地覆盖内容腾空间；[另一回复](https://github.com/f-io/LIVI/issues/281#issuecomment-4750790333)讨论窗口缩放和 GStreamer sink。这是 2026-06-19 的旧显示通路讨论，不能作为当前主题动态重布局的证明，也不能直接用于 Android TextureView 的空白定位。

本轮没找到公开补丁/实测能够在相同主题下，把我们看到的顶部 720 行原生重排为 1080 行。此前本地四包的两屏 1920×720 字段与车测仍是更直接的证据；结论维持“当前主题/配置未适配”，不扩大成“Ultra 协议永远不支持 16:9”。

## 4. RPM：找到相似 ECARX 限制，没有找到本车可用来源

[Belgee X50 / ECARX IHU730P 项目 README](https://github.com/IgnatovichStanislav/belgee-x50-carhome-launcher/blob/017537a0059bcd3cc303cb8d8a18e6b1f1bcaebf/README.md#L93)明确记录 RPM 未收到或是无效读数。它运行 Android 9，但车型/车机是 IHU730P，不能与我们的领克 05 / 753 / 820A 等同。

其 [EcarxCarDataSource.kt:213](https://github.com/IgnatovichStanislav/belgee-x50-carhome-launcher/blob/017537a0059bcd3cc303cb8d8a18e6b1f1bcaebf/app/src/main/java/by/carhome/data/EcarxCarDataSource.kt#L213)写明不再读取 RPM 等信号；第 414 行仍列 `S_RPM=1050880`。本地 SDK 的 `ISensor.java` 也有该常量，但常量存在不等于数据被送到车机。本分支当前走的是 VHAL `291504901`，两条路径不能混称已经成功。

[CarCast #38](https://github.com/rPlayAI/carcast-public/issues/38)于 2026-11-07 请求通过 CAN/Wi-Fi/BLE/ESP32 注入车辆遥测；本轮查询时仍开放、0 条评论。结合其模拟数据说明，公开资料没有提供可直接采用的本车 RPM 接口。开放需求不证明内部完全没有功能，但也不能当作已经支持的证据。

## 5. 新项目和渠道筛选

- [Tarmac 的实验记录](https://github.com/Altair571/tarmac/blob/49e4b6ecb96ee3cc5b781b86b01001eff32cd852/docs/CARPLAY_ULTRA.md)：有 11 月 8–9 日手机台架条件、VehicleState 收发及界面观察，比宣传 README 有用。但其主题/仪表未完成，车辆数值使用样本、TEYES/FYT 车控尚未实车验证，文档明确未向这套 Ultra 车辆数据映射加入 speed/gear/RPM。协议移植自 LIVI，不能视为独立完整实现或升级替代方案。
- CarPlay Ultra-Lite 当前公开说明仍针对 Hyundai/Kia Gen5W + HMG-API，主要是车控/主题接入。车型接口不同，不能直接补 ECARX 的 RPM 或仪表覆盖层。
- X：检索到 `@vmlite` 的 CarCast/iCarCast 演示索引；[X 公开页](https://x.com/vmlite)没有可读正文，镜像搜索摘要未提供协议、布局修复或实车数据接入证据，因此没有据此下技术结论。
- 4PDA / XDA / MacRumors / Ars OpenForum：本轮定向检索主要得到发布新闻、使用讨论和评论，没有找到针对本车四问题的可复现修复。搜索覆盖有限，“本轮未找到”不等于不存在。
- 搜索引擎还列出 `harman-f/mhi2_altscreen_carplay` 的 Ultra 研究笔记，但 GitHub 页面和 API 均返回 404；未把搜索摘要当成已核验源码，也未通过代理镜像补充结论。

建议研究顺序：先在现有实验分支的台架验证 SeatFan 声明；两块屏分别采集能定位图层的同场次证据；RPM 继续寻找本车实际来源。已有实车成功的车控和数据应作为基线保留，无须仅因外部项目叫 Ultra 就整体换栈。

## 6. 显示专项深入：从接收视频转向主题宿主与图层组合

本节以本分支 `872d9952` 为实现基线；只做静态分析，没有改运行代码、执行主题脚本、安装发布包或重新连车。新增 JSON 记录固定提交、发布包 SHA-256、源文件位置和证据边界；不包含认证信息、主题标识、主题源码或反编译正文。

### 6.1 完整主题存在可读的研究入口

从此前已核对的四包中抽查**一个** `CarPlayAccessory.assets.zip`：其中有 **193 份 `.lua` 和 193 份同名 `.luac`**，包含 `main.lua`、`LayoutController.lua`、`Layout.lua` 和 APL 图层模块。`.lua` 含入口、布局、主题解析和宿主调用逻辑，并非仅有字节码。193 份字节码的前两字节均为 `05 01`；这本身不是兼容性证明。

入口通过 `CP.enumerateOutputStreams` 取得输出流，再加载包与主题数据。脚本使用的宿主接口涉及图形/文字/图片/视频图层、车辆数据、消息收发和时钟。因此，研究完整主题可以从现成脚本追踪所需宿主接口，不必仅凭视频猜测表盘绘制方式；但仍须实现这些接口及资源、时序和视频合成，**安装一个 Lua/Luau VM 不等于有完整仪表**。

这是本地样本核验，脚本哈希与计数见显示取证清单，原文件不分发；不能作为任何读者无需持有主题即可独立复跑的证明。通过 Context7 查询的 [Luau 官方嵌入说明](https://github.com/luau-lang/luau/blob/master/README.md)提供源码编译和 VM 加载路径，不提供 Apple 的图形/车辆宿主。当前既未编译这些脚本，也未证明原始字节码能被某个版本的 Luau 直接加载。

### 6.2 UI Sync 有确切的区域更新语义，当前分支没有应用

样本 `LayoutController.lua:36` 的内层命令表规定：`0x02=TRANSITION_DATA`、`0x06=END_LAYOUT_TRANSITION`。前者处理 `data.regions` 和 `data.pts`，并按帧时间窗口应用；`Layout.lua:940` 将区域分到 `comp:`、`stream:`、`layout:`、`popover:`，`:1008` 再更新相应组件。**不要把外层信封的 command 类型 2 与内层过渡数据类型 2 混为一层，也不要把类型 6 当成区域更新。**

对照 [本分支 CarPlayUiSync.kt](../shared/src/main/java/com/shilapi/xcertplay/airplay/CarPlayUiSync.kt)：当前请求固定 layout/palette/activeComponents，收到手机命令后主要记录、应答及处理焦点请求，没有把区域或布局/配色变化交给本地渲染层。它隐藏的是类型 6 日志；区域数据类型 2 并未被这条过滤规则隐藏。本次在本地 `analysis` 下 `.log/.txt` 中未检索到匹配的区域命令记录，不代表手机没有发送。

这证明一处实现缺口，**尚不证明它造成当前固定底部空白**。最小验证应复用现有校准入口，在同一会话保存内层 `0x02` 的区域/PTS、布局选择、视频帧与最终仪表照片；先确认数据确实发送、所属流与坐标单位，再决定是否应用变换。不能看到一个矩形就直接裁整帧。

### 6.3 CarCast 提供了本地主题宿主的更强参照

静态检查 [Android 0.3.27 发布包](https://github.com/rPlayAI/carcast-public/releases/tag/android-0.3.27)，APK 的 SHA-256 与 GitHub release asset digest 一致。ARM64 `libcarcast.so` 中有 Luau、`main.lua` 加载、APL core、`DPVideoLayer`、`DPShapeLayer`、`DPTextLayout` 等标识，以及视频图层坐标诊断；`NativeBridge` 同时声明 `setScreenSurface(int, Surface)` 与 `setUltraSurface(Surface, int, int)`。这些与 [0.3.25 发布说明](https://github.com/rPlayAI/carcast-public/releases/tag/android-0.3.25)中“由 CarCast 绘制仪表、使用模拟车辆数据”的描述一致。

因此，更有依据的研究方向是**接收端运行主题/图层逻辑，与手机视频组合**，不能继续将 CarCast 归为“iPhone 已经画好完整表盘的一路视频”。静态标识尚不能证明它实现全部宿主 API、所有主题兼容或本车帧率，也没有证明其二进制能被本项目直接接入。

后续可把 CarCast 当作同主题/同布局的输出对照，重点比地图区域、仪表下沿和布局切换。当前 Android 发行声明最低 Android 5；这只覆盖版本门槛，不证明 ECARX 仪表通路兼容。该测试版有到期时间，不应作为长期运行方案的前提。

### 6.4 LIVI 的可借鉴部分是自绘仪表和选择同步

固定提交 `870d2644` 的 [dashboard.slint](https://github.com/f-io/LIVI/blob/870d26441e073cdb802c427c5ae8833dba694951/native/crates/livi-ui/ui/dashboard.slint#L15)自行绘制仪表；逻辑画布为 1280×720，并按可用区域缩放、向两侧扩展表盘位置。[布局模型](https://github.com/f-io/LIVI/blob/870d26441e073cdb802c427c5ae8833dba694951/native/crates/livi-ui-model/src/dashboard/layout.rs)对不认识的布局默认采用地图加自绘表盘；[主题解析](https://github.com/f-io/LIVI/blob/870d26441e073cdb802c427c5ae8833dba694951/native/crates/livi-core/src/theme_catalog.rs#L285)提取布局、调色和地图 zone/component，并没有在此执行 OEM 主题脚本。

其 [UI Sync](https://github.com/f-io/LIVI/blob/870d26441e073cdb802c427c5ae8833dba694951/native/crates/livi-cp/src/ui_sync.rs#L174)跟随手机的布局、配色、外观选择；本次所读实现同样没有消费上述 `0x02` 区域数据。此前泛称 LIVI 有“区域处理”不应理解为已经实现这一段主题协议；视频裁切/可用区布局是另一层。

可借鉴方案是沿用现有视频通路，在本车 1920×720 上实现本地仪表层，并跟随选择变化。现有 `UltraBenchActivity` 已有模拟 Canvas 仪表，可作起点，但仍须接真实数据、布局、状态和生命周期；RPM 缺失时不能用模拟值冒充。此路线能补本地信息展示，不能声称复现全部原厂主题。

### 6.5 Ultra-Lite 的源码包提供参数实验，但没有证明 16:9 重排

[Ultra-Lite v1.0.0](https://github.com/SafariForest/carplay-ultra-lite-gen5w/releases/tag/v1.0.0)的源码在 bundle 内，主分支主要放说明。已核对 ZIP 内源码与作者发布的 `SHA256SUMS`，`native/hooks.c` 可见三类操作：显式底部 dock 的 View Area；保留像素尺寸、调整物理尺寸以改变 UI 大小；声明全屏及右侧 2/3 的两块可切换区域。`UiSync.java` 则只记录手机选择，不实现完整主题绘制。

这些可以参考底部空调入口、图标密度和分区布局；本分支已有 View Area、dock edge、多区域切换及物理尺寸配置能力，优先核对实际 `/info`，没有必要另造配置系统。该项目针对 Gen5W/HMG-API，未提供把 1920×720 OEM 主题重排为 1920×1080 的复现，不能当作本车留黑补丁。

中控仍应保留三种方案的区别：适配/取得真正适合 16:9 的主题布局；上方 1920×720 加下方本地 1920×360 区域；缩放裁切或拉伸。后两者都不是原生主题重排。若将一幅 1920×720 有效画面等比放大到高 1080，宽会变成 2880，要填入 1920 宽窗口就会裁掉左右各 480 个输出像素；拉伸则会改变图形比例。没有找到新证据可以承诺仅改尺寸参数就铺满。

### 6.6 建议只推进两个有判据的小验证

1. **先定位当前几何。** 一次会话收齐校准图、源帧、区域/PTS 和最终屏幕。判据是底部空白已经存在于视频内容、属于本地层预留，还是在窗口/原车合成后才出现；两个屏幕分别判断。
2. **再试一个主题、一个布局的最小宿主。** 从可读 `main.lua` 及实际调用的 CP 接口出发，先离线确认能否加载、建立图层和输出一张仪表图，再加入手机视频区域与已有真实数据。对暂缺能力记录明确缺口，避免先做通用主题引擎。若成本过高，再选择现有 Canvas + LIVI 思路的自绘路线。

[VNC Automotive 2026-11-02 的供应商说明](https://www.vncautomotive.com/carplay-ultra-qa-with-peter-galek-director-of-sales-engineering/)也描述了 Linux/Android/AAOS 演示和宿主 Display Plugin 渲染，可作为商业集成路径的线索；不是现成开放 SDK，也不是本车已经支持的证明。

## 通风改动与既有验证

- [真实车辆源](../shared/src/main/java/com/shilapi/xcertplay/hud/EcarxVehicleState.kt)：独立 SeatFan，0–3 档，SDK 支持检查与回读同步；诊断日志新增 `seatVentilation`。
- [模拟车辆源](../shared/src/main/java/com/shilapi/xcertplay/airplay/SimulatedVehicleState.kt)：采用相同的主驾通风字段；与加热、空调风量独立。
- 针对性测试 13 项通过：`EcarxVehicleStateTest` 8 项、`CarPlayVehicleStateTest` 4 项、`EcarxAdaptapiAbiTest` 1 项。覆盖协议声明/订阅/通知、合法档位、非法及不支持值拒绝、SDK 回读更新及厂商常量。
- 验证发生在主驾通风实现完成后，未安装 APK、未运行 CarCast、未连接 iPhone 或实车。全量 CI 按用户要求中止，不记录为通过；以后优先做与变动相关的测试。

可在已配置 Android SDK/JDK 的环境中复跑以下定向测试；`ECARX_ADAPTAPI_JAR` 指向自行持有的完整 753 SDK JAR，不设置时 ABI 测试会跳过，不能算通过：

```sh
./gradlew :shared:testDebugUnitTest \
  --tests '*EcarxVehicleStateTest' \
  --tests '*CarPlayVehicleStateTest' \
  --tests '*EcarxAdaptapiAbiTest' \
  --no-parallel --max-workers=1
```
