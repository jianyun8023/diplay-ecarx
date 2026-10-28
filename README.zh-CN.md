# DiPlay · 领克 01（2021 款）/ ECARX

本 fork 当前主要适配 **2021 款领克 01**。完整说明见 [README.md](README.md)，
其中保留原 fork 的领克 05 历史记录；历史记录不等于本版本在领克 01 上全面验收。

- **分支**：`release` 默认，收纳已确认功能；`dev` 用于待验证改动与 Debug；`main` 保留原 fork 基线。
- **报告环境**：Android 9 / API 28、Qualcomm MSM8996、`dcy11_a1`；报告未绑定 APK SHA，不能证明当前构建已该车验收。
- **已确认**：封面图片、导航车道用户实车确认 OK；可视区域“看起来 OK”，非全部显示场景。
  USB 连接手机已获用户“测试 OK”反馈并单独进入 release，但未说明测试设备、平台或版本。
- **仍待解决/确认**：HiCar 双唤醒、Siri 有效收音、A2DP 抢音乐输出、iPhone 蓝牙关开后的自动回连、OEM 热点控制。
- **界面**：仅英文、简体中文、繁体中文，保留跟随系统。

[构建环境与命令](README.md#从源码构建)：JDK 25、Gradle wrapper 9.5.0、SDK 37.0、Build Tools 36.0.0；
应用最低 API 24，与报告中的车机 API 28 是不同概念。CI 另配 NDK，普通预编译库构建不一概要求安装它。
**source-only 包不含 MFi 身份**，缺少合法认证前置时会停在设置/身份检查处，不能据安装或 OTG 枚举成功声称 CarPlay 已通。

[当前验证说明](README.md#当前适配与验证) · [逐功能证据](docs/LYNK_01_VERIFIED_BRANCH.md) ·
[原上游中文说明（历史存档）](docs/UPSTREAM-DIPLAY-README.zh-CN.md)
