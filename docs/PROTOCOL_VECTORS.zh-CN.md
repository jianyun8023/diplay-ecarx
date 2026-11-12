# 协议样本与黄金向量清单（Protocol Vectors）

本文固定协议迁移使用的样本来源、预期字节／哈希、失败样本与对应测试，避免把“实现自己算出的结果”当作测试答案。新增协议迁移必须先在本表登记，再写实现。

## 1. 来源固定

| 来源 | 固定值 | 用途 |
| --- | --- | --- |
| LIVI Rust | `6ac88bab7351a39b0cffac20c5dcf92ca848319b` | bplist、control cipher、HID、RTSP 的黄金字节 |
| Bouncy Castle | 1.79（旧 Kotlin 行为） | PairVerify 公钥规范／低阶点拒绝边界 |
| 目标车机日志 | ECARX 753 / 820A 实车与桌面 dump（见 `docs/benchmarks/native-media-2026-11-12.md`） | HUD/IMEI/PASCD 等厂商语义 |
| Android 平台 | API 28 / API 35 行为对照 | UTF-16／ASCII 替换、`Math.round`、Long 环绕等 |

规则：

1. 向量输入与预期输出必须来自上述外部来源或已归档的原始日志，**不得用本仓库实现生成预期值**。
2. 同一向量至少有一个 Kotlin 断言和一个 Rust 断言；能上设备的再加设备 parity 测试。
3. 失败样本与成功样本同等重要：截断、非法长度、认证失败、畸形 UTF-16 必须显式登记。
4. 桌面 JDK 与 Android 行为不一致时，各自保留独立黄金值，不互相覆盖（例：`ED A0 80` 替换字节数）。

## 2. 当前向量登记

| 协议 | 向量 | 预期 | Kotlin 测试 | Rust 测试 | 设备 parity |
| --- | --- | --- | --- | --- | --- |
| bplist | LIVI 458 字节字典 | SHA-256 `b4e3e93557bb97d6207978c92e3a52f533a60cd3f0e9034a4253c080b83e872a` | `BplistCodecContractTest` | `protocols/bplist::reference_wire_vector_matches` | `NativeBplistDeviceTest` |
| bplist | 整数宽度 65536 / 2^40 / 2^63 / u64::MAX | 零填充字节序列 | `BplistCodecContractTest` | `unsigned_integer_widths...` | 同上 |
| bplist | 失败样本 | bad magic／截断／超深／膨胀／非字符串键 | `BplistCodecContractTest`（异常） | `rejects_bad_headers...`、fuzz mutations | — |
| control cipher | LIVI cipher／cipher2 | 首帧 SHA `d9a80f…cabd2`，第二帧十六进制固定 | `ControlCipherTest` | `control_cipher::reference_vectors_match...` | `NativeControlCipherDeviceTest` |
| control cipher | 失败样本 | tag 篡改→`AEADBadTagException`；非法 key；部分帧保留前缀计数 | `ControlCipherTest` | `a_failure_keeps_the_counter...` 等 | 同上 |
| HID | LIVI ts_vectors | touch `00016500cf02010003000400`、knob、4 类 descriptor 哈希 | `AirPlayHidWireTest` | `vehicle/hid` 黄金测试 | `NativeHidDeviceTest` |
| HID | 边界样本 | NaN／±Inf／±0／半舍入／低 16 位／±127 | `AirPlayHidWireTest` | `coordinates_keep_java_rounding...` | 同上 |
| RTSP | 标准请求／500 响应 | 逐字节固定 | `RtspMessageWireTest` | `protocols/rtsp` 测试 | `NativeRtspDeviceTest` |
| RTSP | 失败样本 | header>16KiB、body>1MiB、非法 Content-Length、重复头 | `RtspMessageWireTest` | `peer_limits_are_checked...` | 同上 |
| PairVerify | M1/M2/M3/M4 密钥派生 | 独立客户端 + BC 1.79 合同 | `PairVerifyContractTest` | `protocols/pair_verify` 测试 | `NativePairVerifyDeviceTest` |
| PairVerify | 失败样本 | 伪签名／低阶点／非规范 y／截断 TLV／未知 controller | 同上 | `controller_public_key` 测试 | 同上 |
| EV 0xA101 | 电耗已知／未知／溢出 | 固定参数字节 | `Iap2VehicleStatusTest` | `vehicle/vehicle_status` 测试 | `NativeVehicleStatusDeviceTest` |
| 导航 | TLV／缓存／ETA／溢出 | 固定输入输出 | `RouteGuidanceStateTest` | `vehicle/route_guidance` 测试 | `NativeRouteGuidanceDeviceTest` |
| iAP2 body | 参数顺序／重复 id／畸形长度 | 参数头 `u16 length｜u16 id｜payload` | `Iap2ProtocolTest` + `parseKotlin/encodeKotlin` | `protocols/iap2_body` 测试 | `NativeIap2BodyDeviceTest`（独立分支） |
| Identification 20/21 | 名称 + connector 组合 | 纯 TLV，Kotlin fallback 对照 | `Iap2ProtocolTest` / device test | `vehicle/identification` 测试 | `NativeVehicleIdentificationDeviceTest`（独立分支） |

## 3. 已知缺口（后续补）

1. **PairVerify 没有完整 transcript 的外部 golden**：目前用独立客户端与 BC 合同交叉验证；应补一份固定 M1/M2/M3 原始字节与期望回复。
2. **bplist 失败样本**主要是实现内的变异 fuzz，缺一份来自外部实现的畸形样本集。
3. **iAP2 body** 刚迁移，尚未登记更多真实 CSM 帧样本；应从实车／原始日志补固定帧。
4. **HUD/仪表** 的箭头、车道图标、日夜映射目前只有 Kotlin 与 Rust 的合同测试，需补一份来自车机日志的输入→期望值样本。

## 4. 新增向量流程

1. 在 `docs/PROTOCOL_VECTORS.zh-CN.md` 登记：来源版本、原始输入、预期字节或哈希、失败样本。
2. 把原始字节以十六进制字面量或资源文件加入测试，不从生产实现导出。
3. Kotlin 与 Rust 各写独立断言；有 native 时加设备 parity（native vs Kotlin fallback 或 native vs 固定字节）。
4. 若桌面与 Android 行为不同，新增平台专属黄金值并注明原因。
