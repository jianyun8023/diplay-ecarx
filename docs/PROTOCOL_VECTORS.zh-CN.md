# 协议样本与黄金向量清单（Protocol Vectors）

本文登记协议核心的样本来源、预期字节／哈希、合同与测试入口。外部黄金字节、独立数学参考、旧实现合同和设备差分分别记录，不能把一次往返成功当作独立黄金向量。

## 1. 来源固定

| 来源 | 固定值 | 用途 |
| --- | --- | --- |
| LIVI Rust | `6ac88bab7351a39b0cffac20c5dcf92ca848319b` | bplist、control cipher、HID、RTSP 的黄金字节 |
| Bouncy Castle | 1.79（旧 Kotlin 行为） | PairVerify 公钥规范／低阶点拒绝边界 |
| 目标车机日志 | ECARX 753 / 820A 实车与桌面 dump（见[实验记录](benchmarks/native-media-2026-11-12.md)） | PASCD 固定报文及厂商语义；HUD 完整输入输出样本仍待补 |
| Android 平台 | API 28 / API 35 行为对照 | UTF-16／ASCII 替换、`Math.round`、Long 环绕等 |
| 独立 SRP 数学参考 | [公开输入及固定结果](../shared/src/test/resources/pairsetup-srp-golden.properties)，Python 标准库 hashlib／pow 独立重算 | 固定 SRP A／B／S／K／M1／M2；不是完整 PairSetup TLV transcript |

规则：

1. 外部 golden 应固定源码版本或日志来源；独立数学 reference 应保留公开输入、生成方法及固定预期。**不得直接导出生产实现的输出再称其为独立预期**。
2. Kotlin／Rust／设备测试入口按下表如实登记；测试存在不代表已在当前版本或全部设备执行通过。旧实现差分用于保留合同，不能替代外部互通证据。
3. 失败和宽容样本均应记录：截断、非法长度、认证失败及畸形 UTF-16 可能被拒绝、替换或忽略，以实际合同为准。
4. 桌面 JDK 与 Android 行为不一致时，各自保留独立黄金值，不互相覆盖（例：`ED A0 80` 替换字节数）。

## 2. 当前向量登记

| 协议 | 向量 | 预期 | Kotlin 测试 | Rust 测试 | 设备 parity |
| --- | --- | --- | --- | --- | --- |
| bplist | LIVI 458 字节字典 | SHA-256 `b4e3e93557bb97d6207978c92e3a52f533a60cd3f0e9034a4253c080b83e872a` | `BplistCodecContractTest` | `protocols/bplist::reference_wire_vector_matches` | `NativeBplistDeviceTest` |
| bplist | 整数宽度 65536 / 2^40 / 2^63 / u64::MAX | 零填充字节序列 | `BplistCodecContractTest` | `unsigned_integer_widths...` | 同上 |
| bplist | 失败样本 | bad magic／截断／超深／膨胀／非字符串键 | `BplistCodecContractTest`（异常） | `rejects_bad_headers...`、fuzz mutations | — |
| control cipher | LIVI cipher／cipher2 | 首帧 SHA `d9a80f2f38ddd9c2af5f3ac536cd481e4dcfa9dd38c87b6d400289ccfc8cabd2`，第二帧十六进制固定 | `ControlCipherTest` | `control_cipher::reference_vectors_match...` | `NativeControlCipherDeviceTest` |
| control cipher | 失败样本 | tag 篡改→`AEADBadTagException`；非法 key；部分帧保留前缀计数 | `ControlCipherTest` | `a_failure_keeps_the_counter...` 等 | 同上 |
| HID | LIVI ts_vectors | touch `00016500cf02010003000400`、knob、4 类 descriptor 哈希 | `AirPlayHidWireTest` | `vehicle/hid` 黄金测试 | `NativeHidDeviceTest` |
| HID | 边界样本 | NaN／±Inf／±0／半舍入／低 16 位／±127 | `AirPlayHidWireTest` | `coordinates_keep_java_rounding...` | 同上 |
| RTSP | 标准请求／500 响应 | 逐字节固定 | `RtspMessageWireTest` | `protocols/rtsp` 测试 | `NativeRtspDeviceTest` |
| RTSP | 失败样本 | header>16KiB、body>1MiB、非法 Content-Length、重复头 | `RtspMessageWireTest` | `peer_limits_are_checked...` | 同上 |
| PairVerify | M1/M2/M3/M4 密钥派生 | 独立客户端 + BC 1.79 合同 | `PairVerifyContractTest` | `protocols/pair_verify` 测试 | `NativePairVerifyDeviceTest` |
| PairVerify | LIVI hkdf／edSig／chachaLabel／tlv8 | 固定 primitive 字节；不是完整握手 transcript | `PairVerifyContractTest` | `protocols/pair_verify` primitive 测试 | `NativePairVerifyDeviceTest` |
| PairVerify | 拒绝与宽容样本 | 伪签名／低阶点／非规范 y／未知 controller 拒绝；截断必需项失败，已完成项后的截断 duplicate 可被忽略 | 同上 | `controller_public_key` 及握手测试 | 同上 |
| PairSetup／SRP | LIVI srpSalt／srpB + 独立数学参考 | LIVI 仅固定 salt、B长度／哈希；数学 fixture 固定完整 A／B／S／K／proof | `Srp6aContractTest` | `protocols/pair_setup/srp` 固定数学 fixture | `NativePairSetupDeviceTest` |
| PairSetup | 完整随机 M1–M6 与错误／部分提交合同 | 独立 SRP client、BC 签名／AEAD、实时存储；非外部完整 golden | `PairSetupContractTest` | `protocols/pair_setup` save／M6／identity failure 合同 | `NativePairSetupDeviceTest` |
| EV 0xA101 | 电耗已知／未知／溢出 | 固定参数字节 | `Iap2VehicleStatusTest` | `vehicle/vehicle_status` 测试 | `NativeVehicleStatusDeviceTest` |
| 导航 | TLV／缓存／ETA／溢出 | 固定输入输出 | `RouteGuidanceStateTest` | `vehicle/route_guidance` 测试 | `NativeRouteGuidanceDeviceTest` |
| iAP2 body | 参数顺序／重复 id／畸形长度／精确预算／复制 | 参数头 `u16 length｜u16 id｜payload`；standalone parse 可接受不能再 encode 的大参数 | `Iap2ProtocolTest` + `parseKotlin/encodeKotlin` | `protocols/iap2_body` 测试 | `NativeIap2BodyDeviceTest` |
| Identification 20/21 | 名称 + connector 组合 | 完整固定TLV及Kotlin字段合同；平台字符串codec保留NUL拒绝／畸形UTF-16替换 | `Iap2VehicleStatusTest`／设备原字段合同 | `vehicle/identification` 固定字节 | `NativeVehicleIdentificationDeviceTest` |
| PASCD | 固定车机报文／格式化边界 | 原始`$PASCD,...*54`完整报文；1.005舍入／负零／非有限值采用本平台旧Formatter对照 | `VehicleSpeedNmeaTest` | `vehicle/vehicle_speed` 布局／校验固定字节 | `NativeVehicleConversionDeviceTest` |
| NowPlaying 0x5001 | 增量字段／空文本／错误长度／畸形 UTF-8 | 保留旧 Kotlin 字段合并与平台解码合同；实车原始 elapsed 字节保留原缩放 | `CarPlayPlaybackStatusTest`、`PlaybackCoreParityTest` | `session/playback` 字段与保留状态测试 | `NativePlaybackCoreDeviceTest` |
| LaneGuidance 0x5204 | 原始车道报文／截断／短重复字段／±1000 unknown angle | 完整更新按 lane index 稳定排序；角度保留 wire 顺序；只有空 body 表示清空 | `Iap2LaneGuidanceTest`、`LaneGuidanceCoreParityTest` | `vehicle/route_guidance::lane_updates...`，复用 `protocols/iap2_body` TLV parser | `NativeLaneGuidanceDeviceTest` |
| AirPlay 声明／控制／Bonjour | 旧声明／固定 command／status line／probe 计划；平台极值与文本 | 保留字节顺序和 JVM 标量类型；API 28 BigInteger 极值／Regex 数字分类按本平台旧实现对照，不以桌面结果覆盖 | `NativeAirPlayProtocolTest`、`CarPlayBonjourTest`、`BonjourProbePolicyTest` | `protocols/airplay` owner 测试 | `NativeAirPlayProtocolDeviceTest` |

表中设备列登记测试入口，执行结果与源码修订范围见[本轮审计](RUST_CORE_REMAINING.zh-CN.md)；旧候选的成功结果不代表新增源码已经通过验收。网络地址／readiness／radio-return 与导航播放等非 wire 状态合同也登记在该审计中。

## 3. 已知缺口（后续补）

1. **PairVerify 没有完整 transcript 的外部 golden**：目前用独立客户端与 BC 合同交叉验证；应补一份固定 M1/M2/M3 原始字节与期望回复。
2. **bplist 失败样本**主要是实现内的变异 fuzz，缺一份来自外部实现的畸形样本集。
3. **iAP2 body** 尚未登记更多真实 CSM 帧样本；应从实车／原始日志补固定帧。此表列源码测试入口，当前状态见[验收矩阵](RUST_CORE_REMAINING.zh-CN.md)，issue #96 和实验记录保留历史批次结果。
4. **HUD/仪表** 的箭头、车道图标、日夜映射目前只有 Kotlin 与 Rust 的合同测试，需补一份来自车机日志的输入→期望值样本。

## 4. 新增向量流程

1. 在 `docs/PROTOCOL_VECTORS.zh-CN.md` 登记：来源版本、原始输入、预期字节或哈希、失败样本。
2. 把原始字节以十六进制字面量或资源文件加入测试，不从生产实现导出。
3. Kotlin 与 Rust 各写独立断言；增加设备 parity 时，对比 native 与测试源码中的独立参考或固定字节，不恢复生产 Kotlin 算法回退。
4. 若桌面与 Android 行为不同，新增平台专属黄金值并注明原因。
