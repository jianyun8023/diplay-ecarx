# CarPlay Ultra 台架实验（仅 debug）

在测试手机上让 iPhone 进入 CarPlay Ultra：声明车厂主题、协商仪表布局、提供车辆数据，并在本地绘制一块原车风格的仪表。地图是 iPhone 实时视频；车速、转速、ADAS、车身读数和控制响应全部来自内置模拟器，**没有连接真实车身总线**。这是协议研究台架，不是发布功能。

## 开关与隔离

- 只有 debuggable 构建读取应用私有目录 `files/ultra-experiment.txt`；正式包不读，默认关闭，下次连接生效。
- 只有同时存在 `files/ultra-bench.enabled` 才声明模拟车辆能力；只有主题配置时只做显示层。
- 原车风格仪表 `UltraBenchActivity` 只在 debug 源集。
- 仓库不含任何车厂主题素材或主题 ID；主题由 iPhone 按 ID 从 Apple 公共资源目录下载。测试用主题 ID、设备与命令放在本地工作区，不进仓库。

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
- 未接入真实车速、CAN、ADAS 或空调；车机自身覆盖层不会消失，不能称为完整的原厂 Ultra 集成。
- 主题目录可用性、iOS 版本、地区及 Apple 远端禁用都会影响 iPhone 是否接受主题。
- 只在一台 Android 15 测试手机和一台 iOS 27.3 iPhone 上验证过。

## 来源

协议字段对照 LIVI 固定版本 `6d1e0b5473cba464e8c46b9f027a86e61561784e`（GPL-3.0-or-later）；本实现复用项目自己的 Kotlin 编码器、加密数据通道和视频链路，没有移植其 Rust 代码或任何品牌素材。

- [iAP2 身份字段](https://github.com/f-io/LIVI/blob/6d1e0b5473cba464e8c46b9f027a86e61561784e/native/crates/iap2-csm/src/messages/identification.rs)
- [身份声明](https://github.com/f-io/LIVI/blob/6d1e0b5473cba464e8c46b9f027a86e61561784e/native/crates/livi-runtime/src/ident.rs) 与 [会话启动](https://github.com/f-io/LIVI/blob/6d1e0b5473cba464e8c46b9f027a86e61561784e/native/crates/livi-runtime/src/bringup.rs)
- [面板声明](https://github.com/f-io/LIVI/blob/6d1e0b5473cba464e8c46b9f027a86e61561784e/native/crates/livi-cp/src/info.rs) 与 [主题目录](https://github.com/f-io/LIVI/blob/6d1e0b5473cba464e8c46b9f027a86e61561784e/native/crates/livi-core/src/theme_catalog.rs)
- [UI Sync](https://github.com/f-io/LIVI/blob/6d1e0b5473cba464e8c46b9f027a86e61561784e/native/crates/livi-cp/src/ui_sync.rs)
- [VehicleState](https://github.com/f-io/LIVI/blob/6d1e0b5473cba464e8c46b9f027a86e61561784e/native/crates/livi-cp/src/vehicle_state.rs) 及同目录 body/climate/engines/media/settings/volume/wire 模块
