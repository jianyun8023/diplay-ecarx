# 用户手册截图来源（v134 模拟器 + v142 实车）

2026-11-07，Android 9 模拟器 `emulator-5580`，1920×1080 横屏、240 dpi、系统字号 1.0、简体中文、深色界面。使用现有车测包 `DiPlay-launcher-startup-v134-MFi.apk`（`versionCode 134`，`0.2-car-test-launcher-startup-v134`），从应用界面直接截图，未修改控件或拼接画面。

- `15-connection-v134.png`：选择车载热点及其他连接方式。
- `16-hotspot-v134.png`：自动开启热点与默认开启的 Wi-Fi 保护；截图未授予修改系统设置权限，不代表热点已成功启动。
- `17-audio-v134.png`：直接接管媒体，导航声音跟随 iPhone 和车机开启。
- `18-navigation-v134.png`：HUD／仪表导航提示和初始地图开启，桌面地图关闭。
- `19-wheel-v134.png`：直接接管模式，Siri 开启，选择短按。
- `20-auto-connect-v134.png`：打开时连接和开机启动开启，默认无线，USB 自动接管关闭。

实验仪表叠加层已隐藏。图片用于说明设置位置，不作为实车功能通过的证据。不包含真实设备名称、网络密码或认证内容。分享版在工作区 `docs/screenshots/` 保留相同文件。

## v142 实车截图（2026-11-08）

2021 款领克 01（753 车机，Android 9）实拍，使用车测包 `DiPlay-music-pause-v142-MFi.apk`（`versionCode 142`／源码 136，`0.2-car-test-music-pause-v142`，platform 签名），从车机屏幕直接截图，未拼接画面。

- `30-car-overview-v142.png`：首页。
- `31-car-connection-v142.png`：连接页。
- `32-car-auto-connect-v142.png`：自动连接（车主说明在用）。
- `33-car-display-v142.png`：显示与性能，日夜模式选择「跟随车外光线」。
- `34-car-audio-v142.png`：音频页，两个暂停开关已手动开启。
- `35-car-audio-routing-v142.png`：音频路由（车主说明在用）。
- `36-car-navigation-v142.png`：导航页，三个导航开关（车主说明在用）。
- `37-car-wheel-v142.png`：方向盘按键（车主说明在用）。
- `38-car-diagnostics-v142.png`：诊断页。
- `39-car-advanced-cluster-v142.png`：高级 → 仪表显示。

另有 `21-display-daynight-v138.png`（v138 车测包，1920×1080 的日夜设置页）与 `22-audio-music-pause-v141.png`（v141 车测包的暂停开关卡片），用于功能文档。

实车图含原车底部 dock 与系统图标，不含账号、位置或认证信息；用于说明设置位置，**不作为功能通过证据**。车主说明当前使用 `15`、`16`（v134 模拟器）与 `32`、`35`、`36`、`37`（v142 实车），以及脱敏过的 `cluster-media-redacted.png`。工作区 `docs/screenshots/` 保留同名副本。
