# 原生媒体阶段 1–6：台架实验与验证边界

记录日期：2026-11-12。阶段 1 已在 PR #94 合入；本次 PR 汇集阶段 2–6。
本记录区分已测版本与最终提交：最后的 AAudio 快路径和 USB NCM 代码不在 C/D 测试包中，不能把 C/D 数据当作最终包验收。

## 版本与方法

平台为小米 8、Android 15，真实无线 iPhone 会话、两路视频和音乐。它是调试台架，不是 Android 9／ECARX 753 实车。
请求视频帧率为 60 fps，日志有 `operatingRate=60`。每轮安装、重连、启动音乐并等待后采样 300 秒。
先按 A1 → B1 → A2 → B2 执行，再测 C1/C2、D1/D2；后两组没有与 A 再次交错，存在温度、手机画面及运行顺序的影响。

| 包 | 内容 | 本地 revision 文件记录 |
| --- | --- | --- |
| A | 阶段 0：Kotlin 接收＋原生 ChaCha | `19561e9d` |
| B | 阶段 1：Rust 接收、解密与池化包 | `021c494f` |
| C | 阶段 1–3：再加 PCM 与原生异步视频循环 | `03408b0d`；C1 包还包含后续 feeder 线程修正 |
| D | 阶段 1–5：再加 AAudio、opus-pure 与原生麦克风发送 | `3e01650a` |

revision 文件只记录构建时的 HEAD，不包含工作区差异，不能单独用于重现这些实验 APK。
原始 APK、`start.txt` / `end.txt`、`cpu.txt`、`logcat.txt` 和运行脚本保留在本地工作区
`artifacts/native-media-bench-20261111/`，不提交带认证资产的测试 APK。
可公开核对的逐轮计数整理在[CSV](native-media-2026-11-12.csv)，数字从保存的完整日志提取，不使用被截断的 `summary.txt`。

CPU 来自各线程 `/proc/<pid>/task/*/stat` 的 utime＋stime 差值，脚本按每 tick 10 ms、采样 300 秒计算并汇总。
100% 表示一个核心。该方法没有覆盖独立 codec／AudioFlinger 服务进程，不能称为整机 CPU 或功耗。
接收心跳窗口与 CPU 采样窗口不完全对齐；音频发生序号异常时会提前输出、重置统计窗口。

## CPU 结果

| 包 | 第一轮 | 第二轮 | 均值（占单核） | 相对 A 均值 |
| --- | ---: | ---: | ---: | ---: |
| A | 50.72% | 50.77% | 50.745% | 基线 |
| B | 49.68% | 50.20% | 49.940% | −1.6% |
| C | 40.67% | 40.50% | 40.585% | −20.0% |
| D | 41.27% | 41.63% | 41.450% | −18.3% |

阶段 1 的整进程差距只有约 0.81 个百分点，两轮不足以建立统计显著性。
C 的主要线程变化是 `MediaCodec_loop` 从约 16.2% 降到约 9.3%，原 Kotlin 视频 worker 的约 9%
变成 worker、Rust feeder 和 NDK 回调线程合计约 4.6%。这支持视频循环是主要收益来源，但不是逐项隔离的因果实验。

D 比 C 均值高约 0.87 个百分点（2.1%），没有测出 AAudio 的 CPU 优势。
D 日志确认音乐走 `route=usage(aaudio)`，会话输出快照观察到 AudioStreamTrack；不能据此判断车机的 MMAP 能力或实际延迟。
最终工作区还加入了 AAudio 的单位增益／无重采样直接写入路径，此优化尚无新一轮设备数据。

## 帧率、音频与 GC 的口径

接收日志没有流 ID，CSV 按每个视频窗口的包数排序为低／高两路，不强行对应主屏和仪表。
A/B 每 300 秒约有 6,560–6,590 和 10,480–10,530 个接收消息，约为 22 和 35 个/秒；
合计约 57 不能写成“主屏实际 57 fps”。C1、D1/D2 接收量接近，C2 较低一路为 5,175 个，约 17.25 个/秒，负载并非严格相同。
这些是接收统计；没有用 SurfaceFlinger 有效呈现时间戳或外部高速录像证明物理显示持续 60 fps。

A2 的音乐统计记录到一次向前序号缺口和一次晚到／重复包，已输出窗口累计只有 269,261 ms；
D2 同样各有一次，已输出窗口累计只有 55,740 ms。因此两轮保存下来的音乐心跳包数不能当作完整 300 秒收包总量，
也不能据此认定音乐随后停止。各轮结束时媒体会话均记录为 PLAYING，这仍不能替代音频听感与欠载验收。

日志中的 GC 条数依次为 A1=3、B1=4、A2=3、B2=2、C1=3、C2=3、D1=2、D2=3。
它不是严格对齐采样窗口的分配率；HeapTaskDaemon 的 CPU 接近计数精度下限，未测得明确的 GC 收益。
B 的池化包未释放计数峰值为 8，属于观测值，不构成长时间无泄漏证明。

## 已有验证与待验收项

- C 的第二轮 smoke 日志有 `packet=NativePacket decoder=native` 和首帧输出。
- D 的 smoke、D1、D2 日志确认音乐使用 AAudio。
- Rust 主机测试覆盖 PCM 高通／衰减／淡入、Opus 编码回解、RTP 加密黄金向量、视频节拍和池化包所有权，
  以及 NCM NTB16、重组、邻居通告校验和与流量计数；Kotlin 侧保留对应黄金向量。
- 通话、Siri 和指定旧式 stream type 的输出保留 AudioTrack；AAudio 打开失败时回退到 AudioTrack。
- 纯 Rust Opus 已有主机编码回解测试，但现有 D 采样日志没有麦克风上行验证，不能标记真实 Siri／通话验收通过。
- 有线 Rust 通路由高级设置“有线数据通路原生化（实验性）”控制，默认关闭，下次有线连接生效；
  诊断报告和设置备份包含该开关。Rust 引擎无法启动时保留 Kotlin 通路。
- 未做真实 USB 主机 NCM 会话、断连重连及资源释放验收；台架手机当前作为 Mac 的 USB 从设备，未提供该测试拓扑。
- 尚未构建／采样最终 E 包；未验收车机音频 BUS 路由、焦点切换、真实通话、长时间稳定性和物理呈现延迟。

原生库从本次源码重新构建 ARM64 和 ARMv7，最终字节数与校验值见[原生库来源记录](../../shared/src/main/jniLibs/NOTICE.md)。
整理提交时，Rust 24 项主机测试通过；完整 Android 单测为 3394 通过、2 跳过、0 失败，四应用 lint、Debug 构建和 R8 检查通过。
新增的实验开关 UI／设置备份往返测试另行通过，并生成下图。自动检查通过不能替代上述设备验收。

![有线实验开关默认关闭，1920×1080，正常字体大小](native-wired-data-path-1920x1080.png)

## Issue #96：AAudio 正确性修复

在 `fd49e6d1` 后修复焦点线程直接访问 Rust 输出状态、衰减／重采样部分写入重复消费输入两项问题。
焦点回调只保存目标增益，由音频 worker 应用；Rust 保留未写完的 PCM，返回已消费的输入字节，
在接受下一块前排空余量。worker 在没有新包时继续排空；原生写入失败结束 renderer 并释放资源。
普通单位增益路径继续直接写入，只记住实际写入的最后一帧；关闭使用的 FLUSH 清除待写 PCM 和插值历史。

本次验证：

- 开发中运行 AAudio 4 项定向 Rust 测试及 Kotlin 47 项音频／焦点／缓冲／时钟回归，全部通过。
- 最终 Rust 主机测试 27 项通过；Android 完整单测 3396 项通过、2 跳过、0 失败，四应用 lint、Debug 构建及 R8 检查通过。
- 使用 NDK `28.2.13676358` 重建 ARM64、ARMv7；本次校验值更新在原生库 NOTICE 中。
- 小米 8（API 35）和 Android 9 ARM64 模拟器（API 28）各通过 2 项设备测试：暂停时超缓冲容量的单／双声道静音写入、启动后无新输入的尾部排空，以及播放期间／释放后的跨线程音量回调。
- 模拟器首轮回调测试因 200 次短休眠和固定等待时间超时；测试改用关闭 latch 后，只重建并重跑设备测试，两端均通过。生产代码未因此改动或重复运行全局检查。

以上是输出 API 和所有权回归，未新增 CPU／延迟对照结果，未进行 ECARX 753 实车、iPhone 音乐／Siri／通话听感、USB NCM 或长时间稳定性验收。

## Issue #96：视频接收直连解码

在 `18206bc8` 后，将 Rust 视频接收器与既有原生解码队列接通。配置、Surface、镜像拓扑和解码器选择仍由 Kotlin 管理；普通视频帧在 Rust 内完成接收、解密、Annex B 转换、镜像引用分发和入队，不再逐帧回调 Kotlin 后经 JNI 提交。

每条接收流持有独立 route，Rust 用 Arc 持有目的解码器；换流／关闭先断开旧 route。原生句柄销毁后，尚未退出的接收线程只能丢弃发往该目标的新包，不能访问已释放内存。任一目标不能提供原生 pipe 时，整条流保留原有 Kotlin 句柄分发路径。输入 FPS、字节、活跃／静止间隔和主屏触摸到下一帧统计随原生输出统计拉取，保留日志口径。

验证范围：

- 开发中运行 32 项 Kotlin 视频／接收／Surface 定向回归，全部通过；新增 Rust 输入统计回归覆盖跨窗口间隔、静止间隔和触摸样本清零。
- 设备回归覆盖镜像分发、目的替换、目的句柄先销毁、route 先销毁、包池归零、首次接收事件、配置顺序、无原生目的时的回退和认证失败。
- 硬件解码烟测使用 ffmpeg/libx264 生成的 64×64 黑色 baseline SPS／PPS／IDR，通过 ScreenStream 与 AndroidMediaSink 将加密帧送到平台 AVC 硬件解码器和 SurfaceTexture；断言有解码输出且 Kotlin 包回调为 0。测试不包含私有会话样本。该项要求设备具备 AVC 硬件解码器，不能在无硬件解码器的模拟器上替代验收。
- API 28 ARM64 模拟器曾在 ImageReader PRIVATE 分配缓冲后崩于系统 CCodec；改用 YUV 和 SurfaceTexture 后，软件 CCodec 收帧但未输出。对照原有 Kotlin 句柄转交路径也复现了无输出（`rx=119, rendered=0`；直连对照 `rx=130, rendered=0`），本次没有为模拟器修改生产解码器选择或车机校准。此环境只用于路由／生命周期验证，API 28 硬件输出仍需 ECARX 753 验收。
- Rust 主机测试 28 项通过；Android 完整单测 3398 项通过、2 跳过、0 失败，四应用 lint、Debug 构建和 R8 检查通过，完整检查执行一轮（6 分 6 秒）。
- ABI 复核发现原有 `AMediaCodec_queueInputBuffer` 的 offset 固定声明为 i64，而 NDK 头文件要求 `_off_t_compat` 与 C long 等宽，ARMv7 必须为 32 位。改为 `libc::c_long` 后双 ABI 重建；小米 8 强制 ARMv7 和 ARM64 各 3 项设备回归通过，均覆盖真实硬件输出。API 28 ARM64 模拟器 2 项通过，硬件烟测 1 项因缺少硬件解码器跳过。
- ABI 声明修正没有改动 Kotlin 逻辑，沿用已通过的完整单测、lint／R8 结果，只重打包最终四应用 Debug 与独立设备测试 APK；来源校验值更新在原生库 NOTICE。公共源码凭据和 diff 检查通过。本地构建／测试及 API 28 对照日志保存在工作区 `artifacts/issue96-video-20261112/`。

没有新增 CPU／物理延迟性能结论，没有验证真实 iPhone H.264／HEVC、主屏与仪表双路切换、停放／恢复或长时间稳定性。音频队列、缓冲音乐锚点、SETRATE／FLUSH、时钟和背压仍待迁移；协议／会话等后续工作保持 issue #96 的既定顺序。

## Issue #96：音频队列、缓冲音乐控制与时钟

在 `4a6b077c` 后，将渲染器压缩音频队列、缓冲音乐 queue／未听到的 fed 预填、RTP／NTP 锚点、SETRATE／FLUSH／GETANCHOR／feedback、发送节奏与 TCP 背压迁入 Rust。保留 192 包渲染队列、8 MiB／两分钟缓冲上限、15 秒后四倍速填充、400 ms 开始锚点和一秒预填；音乐持续拥塞恢复、RTP 回绕、暂停恢复也沿用原有规则。

Rust 同时拥有音乐 PI 时钟的目标测量、到达加权样本、误差平均和 learned drift，保持车机已有的 1.5% 限幅和测量／更新窗口。Kotlin 串行执行平台开始、交付和停止；开始回调后的 core generation 验证会拒绝重入暂停／FLUSH／关闭所失效的旧交付。关闭先释放并唤醒 Rust owner，再停止 receiver；接收线程通过 Arc 保持内存有效。缓冲接收沿用原有进程级共享包池。渲染器入队／关闭唤醒等待 worker，句柄销毁与生产者串行；取出的包持独立引用，时钟随 output worker 释放。

AAC MediaCodec、AudioTrack／AAudio 平台调用、焦点、路由与校准继续保留。UDP 音频接收到平台 sink、缓冲 feed 到 renderer／平台解码仍有逐包句柄交接，剩余输出调度尚未完全闭环；协议 NTP 同步属于后续协议迁移。本批没有新增依赖、开关或 LIVI 源码。Kotlin 算法只在原生库无法加载时启用，不与 Rust 同时维护状态。

验证：

- 开发中仅运行缓冲音乐、会话、时钟、积压恢复和 rebuffer 的 61 项 Kotlin 定向回归，全部通过；Rust 最终 38 项主机测试通过，新增 10 项覆盖边界、所有权及硬件时钟快慢漂移。
- 最终完整 CI 一轮（6 分 6 秒）：Android 3398 项通过、2 跳过、0 失败，四应用 lint、Debug 构建和 R8 通过。最终包池／双 ABI 库和 NOTICE 同步后仅重打包四应用及独立测试 APK，没有重复全局测试；核对 source → merged → stripped → APK 原生库和 NATIVE-NOTICE 资产一致。
- 新增 `NativeAudioStateDeviceTest` 7 项在小米 8／API 35 的 ARM64、ARMv7 和 API 28 ARM64 模拟器分别通过。覆盖生成静音 AAC 经加密 TCP 接收、真实 MediaCodec 解码和平台播放头推进，暂停／恢复／FLUSH／feedback，回调重入、在途交付排序、满缓冲关闭唤醒、外来对端拒绝、非法长度、TCP 分片／认证失败／RTP 回绕，以及队列关闭和等待唤醒。每个 ABI 还用 12,000 步对照原 Kotlin PI 算法，结果完全一致。
- 小米 8 扩展回归每个 ABI 共 16 项：13 通过、3 失败。新增音频 7 项、原有 AAudio 2 项、视频 3 项均通过；原有 Opus 4 项中的编码、能力探测、非法输入后恢复 3 项失败。分别用 `4a6b077c` 的 ARM64／ARMv7 原生库替换同一独立测试 APK 并重签，在同机复现相同 3 项失败；已按旧 NOTICE 哈希核对基线库。未改动 Opus 实现，也未将该失败当作本批新增回归或已验收通过。
- API 28 模拟器扩展回归 9 项：新增音频 7 项与原有增益回调 1 项通过；旧 AAudio 大块排空用例超时。仅重跑失败用例仍失败，使用 `4a6b077c` ARM64 库对照同样超时，根因尚未定位。该限制不影响本批生成 AAC 的实际播放头推进测试通过，但不能声称模拟器完整 AAudio 回归通过。
- 独立测试包最终恢复为本批构建，小米 8 选择 ARM64；双 ABI 校验值更新到原生库 NOTICE。日志、基线对照和构建元数据保存在本地 `artifacts/issue96-audio-20261112/`，原始日志／测试 APK 不上传。

下一步先定位两项遗留设备失败，继续收敛逐包交接与平台输出调度；随后按 issue #96 对照 LIVI 推进协议／会话迁移。尚未做 ECARX 753 BUS／焦点、真实 iPhone 音乐／导航／Siri／通话听感、实车长测或性能对照，NCM 仍默认关闭，完整迁移 PR 保持 Draft。

## Issue #96：协议、会话与领域并行首批

用户授权新开子会话后，从 `e1bfde1c` 建立三个隔离 worktree。主会话集中审查、合并共享入口、构建与验证；子会话只运行定向回归。

| 工作线 | 主分支提交 | 首批交付与边界 |
| --- | --- | --- |
| 领域 | `3f5d85b4` | EV 0xA101 参数体转换；SDK provider、订阅、有线／无线 reporter 和 Iap2Frame 包装保留 |
| 会话 | `e0bf8e8a` | WirelessRunState 生命周期、handoff、tunnelReady、首次失败；controller 代际、资源顺序、超时／恢复动作保留 |
| 协议 | `de448386`、修正 `b8ed2ccd` | RTSP 请求分帧／解析与响应编码；公开 DTO 不变，bplist／配对／控制加密／iAP2 后续迁移 |

Rust 定义无线状态的 opaque value token，Kotlin 只串行保存该值，所有查询与转移交给 Rust；它不是 native 堆句柄，因此关闭后仍可报告首次迟到失败，无终态策略副本或句柄缓存。EV 转换保留字段顺序、未知能量省略、负值钳零、u32 溢出／NaN 拒绝、缩放／舍入和无穷值钳制。

RTSP 保留本项目 16 KiB header／1 MiB body、重复头、trim／大小写、Content-Length、响应顺序及 ProtocolException。JNI 直接构造既有 Request／Parsed，UTF-16 字符串与按消息 local frame 保留 NUL 并限制局部引用；R8 规则保留 DTO 名称与构造器。两次只读审查后，真实设备对照仍抓到了桌面 JDK 与 Android 的补充字符 ASCII 替换差异：Rust 按 scalar 输出一个 `?`，Android 旧实现按两个 UTF-16 单元输出两个 `?`。改为按 UTF-16 单元转换，保持 Android 既有字节；保留桌面 fallback 的不同黄金预期。未因此修改协议规则或车机校准。

LIVI 对照固定：RTSP 为 `6ac88bab7351a39b0cffac20c5dcf92ca848319b`，标准 RTSP／500 响应与 ts_vectors 逐字节相同；其 16 MiB 上限、leading-digit 长度、非 ASCII 等语义不同，首批保留本仓库行为。EV 对照 `870d26441e073cdb802c427c5ae8833dba694951` 的 iap2-csm vehicle_status 字段／类型一致，livi-cp vehicle_state 是另一车辆特征协议。没有导入 LIVI 文件、宏、运行时或新依赖。

验证：

- 三路分别运行 Kotlin 定向 37／21／22 项，全部通过；Rust 新增 RTSP 4、无线状态 5、车辆转换 4 项，集成后主机共 51 项通过。
- 新增设备测试 9 项：RTSP 分片／合包／边界／ASCII／NUL／异常，10,000 消息 DTO 引用压力及真实 AirPlaySession loopback；无线状态 32,000 步 Kotlin 对照与 50 轮关闭／失败／迟到回调并发；EV 1944 组快照黄金字节及非法值拒绝。
- 最终同一独立测试 APK 在小米 8／API 35 的 ARM64、ARMv7 及 API 28 ARM64 模拟器各 16 项通过（新增 9 项 + 上一批音频 7 项）。初轮两个 ARM64 环境均有且仅有上述 ASCII 对照失败，修正后重新构建／运行，未以主机结果代替 Android 输出语义。
- 本批只在集成后运行一轮完整 CI（6 分 14 秒）：Android 单测 3403 通过、2 跳过、0 失败，四应用 lint、Debug 构建、R8 通过。主应用 R8 保留 NativeRtsp 与 Request／Parsed 的 JNI 类名，构造器规则已核对；主应用／独立测试 APK 的双 ABI 来源／strip 产物和 NATIVE-NOTICE 与最终源码一致，公共源码凭据和 diff 检查通过。
- 来源校验、最终 CI 与 R8 结果随本批集成记录保存到 `artifacts/issue96-parallel-20261112/`。手机测试包最终恢复 ARM64，原始日志／测试 APK 不上传。

这三个首批 owner 可独立实施；完整会话接入与 HID 编码仍须统一协议事件／存储／编码契约。三个大阶段均未整体完成，实车／iPhone、配对、有线／无线重连、导航／HID 和性能仍待验收。上一批已复现的双 ABI Opus 编码失败及 API 28 模拟器 AAudio 大块排空超时未在本批修复；NCM 仍默认关闭，PR 保持 Draft。
