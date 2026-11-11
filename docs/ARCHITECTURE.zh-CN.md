# DiPlay 技术架构

本文说明 DiPlay 领克 01 / ECARX 753 移植版的模块划分、一次 CarPlay 会话的组成、控制面与数据面的分工，以及媒体数据面迁到 Rust 的目标设计。代码归属的细则以根目录 [AGENTS.md](../AGENTS.md) 为准；厂商接口与实车结论见[平台笔记](ECARX_753_PLATFORM_NOTES.zh-CN.md)。

本文描述阶段 1–6 的实现与边界：阶段 1 已由 PR #94 合入 `dev`，阶段 2–6 在本次 PR 中交付。实现完成不等于实车验收通过；台架结果、版本和未验证范围见[原生媒体实验记录](benchmarks/native-media-2026-11-12.md)。

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

## 4. 控制面与数据面

划分原则：**按包、按帧、按采样周期发生的工作是数据面，由 Rust 负责；按会话、按配置、按用户操作发生的决策是控制面，留在 Kotlin。**

| 归属 | 内容 |
| --- | --- |
| 控制面（Kotlin） | 连接编排与重试、RTSP / 配对 / 加密控制通道、设置与持久化、流的建立与拆除；解码器的选型、格式参数、私有参数回退、渲染目标（Surface、fanout、停放）与何时重建；音频的路由、焦点、车机 BUS 与旧式流类型选择；缓冲音乐的锚点与 SETRATE / FLUSH；仪表与 HUD；诊断报告。 |
| 数据面（Rust） | 媒体套接字收发、ChaCha20-Poly1305 开封 / 封装、帧重组与 AVCC→Annex B、关键帧识别；解码器的创建、配置、喂帧、出帧、节拍（NDK `AMediaCodec` 异步模式）；Opus 解码与编码、PCM 处理（字节序、淡入、导航衰减、有声检测、通话高通滤波）、音频输出（AAudio）；RTP 打包；有线 NCM 与 tun 之间的转发。Speex 回声消除仍通过原有 C/JNI 库执行。 |

Kotlin 仍决定“用什么、怎么回退、渲染到哪”，Rust 执行“每一帧、每一包、每一块 PCM”。两点说明：

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
- 模块：`crypto`（ring 的 ChaCha20-Poly1305）、`packet`（池化包）、`video` / `audio` / `buffered`（接收）、`vpipe`（视频解码循环）、`pacing`、`renderer` / `pcm`（Opus 解码与 PCM 步骤）、`aaudio`（输出）、`uplink`（麦克风发送）、`ncm` / `ncm_engine`（有线数据通路）、`stats`、`jvm`（JNI 封装）。
- Opus 由 [opus-pure 0.2.3](https://crates.io/crates/opus-pure/0.2.3) 编解码，编进同一 `.so`；不再携带预编译的 `libopus.so` 与其 JNI 桥。现有测试覆盖本项目的编码回解与 PCM 黄金向量，没有在这里重新验证上游的全部一致性声明。Speex 回声消除仍是原有的 `libspeex_echo.so`。
- 构建：NDK r28、`aarch64-linux-android` 与 `armv7-linux-androideabi`、最低 API 28，`build.sh` 生成后把 `.so` 检入 `shared/src/main/jniLibs/`，CI 不需要 Rust。发布配置 `lto`、`panic = "abort"`、`strip`，并去掉构建机路径。
- 第三方许可写进 `shared/src/main/jniLibs/NOTICE.md` 与 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

### 6.2 边界约定

- **套接字移交**：Kotlin 照旧负责绑定、`accept`、对端校验（只接受控制会话的 iPhone），之后把 fd 交给 Rust（`ParcelFileDescriptor.detachFd`），Rust 拥有它直到关闭。关闭流时 Kotlin 调 `close(handle)`，Rust 用 `shutdown` 唤醒阻塞读并在线程退出时 `close(fd)`。
- **线程**：每条流一个 Rust 接收线程，名字沿用 `airplay-screen`、`airplay-audio-rx`、`airplay-buffered-rx`，诊断和 `/proc` 采样脚本不必改。音频线程设 `THREAD_PRIORITY_AUDIO` 对应的 nice 值。
- **向上回调**：Rust 线程附着到 JVM，每帧调用一次 Kotlin 监听器，只传句柄与元数据（长度、关键帧标志、发送端时间、到达时间、RTP 序号与时间戳），不传字节数组。
- **帧句柄**：`NativePacket` 持有引用计数的池化缓冲，Kotlin 通过 `MediaPacket` 传递所有权。镜像消费方各持有自己的引用；写入解码器、被队列丢弃或流关闭时释放。
- **写入解码器**：原生视频路径的 NDK 回调只排队，由专用线程喂帧和释放输出；Kotlin 保留配置、Surface 和恢复决策。Kotlin 回退路径通过 `MediaPacket.copyInto` 写入解码器输入缓冲。
- **统计**：`StreamReceiveStats` 的字段与日志格式不变，计数在 Rust 内累计，按原有心跳与异常间隔回调一行文本。
- **回退**：每条流在建立时选择实现。库加载失败或在 JVM 单测中，使用现有 Kotlin 实现；日志里记录 `impl=native|kotlin`。Kotlin 实现保留，供主机单测与运行时回退，不再做性能优化。

### 6.3 各路径的实现

| 路径 | 目标 |
| --- | --- |
| 视频 | Rust：`recv` 进池化缓冲 → 原地开封 → 原地 AVCC→Annex B、解析 NAL 类型 → 回调句柄（阶段 1，已实现）。阶段 3：句柄直接进 Rust 的解码循环（NDK `AMediaCodec` 异步模式），关键帧门控、输入背压恢复、按 iPhone 帧时间的节拍与统计在 Rust；`VideoDecoder` 的 worker 只处理配置、Surface、镜像、停放与 Rust 发来的事件（要关键帧、要解码器、重建、出错）。 |
| UDP 音频 | Rust 接收、开封并回调池化包句柄。Opus 用 opus-pure 解码；AAC 保留系统 MediaCodec；LPCM 字节序、通话高通、有声检测、导航衰减与淡入在 Rust 处理。音乐和导航的 usage 路径尝试 AAudio；通话、Siri、旧式流类型及打开失败时使用 `AudioTrack`。 |
| 缓冲音乐 | Rust 读长度前缀帧、开封、回调句柄；锚点、SETRATE、FLUSH、填充节奏与回压留在 Kotlin，队列保存句柄。 |
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
- 选型、回退、路由、焦点、设置相关的判断放在 Kotlin，并保持可用 Robolectric 测试。
- 新的 JNI 入口集中在 `jni` 模块，每个入口检查参数边界后才触碰内存；Rust 侧不 `panic` 跨越 FFI（发布配置为 abort），错误以返回码交给 Kotlin 记录。
- 新的车机接口或属性先登记到平台笔记，再使用。
