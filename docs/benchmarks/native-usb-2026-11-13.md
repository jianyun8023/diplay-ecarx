# 原生 USB NCM 台架验证与待确认项

2026-11-13 小米 8／API 35／ARM64＋真实 iPhone USB host 测试；2026-11-14 按工作区日志复核。USB 已有真实连接、重连和短时传输证据。以下区分原始采样、现场记录与后续版本待验范围；不以 issue 关闭状态代替验收。

## 已测范围与包身份

| 批次 | 已有结果 | 证据与边界 |
| --- | --- | --- |
| v149，`941567d1` | config 6、native NCM、wired transport attached、视频首帧；现场记录 4 次物理拔插均恢复，attach→首帧约 4.2／14.6／3.8／4.6 秒。 | 本地 `artifacts/dev-usb-20261113-origin-dev-941567d1/reconnect-test.log:15–18` 可复核 native 路径及首帧；4 次耗时来自 [#98 现场记录](https://github.com/jianyun8023/diplay-ecarx/issues/98#issuecomment-6853025697)，本次未定位完整 4 轮原始时间线，未重新计算。APK SHA-256：`39aadb1e177e387b9155e2e0ca9533e08081d7904e59b9a240c1146acbf2ec6c`。 |
| #104 修复，rebase 前同逻辑包 | 3 轮 force-stop／重启，均 `attempt=1 action=reuse-descriptors`，记录首帧 35／36／31 秒，无需拔插。 | 本地 `artifacts/usb-memfix-20261113/restart-cycles.log` 及上述现场记录。APK SHA-256：`64d68590b377bd8f3c3cd4226a149dfcf1d06a1dfd004ac26abc189071b6e754`；不是归档的最终 rebased APK。 |
| #104 rebase 及回归测试后的包 | rebased 包启动首帧 33 秒；最终包现场记录为 37 秒，均 reuse-descriptors；新增 2 项会话记忆回归。 | [rebase 记录](https://github.com/jianyun8023/diplay-ecarx/issues/98#issuecomment-6853172149)、[最终包身份与测试](https://github.com/jianyun8023/diplay-ecarx/issues/98#issuecomment-6853468644)及本地 `artifacts/usb-memfix-20261113/README.txt`。最终 APK SHA-256：`df7137a36c0f354016f706d4799400ff0098d1cf5b9094a2ce8d891ea7185611`。 |
| 21:08 有线、21:17 无线切换 | 现场报告记有无线→有线首帧、有线→无线恢复。 | 本地 `reports/mi8-performance-20261113-2100.md` 与截图；本次未找到这两次切换的完整原始 logcat，保留为现场记录，不扩展为自动故障切换、取消授权或多轮组合通过。 |
| 21:11:25–21:14:01 有线采样 | 6 点、156 秒，PID 一致；各点 `host_connected=true`、tun0 存在，收发字节增长。 | 本地 `artifacts/perf-soak-20261113/soak.csv:2–7`、`soak.log`、`run-soak.sh`。属于同晚 v149 usb-memfix 现场记录；采样文件没有单独写入 APK hash。计划 10 分钟未完成。 |

本地路径均相对仓库外的工作区根目录。原日志、APK、设备标识与认证资产不随本文发布。

## 重启慢在哪里

按 `restart-cycles.log` 的同设备事件时间复算，三轮成功重启均主要等待在 MFi 成功之后、AirPlay active 之前：

| 轮次 | config ready→MFi 成功 | MFi 成功→AirPlay active | AirPlay active→首帧 |
| --- | ---: | ---: | ---: |
| 1（原日志 4–7 行） | 0.731 s | 31.914 s | 0.496 s |
| 2（11–14 行） | 0.697 s | 33.557 s | 0.524 s |
| 3（18–21 行） | 0.699 s | 28.437 s | 0.445 s |

这定位了耗时阶段，尚未定位具体阻塞点。下一步需补认证后控制消息、NCM／网络就绪、RTSP／RECORD 等完整时间线。脚本的 `first_frame_s` 起点与物理 attach 不同，不能与 4 次插线耗时混作同一组统计。

旧故障原日志 `artifacts/dev-usb-20261113-origin-dev-941567d1/usb-remount-failure-2010.log` 另记录控制通道 READ FAILED（62 行）、失败后仍按上一会话记忆请求 transition（82 行）、等待 10000 ms（86 行）及配置失效（229 行）。#104 修复失败后的重试决策；最初 READ FAILED 的原因仍未确认，三轮正常重启不等于再次触发原异常后通过。

## 短时采样能说明什么

- tun0 RX `72,861,033 → 126,847,147` 字节，TX `1,082,477 → 2,016,398` 字节；156 秒差分约 **2.77／0.048 Mbps**。这是接口流量，不是纯视频码率。
- PSS 为 `142,907–167,059 KiB`，有波动；没有长时、FD／线程或多轮恢复数据，不能据此判定无泄漏。CSV 的 RSS 列实际为 `TOTAL`，不能使用。
- 六点电池温度均为 41°C，电量 20%→18%。`dumpsys cpuinfo` 摘录均记应用 60%，但未保存每点 CPU 统计窗口，不能当作六次独立区间测量。
- 所选 AudioFlinger 行的 underrun／overrun 为 `32／814`，采样内未增长。采集脚本取第一个匹配行，未绑定应用 track；不能替代应用音频欠载或听感验收。
- `soak.log` 未保存脚本结尾的错误计数段；现场报告中的“零 NCM error／零 rebuffer”等不属于本次由该文件重新确认的结果。`gfxinfo` 是 UI 统计，不是 CarPlay 视频显示帧率。
- 先后采集的无线／有线 CPU、温度和图像负载不同，不能从快照差值计算 USB 开销或 Rust 性能收益。11 月 14 日 `cap-v150-20261114-session1` 的实车性能批次走无线，也不补足 USB 对照。

## USB 后续优先级

| 优先项 | 待确认内容与验收证据 |
| --- | --- |
| 连接慢样本 | 优先追查三轮重启中 MFi 成功→AirPlay active 的 28–34 秒等待；14.6 秒拔插慢样本另列。分开插线、应用重启、手动切换的计时起点，补枚举／权限、config transition、认证后控制消息、网络／RTSP、解码配置和首帧时间线，再判断具体阻塞点。 |
| 异常恢复与资源收尾 | #104 原失败链已有原日志和回归测试，但正常重启通过不等于再次触发原失败后恢复。针对 AirPlay active 前失败后重试、USB／VPN 拒绝或撤销、传输中拔线、native 启动失败回退补验；当前 #111 的 FD 所有权修复有合同测试，仍需在实际 I/O 中核验拆除／重建和 FD／线程回落。 |
| 持续有线性能 | ARM64 同包、同分辨率／codec、相同滑动／导航／音频负载，固定诊断设置与温度条件。分别观察 USB／tun 接收发送、触摸排队、视频输入输出、音频欠载和资源趋势；补足超出本次 156 秒的持续运行及多轮恢复。先获得分段证据，再决定是否优化。 |
| 目标车机与组合 | 旧 v122 已有 ECARX USB 实车记录；新 Rust NCM 与当前改动组合仍需 API 28／753／820A 验收，包括无 ADB 权限流程、线缆重连、熄火唤醒及通话／导航／双屏并行。小米 8 可继续验证 ARM64 逻辑，不能替代固件差异。 |

历史 USB 优化建议也须核对当前源码：USBMUX 已复用 `UsbRequest`／buffer，不能继续把“每读新建请求”当作当前实现。CarKit 控制通道的 256 字节分块及诊断仍存在，其必要性和成本需单独量化；它不是原生 NCM 媒体数据通道，不能据此直接解释视频卡顿。

其他领域的待验项见[当前验收矩阵](../RUST_CORE_REMAINING.zh-CN.md)。本批配置失败交错的 ARM64 green 仍等小米 8 释放；这些 USB 后续项不并入该局部修复 PR 的完成条件。
