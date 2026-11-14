# 原生视频首帧验证（2026-11-14）

## #113：已合并的配置／首包顺序修复

[PR #113](https://github.com/jianyun8023/diplay-ecarx/pull/113) 将 Surface 接收能力在发布路由前初始化，并在 Rust 保留首包直到所有待处理配置完成；配置暂停与故障退休分开，失败后允许新关键帧重试。它修复的是配置／首包门控，不能解释为固定 39 ms 的 SurfaceView 成本。

- 被测 head：`b7444ad5e8f0c74688543f3d20927733390cc43c`。
- 合并提交：`eeca03aef613193e7a49805b702bcc28ce3dcefb`，与被测 head 的 tree 均为 `6ba0d7f44549a3a65e53bc111a005cc64bae55f8`。
- APK 来自被测 head；tree 相同不把既有 APK 改称合并提交重建产物。

| 检查 | 结果与范围 |
| --- | --- |
| 完整 Gradle Checks | 成功，9 分 35 秒；JVM 3593 通过、2 环境跳过、0 失败。 |
| Rust／原生构建 | 226 项 Rust 测试通过；ARM64／ARMv7／x86_64 构建及 494 JNI 导出核对通过。构建不代表三 ABI 均已运行时验收。 |
| 小米 8／API 35／ARM64 设备回归 | 16 项通过、0 跳过，7.38 秒：8 startup＋3 加密 video route＋5 audio route。视频使用 320×240 单 IDR；音频路线为合成输入，不能代替真实 iPhone／实车听感。 |
| GitHub CI | [run 43583202996](https://github.com/jianyun8023/diplay-ecarx/actions/runs/43583202996)，9 jobs 全部成功，对应上述被测 head。 |

同 APK 交替 A1/B1/A2/B2/A3/B3，仅替换 ARM64 原生库；两包均带新 Kotlin。Rust 相对 `663bf028` 到被测 head 仅 `vpipe.rs` 改变；它隔离 Rust 修复，不覆盖 Kotlin 接收初始化改动，也不是完整旧 v150 应用对比。

| 主屏日志间隔（ms） | A：旧 native | B：修复 native |
| --- | --- | --- |
| decoder ready → first input | 108 / 82 / 87；中位数 87 | 5 / 6 / 3；中位数 5 |
| decoder ready → first rendered | 158 / 125 / 137；中位数 137 | 58 / 54 / 29；中位数 54 |
| SETUP 110 → first rendered | 350 / 298 / 300；中位数 300 | 243 / 284 / 174；中位数 243 |
| 关键帧请求 | 每轮 1 次 | 每轮 0 次 |

端点按主屏线程／端口映射逐轮核对；不能将副屏首次事件混入，也不能把各段中位数相加推导端到端时间。上述为日志检查点间隔，未测物理显示时刻。首解密和首输入尺寸一致仅是旁证，没有 monotonic packet identity，不能据此认定同一包。

#113 修复包另做首帧后 300.334 秒持续会话，计划结束时主动 force-stop。该区间未见应用 crash／ANR、自然 tunnel 掉线或视频错误，两路接收在停止前仍活动；启动 RFCOMM 断开属于无线切换，另有 3 次既有蓝牙 guard callback timeout，不把它写成自然视频掉线或“所有日志零异常”。#113 这批会话没有实际音频 SETUP／接收／解码路线，不能把它作为音频组合验收。

两次资源快照同进程：FD 257→263（+6）、线程 67→67、PSS 94039→97775 KiB（+3736）。这是短时观察，不能据此断言有无泄漏。#113 测试结束后已恢复原 APK 和 11 份 shared preferences，移除新增测试包、停止应用并关闭热点；该恢复记录不属于下述后续批次。

原始日志、APK、逐轮端点／行号、解析脚本与机器可读汇总保留在本地工作区 `artifacts/mi8-first-frame-pr-validation-20261114/`；不随公开仓库提交。目标 ECARX／API 28、实际 scanout、实车音频／Siri／通话、熄火与长测缺口见[当前验收矩阵](../RUST_CORE_REMAINING.zh-CN.md)。此前 native-only probeA/B 是定位实验，不是生产包完整单变量验收。

## 后续批次：配置失败交错（修复已实现，green 待验）

基线为已合并 #113 的 `dev @ eeca03aef613193e7a49805b702bcc28ce3dcefb`。新增两项测试通过真实 `VideoDecoder` 入口复现：旧配置失败的清理与后来收到的新配置／唯一 IDR 交错时，新帧被丢弃。它是 #113 既有设备集合未覆盖的失败交错，不改写上述历史通过数。

测试用公开 `Surface(SurfaceTexture)` 创建后已释放的 Surface，确定性触发旧 AVC 配置失败；在失败报告、清理之前排入有效 Surface、新 HEVC 配置和唯一 IDR。重建例先完成旧 Config 的 hold，再发送旧 AVC IDR，触发原生 `NEED_DECODER` 的真实重建失败。测试不 mock codec 工厂或生产清理路径，不补发第二个 HEVC IDR。

| 新增设备测试 | 小米 8／API 35／ARM64 的 red 结果 |
| --- | --- |
| `newHevcConfigKeepsItsOnlyIdrWhenOlderConfigFails` | 新 HEVC 硬解配置成功；唯一 IDR 的呈现断言失败。 |
| `newHevcConfigKeepsItsOnlyIdrWhenPipeRebuildFails` | 无 Config hold 的原生重建失败后，新 HEVC 硬解配置成功；同一呈现断言失败。 |

共 2 项、2 失败，10.815 秒。原始 logcat 的新 HEVC 配置成功在第 293／655 行，测试失败在第 301／665 行；没有首帧呈现或 CheckJNI 致命错误。旧 AVC 配置失败是受控激励；本轮 red 是成功配置 HEVC 后丢失唯一首帧，并非硬件拒绝有效 HEVC 或夹具编译失败。

- red 测试 APK SHA-256：`883d1c0580fe8e209498fa164f15b52880c2f95c977e6e61744693934d08b4e4`。
- APK 内 ARM64 `libdiplay_media.so` SHA-256：`675e12469a1ea33fdec41f0d9663c781d0ef9cffa955454f9a7bb832bf1a824d`；该值指打包库，不与 strip 前原库哈希混用。
- 本地证据：`artifacts/mi8-config-failure-order-20261114/` 的 `red.txt`、`red.logcat.txt`、`red-verification.json`。原始文件不提交公开仓库。

生产修复已实现，涉及 `NativeMedia.kt`、`NativeVideoPipe.kt`、`VideoDecoder.kt`、`vpipe.rs` 四文件，17 行新增／11 行删除。Config job 的失败清理传入自身持有数 1，原生 `NEED_DECODER`／Surface 重建传入 0，普通 release 传入 -1。Rust 清除旧 codec 并等待在途 feeder 后，在与接收端 hold／submit 共用的锁内判断：没有后来配置的 hold 才清理失败链，否则保留新帧；所有待处理配置完成前仍不得喂入。

| 本批本地检查 | 结果 |
| --- | --- |
| 完整 Gradle Checks／四应用 R8 | 成功，8 分 53 秒；466 tasks（88 executed）。四应用 `lintDebug`、`assembleDebug`、`minifyReleaseWithR8` 及 shared 测试 APK 构建通过。 |
| JVM | 3593 通过、2 环境跳过、0 失败，共 3595 项；shared 1985 通过＋2 跳过、common 1594 通过、home 14 通过。跳过项为端口环境与 ECARX 真实 SDK ABI。 |
| Rust | 226 通过、0 失败，10.70 秒。 |
| 原生构建／来源校验 | ARM64／ARMv7／x86_64 均通过，494 Java JNI 导出＋`JNI_OnLoad`；三 ABI 来源 manifest 一致。 |
| 修复测试 APK | 已构建并签名；尚未安装、尚未执行设备 green。 |

- 待验修复 APK SHA-256：`42237fc7eca9bc403cb2e64f58aa6363a23ebc7ccd28f6143db89ad479131b53`。
- 该 APK 内 ARM64 `libdiplay_media.so` SHA-256：`a980be3007308b4b5854c3af5d1c8af11cd6c02bf890f6aa02386c53d1dbb6f3`。strip 前原库为 `7f4f4ccbe79a0a6899dbb8de874cec20b77a8e8f284bfb2002f3947e5ee94a1a`，二者分属不同构建阶段。
- 本地补充证据：同目录 `checks-summary.json`、`gradle-checks.log`、`rust-tests.log`、`native-build.log`、`fixed-verification.json`；Checks 记录的五份生产／测试源码指纹与当前文件一致。

**当前冻结状态：修复已实现、有效 red 已复现、本地检查通过；ARM64 设备 green 待验，PR 尚未创建。** 小米 8 正被其他测试占用，按用户要求等其通知设备释放后再执行 ARM64 回归，完成验证后再创建 PR。本批尚无修复后实机通过或新增设备恢复记录，不继承 #113 的设备结果。
