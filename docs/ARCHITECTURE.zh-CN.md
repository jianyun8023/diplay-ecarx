# DiPlay 技术架构

本文说明 DiPlay 领克 01 / ECARX 753 移植版的模块划分、一次 CarPlay 会话的组成、控制面与数据面的分工，以及媒体数据面迁到 Rust 的目标设计。代码归属的细则以根目录 [AGENTS.md](../AGENTS.md) 为准；厂商接口与实车结论见[平台笔记](ECARX_753_PLATFORM_NOTES.zh-CN.md)。

状态标记：**现状** 指 `dev` 上已有的实现；**目标** 指已确定方向、正在或即将实现的设计。目标一节落地后，把对应行改成现状，不另留历史。

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
| 控制面（Kotlin） | 连接编排与重试、RTSP / 配对 / 加密控制通道、设置与持久化、流的建立与拆除、MediaCodec 的选型 / 配置 / 回退 / Surface 切换与停放、AudioTrack 的创建 / 路由 / 焦点 / 车机 BUS 选择、缓冲音乐的锚点与 SETRATE / FLUSH、仪表与 HUD、诊断报告。 |
| 数据面（Rust，目标） | 媒体套接字收发、ChaCha20-Poly1305 开封 / 封装、帧重组与 AVCC→Annex B、关键帧识别、把压缩数据写进解码器输入缓冲、Opus 解码与编码、PCM 处理（字节序、淡入、导航衰减、有声检测、通话高通滤波）、回声消除、RTP 打包、有线 NCM 与 tun 之间的转发。 |

不迁的部分及原因：

- **视频解码本身**由高通硬件解码器完成，Java `MediaCodec` 与 NDK `AMediaCodec` 走同一条 OMX/Codec2 路径。`VideoDecoder` 里实车验证过的选型、私有参数回退、Surface 停放与 fanout 属于控制面，迁到 NDK 只会重写这些逻辑而不减少解码开销。数据面只接管“把帧字节放进输入缓冲”这一步。
- **AAC 解码**沿用系统 Google AAC（实车上高通 AAC 解码器创建失败，见平台笔记），Rust 负责组 ADTS 头并直接写入输入缓冲。
- **音频输出**沿用 `AudioTrack`。753 的媒体轨没有 FastMixer，HAL 每块 40 ms；API 28 上 AAudio 共享模式仍经 AudioFlinger，降不了延迟，却要重做焦点、旧式流类型回退和车机 BUS 路由。Rust 产出的 PCM 放在直接缓冲里，用 `AudioTrack.write(ByteBuffer)` 写出。

## 5. 现状：媒体数据路径的开销

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

因此数据面迁移的收益主要来自：每包零 Java 分配（减少 GC 暂停）、每包一次以内的 JNI 跨越、少一次线程交接，而不是单纯更快的拷贝。

## 6. 目标：Rust 数据面 `diplay-media`

### 6.1 库与构建

- 一个 crate：`shared/src/main/rust/diplay-media`，产物 `libdiplay_media.so`，取代 `libdiplay_chacha.so`。
- 模块：`crypto`（ring 的 ChaCha20-Poly1305）、`pool`（帧与 PCM 缓冲池）、`video_rx`、`audio_rx`、`buffered_rx`、`opus`（链接已预编译的 `libopus.so`）、`pcm`、`aec`（Speex，用 `cc` 编进同一 `.so`）、`uplink`、`ncm`、`jni`（全部 `extern "system"` 入口）。
- 构建：NDK r28、`aarch64-linux-android` 与 `armv7-linux-androideabi`、最低 API 28，`build.sh` 生成后把 `.so` 检入 `shared/src/main/jniLibs/`，CI 不需要 Rust。发布配置 `lto`、`panic = "abort"`、`strip`，并去掉构建机路径。
- 第三方许可写进 `shared/src/main/jniLibs/NOTICE.md` 与 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

### 6.2 边界约定

- **套接字移交**：Kotlin 照旧负责绑定、`accept`、对端校验（只接受控制会话的 iPhone），之后把 fd 交给 Rust（`ParcelFileDescriptor.detachFd`），Rust 拥有它直到关闭。关闭流时 Kotlin 调 `close(handle)`，Rust 用 `shutdown` 唤醒阻塞读并在线程退出时 `close(fd)`。
- **线程**：每条流一个 Rust 接收线程，名字沿用 `airplay-screen`、`airplay-audio-rx`、`airplay-buffered-rx`，诊断和 `/proc` 采样脚本不必改。音频线程设 `THREAD_PRIORITY_AUDIO` 对应的 nice 值。
- **向上回调**：Rust 线程附着到 JVM，每帧调用一次 Kotlin 监听器，只传句柄与元数据（长度、关键帧标志、发送端时间、到达时间、RTP 序号与时间戳），不传字节数组。
- **帧句柄**：`NativeFrame` 持有引用计数的池化缓冲，暴露只读直接 `ByteBuffer` 视图。主屏与镜像解码器各 `retain` 一次；写入解码器、被队列丢弃或流关闭时 `release`。`VideoDecodeQueue` 的丢弃、`drain` 路径都必须释放。池满时按最新帧优先的规则丢帧并触发现有的 Resync 恢复。
- **写入解码器**：解码线程照旧 `dequeueInputBuffer` / `getInputBuffer`，然后调 `NativeMedia.copyInto(frame, inputBuffer)`，Rust 经 `GetDirectBufferAddress` 一次 `memcpy`，再由 Kotlin `queueInputBuffer`。关键帧判断（`VideoReferenceChain`）读取 Rust 给出的标志，不再扫描字节。
- **统计**：`StreamReceiveStats` 的字段与日志格式不变，计数在 Rust 内累计，按原有心跳与异常间隔回调一行文本。
- **回退**：每条流在建立时选择实现。库加载失败或在 JVM 单测中，使用现有 Kotlin 实现；日志里记录 `impl=native|kotlin`。Kotlin 实现保留，供主机单测与运行时回退，不再做性能优化。

### 6.3 各路径的目标形态

| 路径 | 目标 |
| --- | --- |
| 视频 | Rust：`recv` 进池化缓冲 → 原地开封 → 原地 AVCC→Annex B、解析 NAL 类型 → 回调句柄。Kotlin：队列、背压恢复、选型与 Surface 不变，输入改为 `copyInto`。 |
| UDP 音频 | Rust：`recvmmsg` 批量收包 → 原地开封 → 回调句柄。`AudioRenderer`：Opus 由 Rust 用 libopus 解成 PCM；AAC 由 Rust 加 ADTS 头写入 MediaCodec 输入、从输出缓冲读回 PCM；LPCM 由 Rust 转字节序。PCM 处理（通话高通、有声检测、导航衰减、淡入）在 Rust 内对直接缓冲原地完成，Kotlin 只做 `AudioTrack.write(ByteBuffer)`、重缓冲、时钟微调与焦点。 |
| 缓冲音乐 | Rust 读长度前缀帧、开封、回调句柄；锚点、SETRATE、FLUSH、填充节奏与回压留在 Kotlin，队列保存句柄。 |
| 麦克风 | Kotlin：`AudioRecord.read` 到直接缓冲，每 20 ms 调一次 `uplink.push(buffer, echoRef)`。Rust：Speex AEC → libopus 编码 → RTP 打包 → 封装 → `sendto`。回声参考由 `AudioRenderer` 写出的 PCM 直接进 Rust 环形缓冲。 |
| 有线 | Kotlin 照旧认领 USB 接口、建立 VPN 与 tun、管理生命周期。Rust：用 `UsbDeviceConnection.getFileDescriptor()` 上的 usbdevfs URB 收发 bulk，解析 / 构造 NTB16，与 tun fd 直接互转，包括现有 IPv6 桥接规则。 |

## 7. 分阶段与验收

每一阶段单独成 PR，先在小米 8 台架对照，再上车验证。

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| 0 | 基线：60 fps 会话 + 音乐，记录接收线程与整进程 CPU、GC 次数、帧到达到入解码器的 p50/p95、音频欠载 | 两轮可复现的数字 |
| 1 | `diplay-media` 接收与开封：视频、UDP 音频、缓冲音乐；帧句柄与 `copyInto` | 接收线程 CPU 下降；会话内 GC 次数下降；无新增恢复、欠载 |
| 2 | 音频解码与 PCM 处理迁入 Rust（Opus、AAC 输入输出、LPCM、PCM 处理） | Opus/AAC 输出与 Kotlin 路径逐样本一致或在误差内；导航衰减、通话路由、焦点行为不变 |
| 3 | 麦克风上行全链路（AEC、编码、打包、封装、发送） | Siri 与通话实车可用；上行包格式与现有实现逐字节一致（同输入） |
| 4 | 有线 NCM ↔ tun | 有线连接、断开、重连实车可用；有线视频帧率与 CPU 对照 |

测量方法：接收线程 CPU 取 `/proc/<pid>/task/*/stat` 的 utime/stime，会话日志里的 `Receive:` 心跳行给出每包读、处理与解密耗时；A/B 用应用重启 + 标记文件切换实现，脚本与原始结果放在本地工作区，结论回填到本节或平台笔记。

## 8. 新代码放在哪

- 按包、按帧执行的逻辑放进 `diplay-media`，在 Kotlin 侧只暴露句柄与配置；不要在接收或渲染循环里新建 `ByteArray`。
- 选型、回退、路由、焦点、设置相关的判断放在 Kotlin，并保持可用 Robolectric 测试。
- 新的 JNI 入口集中在 `jni` 模块，每个入口检查参数边界后才触碰内存；Rust 侧不 `panic` 跨越 FFI（发布配置为 abort），错误以返回码交给 Kotlin 记录。
- 新的车机接口或属性先登记到平台笔记，再使用。
