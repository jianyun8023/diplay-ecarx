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

未安装或运行本次设备测试 APK，未操作小米 8；自动化通过不能记为本次真实 codec、音频焦点或车机媒体发布的实机验收。用户此前确认收尾的历史项目保持原结论。
