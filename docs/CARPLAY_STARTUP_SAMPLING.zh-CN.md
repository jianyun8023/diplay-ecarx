# 车机热点与现有 Wi-Fi：启动耗时采样

仅针对 **Built-in car hotspot / MANUAL** 与 **Existing Wi-Fi / Same LAN / EXISTING_WIFI**；不新增 P2P 采样。
记录使用 `SystemClock.elapsedRealtime()`（含休眠的 Android 自启动单调毫秒）；不与 `System.nanoTime()` 的差值相减。

## 导出与采样

- 车机：DiPlay → 设置 → 诊断 → 保存诊断报告，查看 `Wireless startup timeline` 和紧随其后的 `Wireless startup metrics`，保留最近 80 个阶段事件并从中计算各代次指标。每轮结束立即导出，避免后续重连挤掉本轮证据。时间线还写入 `DiPlay-Startup` logcat 标签；不保存 SSID、设备地址、密码、MFi 内容或异常消息。
- 开发台架可执行 `adb logcat -v threadtime -s DiPlay-Startup:I`，但量产运行**不依赖 ADB**。
- 分别测试整车冷启动、ACC 唤醒、应用冷启动。关闭/开启热点自启动、已有热点、已有同 LAN 为不同实验条件；固定设备、iOS、频段和版本，交错至少 10 轮，所有超时/失败均保留。
- `BOOT_BROADCAST` 仅表示 `BOOT_COMPLETED` 已被应用收到，**不等于 ACC 上电时刻**；ACC 真实起点需外部摄像/车辆信号。设备若只休眠唤醒，可能根本没有此广播。
- `VIDEO_FIRST_FRAME` 表示主屏视频解码首次回调；`UI_FIRST_FRAME_CALLBACK` 表示 Host 主线程处理该回调，**均不是物理屏幕首次可操作/可见时刻**。最终应配合外部录像验证画面出现与输入可操作。

## 阶段与判读

1. `LAUNCHER_CREATE` → `AUTO_CONNECT_DISPATCH` → `PROJECTION_REQUEST` → `HOST_CREATE`：自动启动及页面流转。
2. `HOTSPOT_PREPARE_BEGIN/END`、`NETWORK_BACKEND_READY`、`ENDPOINT_READY`：系统热点开关、接口/地址；现有 Wi-Fi 不创建热点。
3. `CONTROLLER_START`、`LOCAL_MFI_READY`、`WIRELESS_RUN_START`、`AIRPLAY_LISTENING`、`BONJOUR_STARTED`：控制器、监听准备。
4. `RFCOMM_CONNECTED`、`IAP2_CSM_READY`、`IPHONE_MFI_ACCEPTED`、`START_SESSION_SENT`：蓝牙与协议。
5. `AIRPLAY_TCP_ACCEPTED`、`AIRPLAY_SESSION_ACTIVE`、`VIDEO_FIRST_FRAME`、`UI_FIRST_FRAME_CALLBACK`：Wi-Fi 建链、会话、视频。
6. `WIRELESS_FAILED` 为失败检查点，由本代第一次判定失败时记录一次，蓝牙 bring-up 失败、handoff 超时和假蓝牙链路三条路径都经过它；结合现有失败类型日志分析，不将恢复超时预算作为正常连接耗时。隧道控制通道在画面仍在输出时关闭只上报状态，不算本代失败，也不记此检查点。

`bootCount` 为系统开机计数，`process` 为进程 PID，`owner` 为进程内控制器标识，`generation` 为同一控制器的重试代次，`attempt` 为蓝牙 bootstrap 尝试序号。指标按 `(bootCount, process, mode, owner, generation)` 分组，避免跨开机、进程重启、控制器或代次拼接。一代内取第一次对应检查点；局部 bootstrap 重试的等待包含在该代耗时内，整栈重启产生另一行。旧版本事件或读不到开机计数的事件只保留原始记录，不生成推测指标。

`outcome` 是所保留记录的观测结果，不是控制器状态：`FAILED` 表示存在失败检查点，`STABLE_FRAME` 表示已记录稳定帧，`FIRST_FRAME` 表示仅有首帧，`INCOMPLETE` 表示两者均未记录。截断、取消或未完成的样本都可能为 `INCOMPLETE`，不能一律算失败；蓝牙 fallback 的首帧也不能冒充隧道稳定帧。缺少起点、终点或时序不合法时耗时为 `unknown`，不记作 0。缺少本代起点时，连 `firstFrameSinceBootMs` 也不计算，防止把截断后残留的重复帧当作最初首帧。

时间线为有限环形记录，不是独立的系统开机监控服务，不自动识别冷启动轮次或计算成功率、p50/p95。

## 冷启动验收与每包记录

三条指标固定如下；单位均为毫秒，不能混用首帧与稳定帧，也不能把连接代次耗时称为整车冷启动耗时。

| 指标 | 报告字段与口径 | 用途 |
| --- | --- | --- |
| 系统启动至首帧 | `firstFrameSinceBootMs`：`VIDEO_FIRST_FRAME.elapsedSinceBootMs`；冷启动轮次取第一次成功连接的首帧 | 衡量系统、桌面、应用、网络和连接总路径；不是点火至实际屏幕显示 |
| 本代连接至首帧 | `runToFirstFrameMs`：本代首次 `VIDEO_FIRST_FRAME - WIRELESS_RUN_START` | 定位连接阶段耗时；不包含先前失败代次 |
| 本代连接至稳定帧 | `runToStableFrameMs`：本代首次 `STABLE_VIDEO_FRAME - WIRELESS_RUN_START` | 观察隧道升级后的稳定画面回调；fallback 无此事件时保留 `unknown` |

每个车测包按固定条件至少做 10 轮，冷启动、ACC 唤醒和应用重启分开统计。每轮保留所有代次及失败原因，不能只留下最终成功的一代。耗时对有对应终点的样本分别排序，使用 nearest-rank：p50/p95 分别取第 `ceil(0.50*N)` / `ceil(0.95*N)` 个值，并标明各指标有效 N；10 轮的 p95 等于最大值，不能据此声称尾延迟已经稳定。

每包车测记录必须填写下表，未车测填“未测”，不能用单测或台架数据替代：

| 版本/提交、测试日期、车辆/iOS、热点条件 | 冷启动总轮数 / 首帧成功 / 稳定帧成功 / 失败 / 证据缺失 | 系统启动至首帧 N、p50/p95 | 本代至首帧 N、p50/p95 | 本代至稳定帧 N、p50/p95 | 失败原因、原始报告与录像 |
| --- | --- | --- | --- | --- | --- |
| 待车测填写 | 未测 | 未测 | 未测 | 未测 | 未测 |

验收先要求证据完整：同条件对照上一车测包，记录上述三项分位数和成功率；出现新失败、成功率下降或耗时回退要逐轮解释并复测。尚无足够冷启动基线，暂不承诺固定秒数或虚设耗时门槛。外部录像另记点火至可见/可操作时刻；自动指标仅用于分段定位。原厂导航还需覆盖“DiPlay 暂停导航后重启且关闭自启”和“解锁已开始 CarPlay 导航，随后收到 BOOT_COMPLETED”两条路径。

## 基线

#41 的 START → 首帧约 3.685s（热点新版中位数）是在热点已开启、手机已入网的台架条件下测得，不可套用到车机上电→首屏。新增时间线用于补齐应用及网络启动阶段，并由实车数据决定下一轮优化。

## 启动调度优化（后续独立 PR）

- 现有 Wi-Fi：已有 `ExistingWifiManager.start()` 在后台等待单个 Wi-Fi Network / IP，因此应用自动重连可立即进入原控制器，不再统一等待 5 秒；仍必须经原网络校验，不能预先声称连接成功。
- 手动管理的车机热点：Android 报告 AP 为 ON 时马上连接，其余状态按 200ms 检查，最多保留原有 5s 等待；`ManualHotspotManager` 继续负责地址和接口的实际验证。OEM 无法读取热点状态时保留原回退行为。
- DiPlay 自己开启车机热点：继续复用已有的异步 prepare → READY → connect 流程，不额外启动第二次热点，也不改默认恢复和超时。
- 有线及 P2P：维持原有调度；不缩短 iAP2/MFi、first-TCP 的失败超时。

对照需要确认：AUTO_CONNECT_DISPATCH 是否提前，NETWORK_BACKEND_READY 至首帧是否无异常恶化；记录重复连接、热点未启动、异常失败和完整冷启动的 p50/p95，不能把节省的固定等待直接当成最终上屏收益。
