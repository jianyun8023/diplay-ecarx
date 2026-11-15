# 默认模式诊断：小米 8 实测（2026-11-15）

## 构建与条件

- 被测源码：`80499891ed57bf84f7537705a6c356731b1a2440`，从该提交导出干净源码构建；未包含工作区另行进行的启动优化。
- 设备：小米 8 / LineageOS / Android 15（API 35）；主应用 ARM64 Release / R8，平台签名，保留既有应用数据、配对和本地 MFi。此设备属于调试环境，不替代 API 28 实车验收。
- 被测版本：`versionCode=150`、`0.2-car-test-diagnostics-80499891-20261115`。交付版本号随后递增为 151；本表不将尚未运行的 v151 安装包冒充本次被测包。
- 无线条件：使用应用已保存的 MANUAL 热点配置，5 GHz；配对 iPhone 在附近。测试期间 `debug_logs_enabled=false`，未打开全量日志，未清除 logcat。既有模拟副屏保持原样。
- APK SHA-256：`5254dfb8aa260be8f62bb1983f1ed8b40ee82b16995d38a9533a0b58a6d72a89`；签名与覆盖安装前一致。主应用与测试 APK 的 ARM64 Rust 库字节一致。

## 结果

| 检查 | 实测结果 |
| --- | --- |
| ARM64 instrumentation | 23 / 23，通过；9.227 s |
| ARMv7 instrumentation | 同一测试 APK 按 ARMv7 安装运行，23 / 23，通过；9.231 s；之后恢复 ARM64 测试包 |
| 原生合同范围 | 媒体健康预算/迟到最终统计/换轨计数、音频路由与 feedback、真实视频输出采样、导航焦点、MQTT 与 HUD 合同 |
| 无线认证与视频 | 两轮均完成 MFi、AirPlay、解码首帧和隧道后输出采样；第二轮由正常断开后重新连接发起 |
| 音乐控制 | 定向使用 `DiPlay CarPlay` 标准 MediaSession，播放、暂停、恢复、下一首通过；状态为预期的 PLAYING/PAUSED，切歌元数据变化 |
| 音画确认 | 用户确认小米 8 的画面和声音均正常；日志确认 AAC_LC / 48 kHz / 双声道及 AAudio 输出 |
| 前后台 | HOME 后返回同一 Host，记录 HOST_STOP/START 及新的解码首帧；播放保持，恢复采样单列 |
| 正常关闭 | 两轮 controllerClosed=true，退出快照 27 / 28 ms；退出开始后 209 / 236 ms 最终归集完成，另行持久化，pendingWorkers=0、closing=true、final=true，owner 各自一致 |
| 媒体故障 | 两轮最终 underruns、writeErrors、zeroWrites、partialWrites、decoderDrops、rebuffers、videoRecoveries、videoCodecErrors 及音视频队列丢弃均为 0 |
| 默认报告导出 | 通过设置页正式导出成功；当前文件在前，历史在后；当前与上轮 logId 不同，历史旧版事件按进程区分 |
| 写入健康 | 当前 writer 刷新成功，pending/dropped/writeFailures/rejectedAfterClose=0；进程 asyncPending/asyncDropped/supersededWrites=0。只描述导出时快照，不追溯推断旧 writer |

两轮指标为单次观察值，不计算 p50/p95，不代表整车冷启动或物理屏幕可见耗时：

| 轮次 | 本代运行至首帧 | 隧道启动至输出提交采样 | 前台恢复至首个输出 |
| --- | --- | --- | --- |
| 首次连接 | 5899 ms | 8 ms | 172 ms |
| 正常断开后重连 | 4751 ms | 14 ms | unknown（本轮未采到返回后的新输出标记） |

报告保留的旧 PID 失败样本来自升级前热点未准备好的运行，不计入本次两轮结果。系统 ACodec 的最小缓冲配置警告、一次 ion ioctl 警告，以及接口/UDP 计数读取不可用仍存在；本次未伴随媒体错误或用户可见异常，不据此宣称这些平台能力已经修复。

## 默认日志量

第一轮会话日志共 37,251 B / 321 行，覆盖 271.794 s。首分钟包含连接初始化，为 23,366 B；随后三个完整分钟分别为 3,205 / 3,176 / 3,587 B。排除初始化和末尾操作窗口，观察到约 3.28 KB/min、25 行/min；这只是本次短时场景，不能外推所有导航/异常负载。

媒体健康有 4 条正常摘要，间隔 60.479 / 60.334 / 60.347 s，另有 1 条关闭快照；每条约 344 B。退出开始后 209 ms 最终归集完成，已保存在独立的 `media_completion`，不把已关闭会话文件的缺行解释成未完成。去重器可核对的 36 次裸 iAP 通知中保留 10 次、抑制 26 次；带状态/计数的记录仍保留。

重连后静态阶段（首分钟后 217.569 s）仅增长 2,311 B / 16 行，约 637 B/min；正常健康摘要间隔 60.288 / 60.278 / 60.315 s。正式导出共 707,843 B，其中六份旧版历史为 594,464 B（约 84%），当前文件仅 30,797 B；不能把完整报告体量当作本轮日志增长量。导出读取窗口 444 ms，六份旧档明确标注版本/revision unknown。

## 证据与限制

原始 APK、日志、屏幕/系统快照和导出报告仅保存在本地 `artifacts/diagnostics-mi8-20261115/`（仓库父目录），不提交设备身份、配对、网络或 MFi 内容。可追溯文件包括 `apk-verification.json`、两 ABI 的 `instrumentation-*.txt`、`media-control-results.json`、`foreground-actions.json`、`session-first-final.log`、`shutdown-first.xml` 和 `diagnostic-report-active.txt`。

测试结束后正常断开第二轮会话，保存 `session-final.log` / `shutdown-final.xml`；热点恢复关闭，Wi-Fi 保持开启，原有模拟副屏不变。新包保留安装，配对和配置保留，全量日志开关仍为 false。

本地 Rust/JVM/三 ABI 构建记录见[默认模式诊断](../DIAGNOSTICS_DEFAULT_MODE.zh-CN.md)。未覆盖实车 ECARX DIM/MQTT 服务、原厂导航和蓝牙恢复实际效果、AEC/通话、长时间运行、ACC/冷启动十轮及 x86_64 设备执行；合同测试通过不等于这些硬件场景验收。
