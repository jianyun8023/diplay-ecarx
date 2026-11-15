# 媒体状态归属补齐（2026-11-14）

基线为 #114 的 `f689c7226e40b56eb06e127d02b610ecf35a9926`。本记录针对其后的工作区重构，不替代 #113、#111 或配置失败交错修复的历史设备结果。

## 本次边界

- Rust 拥有视频配置轮次、最近配置、重复判断、首帧降级及同一 pipe 的关键帧节流。
- Rust 拥有封面曲目标识、恢复代际／一次发送预算、队列版本／内容去重／缓存淘汰，以及 MediaCenter 播放申请、发布比较与完成快照。
- Rust 拥有麦克风读错／无音频恢复预算和导航提示音 duck／可听流计数／释放延迟。
- Kotlin 保留 codec／Surface、录音／焦点请求、图片引用／回收、Handler／Executor、平台 owner 身份和 Binder 生命周期。具体职责见[架构说明](../ARCHITECTURE.zh-CN.md#当前-rust-核心与平台边界)。

旧视频时序门面移入测试源；无生产消费者的候选门面和四个 JNI 导出已删除。独立测试参考仍只在测试中使用，没有恢复 Kotlin 生产后备算法，也没有新增依赖。

## 回归合同

检查包含配置失败交错保护新配置唯一 IDR、强制刷新与自动恢复共用节流、首帧／私参失败降级；封面重入取消、旧会话失效、跨队列 owner 隔离、同内容复用及即时 Executor 的逐项交付；MediaCenter transfer ID 去重与不确定结果保护；独立录音重试预算及原有 1000／30000／60000／400 ms 提示音延迟。

测试适配同步更新了旧配置／发布字段断言、私有 JNI 的 Robolectric 桥接和录音首次读取顺序断言。录音创建次数仍由独立断言检查。

## 本地验证

- Rust：235 项通过，0 失败、0 忽略。
- Android native：NDK 28.2.13676358、API 28，arm64-v8a／armeabi-v7a／x86_64 全部构建；每个 ABI 均为 510 个 Java JNI 导出加 JNI_OnLoad，符号集合和来源凭据校验通过。
- JVM：3602 项中 3600 通过、2 环境跳过、0 失败；shared 1989 通过／2 跳过，common 1597 通过，home 14 通过。跳过项为 `AirPlayPortSelectorTest.fallsBackWhenAnotherListenerOwnsTheWildcardPort` 和 `EcarxAdaptapiAbiTest.adaptersMatchTheVendorInterfaces`，不计作已通过。
- mobile／home／maphost／siribridge 的 lintDebug、assembleDebug、minifyReleaseWithR8 及 shared 的 assembleDebugAndroidTest 全部通过。最终完整 Gradle 调用 `BUILD SUCCESSFUL`，466 项任务（14 执行、452 已是最新）。

## 小米 8 设备自动化

小米 8／API 35／ARM64 共 39 项通过、0 跳过：首轮 33 项（14.545 秒，startup 11、videoRoute 3、audioRoute 5、audioOut 3、audioState 7、playback 2、callAudio 2），随后新增 6 项 JNI 策略合同测试通过（0.044 秒）。日志未见 JNI 致命错误或崩溃。首轮覆盖配置失败交错两项及删除 Java 备用循环后的设备回归；新增测试覆盖曲目识别、恢复取消、封面队列与 LRU、发布判定、录音恢复及导航音频状态。

生产源码 `368cf103` 与已合并 `21459bb6` 的 shared 完全一致；新增 6 项测试为本地新增。首轮测试 APK SHA-256 为 `42ccdfb3c918128bb3c6ff54da519b3c62d94e6ee470ac3276ed02ff6af700ef`，新增 6 项测试 APK 为 `3757dae1202eafd5d70fa1af6ee00ba9d6acb7b0ed5246bf211b333972d22c8d`；两包内 ARM64 库 SHA-256 同为 `585f5462e8aaa65217544f4904ef1c7445cf9c879014b9065834e9b5e9247777`。本地原始证据为 `build/device-validation-114-20261114/` 下的 `results.json`、`*instrumentation.txt`、`*process.logcat.txt` 和 `*sha256.txt`；不提交原始日志、设备标识或私密资产。

这些自动化结果不证明 ECARX Binder 发布、车机音路或听感。用户此前确认收尾的历史项目保持原结论。

## 同批 Release 的真实 iPhone 会话

用户授权使用本地已有签名及认证资产后，构建并原位安装 `v150 / 0.2-car-test-pr114-media-state-20261114`，保留应用数据。Release 构建通过（1 分 12 秒）；安装前验证签名、认证资产与原包一致，ARM64 原生库与上述测试 APK 一致。实际包 SHA-256 为 `03c56e5829a20e54ff7c5a2315234b939ab6a25cc380f2edf5a9b003c5d78242`。APK、认证资产、映射文件和原始会话日志仅留本地，不提交仓库。

新包自动连接真实 iPhone，认证成功，日志和屏幕确认原生视频首帧；音乐使用真实 AAC 解码及 AAudio 输出。通过当前由 DiPlay 接收的系统媒体按键执行两次下一首，以及暂停、恢复：四次 HID 指令均完整写入，封面随曲目更新并解码，系统媒体会话确认 `PAUSED → PLAYING`。期间观察到一次封面恢复订阅发送，新封面到达后取消恢复；不能据此声称覆盖所有恢复条件。证据在同一本地目录的 `live-production-provenance.json`、`live-session.logcat.txt` 和 `live-media-session-*.txt`。

用户随后确认切歌的声音、曲名和封面同步正常；Siri 能识别、答复且退出后音乐恢复；导航播报可听见，结束后音乐音量恢复。日志记录真实 AudioRecord／原生 libopus 上行，两个统计窗口合计编码及发送均为 445 帧／包，读取错误和发送错误均为 0；导航焦点 4 次申请均 `granted=1`，4 次静音释放均 `result=1`。整个采集进程日志未见 JNI 致命错误或崩溃。机器汇总见本地 `live-results.json`，本次日志采集已停止，手机保留新包及当前会话。

本批小米手机自动化及上述真实 iPhone 会话场景通过。ECARX 专有 Binder 与目标车机音路仍待实车验证；没有注入真实录音读错或无音频故障，不把预算合同测试和正常录音记作故障恢复的实机证据。此前用户确认收尾的 #111 等项目保持原结论。
