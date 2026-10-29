# 领克 01（2021 款）dev 验证范围

当前 `dev` 已合入 `release` 的上游 v0.2.22、蓝牙媒体让位、ECARX MediaCenter、
导航/HUD 生命周期、HiCar 控制及统一热点恢复。`release` 收纳已确认功能，`dev` 继续承载待实车验证的改动。
早期 `clean/lynk-01-verified-features` 和 `debug/lynk-01-pending-validation` 已不作为当前分支名使用。

## 最新反馈与待验收项

- 用户曾确认媒体 service 控制、导航/HUD 正常；10 月 30 日新增导航结束后再次启动偶发无响应的反馈，见下文。历史依据见[已确认功能说明](LYNK_01_VERIFIED_BRANCH.md)。
- 用户确认原有 Siri 采集路径正常，当前重点是语音键触发。只保留原采集路径，通信采集实验及本地录音自检已移除。
- ECARX 连接期间语音键短按接管、重复触发抑制、后台保持与断连释放仍需实车验证。
- USB 最新测试仍有问题；早期特定场景通过不能扩大为当前完整 USB 验收。
- 热点失败后的 Wi-Fi 状态恢复、重试一次及自动/手动共用流程，需要覆盖 Wi-Fi 初始 ON/OFF 和取消、权限、超时情况。
- iPhone 蓝牙关开后的自动重连仍需验证；车机蓝牙恢复检查不能证明手机端行为已解决。

## 2026-10-30 导航再次启动调查

基线：`origin/dev` 的 `88f39fa035e5b7fcd701aaad18762507bde4aa21`。
关联：[Issue #5](https://github.com/jianyun8023/diplay-ecarx/issues/5)。
用户反馈为第一次导航正常，结束再开偶发无响应；退出再进 CarPlay 后恢复，之后可能连续正常。
这不是多车道箭头问题。导航进行中必须保留整张导航卡、实时距离和转向；结束、到达、断连仍须清理。
停用原厂导航已实测丢卡，不再作为解决方案；不修改原厂或第三方 APK，不依赖车机 ADB。

本地证据位于工作区父目录的 `analysis/test-evidence-20261030/`，未上传原始日志。
报告 6/7/8 都来自 `0.2-car-test-20261030-dev-88f39fa`，含跨天日志和重复片段；以下按事件而非报告份数计数：

- 报告 8 的 06:56:30 开始、35 结束、38 再开始、50 结束，与报告 7 是同一组事件。
  第二轮仍收到 `0x5202` 和 `0x5201`，并记录 `first=true aggregate=ok`，不能归因为没收到输入。
  这些结果不证明仪表绘制成功，缺少与用户失败画面一一对应的时间标记。
- 07:00:40.528 最后一条 route 更新后，应用继续保活发布 90m；07:01:11.413 因
  `route-stale routeAgeMs=30872` 清卡，07:01:18.074 又启动，随后收到 NoRoute 再结束。
  这证明当前 30 秒过期路径发生过，不能证明它就是用户描述的第二轮失败。

已确认的代码缺口：`EcarxHudBridge.endLocked` 以前只调用 Stop、清字段并释放面板，没有发送 idle 聚合状态。
本地 `analysis/ecarx-app-dump-20261028/vehicle/sources/com/ecarx/xui/adaptapi/diminteraction/NaviInteraction.java`
中，`notifyTurnByTurnStarted` 为空实现，`notifyTurnByTurnStopped` 仅清车道/出口；
`updateNavigationInfo` 根据导航状态、静音状态或 resend 标记决定是否发送 ongoing navigation。
因此只调用 Stop 会留下 SDK 的 active 缓存，下轮 active 可能被去重。
原厂 `oem-navigation/sources/ecarx/naviservice/manager/NaviDashboardManager.java` 的
`stopNavi` 也会先 `refreshDim(NavigationInfoToDim(..., null, ...))` 再 Stop，空引导字段为零。
dump 只证明该实现的行为，不能保证实车当前加载版本完全相同。

本次修复在共有的 `endLocked` 中先发布 `status=0`、空路名和零距离/ETA，再调用 Stop，
让正常结束、到达、断连、空列表超时和 route 过期都完成 active→idle 转换。
首次有效引导仍发布 active，再通知 Start；报告新增 `DIM lifecycle start/stop`，分别记录聚合状态和通知的调用结果。
若平台拒绝 idle 更新，报告会显示 `aggregate=failed`；本次没有增加重试或声称对端已确认。

没有调整 30 秒或空列表 3 秒期限，也没有忽略 NoRoute、伪造距离或增加保活频率。
静止时超过 30 秒是否应保持导航仍需协议/现场证据；现有数据不足以改变期限。
Issue #5 的原厂 `<=0 → 1m` 写入仍只是争用线索，本次状态修复不代表解决距离闪烁。
三份报告保留的 `DIM input/publish` 行中未检出精确的 `next=1`；这些日志不观测原厂写入，不能排除争用。

回归测试使用现有 DIM fixture，补入 dump 所示的 active 去重行为：
两项测试先在旧实现失败（结束后仍 active），再验证相同路线经过 NoRoute、Arrived、断连后均可重启，
旧 maneuver 不跨真正结束复用，进行中保活不发送 idle，29 秒保留、30 秒清理，新部分更新能恢复，空列表超时能结束。
另覆盖 Start/Stop 拒绝和 idle 拒绝的诊断，不把方法返回成功当作仪表渲染确认。

本地验证：`AGENTS.md` 要求的 shared/common/home 单元测试、mobile/home/maphost lint 和 Debug 构建
全部通过（7 分 3 秒）；共 2,822 项测试，2,821 通过、1 跳过、0 失败，其中导航 flow 的 14 项全部通过。
使用工作区已有 Android SDK、离线 Gradle/Robolectric 缓存及独立测试报告目录；`git diff --check` 通过。
未生成新的平台签名实车安装包，未进行实车复测。

无需 ADB 的实车验收：

1. 保持原厂导航启用，沿用同一组设置和手机地图；连续启动、手动结束、再启动同一路线至少 5 轮，
   逐轮记录时刻并确认导航卡、箭头、路名、距离都出现，距离随真实进度变化。
2. 分别覆盖到达结束和 CarPlay 断连后重连；结束后不应留下旧指令，下一轮应显示新路线。
3. 停车等待超过 30 秒，记录手机是否仍导航、仪表是否清卡及时间；出现无更新时对照 `route-stale`，
   不把本次保留的超时行为当作已修复项。恢复新 route 输入后应再次显示。
4. 失败当下先记时间、导出应用诊断报告，再退出重进做对照。检查输入→首次发布→lifecycle 调用结果；
   若均成功而仪表空白，仍需研究平台卡片状态/其它写入方，不能宣称本修复已经实车解决。

## 保留的排障证据

常规报告保留 USB 授权和设备、Siri 按键、蓝牙恢复/重试、热点动作、媒体会话、
实际采集源/电平/失败与麦克风释放结果。移除独立媒体探测面板、其他录音客户端枚举和厂商参数成功日志。
不采集原始录音，不以回调接受、请求成功或热点 ON 推断完整链路成功。

## 验证方式

完整检查遵循 `AGENTS.md`：shared/common/home 单测、三个应用 lint 与 Debug 构建。
UI 截图采用当前车机的 1920×1080 横屏及正常字体。
实车操作顺序和记录项目见[车机回归清单](CAR_TEST_20261028.md)。

source-only Debug 包没有认证身份时只能检查安装、设置界面及独立的系统热点/OTG 枚举，
不能用于证明本应用已完成 CarPlay 握手。端到端验证需满足既有认证前置条件。
普通 Android 手机不能替代 ECARX 方控、DIM、麦克风硬件路由或车机蓝牙时序的验收。
