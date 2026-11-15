# 开机启动优先级与系统应用边界（Android 9 / 753）

维护日期：2026-11-15。系统应用与早期启动的证据来自 2026-11-06；当前启动行为见下文及[平台笔记](ECARX_753_PLATFORM_NOTES.zh-CN.md)。旧实验分支不是当前工作入口。

## 系统应用边界与历史依据（2026-11-06）

1. **平台签名不等于系统应用。** 本车 DiPlay 的 APK 安装在 `/data/app/com.shihab.diplay-.../base.apk`，`dumpsys package` 为 `userId=10125`、`flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]`，没有 `SYSTEM`、`UPDATED_SYSTEM_APP` 或 `PERSISTENT`。平台签名只保证 signature 级权限可授予，不改变安装位置和进程优先级。
2. **`android:persistent="true"` 只在系统分区应用上生效。** Android 9 `PackageManagerService` 对非系统应用会清除 `ApplicationInfo.FLAG_PERSISTENT`（`scanPackageLI` 的 non-system 分支）。因此仅把 APK 装进 `/data/app` 再声明 persistent 不会生效；必须由固件预置到 `/system/priv-app`（或等价系统分区）后重启扫描。
3. **`sharedUserId="android.uid.system"` 也不是系统应用。** 本车 `com.dudu.autoui` 与 `com.hyphp.hywebviewlibdcy` 都是 `/data/app` 应用，Manifest 声明了 `android:sharedUserId="android.uid.system"`，进程确实是 `system/1000`，但 `dumpsys package` 的 `flags` 仍没有 `SYSTEM`。它只改变 UID，不会让 `persistent` 生效；修改 DiPlay 的 sharedUserId 还会因 UID 变化导致覆盖安装失败、需要卸载重装并丢数据。除非确实需要 UID 1000 的厂商接口，不建议走这条路。
4. **数据应用能提前拿到的是 `LOCKED_BOOT_COMPLETED`。** `directBootAware="true"` 的 receiver 可在用户解锁前收到该广播，比 `BOOT_COMPLETED` 早。台架 Android 9 实测：`LOCKED_BOOT_COMPLETED` 9.289s、`BOOT_COMPLETED` 11.565s（同一启动周期，早 2.276s）。本车真实冷启动的 `BOOT_COMPLETED` 在 52.076s、桌面约 26.7s 可见（issue #48），因此该路径只能缩短应用进程首次创建的时间，不能保证把 Activity 带到前台。
5. **网络环境不能作为拉起 Activity 的前置条件。** 手动车机热点模式下，DiPlay 需要先启动才能打开/确认车机热点；iPhone 的 Wi-Fi 凭据又要经蓝牙 iAP2 的 `0x5703 accessory-wifi-configuration` 下发。若先等网络再启动会形成死锁。现有 `LaunchAutoConnectPolicy` 已按模式处理：现有 Wi-Fi 立即进入控制器等待网络；手动热点等待系统热点 ON 后立即 dispatch；自动热点走异步 prepare。启动优先级只应优化“进程/Activity 何时被拉起”，不应改变这条连接顺序。

## 当前启动路径

- `LOCKED_BOOT_COMPLETED` 先读取 device-protected 的自启镜像并预热进程。用户未解锁时不读 CE 配置、不创建窗口；解锁后可走已配置的无线直达入口，不必只等 `BOOT_COMPLETED`。
- `BOOT_COMPLETED` 承担恢复、配置和拉起回退；原厂导航恢复仅在导航输出空闲时执行，避免后到的广播打断已经开始的导航。
- **2026-11-15 工作区待合入改动**：ECARX 的解锁与普通开机入口共用 `EAS_LAUNCH_BAT_2ND` 等待，超时 15 秒放行；等待期间预读配置、加载核心、准备本地身份与认证，不提前创建窗口或控制器。相关原始改动在本轮文档整理前已存在，不能据此认定公开包已包含。
- 对应 `LAUNCHER_SECOND_BATCH`、`LAUNCHER_SECOND_BATCH_MISSED` 和 `BOOT_PREPARE_*` 的时序见[平台笔记](ECARX_753_PLATFORM_NOTES.zh-CN.md)；耗时口径见[启动采样](CARPLAY_STARTUP_SAMPLING.zh-CN.md)。超时放行不证明原厂 Home 服务已经就绪，模拟器广播验证不替代实车冷启动。

## 最初实现记录（2026-11-06）

以下是 `feat/startup-system-priority` 当时的实现快照，保留其验证条件；解锁后入口和桌面等待以当前启动路径为准。

- `BootReceiver` 增加 `LOCKED_BOOT_COMPLETED` + `directBootAware=true` + `intent-filter android:priority="1000"`。锁定启动阶段只读取 device-protected 的 `auto_start_on_boot` 镜像并提前创建进程，不读写 CE 存储、不启动 Activity；正常 `BOOT_COMPLETED` 仍负责实际拉起。
- `AirPlayPersistence.saveAutoStartOnBoot()` 同步写入 `createDeviceProtectedStorageContext()` 的镜像；正常启动时从 CE 设置回填镜像，兼容升级。
- `StartupDiagnosticSnapshot` 改存 device-protected，报告增加 `lockedBootReceivedAtEpochMs`，便于实车对比两个广播时刻。
- `mobile` 的 `<application>` 增加 `android:persistent="true"`。普通 `/data/app` 安装会被 Android 清除该标志（已用台架安装验证，`flags` 无 `PERSISTENT`）；只有预置到 `/system/priv-app` 时才生效。persistent 包不可覆盖升级，系统应用升级需走固件/系统分区更新，或卸载重装。

## 预置为系统应用的条件

- APK 使用本车平台证书签名（已有 `auth-assets/platform.p12`，SHA-256 `C8:A2:E9:BC:…:2A:B8`）。
- 放入 `/system/priv-app/DiPlay/DiPlay.apk`，权限 `644`，owner `root:root`，SELinux label `u:object_r:system_file:s0`，重启后由 PackageManager 扫描。
- 只在预置版本上使用 `persistent` 语义；普通车测包仍按 `/data/app` 升级，避免不可更新。

## 实车问题修复（2026-11-06）

- 开机 90 秒内，普通数据应用的无线连接工作线程遇到系统蓝牙 `STATE_OFF` 时，可以**只观察、不调用 enable()**，最多等待 8 秒至 `STATE_ON`；超时仍报原有错误。用户在正常运行时关闭蓝牙不会被 DiPlay 自动打开。取消连接会中止等待。
- 启动诊断增加 `lockedBootPid`、`receivedPid`、两个 `elapsedMs` 时间点及 `sameProcessAtBoot`，用于确认提前广播创建的进程是否被正常广播复用。
- 新的 `LOCKED_BOOT_COMPLETED` 会清理上一轮启动的诊断结果；仅收到普通广播时，使用开机基准时间过滤上一轮的锁定广播记录。
