# CarPlay Ultra：753 车辆接入与台架实验

## 753 真实车辆接入（实验性）

本分支现在也提供独立的真实车辆源。入口为 **设置 → 高级 → CarPlay 车辆与空调（实验性）**，默认关闭，下次连接生效；关闭也在下次连接停止声明车辆能力。该开关可用于车机签名版本，不要求 ADB 或台架配置文件。需 iPhone 支持 CarPlay 车辆 App，是否显示由 iPhone 决定。主题与仪表布局实验仍遵守下文的 debug 限制，真实数据开关本身不替 iPhone 开启 Ultra 或下载主题。

2026-11-11 现场反馈：已测座椅/空调操作实车成功，除 RPM 外当时已测车辆数据取得；不将结论扩展到未测项。RPM 仍未取得有效值，新增主驾通风待实车验证。公开来源、结论修正和定向测试记录见[研究复核](CARPLAY_ULTRA_RESEARCH_20261111.zh-CN.md)。

| 已接入 | 接口及转换 |
| --- | --- |
| 车速、剩余燃油、总里程、续航 | AdaptAPI `ISensor`：1048832、1050112、1050368、1050624；车速 m/s × 3.6，燃油 mL ÷ 1000，以 L 显示，不冒充百分比 |
| 双区设定温度 | `ICarFunction` 0x10060100，左 1 / 右 4，16–31°C，步进 0.5°C |
| 空调电源、A/C、MAX A/C、AUTO | 0x10010100、0x10010300、0x10010400、0x10010200；布尔值 0 / 1 |
| 风量 | 0x10020100；关闭 0，1–7 档转换成 funcId + level |
| 内外循环 | 0x10030100；内 0x10030101 / 外 0x10030102 |
| 前挡 MAX 除雾、后挡除雾 | 0x10040200、0x10040300；不调用本固件返回未知值的普通前除雾 0x10040100 |
| 前排左右座椅加热 | 0x10050200，左 1 / 右 4；关闭 0，1–3 档转换成 SDK 枚举 |
| 主驾座椅通风（新增，待实车验证） | 独立 `SeatFan` 服务；`ICarFunction` 0x10050100，左前 zone 1；关闭 0，1–3 档为 0x10050101–0x10050103 |
| 温度同步（Dual） | 0x10060500；固件实际映射 `PaClSync` / `CbClSync`，1 = 同步、0 = 独立；不按网页示例反转 |
| 发动机转速（尚未取得有效值） | `CarPropertyManager` 的 `ENGINE_RPM` 291504901，Float r/min，5 Hz；本车属性表声明 1–10 Hz。需 `CAR_ENGINE_DETAILED` 权限；属性存在不等于已有有效转速 |
| 四轮胎压 | 560992339–560992342，顺序左前/右前/左后/右后；使用系统 `GeelyProto.PublicAttrValue` 解析，availability = 1 且有有效 double 数据时，以 kPa 提供原生胎压特征及只读汇总 |
| ADAS 设置状态 | `ICarFunction` 537329920 车道保持开关、537330432 变道警告开关；仅只读信息行。不是正在介入的巡航/车道辅助状态 |
| 媒体、导航、通话、语音音量 | `CarAudioManager` 按 usage 1/12/2/16 查询音量组和实际上下限；CarPlay 0–100% 换算到原车档位，不写 Settings 值模拟生效 |
| 原车 AM/FM 广播 | 绑定 `com.geely.radio.service.RadioService` 的 `IRadioSvc`；读取频段/频率/播放状态，支持播放、暂停、前后搜台、频率调谐和切换频段 |

真实模式优先于台架配置；接口失败时不会退回模拟车辆。转速、胎压通过原生 VehicleState 特征提供，车辆 App 也有只读信息行。仪表 `UltraBenchActivity` 继续只显示模拟器，并没有接管真实仪表或将模拟表盘替换为实车数据。

源码依据为 [753 接口文档](https://jimu.uestcit.com/#/docs/20-05/820A/753/overview) 的空调/对接章节，以及本车 dump 中 `ICarFunction`、`SensorImpl`、`SensorPaUtil`、`HvacController`。嘟嘟的 `VendorExtensionController` / 四轮胎压读取与 HYDCy 的 `CarPropertyManager` 桥确认了 AdaptAPI 以外的属性通路；实车 `dumpsys car_service` 确认转速及四轮胎压属性存在。转速不经过本固件未实现的 AdaptAPI `ISensor` 转速常量。

网页部分示例与 dump 有差异：座椅不能传裸档位；Dual 的含义以 `CarFunctionValueUtil.getMappedFunctionValue(PaClSync)` 和实际 setter 的 `setCbClSync` 分支为准，两端 ON 均映射为 1。ABI 测试也核对真实 753 SDK 的方法、同步功能 ID，并使用 SDK 自带的 protobuf 解析器验证胎压报文形状。

只读取 SDK `active` 或 CarProperty `STATUS_AVAILABLE` 的有效值；胎压还验证 protobuf 内层 availability。断开、未知、解析失败或越界值显示不可用，不填模拟值。属性采用回调；音量和广播最多每秒回读一次，控制后立即尝试回读。CarPlay 会话关闭时取消监听、断开自建 Car 连接并解除广播绑定。写入先校验类型、范围、步进、当前可用性及 SDK 支持枚举；请求受理不乐观改读数，值以回读/回调为准。诊断报告记录连接、读取和写入失败。

广播绑定和会话退出不调用开台、抢音频焦点或关台；只有用户在 CarPlay 中播放、搜台或调谐时才启动调谐器。当前台作为电台列表提供，未实现全量扫描列表、收藏、DAB。跨频段调谐先选择 AM/FM，等待原车实际频段切换后再调谐；不把异步切换返回当作已完成。FM 87.5–108.0 MHz / 100 kHz 步进，AM 531–1629 kHz / 9 kHz 步进。未接入全车静音；语音音量对应原车语音组，不代表所有 CarPlay 音频路由都使用该组。

ADAS 本轮只提供两个已有设置的读数；未找到可确认的 ACC 接管状态、设定速度、跟车距离、车道线或目标物动态信号，不据设置开启推断系统正在工作，也不开放驾驶辅助控制。

车测时核对：iPhone 是否显示车辆/温控/广播 App；转速及四轮胎压与原车一致；先逐项测试左右温度、同步、低档风量及 A/C，再测除雾/座椅；四类音量各自影响正确声音，实体按键改动能同步；原车广播播放时 CarPlay 视频保持连接、手机媒体正确让出焦点；断开/重连后无旧值或重复监听。源码测试和 SDK ABI 检查不能代替这些实车验收。

## 主驾座椅通风（2026-11-11）

此前实车已确认可操作座椅加热和空调，主驾通风硬件存在，但旧接入未声明通风服务。本次在真实车辆源和模拟台架中加入同一组主驾通风字段，不借用加热档位，也不声明副驾通风。

| 声明 | 类型 / 值 | 实例 ID |
| --- | --- | --- |
| 原有座椅 accessory | `0x01500001` | 1024 |
| 新增 `SeatFan` service | `0x22000002` | 1033 |
| `fanLevel` | `0x31000012`，UInt8（format 1），0–3，步进 1；0 关闭 | 1034 |
| `vehicleLayoutKey` | `0x36000065`，`seat_front_left` | 1036 |

声明依据为公开 iOS 18.2 反编译源码 [CAFSeatFan.m](https://github.com/EthanArbuckle/iPhone17-1_18.2_22C152_Restore/blob/e26ed4563f78871c59d2d96856756a65d62517e5/System/Library/PrivateFrameworks/CarAccessoryFramework.framework/CAFSeatFan.m)：`serviceType`、`fanLevel` / `fanLevelRange` / `setFanLevel` 及 `vehicleLayoutKey`。本次只声明档位，关闭写 0；该类的 `on` 有 `hasOn` 检查，不额外实现第二套开关语义。它证明字段定义，不能证明当前 iOS / 主题一定显示此控件。

车端依据为本车 753 SDK 的 `IHvac.HVAC_FUNC_SEAT_VENTILATION`、`SEAT_VENTILATION_OFF`、`SEAT_VENTILATION_LEVEL_1..3`；`CarFunctionSupported.getSupportedFunctionValue` 返回关闭及三档枚举，`CarFunctionValueUtil.getMappedFunctionValue(PaSvcSts)` 也映射这四个值。真实源沿用已有 `ICarFunction` 的 zone 1 路径：先检查 `active` 和支持枚举，再调用 setter；立即回读，后续由 function watcher 更新订阅值。失败或未知值不填模拟数，也不把 setter 接受当作档位已经改变。SDK ABI 测试核对这些常量；zone 1 的通风执行效果仍待实车验证。

复测保持此前能显示车辆/空调 App 的主题配置，更新实验包后重新连接 CarPlay：

1. 查看主驾是否新增通风控件，副驾保持原有加热能力；若未出现，导出诊断报告并保留 iOS 版本和主题信息。
2. 停车时依次切换通风 1、2、3、关闭，核对座椅实际风量、原车面板和 CarPlay 的回显。新增字段的写入日志是 `Vehicle state ECARX write iid=1034 accepted=…`。
3. 从原车面板改通风，确认 CarPlay 跟随。可用性日志新增 `seatVentilation=0..3`，表示 SDK 回读档位；`unavailable` 表示没有可用读数。它不能单独证明座椅风机已经执行。
4. 复核主副驾加热和空调风量独立可用；断开并重连后读数正确。

本次自动测试覆盖服务声明、OPACK 订阅/通知、0–3 档读写、SDK 枚举与 zone、非法/不支持/不可用值拒绝，以及按真实回读更新。未做本次 iPhone 或实车验证。

## 手机台架

在测试手机上让 iPhone 进入 CarPlay Ultra：声明车厂主题、协商仪表布局、提供车辆数据，并在本地绘制一块原车风格的仪表。地图是 iPhone 实时视频；车速、转速、ADAS、车身读数和控制响应全部来自内置模拟器，**没有连接真实车身总线**。这是协议研究台架，不是发布功能。

## 开关与隔离

- 在 debug 或车测 release 包（`-PcarTestLabel=…`）的 **设置 → 高级 → 显示（实验性） → CarPlay Ultra 主题（实验性）** 粘贴下方格式的配置，保存后重连。默认按 8:3 声明中控，勾选「使用车机窗口尺寸」可以对照测试较高的画布。「关闭 Ultra」保留上一次配置，下次重连时使用普通 CarPlay。
- 实验包仍兼容应用 `files/` 或 `/sdcard/Android/data/<包名>/files/` 下的旧 `ultra-experiment.txt`。保存或关闭过主题设置后，以设置为准。公开 Release 不启用这两种配置入口。
- 只有同时存在 `ultra-bench.enabled` 才声明模拟车辆能力；只有主题配置时只做显示层。打开「无主题车控」时，`ultra-bench.enabled` 也会让模拟车辆接到无主题面板上，用来在手机上测普通 CarPlay 界面的车辆功能。
- 原车风格仪表 `UltraBenchActivity` 只在 debug 源集。
- 仓库不含任何车厂主题素材或主题 ID；主题由 iPhone 按 ID 从 Apple 公共资源目录下载。测试用主题 ID、设备与命令放在本地工作区，不进仓库。

## 按车机尺寸搭台架

台架手机可以按车机的两块屏声明尺寸，复现实车画面：

- 中控 1920×1080：把手机逻辑分辨率改成竖屏 1080×1920（`adb shell wm size 1080x1920`），横屏时 CarPlay 窗口就是 1920×1080；同时勾选「使用车机窗口尺寸」，否则 Ultra 会把中控裁成 8:3。
- 仪表 1920×720：开一块开发者叠加屏（`adb shell settings put global overlay_display_devices 1920x720/160`），用 `dumpsys display` 查到显示 ID 后，把 `UltraBenchActivity` 启动到这块屏上。叠加屏窗口本身半透明，会盖住一部分中控，截图看起来半透明不代表绘制有问题。
- 测完恢复：`wm size reset`，`overlay_display_devices` 设回 `null`。

台架仪表按领克 05 原车仪表的几何资料重画（照片标定，误差约 10–15 px）：iPhone 仪表视频铺满 1920×720，异形可视轮廓以外涂黑；顶部状态区（A）和底部状态栏（H）是不透明背景；转速刻度（B）、导航卡片（C）、车速（D）、巡航（E）、车道辅助（F）、右侧菜单（G）按原车位置和透明度叠加。右上角「标注」按钮显示 B–G 的范围虚线，用来核对 Apple 的卡片是否被原车组件压住。几何资料只用于台架，不是原厂素材，也不是安全区。

台架上的调试手段：

| 手段 | 用法 |
| --- | --- |
| 改模拟数据 | `adb shell am broadcast -a com.shilapi.xcertplay.BENCH_SET -p <包名> --es set "leftTemperature=25.5,fan=5"`，或 `--es scene CITY` 切场景。支持 `speed`、`leftTemperature`、`rightTemperature`、`fan`、`ac`、`seatHeating`、`seatVentilation`、`radioKhz`、`fuel`、`range`、`volume`。需要台架仪表界面在运行 |
| 看模拟数据变化 | `adb logcat -s DiPlay-Bench`：每次变化记一行 `Bench vehicle changed 字段=旧->新`，不论来自上面的遥控还是 iPhone 写入 |
| 看 iPhone 订阅了哪些字段 | 应用日志 `Vehicle state registerRequest fields=N supported=N ids=[…]` |
| 看推送和写入 | `Vehicle state updateNotify …`（首次和每 100 次记一行）、`Vehicle state write iid=… status=…` |
| 看 iPhone 端日志 | iPhone 用 USB 接电脑：`pymobiledevice3 syslog live`（实时），或 macOS `sudo log collect --device-udid <UDID> --last 15m`（归档，需在终端里输入密码）。主题相关看 `carkitd [ThemeAssets]` 和 `CarPlay [DBSessionController]` |

判断主题有没有生效，看应用日志里有没有 UI Sync。没有 UI Sync 时，界面就是普通 CarPlay，画面探针铺满整屏也不说明主题能铺满。

## 中控与仪表画布（2026-11-09 实车结论）

- 已测主题把中控定义为 1920×720。声明 1920×944 时 iPhone 只画上部 8:3（`box=0,0..100,75`），声明 1920×720 时整块画满（`box=0,0..100,100`）。声明 1920×1080 时只画上方 2/3（`box=0,0..100,66`），台架（2026-11-11）与实车一致。Ultra 默认声明 8:3 中控画布并贴顶显示。保留原车底栏时，车机窗口剩 224 px；隐藏底栏时，剩 360 px。
- 原车仪表“小屏导航”只露出中间窗口（约 x 31–68 %、y 16–89 %），DigiRing 等表盘布局的地图区也只是中间 600×480。MapsMedia 布局声明地图 1920×720，在原车仪表菜单切到“全屏导航”能扩大可见地图范围，原车读数会叠加在地图上。2026-11-11 复核确认实车画面下方仍有空白，尚未证明铺满；需同场次原始视频、校准图和最终照片定位原因。
- 仪表窗口中的第二张导航卡片来自 DiPlay 发给原车的 DIM 引导（`DIM guidance started …`），与主题自带卡片重叠。可在 设置 → 导航 关闭仪表导航引导对比；关闭也会停止 HUD 引导。
- Ultra 仪表会声明手动保存的「仪表安全区」。重置后 Ultra 用整块画布；iPhone 地图卡片是否随安全区移动仍需实车验证。

### 仪表坐标校准

停车时在 **设置 → 高级 → 仪表显示 → 仪表坐标校准图（实验性）** 打开开关，然后开始支持仪表地图的导航并显示独立仪表地图。坐标图使用同一个仪表投屏窗口，开启和关闭无需重连。如果当前用整屏镜像，先切回独立仪表地图。

以画布左上角为原点。十字中心是标注的 `x,y` 像素坐标，小格间隔 30 px。分别拍摄原车小屏导航和全屏导航，保留至少四个分散且不共线的十字用于照片透视校正，再描出可见边界。拍完关闭开关恢复地图。

## 无主题车控（实验性，领克 05 未通过）

普通 CarPlay 只上报车辆状态时，iPhone 不显示车辆、空调和广播 App（2026-11-09 实车）。LIVI 与 Gen5W Ultra-Lite 在无主题时仍发送 `displayPanels`，Tarmac 台架在无主题时也出现了这些 App。**设置 → 高级 → CarPlay 车辆与空调（实验性） → 无主题车控（实验性）** 在无 Ultra 主题时发送面板描述，只在 debug 和车测包可见，默认关闭，下次连接生效。配置了 Ultra 主题时以主题为准。

当前发送的结构：只描述两块真实屏幕。中控面板与仪表面板的 `uid` 就是各自视频流的 UUID，尺寸与声明一致，都不带插件；仪表面板标 `showsInstruments`。日志：`Themeless vehicle controls: displayPanels without a theme vehicleData=…` 和 `airplay /info displayPanels=[…]`。

2026-11-09 实车结果（iOS 27.3，无线，中控声明 1920×1080，仪表 1920×720 独立地图）：

| 版本 | 面板 | 结果 |
| --- | --- | --- |
| r1，22:14 | LIVI 格式：`<UUID>-panel` 两块真实屏幕 + 一块无视频流、带空插件的虚拟仪表面板 | iPhone 订阅 68 个车辆字段，仪表出图；中控流全黑（`Frame probe … drawn=0% box=none`，持续 1.5 min） |
| r2，22:20 | 上面的两块真实屏幕，无插件 | 中控仍无画面，车辆 App 未出现 |

关闭本开关重连后恢复普通 CarPlay。结论：在这台车与 iOS 27.3 上，无主题 `displayPanels` 会让 iPhone 停止绘制中控，未能得到 Tarmac 记录的无主题车辆 App。

2026-11-11 台架复测（r2 格式，模拟车辆，iOS 27.3，中控 1920×1080）：画面正常，没有黑屏；车辆数据通道建立，iPhone 只订阅 1 组 17 个字段（空调 519–559 和 2050–2065），台架改温度后能推送过去（`updateNotify fields=[520]`），但界面上**没有任何车辆功能**：Dock 没有空调温度，没有空调、广播、车辆 App，设置里也没有车辆数据页。也就是说，只发无主题面板，iPhone 会收数据却不给用户入口。台架和实车在中控是否黑屏上的差别，原因还没查。r2 的诊断报告没有导出（无线 ADB 已断开），下一次实车先补报告，核对 `/info displayPanels` 与 `Frame probe`。开关保留用于复测，不建议默认打开。

## 主题包与黑屏（2026-11-11 台架）

iPhone 只有在本地有这台车主题包的情况下才进 Ultra。主题包不在时：

1. iPhone 照常建立会话，回报 `themeAssets=true`，然后去下载主题包（`com.apple.MobileAsset.CarDDPAssets`，本次约 50 MB）。
2. 下载完成前，iPhone 既不发中控和仪表视频，也不建立 UI Sync，表现为**两块屏都黑**，不会自动退回普通 CarPlay。关闭 Ultra 重连则立即有画面。
3. iPhone 连着 CarPlay 的 Wi-Fi 时，这个下载会被系统后台调度挡住，一直报 `Stalled`：

   ```text
   carkitd [ThemeAssets] theme asset exists for <private> but is not downloaded, requesting download
   dasd    Network Quality Policy ... networkPathAvailability Required:1.00, Observed:0.00 → Decision: AMNP
   DownloadService [Progress]: Stalled
   ```

   车机热点本身能上网也没用。断开 CarPlay（关掉热点）后，iPhone 回到普通 Wi-Fi，很快下完；再连接就正常进 Ultra。

会触发这种情况的操作：

- 在 iPhone 的 设置 → 通用 → CarPlay 里“忘记此车”：主题包会被一起删除。
- 声明一个 iPhone 从没下载过的主题变体（例如 CN、Single）。

对实车的影响：用户第一次连接，或忘记车辆后重新连接时，如果主题包没准备好，Ultra 会一直黑屏，连着车也下载不完。车测前先让 iPhone 在普通网络下完成一次下载。DiPlay 以后可以考虑：会话已建立但长时间没有视频、也没有 UI Sync 时，提示用户断开去下载主题，或者自动退回普通 CarPlay。

## 普通界面下的车辆功能（2026-11-11 台架）

普通 CarPlay 界面（没有 Ultra 布局）里能不能出现车辆功能，取决于 iPhone 如何看待这台车的主题，而不是视频画面。台架上见到三种状态：

| 状态 | 握手时声明主题 | iPhone 端的主题 | 订阅的车辆字段 | 界面上的车辆功能 |
| --- | --- | --- | --- | --- |
| 无主题车控 | 否 | — | 1 组 17 个 | 无，只有返回原车的 OEM App |
| 主题生效 | 是 | 已应用（有 UI Sync） | 5–6 组 | Ultra 界面，侧边栏空调，车辆 App |
| 主题布局解析失败 | 是 | `theme asset: (null)` | 5 组 | 普通界面，Dock 两侧带箭头的空调温度，广播、车辆 App，设置里有车辆数据页 |

主题生效和解析失败时订阅的字段相同：

```text
空调：519 520 521 524 528 530 533 534 535 538 549 554 559 2050 2055 2060 2065
空调与座椅：514 515 516 517 525 526 541 545 550 551 555 556 560 561 1026 1027 1030 1031 1034（主驾通风）
音量与广播：2306 2309 2311 2314 2317 2321 4116 4117 4118
其他：1538 4355 4359
胎压：1284 1292 1300 1308 1316 1324 1332 1340 1348 1356 1364 1372
```

解析失败状态下，两个方向都在台架上验证过：

- 推送：台架把左侧温度改为 24.5，Dock 左侧即时显示 24.5。
- 控制：在中控上调左侧温度（`iid=520`，24.5 → 26.0）、开关前挡除雾（`iid=517`）、打开温度同步（`iid=538`），写入全部 `status=0`，模拟车辆随之变化。广播、座椅和音量没有在这个状态下测。

解析失败的复现方法：主题生效后，声明一个 iPhone 没下载过的变体（CN）连一次，再切回原主题（EC）连一次。切回那次 iPhone 报：

```text
CarPlay [DBSessionController] Theme asset layout document available at URL <private>
CarPlay [DBSessionController] Failed to decode document at URL <private>
CarPlay [DBSessionController] Notifying session did connect with theme asset: (null)
```

DashBoard 把主题当成 null，退回普通界面；carkitd 仍认为车辆支持主题，所以车辆数据和车辆 App 都保留。只重新生成配对身份、或在 UI Sync 刚开始时强杀应用，都不会触发。

这个状态会一直保留：之后断开重连、在 iPhone 上开关 Wi-Fi，每次都还是普通界面加完整的车辆功能。它跟着 iPhone 上这台车的记录走，预计只有“忘记此车”能清掉。

**这个状态依赖 iPhone 的解析错误，不能当作产品方案**，只能说明：普通界面本身能承载完整的车辆功能，iPhone 是否打开它，与“这台车声明了主题、且 iPhone 认为主题可用”有关。要回到正常 Ultra，需要在 iPhone 上忘记此车，再按上一节让它重新下载主题包。

还没回答的问题：

1. 不依赖解析错误时，怎样让 iPhone 在普通界面里打开车辆功能；
2. 无主题时，实车中控黑屏而台架画面正常，差别在哪。

## 实车测试：探针与 A/B 开关

以下标记文件与 `ultra-experiment.txt` 放在同一目录，同样只在 debug 或车测包中读取，下次连接 CarPlay 生效：

| 文件 | 作用 | 日志 |
| --- | --- | --- |
| `frame-probe.enabled` | 中控画面探针，不依赖 Ultra；每 5 s 记录绘制占比与范围，每 30 s 一张 32×18 亮度图 | `Frame probe main 64x36 view=… canvas=… shown=… drawn=…% box=x0,y0..x1,y1 mean=…`、`Frame map main 32x18` |
| `ultra-no-corner-masks.enabled` | 主题会话中控显式声明 `cornerMasks=false`（原来不声明，iPhone 使用主题默认圆角） | `Ultra A/B main cornerMasks=false` |

`box` 是解码画布中高于亮度阈值的范围，`shown` 是画布在窗口中的显示位置，均为百分比。亮度图把 18 行用 `/` 分隔后写入一条诊断日志。开启后的连接会先记录 `Debug probes: frameProbe=… ultraNoCornerMasks=…`。

车辆数据开关（设置 → 高级）会在可用性或通风档位变化时记录一行：`Vehicle state ECARX availability connected=… present=[…] missing=[…] tires=n/4 climate=n/14 seatVentilation=… volume=n/4 radio=…`。转速等高频信号的原始值每 30 s 最多记一条（`Vehicle signal engineRpm changed raw=…`）。另一份领克 05 实车记录称 VHAL 的转速、胎压恒为空；这一行能直接确认本车是否同样如此。

每轮只改一个变量，否则无法归因：

1. 基线：Ultra 关闭，只放 `frame-probe.enabled`。预期 `box=0,0..100,100`。
2. 主题：在设置中启用主题，勾选「使用车机窗口尺寸」并重连。记录 `box`、`shown` 和四角。
3. 圆角：保持主题和窗口尺寸一致，开启中控「直角铺满，不使用安全区域」并重连。核对 `/info` 的 `cornerMasks=true`，再对比圆角和底部空区。这一轮不要放 `ultra-no-corner-masks.enabled`：中控已声明 `cornerMasks=true` 时该开关不生效，日志记 `Ultra A/B ignored: main already declares cornerMasks=true`。
4. 仪表组件：`ultra-experiment.txt` 不写 zone 行（`activeComponents` 为空），与只写地图 zone 的版本对比仪表上的组件。

```sh
P=com.shihab.diplay   # 车测包与正式包同包名
D=/sdcard/Android/data/$P/files
adb shell mkdir -p $D
adb push ultra-experiment.txt $D/
adb shell touch $D/frame-probe.enabled
adb shell rm -f $D/ultra-no-corner-masks.enabled
adb shell ls $D
```

改完标记后断开再连接 CarPlay（不要重启车机），然后导出诊断报告。

## 配置格式

```text
<资源ID>:<u32 版本>:<最低 SDK，如 19.0>
<主屏面板 ID>
<仪表面板 ID>
<布局 ID>              # 可选，与下一行一起出现
<配色 ID>
<zoneID>:<componentID> # 0 行或多行
```

面板、布局、配色和组件 ID 取自该主题包的 `package.json` / `theme.json`。

## 协议依据

- iAP2 `0x1d01`：USBHostTransport（16）或 WirelessCarPlayTransport（24）的**子参数 6**声明主题 ID。17 是 BluetoothTransport 的顶层参数，不是主题子参数。
- `0x4301`：参数 6 为主题 SDK，组 7 的子参数 0、1 为资源 ID 和 u32 版本。
- `/info`：面板 uid、视频流 UUID 和 HID/控制消息一致；`showsInstruments`、`initialVideoStreams`、`displayPlugins` 关联仪表。实验请求 1920×720、30 fps，不声明未实现的旋钮。
- UI Sync：声明 `uiSyncInfo`，协商 feature 和加密数据流，完成 reset/reset-complete、允许布局、请求布局/调色板/活动组件、交还焦点。OPACK 长度用最短编码；过宽的长度字段会让 reset 停滞。
- VehicleState：声明配件/服务/特征；实现配置、读取、订阅、写入、控制响应，以及高优先级通道的增量更新。正常与高优先级通道复用现有 data-stream 加密帧；嵌套 OPACK 支持字典、数组、浮点数及引用。
- 只声明主题、不协商布局时，iPhone 报 `missingLayout`，仪表黑屏。

## 画面与数据（模拟）

| 范围 | 已实现 | 边界 |
| --- | --- | --- |
| 原车风格仪表 | 按领克 05 仪表几何重画：异形轮廓、顶部状态区、转速、导航卡片、车速、巡航、车道辅助、右侧菜单、底部续航/安全带/驾驶模式/水温；「标注」显示组件范围 | 几何来自照片标定，误差约 10–15 px，非原厂资产 |
| 行驶数据 | 车速、转速、挡位、油量、续航、里程、胎压、水温/油温、车门、安全带、灯光、ADAS、故障警告；驻车/城市/高速/警告四场景 | 不改变 iPhone GPS |
| 车辆 App | 十二组模拟读数和来源，音量入口 | 部分读数以自定义只读信息行传给 iPhone |
| 温控 App | 双区温度、同步、风量、A/C、AUTO、MAX A/C、内外循环、出风、除雾、座椅加热 | 只改模拟状态 |
| 广播 App | 模拟 FM 列表、选台/调谐、播放/暂停 | 没有真实调谐器或音频 |
| 音量 | 媒体、导航、通话、其他、Siri 音量及静音 | 不改变真实音频设备 |

同一个 `SimulatedVehicle` 同时供本地仪表和 iPhone VehicleState 使用；iPhone 上的写入会反映到本地界面。

## 边界

- 未实现完整的动态主题布局渲染器，不保证其他主题/布局的透明区域正确。
- 手机台架的车速、CAN、ADAS 和空调仍为模拟值；上文的真实车辆源独立启用。车机自身覆盖层不会消失，不能称为完整的原厂 Ultra 集成。
- 主题目录可用性、iOS 版本、地区及 Apple 远端禁用都会影响 iPhone 是否接受主题。
- 只在一台 Android 15 测试手机和一台 iOS 27.3 iPhone 上验证过；另一台 iOS 27.2.1 的 iPhone Air 在台架上每次握手后立刻断开，原因未查。

## 来源

协议字段对照 LIVI 固定版本 `6d1e0b5473cba464e8c46b9f027a86e61561784e`（GPL-3.0-or-later）；本实现复用项目自己的 Kotlin 编码器、加密数据通道和视频链路，没有移植其 Rust 代码或任何品牌素材。

- [iAP2 身份字段](https://github.com/f-io/LIVI/blob/6d1e0b5473cba464e8c46b9f027a86e61561784e/native/crates/iap2-csm/src/messages/identification.rs)
- [身份声明](https://github.com/f-io/LIVI/blob/6d1e0b5473cba464e8c46b9f027a86e61561784e/native/crates/livi-runtime/src/ident.rs) 与 [会话启动](https://github.com/f-io/LIVI/blob/6d1e0b5473cba464e8c46b9f027a86e61561784e/native/crates/livi-runtime/src/bringup.rs)
- [面板声明](https://github.com/f-io/LIVI/blob/6d1e0b5473cba464e8c46b9f027a86e61561784e/native/crates/livi-cp/src/info.rs) 与 [主题目录](https://github.com/f-io/LIVI/blob/6d1e0b5473cba464e8c46b9f027a86e61561784e/native/crates/livi-core/src/theme_catalog.rs)
- [UI Sync](https://github.com/f-io/LIVI/blob/6d1e0b5473cba464e8c46b9f027a86e61561784e/native/crates/livi-cp/src/ui_sync.rs)
- [VehicleState](https://github.com/f-io/LIVI/blob/6d1e0b5473cba464e8c46b9f027a86e61561784e/native/crates/livi-cp/src/vehicle_state.rs) 及同目录 body/climate/engines/media/settings/volume/wire 模块
