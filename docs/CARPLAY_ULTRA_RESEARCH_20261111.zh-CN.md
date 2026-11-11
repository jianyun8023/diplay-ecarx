# CarPlay Ultra：带着四项实车问题复查公开资料

检索与核对日期：2026-11-11。检索 GitHub 源码、issues/comments、X/Twitter、Reddit、4PDA、XDA、MacRumors 和 Ars 论坛。固定版本、文件 blob SHA 和当日 issue 状态见[取证清单](evidence/carplay-ultra-community-20261111.json)。本文整理既有研究，未重新开展外部检索。

车辆范围：领克 05 2020 / ECARX 753 / Qualcomm 820A / Android 9，仪表 1920×720、中控 1920×1080。沿用用户已经确认的实车基线：已测空调/座椅操作成功，除 RPM 外当时已测车辆数据取得；测试时主驾有通风硬件但没有接入；仪表下方空白、中控上方 8:3 下方黑区仍存在。本轮未重新连车。

实现更新：本次已在 `exp/carplay-ultra-bench` 增加主驾 SeatFan 声明、ECARX 通风档位读写/回读、模拟台架和针对性测试。[接入与复测说明](CARPLAY_ULTRA_BENCH.zh-CN.md#主驾座椅通风2026-11-11)记录具体映射。下文的“缺项”描述固定基线 `2cd54650`；当前代码已补齐，iPhone 控件展示及实车执行仍未验收。

**最有价值的新发现是座椅通风的协议候选已经能从公开反编译代码定位；两块屏幕的留白仍没有找到可直接采用的公开修复。**

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

## 本次改动与验证

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
