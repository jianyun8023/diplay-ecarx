# AAC 输入与原生麦克风链路：小米 8 设备验证

2026-11-15，按用户授权，在小米 8／Android 15／API 35 上验证 G/H 本轮改动。使用独立 Debug instrumentation 包 `com.shihab.diplay.shared.test`，同一 APK 分别安装为 ARM64 和 ARMv7。

源码基线为 `dev bfd6b6a996319f79741b899836e377f41abcea9e`，测试时叠加工作分支 `refactor/aac-input-speex-pcm` 的 G/H 改动；以下冻结 APK 和原生来源摘要标识当时的测试产物，不能仅凭基线 SHA 复现。

## 结果

| ABI | 通过 | 失败 | 跳过 | instrumentation 用时 |
| --- | ---: | ---: | ---: | ---: |
| arm64-v8a | 22 | 0 | 0 | 8.821 秒 |
| armeabi-v7a | 22 | 0 | 0 | 9.280 秒 |

两次都完整返回 `OK (22 tests)`，安装 ABI 由 `dumpsys package` 核对。首轮 ARM64 的原有 21 项也通过；补入真实录音烟测后，以表中最终同包的两轮结果为准，不把首轮重复计入最终覆盖数。

| 测试类 | 每 ABI 项数 | 实际覆盖 |
| --- | ---: | --- |
| NativeAacDeviceTest | 3 | ART 数组异常、AAC 配置／payload、native／heap 包与 direct／heap 输入缓冲、边界和只读拒绝。 |
| NativeMicrophoneCaptureDeviceTest | 3 | 分片 PCM 经 JNI 到加密 UDP 的独立字节合同；16／24／48 kHz 静态 Speex；真实 AudioRecord 采集、PCM／Opus、关闭重开。 |
| NativeOpusEncoderTest | 4 | 双码率和不同信号可解码、非法帧后恢复、能力探测、1,000 次创建释放、编码与关闭并发。 |
| NativeCallAudioCoreDeviceTest | 2 | echo ring 的 heap／direct 输入与独立引用寿命、采集时钟／路由对拍。 |
| NativeAudioStateDeviceTest | 7 | 加密 buffered AAC 经真实 MediaCodec 解码、输出播放头推进、暂停／恢复／FLUSH，以及既有队列／接收合同。 |
| NativeAudioOutDeviceTest | 3 | AAudio 残留排空、输出关闭重开、焦点回调与释放交错。 |

新增的 `audioRecordFeedsNativePcmAndOpusAndCanRestart` 对 PCM 16 kHz 和 Opus 48 kHz 各执行两次启动，每次接收并验证 5 个加密 RTP 包：版本、payload type、sequence、timestamp、nonce、认证解密及 PCM 帧长／Opus 960 样本解码。软件 Speex 已启用，结束后检查 worker 退出、采集 owner／recorder 引用清空和最终统计报告。采集字节仅用于本进程和 loopback 接收，不保存录音或发送到外部主机。

只为独立测试包临时授予 RECORD_AUDIO；结束后已撤回，恢复此前未授权状态，并将测试包安装 ABI 恢复为 ARM64。

## 产物与证据

- 测试 APK SHA-256：`ede6699e4d1dbe07b5cfbdcb499190c007a9ae38bf1bb1ef0a0fbeb70874be93`。
- Rust/C 输入来源摘要：`a26b21f799277713de81570b6b9bad99bac16fb7e757706b1e36f4bf69e9173a`。
- 包内 ARM64 核心 SHA-256：`42e99bb2364ac819ee27b05d3ceba97e0c99c3e5bd8dd10fb8496c2fd40d1759`。
- 包内 ARMv7 核心 SHA-256：`dbea844ec30822afbb7ba9cc1089f0462464fa8192b542e3ddbdd688bc191e9e`。

本地原始证据在 `build/device-validation-gh-20261115/`：冻结的 `gh-tests.apk`、`results.json`、两份 `*-final-instrumentation.txt`、安装／恢复后的 package 信息及构建日志。原始设备信息和 APK 不入库。

复跑同组测试时，使用上述六个类的完整类名，逗号分隔后传给 `am instrument -w -r -e class`，runner 为 `com.shihab.diplay.shared.test/androidx.test.runner.AndroidJUnitRunner`；真实录音烟测需要先授予独立测试包 RECORD_AUDIO。通过 `adb install -r -t --abi <abi>` 选择安装 ABI，测试后恢复原 ABI 和权限状态。

## 验证边界

这是小米手机上的真实 ART／JNI、MediaCodec、AAudio 和 AudioRecord 验证。回声引用使用测试数据，未验证实际声学回声消除效果；没有进行真实 iPhone Siri／通话会话或 ECARX／Android 9 车机音路验收。未测性能收益、长时稳定性或缩减 Release 包的设备运行。

本轮未改生产处理逻辑以适配手机；仅新增上述设备烟测并修正已删除 Java 发送路径的遗留注释。此前 Rust／JVM／三 ABI／R8 结果保留原记录，见 [PR #122](https://github.com/jianyun8023/diplay-ecarx/pull/122)；当前职责见[架构说明](../ARCHITECTURE.zh-CN.md)。
