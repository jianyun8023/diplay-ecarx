# DiPlay 技术架构

本文说明 DiPlay 领克 01 / ECARX 753 移植版的模块划分、会话组成、当前媒体实现，以及完整 Rust 核心的迁移目标。代码归属的细则以根目录 [AGENTS.md](../AGENTS.md) 为准；厂商接口与实车结论见[平台笔记](ECARX_753_PLATFORM_NOTES.zh-CN.md)。

当前媒体部分描述原生媒体阶段 1–6：阶段 1 已由 PR #94 合入 `dev`，阶段 2–6 及 AAudio 正确性修复从原 PR #95 继承到完整迁移分支。实现完成不等于实车验收通过；台架结果、版本和未验证范围见[原生媒体实验记录](benchmarks/native-media-2026-11-12.md)。

## 完整 Rust 核心迁移（进行中）

工作分支为 `feat/rust-carplay-core`，任务与验收进展统一维护在 [issue #96](https://github.com/jianyun8023/diplay-ecarx/issues/96)。该分支承接原生媒体工作，目标是 Rust 核心与 Kotlin Android 薄宿主；下文的 Kotlin 控制面是当前实现，不是长期目标。

| 目标归属 | 职责 |
| --- | --- |
| Rust 核心 | AirPlay／RTSP、bplist、配对／控制加密、iAP2；有线／无线会话代际、超时、重试、拆除与恢复；媒体队列、锚点、时钟、背压、编解码及路由决策；导航状态、车辆数据转换、触摸与按键协议。 |
| Kotlin 宿主与适配 | UI、Activity／Service 生命周期、权限、平台对象和线程；蓝牙／热点、音频焦点、USB 认领／VPN、平台存储及车机 SDK 调用。向 Rust 报告能力、事件与动作结果，执行 Rust 请求。 |
| 继续使用的平台／原生能力 | 硬件编解码器、Surface、AudioTrack／AAudio、AudioRecord、既有 Speex 库与车机接口，保留现有校准及兼容行为。 |

迁移顺序：

1. 完成媒体闭环。AAudio 正确性与视频直连已完成；音频压缩队列、缓冲音乐锚点、SETRATE／FLUSH、音乐 PI 时钟与 TCP 背压现由 Rust 拥有。继续收敛平台输出调度与逐包交接，并完成实车验收。
2. 迁移协议编解码、配对与控制加密，优先对照现有 Rust 实现及黄金向量。RTSP、bplist、control／event 加密和 PairVerify 已接入 Rust，PairSetup／SRP、iAP2／NTP 等仍待迁移，平台存储与 MFi 接入继续保留。
3. 迁移会话控制，统一代际、取消、恢复和资源关闭，平台结果携带所属代际。WirelessRunState、WirelessBootstrapRecovery 及 wired Iap2ControlDeadline 策略已迁移；wireless deadline、watchdog 调度、controller 代际与平台资源动作仍保留。
4. 迁移导航、车辆转换和输入协议；EV 0xA101、导航状态及 HID 报告／描述符编码已迁移，其余车辆转换与触摸槽状态尚待迁移，Android 输入适配保留 Kotlin。验收后删除重复 Kotlin 核心及无用依赖，更新归属规则。

验证覆盖范围与批次结果见[实验记录](benchmarks/native-media-2026-11-12.md)；完整协议／会话核心尚未迁入 Rust。开发中运行定向回归，可交付批次最终运行完整 CI、双 ABI 构建和可执行的设备验证；实车待验收项保留在 issue 中。

### LIVI 参考与复用

[LIVI](https://github.com/f-io/LIVI) 是协议迁移的优先参考：`native/crates/livi-cp` 包含 RTSP、bplist、SRP、配对、控制加密、时钟、HID、UI Sync 和车辆状态，iAP2 在 `iap2-csm/link/mfi/usbmux/wired` 等 crate；`livi-cp` 还保留 `ts_vectors` 测试。现有 `Srp6a.kt` 注明移植自其早期 `srp.ts`；本地 2026-11-11 的 Ultra 调研已核对 Rust 提交 `870d2644`。2026-11-12 重新核查了当前 Rust 模块结构。

每次复用按实际协议边界记录来源提交，先验证本项目样本的字节布局、认证结果、异常和恢复语义。参考 workspace 包含 Tokio／OpenSSL，livi-cp 密码实现使用 aws-lc-rs；其 Android 10+ ARM64 分发不能代替本项目 API 28 和 ARMv7 的验证。bplist、control cipher、HID 与 PairVerify primitives 复用 `6ac88bab7351a39b0cffac20c5dcf92ca848319b` 的黄金向量数据，未导入实现或运行时；PairVerify 没有完整握手黄金向量。已将其 [NOTICE](https://github.com/f-io/LIVI/blob/6ac88bab7351a39b0cffac20c5dcf92ca848319b/NOTICE) 要求的署名加入 README 和应用可查看的第三方许可声明。

## 1. 目标平台

- 量产目标：Android 9 / API 28，ECARX 753（高通 820A），车机平台签名。只发布 `arm64-v8a` 与 `armeabi-v7a`。
- 其他 Android 版本（小米 8 台架、模拟器）只用于调试与对照，不作为性能结论的唯一依据。
- 运行、权限与恢复不能依赖 ADB。

## 2. 模块

| Gradle 模块 | 目录 | 职责 |
| --- | --- | --- |
| `:mobile` | `mobile/` | 车机安装的应用（`com.shihab.diplay`），打包与签名、R8 规则。 |
| `:common` | `common/` | 宿主界面与生命周期：`CarPlayHostActivity`、`DiPlayActivity`（设置）、侧边栏、媒体会话、持久化。 |
| `:shared` | `shared/` | 协议、连接编排、媒体管线与原生库。车机无关的核心都在这里。 |
| `:home` | `samples/home/` | 桌面集成示例。 |
| `:maphost` | `samples/maphost/` | 仪表地图宿主示例。 |
| `:siribridge` | `integrations/siri/` | 方向盘语音键到 Siri 的桥接。 |

`shared` 的包分层：

| 包 | 内容 |
| --- | --- |
| `orchestration` | `CarPlayController`：会话代际、无线一次运行（`WirelessRun`）的状态与回收、有线拆除、状态契约。 |
| `bluetooth`、`transport`、`iap2`、`mfi` | 蓝牙 RFCOMM、iAP2 链路与消息、USB（usbmux / NCM）、MFi 认证。 |
| `network` | 车机热点、本地热点、Wi-Fi Direct、现有 Wi-Fi、Bonjour、有线用的 `CarPlayVpnService` 与 IPv6 NCM 桥。 |
| `airplay` | AirPlay 控制会话（RTSP、配对、加密控制通道、bplist）与各媒体流的接收端（`ScreenStream`、`AudioStream`、`BufferedAudioStream`）。 |
| `media` | 渲染与采集：`AndroidMediaSink`、`VideoDecoder`、`AudioRenderer`、`MicrophoneUplink`、音频焦点与路由。 |
| `hud`、`location`、`glance` | 仪表 / HUD 导航输出、车辆信号、定位上送。 |

## 3. 一次会话的组成

1. **发现与引导**：蓝牙 RFCOMM 上跑 iAP2，交换 Wi-Fi 凭据（无线）或经 USB 走 iAP2 / NCM（有线）。
2. **网络**：无线时 iPhone 连到车机热点、本地热点、Wi-Fi Direct 或同一现有 Wi-Fi；有线时 `NcmUsbBridge` 收发 USB NCM 帧，经 `CarPlayVpnService` 的 tun 接口进入内核网络栈。
3. **AirPlay 控制会话**：`AirPlaySession` 处理 RTSP、Pair-Setup / Pair-Verify、MFi SAP，之后控制通道加密（`ControlCipher`）。`SETUP` 按流类型建立媒体流，`CarPlayMediaEngine` 为每条流创建接收端并把解密后的数据交给 `MediaSink`。
4. **媒体流**（类型号为 CarPlay 协议值）：

| 流 | 传输 | 内容 | 接收端 → 消费者 |
| --- | --- | --- | --- |
| 110 主屏 / 111 第二屏（仪表） | TCP | 128 字节头 + ChaCha20-Poly1305 封装的 H.264/HEVC 帧，或明文 avcC/hvcC 配置 | `ScreenStream` → `AndroidMediaSink` → `VideoDecoder` |
| 100/101/102 音频 | UDP RTP | 媒体、导航、通话、Siri；AAC-LC、Opus 或 LPCM，每包 ChaCha20-Poly1305 | `AudioStream` → `AudioRenderer` |
| 103 缓冲音乐 | TCP | 长度前缀的加密 RTP，提前最多约两分钟 | `BufferedAudioStream` → `AudioRenderer` |
| 麦克风 | UDP RTP | Opus 编码、ChaCha20-Poly1305 加密上行 | `MicrophoneUplink` |
| 130 数据流 | TCP | iAP2 隧道等 | `IapTunnel` |

## 4. 当前控制面与数据面

以下为当前过渡边界：Rust 已持有媒体队列、缓冲音乐控制与时钟状态；尚未迁移的协议、会话和平台决策暂由 Kotlin 管理，最终目标见第 1 节。

| 归属 | 内容 |
| --- | --- |
| 控制面及平台适配（Kotlin） | controller 连接编排与重试、配对／加密控制通道、设置与持久化、流的建立与拆除；解码器的选型、格式参数、私有参数回退、渲染目标（Surface、fanout、停放）与何时重建；音频的路由、焦点、车机 BUS 与旧式流类型选择，AAC / AudioTrack 平台调用及输出回调顺序；仪表与 HUD 平台输出、车辆 SDK provider／订阅；诊断报告。RTSP、bplist、无线 run 与导航状态经适配器调用 Rust。 |
| 数据面（Rust） | 媒体套接字收发、ChaCha20-Poly1305 开封 / 封装、帧重组与 AVCC→Annex B、关键帧识别；解码器的创建、配置、喂帧、出帧、节拍（NDK `AMediaCodec` 异步模式）；Opus 解码与编码、PCM 处理（字节序、淡入、导航衰减、有声检测、通话高通滤波）、音频输出（AAudio）；RTP 打包；有线 NCM 与 tun 之间的转发。Speex 回声消除仍通过原有 C/JNI 库执行。 |

Kotlin 目前仍负责解码器与路由决策，Rust 负责已迁移的包、帧、PCM、音乐控制与时钟状态。两点说明：

- **视频**：解码由高通硬件完成，Java 与 NDK 走同一条 OMX/Codec2 路径，迁移省下的是轮询与线程交接。Java `MediaCodec` 以 `dequeueOutputBuffer` 超时轮询，台架上它的 `MediaCodec_loop` 线程单独占一个核的约 16%；API 28 起 NDK 提供 `AMediaCodec_setAsyncNotifyCallback`，输出到达时回调，不再轮询，接收线程也能直接喂帧。`VideoDecoder` 的选型与回退逻辑保留在 Kotlin，由它驱动 Rust 侧的解码循环。
- **音频输出**：按 usage 路由的音乐与导航流尝试 AAudio，打开失败时回退到 `AudioTrack`。通话、Siri 和指定旧式流类型的流保留 `AudioTrack`。AAudio 路径用 Rust PCM 增益处理焦点衰减，用重采样处理原先由 `AudioTrack.setPlaybackRate` 执行的时钟微调。AAC 仍由系统 Google AAC 解码（实车上高通 AAC 解码器创建失败，见平台笔记）。

## 5. 迁移前的开销与测量依据

| 路径 | 现状 | 每包 / 每帧的 Java 侧工作 |
| --- | --- | --- |
| 视频接收 | `ScreenStream` 用 `InputStream` 读头和体 → `NativeChacha`（Rust）解密到新数组 → Kotlin 原地转 Annex B → 经 `LinkedBlockingQueue` 交给解码线程 → `ByteBuffer.put` 拷进输入缓冲 | 每帧一个与帧等长的新数组（关键帧达数百 KB）；解码前再扫描一次 NAL |
| UDP 音频 | `AudioStream` 每包复制 wire、AAD、密文、nonce、明文、RTP 拼接共约 6 个数组 → 渲染队列 → 再复制 payload → MediaCodec | 每秒数百个小对象 |
| 缓冲音乐 | `DataInputStream` 每帧新数组，解密再拼接 | 同上 |
| 麦克风 | `AudioRecord` → Speex AEC（C/JNI）→ libopus（C/JNI）→ Kotlin 打包、Java Cipher 封装、`DatagramSocket.send` | 每 20 ms 三次跨 JNI，多次拷贝 |
| 有线 | iPhone → USB bulk（Java `UsbRequest`）→ NTB16 解析（Kotlin）→ tun fd → 内核 TCP → 回到 Java 套接字 | 所有字节两次经过 Java |

已测数据（小米 8，2026-11-11，原生 ChaCha 与 Java 对照，同等负载）：

- 视频主屏解密平均 385 µs → 81 µs，但接收线程每包 CPU 只从 934 µs 降到 768 µs；单换解密只省一小部分，其余在套接字读、对象分配和线程交接上。接收线程内核态约占 35–40%。
- 整进程约占一个核的 39–46%。
- 实车：视频输入拷贝约 29 µs/帧，不是主要成本（`analysis/20261110-car-codec-kit`，工作区）。

这些数据用于选择迁移方向。后续实验确认的主要收益来自视频解码循环变化；不能把减少数组分配直接等同于已测得的 GC 或体感改善。

## 6. Rust 数据面 `diplay-media`

### 6.1 库与构建

- 一个 crate：`shared/src/main/rust/diplay-media`，产物 `libdiplay_media.so`，取代 `libdiplay_chacha.so`。
- 源码按职责组织，[lib.rs](../shared/src/main/rust/diplay-media/src/lib.rs) 只组装六个正常 Rust namespace，不保留平铺兼容导出或 `#[path]` 别名：

| 目录（相对 `diplay-media/src`） | 职责 |
| --- | --- |
| `protocols/` | RTSP、bplist、control cipher、PairVerify 与共享 crypto；前四个 owner 各携带私有 `jni` 子模块。 |
| `session/` | wireless_state、wireless_recovery、wired control_deadline 的值状态与策略。 |
| `media/audio/`、`media/video/` | 音频接收／队列／buffered／时钟／PCM／输出／上行；视频接收／Annex B／pacing／vpipe。`media/packet` 和 `media/stats` 为媒体共享设施。 |
| `vehicle/` | HID、EV 参数体与导航状态；route_guidance 的 JNI 属于其私有子模块。 |
| `platform/` | JVM／NDK 桥接、共享 stream 句柄与关闭生命周期，包含 stop JNI。 |
| `transport/ncm/` | NCM 帧编解码及 USB／tun engine。 |

原 public owner 保持可达，私有字段及 JNI 子模块不因搬迁扩大权限。Android／Linux receiver、stream、NCM engine 与 Android-only NDK／vpipe 保留各自 cfg；纯算法和 AAudio 状态仍可 host 编译。NCM engine 沿用媒体 stats 的 clock，不为目录整理新增通用时间层。

全部 owner 继续编入同一库，无额外运行时或依赖。bplist 保留 32 层解码与工作／载荷预算，编码两路径统一限制为 128 层；Java 类型／畸形 UTF-16 替换留在 JNI 平台边界。导航两个进程级输出各持独立 owner，Kotlin 仅提供时钟、当前会话存活和 SDK 输出。
- Opus 由 [opus-pure 0.2.3](https://crates.io/crates/opus-pure/0.2.3) 编解码，编进同一 `.so`；不再携带预编译的 `libopus.so` 与其 JNI 桥。现有测试覆盖本项目的编码回解与 PCM 黄金向量，没有在这里重新验证上游的全部一致性声明。Speex 回声消除仍是原有的 `libspeex_echo.so`。
  2026-11-12 扩展设备回归发现的 Opus 失败已定位为 JNI 误将 120 ms／7662 字节上限用于验证 Kotlin 的 20 ms／1275 字节缓冲；现统一为单帧上限，并保留原码率／VoIP 校准。AAudio 旧后端不遵守正超时值的时长，一次阻塞排空曾耗时 6.9 秒；旧测试提前启动 5 秒播放等待导致误报，现从首次阻塞提交完成后计时，仍严格验证全部帧播放，未改变生产缓冲或重采样。最终设备结果与旧库对照见 issue #96 和[实验记录](benchmarks/native-media-2026-11-12.md)；台架不能替代 iPhone 互通与实车听感验收。
- 构建：NDK r28、`aarch64-linux-android` 与 `armv7-linux-androideabi`、最低 API 28，`build.sh` 从 `CARGO_TARGET_DIR`（缺省 `target`）复制本轮产物，再把 `.so` 检入 `shared/src/main/jniLibs/`，CI 不需要 Rust。发布配置 `lto`、`panic = "abort"`、`strip`，并去掉构建机路径。
- 第三方许可写进 `shared/src/main/jniLibs/NOTICE.md` 与 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

### 6.2 边界约定

- **套接字移交**：Kotlin 照旧负责绑定、`accept`、对端校验（只接受控制会话的 iPhone），之后把 fd 交给 Rust（`ParcelFileDescriptor.detachFd`），Rust 拥有它直到关闭。关闭流时 Kotlin 调 `close(handle)`，Rust 用 `shutdown` 唤醒阻塞读并在线程退出时 `close(fd)`。
- **线程**：每条流一个 Rust 接收线程，名字沿用 `airplay-screen`、`airplay-audio-rx`、`airplay-buffered-rx`，诊断和 `/proc` 采样脚本不必改。音频线程设 `THREAD_PRIORITY_AUDIO` 对应的 nice 值。
- **AAudio 输出**：`NativeAudioOut` 的原生句柄仅由音频 worker 访问；焦点回调只更新 Kotlin volatile 目标增益，由 worker 在写入／排空前应用。原生写入返回消费的输入字节；衰减／重采样后的未写完 PCM 留在 Rust，排空前不接受下一块输入，零长度写入只排空。输入／生成输出分别计数，部分写入不重算重采样、不重复消费；worker 空闲时继续排空，原生写入失败结束 renderer，关闭前取消焦点，FLUSH 丢弃待写 PCM 和插值历史。
- **视频直连**：每条接收流持有 Rust route，直接把解密后的包送入一个或多个原生解码队列；镜像各持有包引用，逐帧不回调 Kotlin、不经 JNI 提交。Kotlin 在配置／拓扑变化时更新目的地；route 与解码器通过 Arc 保持内存所有权，已销毁的解码器只释放新到的包。换流、关闭先断开旧 route，接收线程即使尚未退出也不能向新流提交。
- **向上回调**：视频仅回调配置、首次接收、诊断与结束，解码器事件走既有事件线程；任一目标没有原生 pipe 时整条流回退到原有句柄回调，再由 Kotlin 分发，避免镜像漏帧。UDP 音频仍逐包回调句柄；缓冲音乐接收器直接入 Rust 队列，Kotlin 输出 worker 按 Rust 返回的步骤执行开始／交付，保留平台调用边界，不传字节数组。
- **音频状态**：缓冲音乐的 queue 与尚未听到的 fed 包、字节和帧数上限、15 秒后四倍速填充、400 ms 锚点、1 秒预填、RTP 回绕、SETRATE／FLUSH／GETANCHOR／feedback 均由 Rust 计算。平台回调外不持 Rust 锁；Kotlin 输出锁排序暂停／切歌／关闭与交付，开始回调后用 core generation 验证，重入控制使旧交付失效。关闭先销毁／唤醒 owner，再停止接收线程；Arc 保持接收器结束前的内存安全。
- **音频队列与时钟**：渲染器压缩包在 Rust 持有，保持 192 包上限及持续拥塞恢复阈值，语音不执行音乐积压跳过。入队／关闭唤醒 Android worker；Rust 计算压缩积压与到达加权样本、PI 误差平均、目标测量和进程级 learned drift。Kotlin 只向输出设置请求的速率；系统拒绝时关闭该时钟控制。JNI 句柄销毁与生产者串行，已取出的包独立持引用；时钟随 output worker 释放。库不可用时才初始化／更新 Kotlin 算法状态。
- **帧句柄**：`NativePacket` 持有引用计数的池化缓冲，Kotlin 通过 `MediaPacket` 传递所有权。镜像消费方各持有自己的引用；写入解码器、被队列丢弃或流关闭时释放。
- **写入解码器**：原生视频路径的 NDK 回调只排队，由专用线程喂帧和释放输出；Kotlin 保留配置、Surface 和恢复决策。Kotlin 回退路径通过 `MediaPacket.copyInto` 写入解码器输入缓冲。
- **统计**：`StreamReceiveStats` 的字段与日志格式不变，计数在 Rust 内累计，按原有心跳与异常间隔回调一行文本。
- **视频输入统计**：输入帧数、字节、活跃／静止间隔和触摸到下一帧由 Rust 累积，随解码统计定期拉取；主屏独占触摸样本。FPS 直接读取原生计数，保留原有日志字段，不为统计恢复逐帧回调。
- **回退**：每条流在建立时选择实现。库加载失败或在 JVM 单测中，使用现有 Kotlin 实现；日志里记录 `impl=native|kotlin`。Kotlin 实现保留，供主机单测与运行时回退，不再做性能优化。

### 6.3 协议、会话和领域首批

- `RtspMessage` 的公开 DTO 与调用入口不变；Rust 解析合包／分片、限制头／体大小、生成响应，JNI 直接构造 Request／Parsed。边界保留 Kotlin US_ASCII 替换、trim／大小写、Content-Length、NUL、重复头和响应顺序语义；错误继续抛 ProtocolException。JNI 使用 UTF-16 并按消息释放局部引用，R8 保留 DTO 名称／构造器。
- 对照 LIVI `6ac88bab7351a39b0cffac20c5dcf92ca848319b` 的 rtsp.rs／ts_vectors：其 16 MiB body、leading-digit 长度解析、非 ASCII 等规则与本项目不同，因此移植本仓库行为，保留 16 KiB header／1 MiB body 上限；标准 RTSP／500 响应与其向量逐字节相同。没有复制 LIVI 文件或导入依赖。
- `WirelessRunState` 的生命周期、handoff、tunnelReady 与首次失败由 Rust 查询／转移。Kotlin 只串行保存一个 opaque value token，不解释其编码，不分配 native 堆句柄。关闭后首次迟到失败沿用相同转移规则；原 controller 资源锁、代际、超时和恢复动作尚未迁移。
- `Iap2VehicleStatus.update` 将 EV 快照的范围／电量／能量转换交给 Rust；保留未知能量省略、负值钳零、u32 溢出拒绝、缩放／舍入、NaN 拒绝和无穷钳制。对照 LIVI `870d26441e073cdb802c427c5ae8833dba694951` 的 iap2-csm vehicle_status 字段／类型一致；livi-cp vehicle_state 属于另一车辆特征协议，不能替代该参数体。SDK provider、订阅和 Iap2Frame 包装继续使用 Kotlin。
- `ControlCipher` 的分帧、AEAD 与双向 wrapping nonce counter 由 Rust 持有。Kotlin 以同步的 Closeable owner 串行使用／释放句柄；AirPlaySession 关闭 control 和 event owner，拒绝关闭后的迟到安装，并在等待 event 写锁前关闭 socket 以唤醒阻塞写。保留空输入加密帧、u16 解密长度、部分帧 rest identity 及认证失败前已成功帧的计数进度。
- `PairVerify` 的完整 M1/M2/M3/M4、临时／shared／加密 key、验证状态与 control keys 由 Rust 持有；同步 facade 和 Session 管理释放。Kotlin 只保留实时 PairingStore 查询及平台 UTF-8，JNI 显式保留回调／DTO 签名。保留 M2 阶段 shared 可读、M1 部分失败更新顺序、旧验证结果不被新／失败握手清空、长 key 前32字节计算而完整 raw 参与签名。原生边界保留 BC1.79 的 Ed25519 编码拒绝范围，拒绝低阶／非规范公钥。PairSetup／SRP、身份与配对存储、MFi 仍保留 Kotlin。
- `WirelessBootstrapRecovery` 用 Rust value token 持有 run 预算与 attempt 状态，并决定 Java Throwable facts 的终止／重试／超时包装。保留一次 connect retry、一次 fallback、一次 bootstrap retry、400／800 ms 等待、验证及资源执行顺序；Kotlin 仅保留平台异常描述、100 ms 可取消等待、watchdog 调度与实际资源动作。
- `Iap2ControlDeadline` 的有限认证窗口、认证后无限模式与30秒 poll 上限由 Rust 决定；Kotlin 保留时钟与原 Iap2WiredControlClient I/O。两 Long token 原样保留全部时间／预算位，认证无限态用独立空 token 表示；不分配 native 堆句柄。保留 Long wrapping、回拨、向上取整与时钟重入的单次查询快照，无限认证后不再读取时钟。
- Rust `hid` 持有二指触摸、旋钮、媒体及电话报告和四类 descriptor 的编码。原 `/info` 注册与 Session 发送入口直接消费；UID／元数据、Android 触摸适配与 AirPlayTouchSlots 留在 Kotlin。保留按 slot id 首次匹配、Android Math.round 与低 16 位截断；这些与 LIVI 的列表顺序／饱和规则不同。
- Kotlin 旧核心算法仅在库不可用时启用，所有权和设备验收详见 issue #96。PASCD／VehicleSpeedLocationProvider 当前无生产 SDK 装配点，未新增无人消费的 Rust 模块。

生产 Kotlin 回退属于迁移期机制，后续按已验收模块移出生产路径，仅保留独立测试对照，并为原生核心加载失败提供明确启动错误。这个退出方案尚未实施；Bouncy Castle 当前仍服务 PairSetup／SRP、身份密钥及 MFi ASN.1，不能仅因 PairVerify 迁移而删除。AudioTrack 等硬件能力回退另按平台需求保留。

### 6.4 各路径的实现

| 路径 | 目标 |
| --- | --- |
| 视频 | Rust：`recv` 进池化缓冲 → 原地开封 → 原地 AVCC→Annex B、解析 NAL 类型 → route 直接分发至解码队列 → NDK `AMediaCodec` 异步循环。关键帧门控、输入背压恢复、按 iPhone 帧时间的节拍与统计在 Rust；`VideoDecoder` 的 worker 处理配置、Surface、镜像、停放与 Rust 发来的事件（要关键帧、要解码器、重建、出错）。无原生 pipe 的目标保留句柄回调路径。 |
| UDP 音频 | Rust 接收、开封并回调池化包句柄。Opus 用 opus-pure 解码；AAC 保留系统 MediaCodec；LPCM 字节序、通话高通、有声检测、导航衰减与淡入在 Rust 处理。音乐和导航的 usage 路径尝试 AAudio；通话、Siri、旧式流类型及打开失败时使用 `AudioTrack`。 |
| 缓冲音乐 | Rust 读长度前缀、开封并直接入有界队列，拥有锚点、SETRATE／FLUSH、暂停预填保留、填充节奏与 TCP 回压；Android worker 拉取核心发送步骤，串行执行平台开始／交付／停止。 |
| 麦克风 | Kotlin 保留 `AudioRecord` 采集和原有 Speex C/JNI 回声处理。原生发送器用 opus-pure 编码、组 RTP、加密并发送；不可用时保留 Kotlin 发送路径。 |
| 有线 | Kotlin 保留 USB 接口认领、VPN 与生命周期。默认使用原 Kotlin 通路；实验开关开启后尝试 Rust usbdevfs 同步 bulk、NTB16 重组、邻居通告修补与 tun 转发，启动失败时使用 Kotlin 通路。 |

## 7. 分阶段与验收

阶段 1 已单独合入；本次按要求把阶段 2–6 放在同一个 PR。小米 8 台架对照与实车验收分别记录，不能互相替代。

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| 0 | 基线：60 fps 会话 + 音乐，记录各线程与整进程 CPU、每包接收耗时、恢复次数 | 两轮可复现的数字 |
| 1 | 接收与开封：视频、UDP 音频、缓冲音乐；池化包句柄，解码器从包直接拷入输入缓冲 | 接收线程每包 CPU 下降；包无泄漏（`pooled` 稳定）；无新增恢复、欠载 |
| 2 | 音频解码与 PCM 处理（opus-pure、LPCM、PCM 步骤；AAC 输出经直接缓冲） | 与 Kotlin 实现同一黄金向量；Siri 走原生 Opus；导航衰减、通话路由、焦点不变 |
| 3 | 视频解码循环：NDK `AMediaCodec` 异步模式，回调只排队、专用喂帧线程调用编解码器；Rust 内节拍、关键帧门控与背压恢复 | `MediaCodec_loop` 与解码线程 CPU 下降；首帧、恢复、Surface 切换与停放行为不变 |
| 4 | 音频输出：AAudio（按 usage 路由的音乐与导航），通话、Siri 与旧式流类型保留 `AudioTrack`；焦点压低音量改为 PCM 增益，时钟微调改为 Rust 重采样 | 实车各声道路由、焦点、欠载不变 |
| 5 | 麦克风发送：opus-pure 编码、RTP、封装、`sendto` 一次调用；采集仍是 `AudioRecord`（车机麦克风策略与平台音效挂在它的会话上），Speex 回声消除仍是原 C 库 | Siri 与通话实车可用；同输入下上行包与 Kotlin 逐字节一致 |
| 6 | 有线 NCM ↔ tun：usbdevfs 同步 bulk、NTB16 重组与重同步、邻居通告修补、流量计数全在 Rust；高级设置“有线数据通路原生化（实验性）”，默认关 | 有线连接、断开、重连实车可用；有线帧率与 CPU 对照 |

阶段 0 / 1 结果（小米 8，Android 15，请求 60 fps，两路视频合计约 57 个接收消息/秒，并播放音乐；A/B/A/B 各 300 秒，2026-11-11）。两路均值约为 22 和 35，日志没有流 ID，不把合计值写成主屏帧率：

| 指标 | 基线 A1 / A2 | 阶段 1 B1 / B2 |
| --- | --- | --- |
| 整进程 CPU（一个核） | 50.7% / 50.8% | 49.7% / 50.2% |
| 视频接收线程 | 3.55% / 3.75%，每帧 633 / 666 µs | 3.21% / 3.26%，每帧 571 / 578 µs |
| 音乐接收线程 | 0.61% / 0.62%，每包 132 µs | 0.34% / 0.34%，每包 73 / 72 µs |
| 未释放的包（`pooled` 峰值） | — | 8 |

接收线程每帧 / 每包 CPU 分别降约 11% 与 45%；整进程只降约 0.8 个点，在噪声范围内。

阶段 2–5 结果（同一台架与流程，2026-11-12）：

| 指标 | 基线 A | 阶段 1–3（C1 / C2） | 阶段 1–5（D1 / D2） |
| --- | --- | --- | --- |
| 整进程 CPU | 50.7% / 50.8% | 40.7% / 40.5% | 41.3% / 41.6% |
| `MediaCodec_loop` | 16.3% / 16.2% | 9.4% / 9.2% | 9.3% / 9.3% |
| 视频解码（Kotlin worker → Rust 喂帧线程 + NDK 回调线程） | 9.0% / 8.9% | 0.4% + 2.0% + 2.2% | 0.4% + 2.0% + 2.2–2.4% |
| `carplay-audio` | 6.6% / 6.5% | 7.2% / 7.1% | 7.4% / 7.4% |

包含原生视频解码循环的 C 版本比 A 基线低约 10.16 个百分点（20.0%）；主要线程下降与视频循环变化相符，但这不是单一改动的隔离实验。D 版本约为 41.45%，比 C 均值高约 0.87 个百分点，没有测出 AAudio 带来的 CPU 改善。台架日志观察到 AAudio 使用 AudioStreamTrack，尚不能推断 753 车机的 MMAP 能力或实际延迟。台架 GC 线程 CPU 接近计数精度下限，没有测得明确的 GC 收益。各轮数据、版本与限制见[实验记录](benchmarks/native-media-2026-11-12.md)。

测量方法：接收线程 CPU 取 `/proc/<pid>/task/*/stat` 的 utime/stime，会话日志里的 `Receive:` 心跳行给出每包读、处理与解密耗时；A/B 为同一流程构建的两个 release 车测包交替安装，脚本与原始结果放在本地工作区，结论回填到本节或平台笔记。

## 8. 新代码放在哪

- 按包、按帧执行的逻辑放进 `diplay-media`，在 Kotlin 侧只暴露句柄与配置；不要在接收或渲染循环里新建 `ByteArray`。
- 尚未迁移的选型、回退、路由、焦点和设置接入继续沿用当前 Kotlin 实现；按 issue #96 将核心决策与状态成组迁入 Rust，平台调用和存储保留适配层，同步迁移调用方及测试。
- 新的 JNI 入口跟随所属 owner 的边界；独立桥接放在 owner 的私有 `jni` 子模块，共享 JVM／NDK 设施留在 `platform`。每个入口检查参数边界后才触碰内存；Rust 侧不 `panic` 跨越 FFI（发布配置为 abort），错误以返回码或既有异常契约交给 Kotlin。
- 新的车机接口或属性先登记到平台笔记，再使用。
