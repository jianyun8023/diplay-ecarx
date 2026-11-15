# DiPlay 技术架构

本文说明 DiPlay 领克 01 / ECARX 753 移植版的模块划分、会话组成和当前 Rust 核心／Kotlin 薄宿主边界。代码归属的细则以根目录 [AGENTS.md](../AGENTS.md) 为准；厂商接口与实车结论见[平台笔记](ECARX_753_PLATFORM_NOTES.zh-CN.md)。

原生媒体阶段 1 由 PR #94 合入 `dev`，阶段 2–6 及 AAudio 正确性修复经 PR #97 合入，Rust 核心主体迁移已完成。后续修复与验收按批记录；实现完成不等于所有实车场景通过。历史台架结果见[原生媒体实验记录](benchmarks/native-media-2026-11-12.md)，当前状态见[验收矩阵](RUST_CORE_REMAINING.zh-CN.md)。

2026-11-14 后续职责整理见 [Rust 与 Kotlin 职责收敛方案](RUST_KOTLIN_BOUNDARY_PLAN.zh-CN.md)：歌曲状态、普通音频 feedback、媒体协商及焦点策略仍有可收拢的规则，视频控制邮箱与 JNI 门面也有可简化的边界。该文列出待实施范围、调用方与分批条件，不改变本页已落地归属或重开既有验收。

## 当前 Rust 核心与平台边界

PR #97 已完成 Rust 核心与 Kotlin Android 薄宿主集成；`feat/rust-carplay-core`／`feat/rust-protocol-media-all` 和已关闭的 [#96](https://github.com/jianyun8023/diplay-ecarx/issues/96) 仅作历史入口，不再指定工作分支或当前待办。源码归属与设备／实车验收分别记录；当前剩余项以[验收矩阵](RUST_CORE_REMAINING.zh-CN.md)为准，不能由 #96／#98／#99 的关闭状态推定全部通过。

| 当前归属 | 职责 |
| --- | --- |
| Rust 核心 | AirPlay／RTSP、bplist、配对／控制加密、iAP2；有线／无线会话代际、超时、重试、拆除与恢复；媒体队列、锚点、时钟、背压、编解码及路由决策；导航状态、车辆数据转换、触摸与按键协议。 |
| Kotlin 宿主与适配 | UI、Activity／Service 生命周期、权限、平台对象和线程；蓝牙／热点、音频焦点、USB 认领／VPN、平台存储及车机 SDK 调用。向 Rust 报告能力、事件与动作结果，执行 Rust 请求。 |
| 继续使用的平台／原生能力 | 硬件编解码器、Surface、AudioTrack／AAudio、AudioRecord、既有 Speex 库与车机接口，保留现有校准及兼容行为。 |

2026-11-14 媒体状态归属补齐：

| 状态 | Rust 唯一决策方 | Kotlin 保留 |
| --- | --- | --- |
| 视频配置与关键帧 | 配置候选推进、拒绝／降级、首帧状态、最近配置与重复判断；主动刷新与自动恢复共用 pipe 内的关键帧节流时间。帧队列、背压与参考链也只在 vpipe。 | codec 操作结果、Surface／GL、能力枚举、硬件探测、简单控制邮箱（`VideoControlMailbox`：FIFO、可中断、关闭唤醒、Detach 排空）与资源锁。 |
| 封面恢复与队列 | 曲目识别、代际、一次发送预算、取消规则、版本淘汰、内容去重和缓存淘汰。 | Handler／Executor、发送和解码、图片引用／回收、平台会话对象身份、媒体发布；Kotlin 门面以具名结果消费 artwork 队列快照，业务不再读取变长布局。 |
