# CarPlay 期间仅停用原车蓝牙媒体

## 旧方案为什么没成功

提交 `4646e739` 的说明记录：旧版 `getProfileProxy` 等不到回调，随后用
ServiceManager 查找对应 Binder 也没有找到，因此根本没有执行到
`setConnectionPolicy(FORBIDDEN)`。本次核对了提交历史和代码；没有找到当时的完整实车原始日志，
不能把历史提交说明当成这次新采集的日志。

旧代码还请求了 `A2DP=2` 和 `HEADSET=1`，这是手机端的角色。
车机接收手机媒体对应 **`A2DP_SINK=11`**，电话客户端对应 `HEADSET_CLIENT=16`。
因此旧版 1/2 的获取失败，并不能证明车机的 11 不可用。AOSP 的 Sink 代理通过
`Context.bindServiceAsUser` 获取，通常不是 ServiceManager 的全局服务；旧 Binder 兜底也不能证明
车机没有蓝牙媒体服务。实际 ROM 是否允许本应用访问 Sink，仍要通过实车验证。

参考：

- [AOSP Android 9 BluetoothProfile 角色定义](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-9.0.0_r1/core/java/android/bluetooth/BluetoothProfile.java)
- [AOSP Android 9 BluetoothA2dpSink](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-9.0.0_r1/core/java/android/bluetooth/BluetoothA2dpSink.java)
- [AOSP 新版 A2dpSinkService](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/a2dpsink/A2dpSinkService.java)
- [DiPlay 社区案例 #207](https://github.com/shihabal3amri/DiPlay/issues/207)：原车取消 iPhone“媒体音频”后改善播放暂停，不能代替本车型验证。

## 本次实现

设置 → 高级 → 视频和音频 → **CarPlay 期间停用蓝牙媒体（实验性）**，默认关闭，保存后下次连接生效。
先关闭“通过车辆蓝牙播放 CarPlay 音频”，否则两个选项的目的冲突。
开启新选项会关闭旧的“关闭整个蓝牙”选项，但不立即改变正在运行的会话。

CarPlay 报告会话 active 后，只查找已配对列表中与当前所选手机地址相同的设备；
不扫描全部 iPhone，也不操作其他配对设备。随后请求 Sink profile 11：

1. 优先使用 `getConnectionPolicy/setConnectionPolicy`，旧版使用 `getPriority/setPriority`。
2. 读取原策略和连接状态。只接受可确切恢复的 `0/100`，连接正在切换时不操作。
   Android 9 setter 不能写回 `1000`，所以不会把它擅自降为 `100`。
3. 用户原本已关闭媒体时保持原样。其他情况先同步落盘恢复记录，再设置媒体策略为 `0`。
   回读确认 `0` 后，仅在媒体仍连接时发出 disconnect 请求。
4. 会话结束或控制器关闭时，恢复原策略，并回读确认后清除记录。
   旧接口下只对原本连接、现在断开的设备显式请求重连；新版 AOSP 的策略 setter 本身也会发起连接。
   请求返回 true 只代表请求被接受，不代表物理连接已完成。
5. 恢复服务不可用、权限不足、写入失败、回读不符或用户关闭蓝牙时，保留记录，启动时重试。
   另一台手机正在使用媒体连接时，也保留记录，避免恢复操作抢走它的连接。

全程不调用 HFP/HEADSET_CLIENT，不调用蓝牙 enable/disable；接口不可用不会退回关闭整个蓝牙。
电话 profile 保留，但电话最终走原车还是 CarPlay，仍由系统、手机路由选择和现有电话音频设置决定。
本次不处理热点导致的蓝牙重连问题。

恢复记录保存在应用私有 `diplay_bt_profile_guard` 中。媒体模式的标记不授予应用重新打开用户手动关闭的蓝牙的权限。
策略恢复是核心验收条件；日志会另外记录连接请求及即时连接状态，不能把 `connectRequested=true`
当成已经连接。服务不可访问时，无应用层通用方法能够保证替代厂商设置页的媒体开关。

## 日志与实车一次覆盖

导出日志搜索 `BT guard media`：

- `profile=11 request rejected` / `callback timed out`：Sink 服务不可获取，不修改媒体策略。
- `original policy/state not safely restorable`：原值未知、不可写回或正在切换，不修改。
- `policyAccepted=true policyNow=0`：禁用请求被接受，且回读确认为 0。
- `disconnectRequested=... state=...`：请求结果与即时状态；`0/1/2/3` 分别是断开/连接中/已连接/断开中。
- `policyRestored=true`：原策略已回读确认。`keeping recovery pending` 表示还要重试，不能判定已恢复。

建议一次采集以下完整流程，记录车型、ROM、iOS 版本、手机路由选择和设置页媒体/电话状态：

| 场景 | 需要确认 |
|---|---|
| 选项关闭的基线 | 音乐是否选原车蓝牙、是否立即暂停 |
| 开启后连接无线 CarPlay | 只有所选 iPhone 的媒体停用；电话和蓝牙总开关保持开启 |
| 音乐 + 导航播报 + Siri | 音频能持续播放，路由正确；不把音频后端改善误判成路由修复 |
| 来电、接听、挂断 | 电话 profile 未断，检查原车/CarPlay 实际路由与通话音质 |
| 正常退出 CarPlay | 原媒体策略恢复，原连接能重新建立 |
| 中断 Wi-Fi、关闭控制器、重启应用 | 恢复记录得到执行，失败时仍可重试 |
| 原本关闭媒体 | 结束后仍关闭，不强行开启 |
| 会话中手动关闭蓝牙 | 应用不自动重新打开；手动开启后重试恢复 |
| 另一台手机已在播放 | 不抢连接，保留恢复记录等待重试 |
| 连续连接/结束 10 次 | 无残留媒体禁用，电话 profile 和总开关不被操作 |

自动化覆盖 Android 9/13 两代策略接口、真实高级设置保存/冲突状态、简中/繁中/英文大字体与 RTL 截图。
实车访问权限、iPhone 最终输出路由和 HFP 通话需要上述实测；主机测试不能确认这些结果。

### 本次主机验证

2026-10-28，基于 `dev` 的 `8620921d` 加本次工作区改动：

- 仓库规定的九个 Gradle 任务全部成功：shared/common/home 单测，mobile/home/maphost lint 与 debug 构建。
- 2350 项通过、0 项失败，1 项因主机允许通配和指定地址 socket 监听重叠而跳过，与本次改动无关。
- 新增蓝牙专项 58 项（29 个场景 × Android 9/13），设置和截图 6 项，均通过。
- 公共源码树凭据检查与 `git diff --check` 通过。
- 截图：`common/build/reports/bluetooth-media-yield-ui/`；验证汇总：`build/bluetooth-media-yield/verification.json`。
- 未进行实车验证。

### BOOX NoteX2 实机探测（Android 11 / API 30）

2026-10-28 本地构建并覆盖安装 `com.shihab.diplay.hudtest`，保留原有应用数据。
独立测试 APK 使用 `BluetoothMediaProbeInstrumentation`，在目标应用 UID 下执行；
没有借用 shell 身份、修改系统权限、启用隐藏服务或改变非 SDK 限制。

| 项目 | 实测结果 |
|---|---|
| APK 签名 | Android Debug，不能据此推定拥有车机平台签名权限 |
| 应用 `BLUETOOTH_PRIVILEGED` | 未授予 |
| Sink profile 11 | 获取代理回调超时，未取得可用代理 |
| ROM 的 Sink 支持配置 | `profile_supported_a2dp_sink=true`，且包中存在服务声明 |
| Sink 服务运行状态 | ActivityManager 中 `app=null`、`binder=null`，未得到运行中的服务 Binder |
| Source profile 2（只读对照） | 代理可获取，连接设备数为 0，策略 getter/setter 方法可找到 |
| Source 策略读取 | `SecurityException: Need BLUETOOTH PRIVILEGED permission` |
| 生产媒体让位/恢复流程 | 选择已配对 iPhone 执行，`mediaApplied=false`、`recoveryPending=false` |
| 状态恢复检查 | 蓝牙始终 ON，原开关设置恢复，恢复记录为空，未调用电话 profile |

结论：**这台设备以当前普通应用权限无法完成媒体策略控制**。没有执行成功的策略写入，
不能把探针正常退出理解为功能成功。Sink 服务有声明和配置，也不等于应用能够取得它的 Binder。
Source 的权限拒绝是独立观测，不能据此断言 Sink 超时一定也是同一个权限原因。
本结果不能代替 ECARX Android 9 车机验证；车机需要单独核对实际服务、接口版本及签名/授权。

本地独立 APK：`build/bluetooth-media-yield/diplay-dev-bluetooth-media-debug.apk`。
测试 APK：`build/bluetooth-media-yield/diplay-bluetooth-device-probe.apk`。
实机原始输出：`build/bluetooth-media-yield/device-note-x2/{inspect,cycle}.txt`。

重复只读检查（先安装应用 APK 和测试 APK）：

```sh
adb shell am instrument -w -e action inspect \
  com.shihab.diplay.hudtest.test/com.shilapi.xcertplay.BluetoothMediaProbeInstrumentation
```

验证生产 Sink 让位/恢复流程需显式指定已配对 iPhone 的地址；探针会还原原设置，
有旧恢复记录时拒绝覆盖，不会对 Source profile 写策略：

```sh
adb shell am instrument -w -e action cycle -e peerAddress '<已配对 iPhone 地址>' \
  com.shihab.diplay.hudtest.test/com.shilapi.xcertplay.BluetoothMediaProbeInstrumentation
```
