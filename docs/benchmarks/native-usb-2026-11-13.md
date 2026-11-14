# 原生 USB NCM 台架验收记录（本轮已收尾）

2026-11-13 小米 8／API 35／ARM64＋真实 iPhone USB host 测试；2026-11-14 按工作区日志复核。USB 已有真实连接、重连和短时传输证据。以下区分原始采样、现场记录与后续版本待验范围；不以 issue 关闭状态代替验收。

**收尾状态（2026-11-14）：用户确认当前状态 OK，本轮 USB 工作结束。** 已有连接、拔插、重启、切换和短时采样结果归档，不继续安排 USB 分析、优化或补测。下述未覆盖范围仅限定结论，不作为本轮待办或其他修复 PR 的阻塞条件。

后续从 Deepseek Harness 会话 `session-b2b76378-9ce0-4e43-8a6e-25c134906f56` 找回原工具调用及输出，补齐此前仅见现场摘要的证据。摘录及来源校验保存在本地 `artifacts/mi8-usb-log-review-20261114/`；原会话解压行号为 `seq + 2`。本摘要仅采用小米 8，未采用 HP 模拟器结果。

## 已测范围与包身份

| 批次 | 已有结果 | 证据与边界 |
| --- | --- | --- |
| v149，`941567d1` | config 6、native NCM、wired transport attached、主副屏首帧；4 次主动物理拔插均恢复。 | Harness seq 945／974 为用户操作确认，952／956／985／989 为原工具日志。首轮 4.258 秒起自重枚举后的 attach，不能与后三轮重枚举前 attach→主首帧的 14.578／3.784／4.601 秒混作同口径统计。[#98 历史记录](https://github.com/jianyun8023/diplay-ecarx/issues/98#issuecomment-6853025697)保留原口径。APK SHA-256：`39aadb1e177e387b9155e2e0ca9533e08081d7904e59b9a240c1146acbf2ec6c`。 |
| #104 修复，rebase 前同逻辑包 | 3 轮 force-stop／重启，均 `attempt=1 action=reuse-descriptors`，记录首帧 35／36／31 秒，无需拔插。 | 本地 `artifacts/usb-memfix-20261113/restart-cycles.log` 及上述现场记录。APK SHA-256：`64d68590b377bd8f3c3cd4226a149dfcf1d06a1dfd004ac26abc189071b6e754`；不是归档的最终 rebased APK。 |
| #104 rebase 及回归测试后的包 | rebased 包启动首帧 33 秒；最终包脚本记录 37 秒，均 reuse-descriptors；新增 2 项会话记忆回归。 | [rebase 记录](https://github.com/jianyun8023/diplay-ecarx/issues/98#issuecomment-6853172149)、[最终包身份与测试](https://github.com/jianyun8023/diplay-ecarx/issues/98#issuecomment-6853468644)；最终包原工具安装与起播输出已从 Harness seq 2118 找回。最终 APK SHA-256：`df7137a36c0f354016f706d4799400ff0098d1cf5b9094a2ce8d891ea7185611`。 |
| 21:08 有线、21:17 无线切换 | 无线→有线脚本首帧 17 秒，native NCM 与主副屏首帧；随后按用户要求切回无线成功。 | Harness seq 2419／2543／2610 找回原输出及操作指令。不扩展为取消授权或多轮组合通过。 |
| 21:11:25–21:14:01 有线采样 | 6 点、156 秒，PID 一致；各点 `host_connected=true`、tun0 存在，收发字节增长。 | 本地 `artifacts/perf-soak-20261113/soak.csv:2–7`、`soak.log`、`run-soak.sh`。属于同晚 v149 usb-memfix 现场记录；采样文件没有单独写入 APK hash。计划 10 分钟未完成。 |

本地路径均相对仓库外的工作区根目录。原日志、APK、设备标识与认证资产不随本文发布。

## 已测启动分段

按 `restart-cycles.log` 的同设备事件时间复算，三轮成功重启均主要等待在 MFi 成功之后、AirPlay active 之前：

| 轮次 | config ready→MFi 成功 | MFi 成功→AirPlay active | AirPlay active→首帧 |
| --- | ---: | ---: | ---: |
| 1（原日志 4–7 行） | 0.731 s | 31.914 s | 0.496 s |
| 2（11–14 行） | 0.697 s | 33.557 s | 0.524 s |
| 3（18–21 行） | 0.699 s | 28.437 s | 0.445 s |

这定位了耗时阶段，尚未定位具体阻塞点。若日后另行开展启动优化，可从认证后控制消息、NCM／网络就绪、RTSP／RECORD 时间线继续；本轮不展开。脚本的 `first_frame_s` 起点与物理 attach 不同，不能与 4 次插线耗时混作同一组统计。

旧故障原日志 `artifacts/dev-usb-20261113-origin-dev-941567d1/usb-remount-failure-2010.log` 另记录控制通道 READ FAILED（62 行）、失败后仍按上一会话记忆请求 transition（82 行）、等待 10000 ms（86 行）及配置失效（229 行）。#104 修复失败后的重试决策；最初 READ FAILED 的原因仍未确认，三轮正常重启不等于再次触发原异常后通过。

后段采样同样记录了认证后的等待：最终包 20:51 起播的 MFi→active 为 **34.194 秒**，active→主首帧为 **0.592 秒**；21:08 手动切有线分别为 **11.809 秒／0.444 秒**。两者启动场景不同，不能据此计算优化收益。

## 断开与恢复的操作背景

20:57:59 的 NCM `Protocol error(71)`／`No such device(19)`、USBMUX timeout 和 AirPlay ended 由 Harness seq 2204 确认；**用户在本次复核中确认该次为主动断开**，不计自然掉线或应用崩溃。随后同 PID 恢复无线（seq 2355），有线会话结束→无线首帧为 29.126 秒。teardown begin 与 wireless start 间的 20.773 秒缺少资源完成记录，不能直接称为清理耗时。

四轮物理拔插和 21:17 切无线都有用户操作记录；Harness 于 21:25:38 因模型限流结束，也不是设备断连。已采集窗口未确认自然掉线，但筛选输出与时间空档不能证明通宵连续稳定。11 月 14 日重新只读检查时，小米 8 已在进行另一组无线 A/B 测试，应用轮转日志均为当天，不能作为昨晚同一条 USB 会话延续的证据。

## 短时采样能说明什么

- tun0 RX `72,861,033 → 126,847,147` 字节，TX `1,082,477 → 2,016,398` 字节；156 秒差分约 **2.77／0.048 Mbps**。这是接口流量，不是纯视频码率。
- PSS 为 `142,907–167,059 KiB`，有波动；没有长时、FD／线程或多轮恢复数据，不能据此判定无泄漏。CSV 的 RSS 列实际为 `TOTAL`，不能使用。
- 六点电池温度均为 41°C，电量 20%→18%。`dumpsys cpuinfo` 摘录均记应用 60%，但未保存每点 CPU 统计窗口，不能当作六次独立区间测量。
- 所选 AudioFlinger 行的 underrun／overrun 为 `32／814`，采样内未增长。采集脚本取第一个匹配行，未绑定应用 track；不能替代应用音频欠载或听感验收。
- `soak.log` 未保存结尾错误计数，但已从 Harness seq 2523／2526 找回 21:14:33 的查询：按 PID、时间下限 21:11:25 筛选后，Video recovery／rebuffer／NCM failed／transport error／AirPlay ended 等均为 0。它是当时的筛选查询，未保存完整连续原日志，不覆盖全部可能错误。停止后的独立 battery 快照为 17%／42°C，和 CSV 终点 18%／41°C 分开记录。`gfxinfo` 是 UI 统计，不是 CarPlay 视频显示帧率。
- 先后采集的无线／有线 CPU、温度和图像负载不同，不能从快照差值计算 USB 开销或 Rust 性能收益。11 月 14 日 `cap-v150-20261114-session1` 的实车性能批次走无线，也不补足 USB 对照。

## 结论适用范围

本批覆盖小米 8／ARM64 的指定版本和场景。目标 ECARX／API 28 新组合、故障注入、权限撤销、长时资源趋势及严格同负载性能对照未在本批完成；保留这一边界，不追认为全面通过，也不自动安排后续测试。

历史实现线索保留：USBMUX 已复用 `UsbRequest`／buffer；CarKit 控制通道仍有 256 字节分块与诊断。它们不作为当前视频卡顿的根因或本轮整改项。

其他领域的待验项见[当前验收矩阵](../RUST_CORE_REMAINING.zh-CN.md)。本批配置失败交错的 ARM64 green 仍等小米 8 释放；USB 已收尾，不并入该局部修复 PR 的剩余工作。
