# USB / Wi-Fi 双链路切换问题分析与方案（分支 dev）

分析对象：`dev`（`2a99c1cb`，已完成 `origin/dev` 快进）。
现象：Wi-Fi 已连上 CarPlay 时插 USB，USB 起不来；反过来，USB 会话在跑时切 Wi-Fi 也不行。
本文只做事实核对 + 方案设计，未改动代码；所有结论标注了代码位置，未标注的属于待实车验证的推断。

---

## 1. 结论摘要

这不是一个单点 bug，是三层问题叠在一起：

1. **iOS 侧只有"一个 CarPlay 会话"**。旧会话没有被 iPhone 判定结束时，它不会接受第二条链路；而且项目自己的注释已经记录过这个现象：iPhone 仍持有旧会话时，会持续把 USB 侧 NCM 链路的 SYN-ACK 丢掉 15–26 s（`shared/.../orchestration/WiredStartAgain.kt:4-16`，对应 #889 / #610）。
2. **本项目是"单栈 + 整栈重启"模型**：`CarPlayController` 的 transport 在构造时固定，同一时刻只允许一个 controller 存在（`CarPlayHostActivity.kt:5906` 的 `controller != null` 门控）。切换 = 关掉旧栈 + 重建新栈，没有任何"等旧链路真正消失"的编排。
3. **切换的可观测与回退缺失**：USB 插入的处理分散在 `onCreate` 和 `onNewIntent` 两处，其中 `onCreate` 这条例径会被后台会话接管吃掉；全仓没有任何 `ACTION_USB_DEVICE_DETACHED` 监听，拔线后不会回退无线；切换时还会把 `wireless` 偏好永久写死为 `false`。

可以在本项目内修掉的是第 2、3 层，共 4 个确定缺陷（D1–D4）。第 1 层是 iOS 语义，只能靠"先断后建 + 冷却窗口 + 定向重试"绕过去，不能靠改 Android 代码消灭。

---

## 2. 现状：两条链路各自怎么跑

### 2.1 会话模型

- `CarPlayRuntimeConfig.transport` 在 controller 构造期固定，`start()` 里按 transport 分流（`CarPlayController.kt:506-532`）。
- 同一时刻只允许一个 controller：`maybeStartCarPlay()` 在 `controller != null` 时直接返回（`CarPlayHostActivity.kt:5877-5910`）。
- 跨 Activity 复用一个会话，靠 `CarPlayBackgroundSession` + `adoptBackgroundSession()`（`CarPlayHostActivity.kt:5408`）。

结论：**不存在"双栈并存"，切换必须串行**。这一点本身是合理的（iOS 也不允许双会话），问题出在串行的编排上。

### 2.2 有线链路

MFi 协处理器 → USB 发现/授权 → iAP2 认证 → 请求 iPhone 切换到 CarPlay USB 配置（重新枚举）→ NCM + VPN → 发送 `CarPlayStartSession (0x4301)` → AirPlay TCP。

- 启动看门狗 30 s，可延长到 45 s（`WiredStartWatchdog.kt:83,86`）。
- 链路晚开时补发一次 `0x4301`（`Iap2WiredControlClient.kt:152`、`WiredStartAgain.kt`）。
- 关键：`WiredStartAgain` 的注释明确写了"iPhone 仍持有上一个未正常关闭的 CarPlay 会话时，NCM 链路会被 NAK 15–26 s"。这就是**无线会话残留直接打崩有线启动**的既有证据。

### 2.3 无线链路

MFi → BT RFCOMM 引导 → 下发 `CarPlayAvailability (0x4300)` + Wi-Fi 配置 → iPhone 入网 → AirPlay 监听等 TCP。

- 消息定义见 `Iap2IdentificationClient.kt:447-448`。
- 无线模式**完全没有**注册 USB attach/权限接收器：`CarPlayController.kt:526` 只在 `transport == WIRED` 时注册。

### 2.4 切换入口只有两个

| 入口 | 行为 |
| --- | --- |
| DiPlay 首页 Connect（有线/无线按钮） | `connectWithSource()` 先 `saveWirelessEnabled()`，有会话则 `CarPlayBackgroundSession.stop{}` 后重新打开投影（`DiPlayActivity.kt:4508-4546`、`4538`） |
| CarPlay 界面内设置里的 Wireless 开关 | 只改内存值 + `requestStartupPrerequisites()`，退出设置时如需重连才 `restartCarPlay()`（`CarPlayHostActivity.kt:2818-2836`、`6204-6208`） |

两个入口都是"全量重启"，没有"切换中"的状态，也没有失败后的定向回退。

---

## 3. 根因（按"检测 → 拆栈 → 建栈 → 回退"四段拆）

### D1 插入事件在"后台会话接管"路径被吞掉（确定缺陷，无线→有线的主因之一）

USB 插入有两个处理点：

- `onCreate`：只做 `saveWirelessEnabled(false)`（`CarPlayHostActivity.kt:893-895`）。
- `onNewIntent`：真正做切换（`CarPlayHostActivity.kt:1187-1200`）。

当插入事件创建了一个**新实例**（而不是投递到已存在的实例）时，`onCreate` 这条路会 `adoptBackgroundSession()`（`CarPlayHostActivity.kt:935`、`5883`）把正在跑的**无线** controller 接管过来，然后：

- 偏好已被写成 `wireless=false`，但屏上跑的是无线会话，两者不一致；
- `controller != null` 让 `maybeStartCarPlay()` 再也不启动有线（`5906`）；
- `onNewIntent` 不会被调用，所以"switching to USB"根本不会执行。

是否走这条路取决于 `CarPlayScreen.forwarded()`：只有"当前有一个打开的 CarPlay 屏"或"偏好是另一种声明"时才会转发（`CarPlayScreen.kt:39-53`）。**会话在后台、屏幕已销毁的场景正好不满足**——这恰好是"无线连着，人在首页，插 USB"。

### D2 没有拔线监听，也没有回退（确定缺陷，有线→无线的主因）

全仓检索 `ACTION_USB_DEVICE_DETACHED` 为 0 处。有线会话在拔线后只会走通用失败重连，用同一份 `wirelessEnabled=false` 偏好再起有线，然后卡在"等待 iPhone USB"。用户看到的就是"USB 拔了，无线也上不来"。

### D3 切换只关本地栈，不等 iPhone 侧会话结束（确定缺陷，双向都受影响）

`restartCarPlay()` 做的是：递增 generation → `handshakeResetInProgress=true` → `oldController.close()` → 等 `awaitClosed(4000ms)` → `maybeStartCarPlay()`（`CarPlayHostActivity.kt:6005-6055`、`6842-6844`）。

- 没有任何"向 iPhone 表明会话结束"的步骤：iAP2 目录里没有会话停止消息，纯靠 Socket/链路消失让 iOS 自己超时判定。
- 没有冷却窗口：拆完立刻建新栈，正好撞进 `WiredStartAgain` 注释里那 15–26 s 的 NAK 窗口，或者让无线引导发出的 `0x4300` 落在一个仍在跑有线会话的 iPhone 上。
- 通用重试是 2 s 起步、指数退避到 30 s（`6842`），与 iPhone 的会话清理时间不同量级，表现为"一直转圈"而不是"切换失败"。

### D4 偏好是单向写死的（确定缺陷，影响回退）

`onNewIntent` 里 `saveWirelessEnabled(this, false)`（`1193`）是**持久化**的。一旦这次切换失败（iOS 拒绝、USB 权限被拒、VPN 授权被取消），偏好仍然是 USB：

- 下次启动还是走有线；
- 用户"拔了线想回无线"，需要手动回设置重新打开 Wireless，否则永远起不来。

### D5 无线链路可能"仍然在场"（需要实车确认）

iOS 判定无线会话结束，前提是它感知到链路消失。三种无线后端里：

- **Wi-Fi Direct**：`closeWirelessStack` 会 `removeGroup`，iPhone 能较快感知；
- **车机热点 / 同网**：热点是车机系统能力，DiPlay 不会关它，iPhone 可能仍然关联在车机 Wi-Fi 上，iOS 侧会话"半挂"更久，有线请求被拒的窗口被拉长。

这不是代码 bug，但决定了"冷却窗口"应该设多长，以及要不要在切换时提示用户。

### D6 多级超时叠加，缺少"正在切换"的可见状态

无线：热点 60 s、P2P 20 s、首包 45 s、控制环 5 min（`CarPlayController.kt:3336-3348`）；有线：启动 30/45 s；关闭 4 s。切换过程中用户只看到上一轮的状态文案，没有任何"正在从 Wi-Fi 切到 USB"的提示，体验上等同于"卡死/没反应"。

---

## 4. 方案对比

| 方案 | 做法 | 改动面 | 能否解决双向 | 风险 |
| --- | --- | --- | --- | --- |
| A 最小修补 | 只补 D1/D2/D4：接管路径也走切换、加拔线监听、偏好可回退 | 小（2 个文件） | 能显著改善，但切换仍会偶发 30 s 失败 | 低 |
| **B TransportHandoff 状态机（推荐）** | A + 切换编排：先断后建、冷却窗口、定向重试、可见状态 | 中（新增 1 个协调器 + 2 处接入） | 能 | 中，需要实车调参 |
| C 双栈并存 | 同时保持两条链路，快切 | 大 | 不能 | 高，且违反 iOS 单会话语义，不做 |

方案 C 直接排除：iOS 只允许一个 CarPlay 会话；Android 侧 USB 只能被一个进程 claim；音频焦点、VPN、NCM 桥都会互相踩。

---

## 5. 推荐方案 B：TransportHandoff 状态机

### 5.1 状态与迁移

```
IDLE
 └─(插入 iPhone / 启动)→ BRING_UP(transport) → RUNNING(transport)
RUNNING(WIRELESS) ─USB attached→ 先置 pendingTransport=WIRED
        → TEAR_DOWN(WIRELESS) → QUIET_WAIT(iOS 会话冷却) → BRING_UP(WIRED)
        └ 任何一步失败/超时 → 回退 RUNNING(WIRELESS) 并提示"保持 Wi-Fi"
RUNNING(WIRED) ─USB detached 或用户选无线→ TEAR_DOWN(WIRED) → QUIET_WAIT → BRING_UP(WIRELESS)
        └ 失败 → 停在 WIRED 的等待态并提示"请插回 USB 或用 Wi-Fi"
```

四个必须落到代码里的判据：

1. **插入是"请求"不是"命令"**：进入 `SWITCH_PENDING`，先在 UI 上显示"正在切换到 USB"，再动手，避免无反馈的静默失败。
2. **先断后建**：拆旧栈要包含"让 iPhone 看得见链路消失"的动作（无线：关监听 + `removeGroup`；有线：释放 USB claim + `detach` VPN），并**确认拆完**（`awaitClosed` 现有 4 s，可放宽到 8 s）。
3. **冷却窗口**：拆完后等 `QUIET_MS`（建议初值 8 s，可调；覆盖 `WiredStartAgain` 记录的 15–26 s NAK 窗口的头部，并配合既有"链路开后补发 0x4301"机制）。冷却期内禁止任何重试，避免撞窗口反复失败。
4. **失败必须回退偏好**：切换失败时把 `wirelessEnabled` 恢复成切换前的值，并给出可操作提示，而不是把用户留在"USB 起不来、无线也没了"的状态。

### 5.2 关键实现点（建议命名，按现有代码风格）

| 位置 | 改动 |
| --- | --- |
| 新增 `orchestration/TransportHandoff.kt` | 纯 Kotlin 状态机，只依赖注入的时钟/动作，便于单测（对齐现有 `WiredStartWatchdog` / `WiredStartAgain` 的风格） |
| `CarPlayHostActivity.onCreate` (893) | 不再只写偏好：插入事件统一交给 `requestTransport(WIRED, source=USB_ATTACH)`；**接管后台会话时也要走同一个入口** |
| `CarPlayHostActivity.onNewIntent` (1187) | 同一个入口，与 `onCreate` 合并，消除两条不一致路径 |
| `CarPlayController` (526) | 无线模式也注册 attach/detach 接收器（或由 Activity 层统一监听后转交），让控制器不再"聋" |
| 新增 `UsbAttachmentWatcher` | 同时监听 `ACTION_USB_DEVICE_ATTACHED` 和 `ACTION_USB_DEVICE_DETACHED`，只在 Apple VID 上生效 |
| `restartCarPlay` (6005) | 抽出 `teardownAndWait()`，供状态机复用；把"切换"与"普通重连"分开，切换走冷却窗口 |
| `AirPlayPersistence` | 偏好写入改为"带来源 + 可回退"，失败回滚到切换前的值 |
| 状态文案 | 新增"正在切换到 USB / 正在切换到 Wi-Fi（请拔掉 USB 线）"，并复用现有 `setConnectionStage` |

### 5.3 有线→无线必须提示拔线

iOS 在 USB 线还插着的时候优先有线，会拒绝（或延迟）无线引导。所以 USB→Wi-Fi 的正确交互是：

1. 用户选无线 → 拆有线栈；
2. 若检测到 USB 仍 attached → 文案提示"请拔掉 USB 线"，并停在待切换态（不要立刻发起无线引导去撞墙）；
3. 收到 `DETACHED` → 冷却 → 起无线。

这一步不做，用户会觉得"点了无线没反应"。

---

## 6. 分阶段改动清单

**P0（先把"能切"做出来，改动集中、可单测）**

1. `onCreate` 接管路径也执行切换（D1）——不再出现"偏好已改成 USB 但仍在跑无线"的错位状态。
2. 新增 USB detach 监听 + 有线断链后回退无线（D2）。
3. 切换失败时回滚 `wirelessEnabled`（D4）。
4. 切换过程中给出可见状态，不再静默（D6）。

**P1（把"稳定切"做出来）**

5. 引入 `TransportHandoff` 状态机，切换与普通重连分流（D3）。
6. 冷却窗口 + 定向重试，替换现在的 2 s→30 s 通用退避在切换场景下的行为。
7. 有线→无线的"请拔线"闸门。

**P2（可选）**

8. 无线后端感知（Wi-Fi Direct / 车机热点 / 同网）决定冷却长度与提示语。
9. 切换耗时与失败原因进诊断报告（新增 `lastHandoff` 段：来源、旧 transport、拆栈耗时、冷却时长、新栈首个失败阶段）。

---

## 7. 验证

### 7.1 单元测试（对齐现有 Robolectric + 纯 Kotlin 测试风格）

- `TransportHandoffTest`：注入 attach/detach 事件，断言状态迁移、冷却期内不发请求、失败回滚偏好。
- `CarPlayHostActivity` 接管测试：构造"后台无线会话 + 插入 intent 创建新实例"，断言**最终发起的是有线启动**（当前实现会失败，正好作为回归用例）。
- `UsbAttachmentWatcherTest`：非 Apple VID 忽略、detach 只在有线态触发回退。

### 7.2 实车用例（每条都要看诊断报告，不看"能不能连上"这一句）

| # | 步骤 | 期望 |
| --- | --- | --- |
| 1 | 无线会话中插 USB | 8 s 内出现切换状态，最终有线跑起来 |
| 2 | 无线会话中插 USB 后立即拔掉 | 回到无线，且偏好未停留在 USB |
| 3 | 有线会话中拔线 | 自动切无线（或提示"未启用无线"） |
| 4 | 有线会话中点无线（线还插着） | 提示拔线，拔线后自动切无线 |
| 5 | 有线启动立即失败（模拟拒绝） | 回滚到原始偏好，提示可操作原因 |
| 6 | 连续 3 次插拔 | 不出现 generation 错乱、无残留 Wi-Fi 组 |
| 7 | 三种无线后端各跑 1 次（Wi-Fi Direct / 车机热点 / 同网） | 记录切换耗时分布 |
| 8 | iOS 16 / 17 / 18 各 1 台 | 记录 NAK 窗口差异，校准冷却值 |

### 7.3 诊断报告里看什么

- `Connection decisions` 段：`event=CONNECT_ENTERED / PROJECTION_REQUESTED / NOT_OWNER / MENU_OPEN / HANDSHAKE_RESET` —— 判断切换是否**被守卫挡掉**（D1 的典型表现是完全没有这些行）。
- `wired iPhone discovered / waiting for reenumeration / iap2 tx=0x4301` —— 判断有线走到了哪一步。
- `CarPlay configuration request transferred x of y bytes` —— iOS 拒绝切换 USB 配置（旧会话未清）。
- `first TCP timeout atNs=...` —— 无线引导没人来连（通常是有线仍占着会话，或 iPhone 没入网）。

---

## 8. 需要你确认的信息

要把上面第 1 层（iOS 语义）落到具体参数，需要三条现场信息：

1. 车机型号 + Android 版本 + iPhone 型号/iOS 版本。
2. 无线用的是哪种后端：Wi-Fi Direct / 车机热点 / 同网（决定冷却窗口和"请拔线"提示的必要性）。
3. 一份失败时的诊断报告（`设置 → 诊断 → 保存诊断报告`），重点看 7.3 里的四类行。

有这三条，可以把 P0 的四项直接排成 PR；没有实车也能先把 `TransportHandoff` + 三个回归测试写出来（纯逻辑，不依赖硬件）。
