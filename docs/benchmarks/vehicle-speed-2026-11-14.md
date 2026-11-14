# 车速、挡位与恢复：代码及既有实车证据复核

2026-11-14；生产代码检查基线 `beecdfb`。只读既有资料并运行本地定向测试，未连接设备。Ultra 参考分支为 `exp/carplay-ultra-bench`，本地提交 `eee320468c154e43420b255409a6453edb5543fa`。

## 可以确认的部分

| 范围 | 证据与结论 | 尚不能推出的结论 |
| --- | --- | --- |
| 真实车速来源 | Ultra 使用同一 `ISensor` 1048832；11 月 9 日原日志记录 `present=[speed,...]`，现场照片显示 `0.0 km/h`。11 月 11 日用户确认已测车速取得成功。另有 11 月 8 日原始系统日志记录该传感器 `0.17–0.37`、`status:active` 的非零回调。 | 这些不是当前 PASCD 发送证据；旧系统日志的 PID 1390／1567 不归入本批 v150。非零值也没有同时间独立参考速度，不能量化精度。 |
| 单位与转换 | 固件 `SensorImpl` 将 1048832 接到 `PaNaviVehSpeed`；`SensorPaUtil` 将原始整数除以 100。Ultra 与 HY 753 客户端均将返回值乘 3.6 显示 km/h，生产 PASCD 则保留 m/s。这支持当前单位和转换实现。 | 行驶时实际比例和精度尚无对照测量；不能用零速照片校准比例。 |
| 订阅／采样 | v150 `full.log` 有 8 次车速 listener 注册和即时回调路径，覆盖 4 个应用 PID。固件三参数 `registerListener` 无条件打印 rate 警告，再调用默认注册；它不表示失败，也不表示 3 Hz。旧日志单个 PID 有连续约 200 ms 更新片段。 | 不能由短片段承诺固定 5 Hz，更不能合并两个 PID 的重复事件计算采样频率。当前会话没有数值变化回调记录，不能单据此判故障。 |
| 挡位来源 | HY DCYTools 1.13 的 `ApiService` 明确将 289408000 列入属性集合，用 `CarPropertyManager` 注册并透传整数、status、timestamp；与生产选择的 `GEAR_SELECTION` 相同。实车 property dump 确认该属性为 Integer／global／on-change；v150 记录其服务端注册。 | HY 的订阅代码不等于当前 DiPlay 已实际收到全部挡位，也不能把系统服务端注册日志单独归属为应用的数值回调。 |
| 挡位映射 | 真实固件 `CarSensorEvent` 常量为 P=4、R=2、N=1、D=8，和生产 `readGear` 一致。只接受 status=0；未知值清空，不猜 P／D。Rust 在换挡时丢弃旧挡位批次。 | 仍缺当前应用的真实 P／R／N／D 值与输出对应记录。Ultra 那批明确未接挡位；原车仪表显示挡位不能替代应用验收。 |
| 会话重建 | v150 8 次重新注册、8 次 `CarImpl:connected`，采集说明包含应用 force-stop 和重连。 | 这支持应用／会话重建后能再次注册，不是车速服务或 VHAL 服务真正断开后的恢复验收。 |

## 断线恢复的实现与测试边界

- **AdaptAPI 车速服务**：生产 watcher 收到断开后清空未发送批次，停止读取；重新 ready 后保留当前有效挡位并重新取速度。真实 SDK 的 `CarImpl` 转发 ready／断开事件；`VehicleSignalManager` 重连时恢复已登记的信号回调。因此生产代码不另写一套 SDK 重订阅。
- **VHAL 挡位服务**：`onServiceDisconnected` 清除 property manager 与挡位；重新连接后 `attach()` 重新取 manager、读初值并注册。此时即使有速度，没有有效挡位也不生成 PASCD。
- **停止／新会话**：停止时清空状态并释放监听器；SDK 回调通过当前 `Run` 身份检查，旧实例回调不能修改新会话。

本轮离线运行 `EcarxVehicleSpeedSourceTest`（5）、`VehicleSpeedCoreTest`（1）、`VehicleSpeedNmeaTest`（9），**15 项通过，0 失败／跳过**。覆盖正常速度、P/R/N/D 与无效状态、AdaptAPI 断开后重新采样、停用、Rust 批次及 PASCD 编码等。

测试局限：现有 AdaptAPI 恢复用例在断开前已 drain，不单独证明“尚未 drain 的旧批次被断开清空”；VHAL property service 的断开／重连没有对应专门用例。这两项可以先补本地回归，不必等实车。真实服务中断后的恢复仍需平台证据，不能由 mock 通过替代。

## 确实缺少的验证

1. **当前包的端到端数据**：非零车速与参考速度对照、采样时间戳、P/R/N/D 实际值，以及对应 PASCD 生成／发送结果。已有源接口、单位转换与枚举依据，无需从“是否能读车速／该选哪个挡位接口”重新开始。
2. **真实服务恢复**：分别观察 AdaptAPI 和 VHAL 的断开、恢复及新数据到达，确认中断期间不继续发送旧批次。应用重启和手机重连不能代替这两类事件。

补证应同时保留应用诊断／会话文件。`VehicleSpeedLocationProvider` 的 `withWheelSpeed`、`wheelSpeedSamples`、`lastWheelSpeed` 写入应用文件日志；`CarPlayHostActivity.appendLog` 不把它们写入系统 logcat。故 `full.log` 没有这组摘要不证明没有采集或发送。该摘要统计 provider 生成的读数，也不是 iPhone 已接收的确认；挡位原值、逐样本时间和实际发送仍需对应证据。

## 可追溯材料

以下原始材料留在仓库外的工作区，不随文档发布：

- `logs/cap-v150-20261114-session1/full.log`：共 432734 行；车速注册 8、即时回调路径 8、带数值回调 0、`CarImpl:connected` 8、`CarImpl:disconnected` 0、挡位属性服务端注册 8。注册首例在 15197／15224／15228／15231 行；不同 PID／不同会话分开理解。`SESSION.md` 保存版本与操作背景。
- `logs/v141-cartest-20261108/logcat-20261108-203053.txt`：65588 行起有低速非零回调；75314／75388／75457／75539 行的同 PID 更新约隔 200 ms。共 32 条、来自两个 PID，不算 32 个独立采样。
- `artifacts/cartest-ultra-probes-v145-20261109/reports/DiPlay-20261109-200054-747.txt:3281`；同批 `photos/03-2022-vehicle-data-rows.png`；11 月 11 日现场确认见 `analysis/20261111-carplay-ultra/证据索引.md`。
- `analysis/dcytools113-20261029/sources/com/hyphp/hywebviewdcltools/ApiService.java:144,463,509`：HY DCYTools 的属性集合、整数回调和注册路径。`analysis/20261107-voice-key/interactive/sources/android/car/hardware/CarSensorEvent.java:20–28`：固件挡位常量。
- `oem-launcher/build/adaptapi-src/sources/com/ecarx/xui/adaptapi/car/sensor/`：`SensorImpl`、`SensorPaUtil`、`SensorListener`；同目录上层 `CarImpl` 与 `se/cevt/vehiclesignals/VehicleSignalManager.java`：连接通知和 SDK 重订阅。
- `analysis/20261114-vehicle-speed-review/`：日志摘录及 SHA-256、此次 3 个测试类的 JUnit XML。

这些证据缩小了验收缺口；本轮没有确认需要修改生产逻辑的故障，也没有把历史 Ultra 成功扩展为当前 PASCD 全链路通过。
