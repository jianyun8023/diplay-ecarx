# Siri 采集路径 P0/P1 对照验证

本次实现仅包含诊断 P0 和通信采集对照 P1。用户报告中的同车 telephony/Siri 电平差异
是根因候选的依据；没有本次 APK 的实车数据，不能宣称 Siri 已修复。
不改变 source 选择、电话采集、按键分发、HiCar 或原厂助手行为。

## 构建 A/B 包

普通 Debug 包默认关闭实验。只有显式传入 `-PsiriCommunicationCapture=true` 的 Debug
构建会让 `speechrecognition` 调用现有 `enterCommunicationMode`：
`MODE_IN_COMMUNICATION`、`hicar_enable=true`、`AudioControlMode=01121001`、`ASR_AEC=ON`，
以及在 ECARX `isNeedrequestMic` 为 true 时的 `requestMic(CALL=2)`。Release 始终关闭。
不新增 SYSTEM/RECORD 或逐项参数开关；等 B 的数据证明有效后再进入 P2。

```sh
# A：P0 + 现有采集路径
./gradlew :mobile:assembleDebug
mkdir -p build/siri-capture
cp mobile/build/outputs/apk/debug/mobile-debug.apk build/siri-capture/siri-A-source-debug.apk

# B：P0 + P1 完整通信采集路径
./gradlew -PsiriCommunicationCapture=true :mobile:assembleDebug
cp mobile/build/outputs/apk/debug/mobile-debug.apk build/siri-capture/siri-B-source-debug.apk
```

两个包使用同一个 Debug applicationId 和签名，依次覆盖安装可保留设置；不要清除数据。
默认 source-only 包没有认证素材，不能进入本项目的 CarPlay 握手。
实车独立包使用已有合法认证目录和现有构建入口（不把认证文件加入 Git）：
该目录的 `offline-mfi/` 仅放 `identity.pk8` 和 `certificate.p7b`；原目录混有额外文件时，
只复制这两项到专用本地目录，不跳过构建的认证文件检查。

```sh
DIPLAY_AUTH_ASSETS_DIR=/absolute/path/to/runtime-assets ./gradlew :mobile:assembleStandaloneDebug
DIPLAY_AUTH_ASSETS_DIR=/absolute/path/to/runtime-assets ./gradlew -PsiriCommunicationCapture=true :mobile:assembleStandaloneDebug
```

分别构建后立即另存 A/B APK，后一个构建会覆盖 `mobile-debug.apk`。
此开关在编译时确定，设置界面不需要增加开关。测试报告中的
`siriCommunicationCapture=false/true` 用于核对实际安装的组别。

## P0 报告字段

- 原有 `Microphone: start/stats`：请求 source、`AudioRecord.getAudioSource()` 的
  `actualSource`、route type、peak/RMS、全零/削波、读取/编码/发送统计。
- `Microphone: environment`：开始前、开始后、每个五秒窗口、停止后的实际 mode、
  microphoneMute、实验开关、activeRecordings 数量与可见性。停止快照在恢复 mode 后。
- `Microphone: recording`：最多八个活跃客户端的包名（可用时）与 client source 数值；
  总数单独保留。不会导出会话 ID、设备标识、PCM 或网络地址。
- `Audio: ECARX mic policy/request/onResume/onAbort`：需要仲裁与否、CALL(2)、请求返回、
  回调确认与中止 reason。`accepted=true` 只代表请求被接受；同步返回不能证明持续获麦，
  请求行的 granted 为 unknown，实际授予/中止看回调。
  `isNeedRequestMic=false` 沿用现有门控的容错语义，也可能是 API 不可用；结合类探测字段判断。
- `Audio: 820 mic parameter`：每项参数写入是否抛错。`writeOk=true` 不能证明 HAL 已生效。
- `Audio: mode restored` 与 `Audio: ECARX mic released`：原 mode 和当前 mode、是否持有资源、
  releaseMic 返回是否成功。即使 mode 恢复被系统拒绝，仍尝试关闭参数并释放仲裁。

Android 的公开接口提供 [active recording configurations](https://developer.android.com/reference/android/media/AudioManager#getActiveRecordingConfigurations())
和 [client source](https://developer.android.com/reference/android/media/AudioRecordingConfiguration#getClientAudioSource())；
包名不是公开 SDK API，仅在车机允许反射且没有匿名化时可读。无法读取时是 unavailable/unknown，
不会猜测为 DiPlay。`visibility=anonymized-or-filtered` 的列表不能证明没有其他录音客户端；
排他性结论需要车机允许的系统级观测。诊断拒绝或异常不阻断录音。

## 一次实车验证清单

停车、车窗关闭、固定空调风量。每次新会话说同一段长句至少 15 秒，保留全部五秒窗口，
避免把 mic gain ramp 的前五秒当成最终结果。P1 仍复用单一通信资源持有者，
本轮禁止 Siri 与电话重叠；不把这一限制当成 P3 的重叠资源管理已完成。
合入 dev 的 UNI/DIM 后端选择后，A/B 必须使用同一个媒体后端，并在记录中注明；
不要在切换采集实验组的同时更换媒体后端。

| 检查 | 操作 | 记录/通过条件 |
| --- | --- | --- |
| A | 安装基线包，重复 Siri 指令 | 真实 source/mode、录音可见性、peak/RMS，确认能复现原问题 |
| B | 安装实验包，固定时间/导航/音乐/天气/车控五条指令 | 记录每条实际结果，目标 5/5；正常说话 peak > −25 dBFS 或相对 A 提高至少 10 dB |
| 释放 | 每次 Siri 结束后 | mode 恢复到开始前值（开始前为 0 才要求恢复为 0）、mic release 成功、媒体/导航恢复 |
| 连续会话 | B 连续十次 | 10/10，无 mode 残留、无仲裁泄漏 |
| F | 短按原厂助手，等五秒后长按 Siri | 比较 B 电平；没有争抢证据前不停止原厂应用 |
| G | 长按时看中控 UI 和提示音 | 只记录是否原厂助手双触发；若出现，另立按键抑制问题 |
| 电话回归 | 对照 A/B 各三次通话 | 3/3、电平一致、路由/媒体/导航恢复正常 |
| C/D/E | B 有效之后另一个实现阶段 | SYSTEM/RECORD、mode/hicar/单参数逐项最小化；本次未实现、不预判结果 |

B 稳定超过阈值后才认为通信采集路径候选获支持，进入 P2。
若 B 仍 ≤ −36 dBFS，先看活跃录音客户端和可见性；只有系统级证据确认没有其他客户端，
才转查 HAL，并在独立对照中尝试 MIC/VOICE_RECOGNITION。
不直接默认化 CALL、不增加启动 watchdog、不实施 P3 重叠管理；这些都留待实车结果决定。

## 软件验证范围

`SiriCommunicationCaptureTest` 在 API 28 和 33 使用真实 sink/uplink 与反射 policy 夹具，
覆盖 A/B、参数与仲裁在采集前、电话基线、原 mode 恢复、录音创建失败、仲裁拒绝/中止、
无需仲裁、mode/参数写入失败、mode 恢复失败时仍释放、释放拒绝、非匹配流停止、
连续十次、sink 关闭、诊断异常、录音客户端与静音状态、客户端不可读。
`MicrophoneCaptureStatsTest` 验证快照只在开始/五秒窗口/结束触发，包含至少 15 秒的多窗口。
既有 source 回退、真实 source、电话音源与扬声器回归一并运行。
这些验证不模拟车机 HAL 增益、Siri 识别成功率或真实麦克风争抢。

完整检查使用 `AGENTS.md` 的九项任务。环境支持可复用现有 Robolectric 离线 init 脚本，
不筛掉 SDK/测试、不修改生产逻辑掩盖失败。实车结果和软件结果分别记录。
