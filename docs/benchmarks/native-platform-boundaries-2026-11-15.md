# MQTT、HUD、控制缓冲与下行音频职责收敛（2026-11-15）

本轮为 MQTT、HUD、RTSP 输入与下行音频四项收敛（原工作包 I–L），以 AAC 输入与原生采集链合入后的 `23838d88829b5b62cc99bf5d311ed9ac692ca4ba` 为基线，工作分支为 `refactor/native-platform-boundaries`。四项已实施，不新增第三方依赖或 Kotlin 算法回退；当前归属见[架构说明](../ARCHITECTURE.zh-CN.md)。本记录区分源码检查、合同测试和真实连接，不将小米结果追认为 ECARX 实车验收。

## 最终边界

| 范围 | Rust/C 负责 | Kotlin 保留 |
| --- | --- | --- |
| ECARX MQTT | framing、ACK、packet ID、投屏／ongoing JSON 生成、发布去重与重试、12 个订阅主题的观察状态与摘要规则 | Socket／InputStream、执行器、锁、配置、JSONObject／UTF-8 平台适配、日志 |
| HUD | 投屏意图、重试倒计时、车道缓存、导航快照、发布差异、刷新与清理计划 | DIM 对象和实际调用、调用结果提交、持久设置与生命周期 |
| AirPlay control／event | 每个读取器的加密和明文残留、解密后增量解析 | Socket 读取、平台动作和完整消息回调；借用既有 cipher 并串行关闭 |
| 下行音频 | LPCM／AAC／Opus 分派、SDK 配置字节、输入泵和输出排空、libopus 能力选择、PCM 工作缓冲与 DSP | MediaCodec 对象的创建／配置／关闭，AudioTrack／AAudio 写入、焦点、路由、增益和诊断 |

HUD、RTSP 残留、PCM 工作缓冲由原生堆所有者持有。MQTT 延续项目的 GC 值 token 方案：token 的物理载体仍由 JVM 管理，内容只由 Rust 解释和变更。平台 JSON 解析、SDK 对象和跨 JNI 的 DTO／字节数组仍存在，不能将本轮描述成“全部数据都在 Rust”。

下行音频保留原生 libopus 不可用时的 **SDK 解码能力路径**，由 Rust 选择并驱动既有 MediaCodec；没有恢复 Kotlin 输入／输出算法或 PCM 工作缓冲。G/H 上行麦克风的无 SDK Opus 编码回退要求保持不变。

## 保留的合同

- MQTT 发布端保留原宽松 ACK，订阅观察端保留严格 ACK；remaining-length 保留三字节上限。投屏只在 PUBACK 成功后提交，ongoing 在排队前去重。连接失败立即结束，发布失败断开后只重试一次；packet ID 回绕与提交顺序不变。
- HUD 保留 2 秒／30 次快重试、之后 10 秒慢重试及 5 tick 刷新；32 项车道缓存保护当前索引。实际 SDK 调用成功后提交快照，清理覆盖半成功发布；持久化清理责任先于投屏打开，隐藏成功后才清除责任。
- RTSP 使用原 cipher 和解析限制，收发计数独立；一次读取内的完整消息仍一起解析，只将残留留在 Rust。异常、长度限制和同步关闭边界保持。
- 音频保留 10 ms 输入 dequeue、100 ms 音频输入预算、Opus pre-skip 和既有车机校准。SDK 输出复制后先 release 再同步写 PCM；复制失败仍 release，release 失败遵循 Java `finally` 异常优先级。跨 Java 回调前结束 Rust 可变借用，PCM 视图只在回调内有效，写入回调可重入 DSP。

## 主机与构建验证

| 检查 | 结果 |
| --- | --- |
| Rust `cargo test --locked --lib` | 262 通过；完整运行允许既有 UDP socket 测试所需的本机权限 |
| shared JVM | 2029 项：2027 通过，2 项既有环境跳过，0 失败 |
| common JVM | 首轮 1584 通过；另 20 项因 HUD 旧字段反射失效，改为访问新门面后定向重跑 20/0；原行为断言不变 |
| Android 三 ABI 构建与导出核对 | ARM64、ARMv7、x86_64 均通过；每个 ABI 568 个 Java JNI 导出及 `JNI_OnLoad` |
| androidTest APK | 编译通过；APK 内三份 Rust 库与 Gradle stripped 输出逐字节一致 |
| 主应用 Release／R8 | 三 ABI APK 构建通过；8 个 AudioRenderer 回调和 4 个 MQTT 发布回调保留；ARM64 本地签名包实测 |

common 结果覆盖全部 1604 项，但不是修复夹具后又完整执行了一轮。工作区同时存在独立的启动链路改动；测试与车测 APK 基于当时完整工作区，本轮职责迁移不包含那些改动。

## 小米合同测试

设备为 MI 8、Android API 35。测试包 `com.shihab.diplay.shared.test` 在 ARM64 和 ARMv7 进程中分别运行，**均为 8/8 通过**；结束后恢复 ARM64，测试包的 `RECORD_AUDIO` 仍为未授权。本组测试没有启动录音或扬声器播放。

| 测试类 | 覆盖 |
| --- | --- |
| `MqttBoundaryDeviceTest` | framing／短读／ACK／异常，完整重试轨迹，投屏／ongoing／观察去重和 JSON 边界 |
| `HudPublicationDeviceTest` | 重试阶段、车道缓存、当前索引、发布差异、清理、重置与关闭 |
| `ControlInputDeviceTest` | 加密切片、明文残留、多消息、cipher 激活、计数、限制、认证失败和关闭 |
| `DownlinkPcmDeviceTest` | LPCM 大缓冲增长、字节序和奇数尾部，真实 C Opus 解码及 pre-skip，配置、无效包、同步 PCM 回调重入 |
| `DownlinkAacDeviceTest` | 真实 SDK AAC 编码 → Rust ADTS／输入驱动 → SDK 解码 → Rust PCM 缓冲与 DSP，验证有效 PCM 输出 |

AAC 有限测试帧需要向解码器发送 EOS 排空尾帧。初次运行漏发 EOS，PCM 总量断言失败；修正测试收尾后两个 ABI 均通过，未为此修改生产解码行为或降低断言。

测试 APK SHA-256：`81c622b2d6d859c3d4e0b2092e7364e57079542436810b2a37f75d4fedb175d8`。

三 ABI 的原生源码 receipt SHA-256：`023b53aef266fcf9336edbfaa68ba0c51ef89fc1b8fb41d590779ebd26bcc88d`。

| ABI | APK 内 `libdiplay_media.so` SHA-256 |
| --- | --- |
| arm64-v8a | `6a5886ec6bf842f084994a886f6a9b9793392400e34d4fb54f23cbedb88183da` |
| armeabi-v7a | `56798d6d7d0afe80bb8520a27de485b4992540c0608350ce70e76543d2b0f099` |
| x86_64 | `62e30477c57c648ba649307db0721ea7f64bd36365e31da45c295007a9d2f0ef` |

Gradle 会 strip 调试符号，因此 APK 库的 SHA 与 `.native` 原始库不同，不能直接要求二者 SHA 相等。本轮另用同一 NDK 的 `llvm-strip --strip-unneeded` 处理 receipt 对应原始库，三个 ABI 均与 APK 内库逐字节相等。x86_64 本轮仅构建／导出检查，没有设备运行。

## 无线 CarPlay 实测

覆盖安装主应用 `com.shihab.diplay`，版本 `0.2-car-test-native-platform-boundaries-20261115`（v150／ARM64／Release R8）。安装前核对签名及两项本地认证资产与原 APK 一致，保留配对、应用数据和已有权限。其 Rust 库与上述合同测试 APK 的 ARM64 库逐字节一致。

本地主应用 APK SHA-256：`48e669635c6e21ab4b9c58f7c40689b076889ad90581bb719f755aac82a21642`。此包包含用户本地认证资产，仅用于本次设备测试，不上传公开仓库。

以下时刻使用设备日志的本地时间；用户在测试期间明确确认“画面和声音均正常”。

| 场景 | 观察结果 |
| --- | --- |
| 无线连接 | 首次连接于 12:28:38 输出 1920×1080 视频首帧；标准 `DiPlay CarPlay` MediaSession 为 active |
| 音乐起播 | 定向 MediaSession 发送播放媒体键后，iPhone 返回 playing=true，Android 状态为 PLAYING；48 kHz／双声道 AAC 经 SDK 解码，12:30:27 首次输出 4096 B 原生 PCM 并启动 AAudio |
| 暂停／恢复 | 暂停后收到 iPhone playing=false 和 PAUSED 状态；在确认暂停后再次播放，收到 PLAYING，并重新输出原生 PCM／启动 AAudio |
| 下一曲 | 下一曲媒体键发送成功，收到曲目元数据变更与持续播放状态 |
| Opus 下行 | 测试期间实际建立 type=101／audioType=default 流；libopus 首帧输出 1296 B 原生 PCM，启动 NAVIGATION 路由的 AAudio，正常 TEARDOWN；接收统计 1171 包、序号缺口 0。此项不代表完整 Siri／麦克风验收 |
| 正常断开 | 12:39:14 通过应用断开按钮结束；control／event 与媒体流关闭，`shutdown complete clean=true`，旧 controller 资源释放完成 |
| 重连 | 新 controller 于 12:40:22 启动，12:40:26 再次输出视频首帧；12:40:28 再次输出 4096 B 原生 AAC PCM 并启动播放 |

媒体控制使用 `cmd media_session monitor "DiPlay CarPlay"` 定向发送媒体键，状态用 `dumpsys media_session` 及 iPhone 回报交叉核对。该 shell 工具走 `onMediaButtonEvent`：项目原有硬件播放／暂停键按 toggle 处理，因此先确认当前状态再发送；它不等同于直接调用 `TransportControls.play/pause`，本轮未改变该映射。

初次连接持续约 10 分 36 秒，包含暂停、切歌、导航消息和 Opus 流；正常重连后继续验证 AAC 输出。应用进程保持存活，记录中未见 JNI 错误、native 崩溃或 Java 致命异常。结束时保留新版应用和无线连接，通过 MediaSession 将音乐暂停，核对最终为 PAUSED。这是短时功能实测，不是长时间稳定性或性能对比。设备现有模拟副屏遮挡了断开按钮一部分，测试从未遮挡处点击，未调整副屏设置。

## 证据与适用范围

本地日志位于工作区父目录 `build/native-boundary-*`：`rust-tests.log`、`regression.log`、`package.log`、`xiaomi-arm64.log`、`xiaomi-armv7.log`、`mobile-apk.log`、`wireless.log`、`reconnect.log` 和 `session-*.txt`。原始设备日志、认证资产和签名材料不提交。

未覆盖 ECARX 753／API 28 的真实 MQTT broker 与 DIM 发布、实车焦点／音量／路由校准或性能收益；这些需要车端验收。小米合同测试验证跨语言调用、设备 SDK 和内存所有权，不替代导航显示、实际音质与长时间稳定性验收。
