# 2026-10-28 Release 功能提取

从 `release@43392d40` 追加提交，保留 Release 历史；没有整体合入 dev 或上游 v0.2.22。
用户确认来源为聊天“评估 DiPlay v0.2.22 合并方案”（01a181ee-efe2-7b92-a7a7-2a13304610d4）及 2026-10-28 最新补充。

| 纳入功能 | 用户实车结论 | 代码来源与必要依赖 |
| --- | --- | --- |
| 蓝牙媒体让位 | “Carplay没有再走蓝牙，看起来是OK的” | `818c5682`；`0b928ec3` 的 guard 内部恢复状态及 `BluetoothAdapterRestore`，保留原策略恢复和日志，不迁入 Controller 的连接改动 |
| ECARX MediaCenter | “媒体service方式控制，这个没有问题”；随后要求收敛到亿咖通 ECARX MediaCenter | `063e7745`/`a2026d56` 的服务权限、transport、engine 和调用入口，加 dev 工作区 2026-10-28 已完成的 ECARX 命名与单后端收敛；保留 Android 系统媒体会话、标准按键、封面及会话隔离 |
| 导航/HUD/仪表恢复 | 最新补充：“导航的 HUD 投屏信息什么都正常了” | dev 工作区 HUD 修复：DIM 在主线程创建；当前会话主画面首帧状态；仪表三个绑定入口统一门控、已有 Surface 重绑、会话切换清理与 Activity 接续 |

媒体主开关沿用 Release 原有“仪表媒体卡片”持久化设置，默认值保持不变。删除 DIM 媒体通道与多后端选择，不改变 DIM 导航和车道通道；导出报告增加 ECARX MediaCenter 状态。

本次不包含 Siri 通信采集实验、语音自检、新的语音键短按接管/重复抑制/Geely inputservice 拦截、热点/Wi-Fi 控制与恢复、HiCar 新控制、USB 改动及其他上游更新。
同文件中的媒体/HUD 差异按功能提取，`GeelyWheelKeys`、`GeelyWheelKeyCodes`、`CarPlayMediaButton` 和 USB/录音源码保持 Release 基线。

USB 在历史上有另一台手机通过，但最新补充仍报告问题；Siri 采集看似正常，也不代表唤起后不会中断。两者继续待查。

实车结论对应原测试包。提取后的新组合需要独立软件检查，并在正式发布前回归音乐输出、退出后的蓝牙恢复、媒体按键/封面、导航/HUD/车道和断线重连。软件测试不能代替新组合的实车验收。
