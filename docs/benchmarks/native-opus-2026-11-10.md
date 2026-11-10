# 预编译 libopus 实测（2026-11-10）

本次测量直接调用本 PR 的 `NativeOpusEncoder`、提交的 JNI 桥接和 Kopus 1.0.1 AAR
中的 **libopus 1.5.2**，对照未修改的 `SoftwareOpusEncoder` / Concentus。
此前 libopus 1.6.1 源码构建的探索性结果不作为本 PR 的性能依据。
原生库来源、SHA-256 和重建桥接命令见 [NOTICE](../../shared/src/main/jniLibs/NOTICE.md)。

## 设备与方法

- 小米 8，SDM845，ARM64，LineageOS Android 15 / API 35；接电、屏幕保持唤醒，电池温度约 30°C，未锁 CPU 频率。
- 独立 `lab.opusbench` instrumentation APK，ART `compile -m speed`。每轮新进程；音频线程优先级 -16。
  Kotlin 编码器类直接来自本分支 Gradle debug 输出，Concentus 从同一源码编译。
- 48 kHz、单声道、PCM16LE、960 samples / 20 ms、VOIP、VOICE、complexity 5、48 kbps；
  另测 24 kbps。保留两库默认 constrained VBR、FEC 关闭、DTX 关闭。
- [公开语音样本](https://jmvalin.ca/opus/opus-1.3/speech_samples/speech_orig.wav)
  经 ffmpeg 转为 48 kHz 单声道 PCM16LE，取完整帧循环使用；另测固定种子噪声和静音。
  PCM SHA-256：`02b50806fa9e3b2dd64d3f400e59d0ece6064efd9aab9e2f371e6cfb58faae09`。
- 每轮先预热 1,000 帧。连续吞吐每轮测 2,500 帧；实时节奏每 20 ms 投入一帧，测 1,500 帧 / 30 秒。
  两实现按 AB / BA 换序，共 20 轮、46,000 个计量帧。下表取各轮统计值的中位数；
  P95 是各轮 P95 的中位数，不是合并样本 P95。
- `Debug.threadCpuTimeNanos()` 测编码线程 CPU，`Process.getElapsedCpuTime()` 测进程 CPU；
  ART runtime stats 测进程 Java 累计分配和 GC，`Debug.MemoryInfo` 测进程 PSS。
  分配量包含测试框架开销；PSS 是测量结束时编码器尚未关闭的快照。

## 小米 8 结果

实时节奏、48 kbps 语音，每种实现两轮：

| 指标 | Concentus | 预编译 libopus |
|---|---:|---:|
| 平均编码调用耗时 | 8.140 ms | 2.644 ms |
| P95 | 12.043 ms | 3.904 ms |
| 编码线程 CPU，单核占比 | 39.37% | 13.38% |
| 进程 CPU，单核占比 | 41.21% | 13.61% |
| Java 分配 / 帧 | 122,596 B | 164 B |
| GC / 30 秒 | 8 | 1 |
| 结束时进程 PSS | 18.45 MiB | 10.99 MiB |

平均调用耗时降低约 **67.5%**，编码线程 CPU 降低约 **66.0%**。
Java 分配由约 6.13 MB/s 降至 0.0082 MB/s；独立测试进程 PSS 低约 7.46 MiB。
这不是整个 DiPlay 应用的 CPU 或内存节省预测。
这四轮中 Concentus 有一次调用超过 20 ms（51.39 ms），libopus 没有；没有追踪该离群值的系统原因。
实际 VBR 输出分别约 45.37 / 43.68 kbps，并非相同比特流或相同音质证明。

连续吞吐结果（与真实 20 ms 间隔的 CPU 调频状态不同，不能混作实时负载）：

| 输入 | Concentus 平均耗时 | libopus 平均耗时 | 每种实现轮数 |
|---|---:|---:|---:|
| 48 kbps 语音 | 0.838 ms | 0.230 ms | 4 |
| 24 kbps 语音 | 0.816 ms | 0.218 ms | 2 |
| 48 kbps 噪声 | 0.791 ms | 0.219 ms | 2 |

全部轮次的原始统计量见 [CSV](native-opus-2026-11-10.csv)。测试 APK SHA-256：
`bfd8f8e51db0da25461022fcdc6cc64aee1069b67be2cb411975366b9c59ef7a`。
完整本地日志、测量程序及构建证明保存在工作区 `analysis/20261110-native-opus-pr/`。

## 正确性、生命周期及构建

- 小米 8 与 Android 9 / API 28 ARM64 模拟器各跑两编码器 × 语音/噪声/静音，
  每项 500 帧，共 12 项通过。每个 packet 均分别经 Concentus 和 libopus 1.6.1 解码验证；
  后者仅在正确性模式加载，不进入性能测试进程。
- 仓库内 `NativeOpusEncoderTest` 四项在小米 8 ARM64、小米 8 强制 ARMv7、API 28 ARM64
  上全部通过。覆盖 24/48 kbps 三种输入的解码、错误帧、重复关闭、关闭后编码、
  1,000 次创建/释放及编码与关闭并发。`dumpsys package` 确认两次小米安装分别为 `arm64-v8a` / `armeabi-v7a`。
- AGENTS.md 要求的完整 Gradle 命令通过：3,316 项单测通过、2 项既有测试跳过，四个应用 lint / debug APK 构建成功。
  JVM 单测覆盖原生库不可加载时的 Concentus 回退；公开仓库内容检查与 `git diff --check` 通过。
- 主应用 APK 包含两个 ABI 的 Opus 库和对应许可证。四个新增 `.so` 共 710,552 B（约 694 KiB）；
  当前 APK 原样存储这些库，另有 ZIP 对齐/元数据开销。普通 Gradle / CI 构建不编译 native 代码。

没有测试 ECARX 753 / 820A 实车、完整 Siri / CarPlay 上行链路或主观音质。
保留系统 MediaCodec 优先、libopus 次之、Concentus 最后的选择顺序；已有系统 Opus 编码器的设备不会因此切换到 libopus。
