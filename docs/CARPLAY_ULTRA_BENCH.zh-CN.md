# CarPlay Ultra：753 车辆接入与台架实验

## 753 真实车辆接入（实验性，待实车验收）

本分支现在也提供独立的真实车辆源。入口为 **设置 → 高级 → CarPlay 车辆与空调（实验性）**，默认关闭，下次连接生效；关闭也在下次连接停止声明车辆能力。该开关可用于车机签名版本，不要求 ADB 或台架配置文件。需 iPhone 支持 CarPlay 车辆 App，是否显示由 iPhone 决定。主题与仪表布局实验仍遵守下文的 debug 限制，真实数据开关本身不替 iPhone 开启 Ultra 或下载主题。

| 已接入 | 接口及转换 |
| --- | --- |
| 车速、剩余燃油、总里程、续航 | AdaptAPI `ISensor`：1048832、1050112、1050368、1050624；车速 m/s × 3.6，燃油 mL ÷ 1000，以 L 显示，不冒充百分比 |
| 双区设定温度 | `ICarFunction` 0x10060100，左 1 / 右 4，16–31°C，步进 0.5°C |
| 空调电源、A/C、MAX A/C、AUTO | 0x10010100、0x10010300、0x10010400、0x10010200；布尔值 0 / 1 |
| 风量 | 0x10020100；关闭 0，1–7 档转换成 funcId + level |
| 内外循环 | 0x10030100；内 0x10030101 / 外 0x10030102 |
| 前挡 MAX 除雾、后挡除雾 | 0x10040200、0x10040300；不调用本固件返回未知值的普通前除雾 0x10040100 |
| 前排左右座椅加热 | 0x10050200，左 1 / 右 4；关闭 0，1–3 档转换成 SDK 枚举 |
| 温度同步（Dual） | 0x10060500；固件实际映射 `PaClSync` / `CbClSync`，1 = 同步、0 = 独立；不按网页示例反转 |
| 发动机转速 | `CarPropertyManager` 的 `ENGINE_RPM` 291504901，Float r/min，5 Hz；本车属性表声明 1–10 Hz。需 `CAR_ENGINE_DETAILED` 权限 |
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

## 手机台架

在测试手机上让 iPhone 进入 CarPlay Ultra：声明车厂主题、协商仪表布局、提供车辆数据，并在本地绘制一块原车风格的仪表。地图是 iPhone 实时视频；车速、转速、ADAS、车身读数和控制响应全部来自内置模拟器，**没有连接真实车身总线**。这是协议研究台架，不是发布功能。

## 开关与隔离

- 只有 debuggable 构建和车测 release 包（`-PcarTestLabel=…`）读取 `ultra-experiment.txt`；公开 Release 不读，默认关闭，下次连接生效。文件可放在应用私有目录 `files/`，或 `adb push` 可写的 `/sdcard/Android/data/<包名>/files/`（车测包不可 debug，只能用后者）。
- 只有同时存在 `ultra-bench.enabled` 才声明模拟车辆能力；只有主题配置时只做显示层。
- 原车风格仪表 `UltraBenchActivity` 只在 debug 源集。
- 仓库不含任何车厂主题素材或主题 ID；主题由 iPhone 按 ID 从 Apple 公共资源目录下载。测试用主题 ID、设备与命令放在本地工作区，不进仓库。

## 实车测试：探针与 A/B 开关

以下标记文件与 `ultra-experiment.txt` 放在同一目录，同样只在 debug 或车测包中读取，下次连接 CarPlay 生效：

| 文件 | 作用 | 日志 |
| --- | --- | --- |
| `frame-probe.enabled` | 中控画面探针，不依赖 Ultra；每 5 s 记录绘制占比与范围，每 30 s 一张 32×18 亮度图 | `Frame probe main 64x36 view=… canvas=… drawn=…% box=x0,y0..x1,y1 mean=…`、`Frame map main 32x18` |
| `ultra-no-corner-masks.enabled` | 主题会话中控显式声明 `cornerMasks=false`（原来不声明，iPhone 使用主题默认圆角） | `Ultra A/B main cornerMasks=false` |
| `ultra-main-8x3.enabled` | 主题会话中控声明 8:3 画布（1920 宽对应 1920×720），画面在窗口内等比居中，触摸跟随 | `Ultra A/B main canvas 1920x1080 -> 1920x720 …` |

`box` 是 iPhone 实际绘制区域占画布的百分比；静止画面码率很低不代表黑屏，以 `box` 为准。亮度图只用空格和 `.:-=+*#%@`，不会被报告脱敏规则当作十六进制删掉。开启后的连接会先记录 `Debug probes: frameProbe=… ultraNoCornerMasks=… ultraMain8x3=…`。

车辆数据开关（设置 → 高级）会在可用性变化时记录一行：`Vehicle state ECARX availability connected=… present=[…] missing=[…] tires=n/4 climate=n/13 volume=n/4 radio=…`。转速等高频信号的原始值每 30 s 最多记一条（`Vehicle signal engineRpm changed raw=…`）。另一份领克 05 实车记录称 VHAL 的转速、胎压恒为空；这一行能直接确认本车是否同样如此。

每轮只改一个变量，否则无法归因：

1. 基线：Ultra 关闭，只放 `frame-probe.enabled`。预期 `box=0,0..100,100`。
2. 主题：加 `ultra-experiment.txt`。记录 `box` 是否只到约 66%，目视四角是否圆角。
3. 圆角：在第 2 轮基础上加 `ultra-no-corner-masks.enabled`。只看圆角是否消失，`box` 应与第 2 轮相同。
4. 画布：删掉圆角标记，改加 `ultra-main-8x3.enabled`。`box` 接近 `0,0..100,100` 说明 iPhone 按我们声明的尺寸绘制；仍是上部 2/3 说明主题自身就是这样绘制。
5. 仪表组件：`ultra-experiment.txt` 不写 zone 行（`activeComponents` 为空），与只写地图 zone 的版本对比仪表上的组件。

```sh
P=com.shihab.diplay   # 车测包与正式包同包名
D=/sdcard/Android/data/$P/files
adb shell mkdir -p $D
adb push ultra-experiment.txt $D/
adb shell touch $D/frame-probe.enabled
adb shell rm -f $D/ultra-no-corner-masks.enabled $D/ultra-main-8x3.enabled
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
| 原车风格仪表 | 顶部时间/灯光/挡位/雨刷/环境温度；转速条；左侧车速和 ADAS；地图；右侧页签；底部续航、驾驶模式、水温和警告 | 近似重绘，非原厂资产；只针对固定的地图+媒体布局 |
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
- 只在一台 Android 15 测试手机和一台 iOS 27.3 iPhone 上验证过。

## 来源

协议字段对照 LIVI 固定版本 `6d1e0b5473cba464e8c46b9f027a86e61561784e`（GPL-3.0-or-later）；本实现复用项目自己的 Kotlin 编码器、加密数据通道和视频链路，没有移植其 Rust 代码或任何品牌素材。

- [iAP2 身份字段](https://github.com/f-io/LIVI/blob/6d1e0b5473cba464e8c46b9f027a86e61561784e/native/crates/iap2-csm/src/messages/identification.rs)
- [身份声明](https://github.com/f-io/LIVI/blob/6d1e0b5473cba464e8c46b9f027a86e61561784e/native/crates/livi-runtime/src/ident.rs) 与 [会话启动](https://github.com/f-io/LIVI/blob/6d1e0b5473cba464e8c46b9f027a86e61561784e/native/crates/livi-runtime/src/bringup.rs)
- [面板声明](https://github.com/f-io/LIVI/blob/6d1e0b5473cba464e8c46b9f027a86e61561784e/native/crates/livi-cp/src/info.rs) 与 [主题目录](https://github.com/f-io/LIVI/blob/6d1e0b5473cba464e8c46b9f027a86e61561784e/native/crates/livi-core/src/theme_catalog.rs)
- [UI Sync](https://github.com/f-io/LIVI/blob/6d1e0b5473cba464e8c46b9f027a86e61561784e/native/crates/livi-cp/src/ui_sync.rs)
- [VehicleState](https://github.com/f-io/LIVI/blob/6d1e0b5473cba464e8c46b9f027a86e61561784e/native/crates/livi-cp/src/vehicle_state.rs) 及同目录 body/climate/engines/media/settings/volume/wire 模块
