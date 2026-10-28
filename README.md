# DiPlay · 领克 01（2021 款）/ 亿咖通 ECARX 适配

[当前适配与验证](#当前适配与验证) · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-TW.md) · [English summary](#english-summary) · [上游原版 README（存档）](docs/UPSTREAM-DIPLAY-README.md) · [更新记录](CHANGELOG.md)

本仓库当前主要适配 **领克 01（2021 款）亿咖通 ECARX 车机**。源于
[jiopia/DiPlay-ECARX](https://github.com/jiopia/DiPlay-ECARX) 的领克 05 移植版，
其通用上游为 [DiPlay 0.2.21](https://github.com/shihabal3amri/DiPlay)。原 fork 的适配记录在下方保留，
**不等于本仓库当前版本已在所有车型、固件或场景通过验证**。

## 当前适配与验证

### 分支与界面语言

- **`release`（默认）**：收纳已确认功能；USB 连接手机已独立晋级。
- **`dev`**：待验证改动与 Debug 测试；麦克风真实音源/统计、车机蓝牙恢复、ACL/重试诊断仍在这里。
- **`main`**：保留原 fork 基线 `b46ff9be`；旧 main 备份为 `backup/main-before-upstream-reset`。
- 应用界面仅保留 **English、简体中文、繁體中文**，以及跟随系统；不支持的系统语言回退英文。

### 环境与证据边界

| 项目 | 当前已知信息 |
| --- | --- |
| 主要适配目标 | 领克 01，2021 款，ECARX 车机 |
| 用户测试报告环境 | Android 9 / API 28；Qualcomm MSM8996；板型 `dcy11_a1` |
| 报告与构建关联 | 该报告未绑定 APK 提交 SHA，不能据此声称当前构建已在该车完整验收 |
| 应用最低 Android 版本 | Android 7.0 / API 24（源码安装下限，不代表所有 Android 设备均支持车机功能） |

| 功能 | 确认程度 |
| --- | --- |
| 仪表封面图片 | 用户实车确认 OK |
| 导航车道 | 用户实车确认 OK |
| 仪表可视区域 | 用户反馈“看起来 OK”；不等于全部显示尺寸与重连场景均通过 |
| USB 连接手机 | 用户确认“测试 OK”，已进入 `release`；此次未说明测试设备、平台或版本，不扩大为所有 USB 异常路径已验收 |

具体来源与范围见 [已确认功能说明](docs/LYNK_01_VERIFIED_BRANCH.md)。
**仍待解决/确认**：语音键同时唤醒 HiCar、Siri 能否获得有效语音、普通蓝牙 A2DP 抢音乐输出、
iPhone 设置中蓝牙关开后自动重连，以及 OEM 热点控制。目标是音乐走 CarPlay、蓝牙保留建连，
不是禁用原生电话；目前不宣称这些问题已修复。

最近一次 USB 晋级的云端软件验证对应 [`285953e7`](https://github.com/jianyun8023/diplay-ecarx/commit/285953e77b7eb6f92da8c6601512623ed162e116)
（树 `436b917db86d03d8bcedf5c6b353c445e0e1cd0b`）：USB 44 项与既有功能回归 72 项通过，
三个 lint 无错误、三个 Debug 构建通过。全量仍有 **14 项网络接口环境失败、1 项跳过**；
失败在纯基线同样出现（云端仅 loopback 接口），不能称全量全绿，更不能替代实车验收。

> ### ⚠️ 发布的 APK 不含 MFi 身份
>
> 要让 iPhone 把车机当成合法 CarPlay 配件，APK 里必须有 Apple 签发的配件身份
> （`offline-mfi/identity.pk8` + `offline-mfi/certificate.p7b`）。这是 Apple 的授权资产，
> 本仓库**不提供、不打包、不再分发**。
>
> source-only 构建不含这些资产；缺少可用身份时，应用会在设置/身份前置检查处停止，
> **不是已经进入 USB 或无线握手才失败**。普通 Android 手机可先看 UI，独立测试系统热点入网或 OTG 枚举，
> 但这些不等于本应用 CarPlay 链路验证。只有合法认证前置满足后，才谈端到端连接。要在车上试，请自行构建：
> [如何把 MFi 身份打进 APK](#如何把-mfi-身份打进-apk)。
>
> 例外：如果这台车机上**已经装过**含身份的旧版（签名相同），覆盖安装不卸载时，旧版装进私有目录的
> 身份会被继续沿用，可以直接连。

---

## 适用环境

> **以下为原 fork 的领克 05 历史适配记录。** 环境、状态矩阵、车型特定操作与限制均按其原记录保留；
> 本仓库领克 01 与 USB 的最新状态以上方[当前适配与验证](#当前适配与验证)为准。

| 项 | 实测的那一台 |
|---|---|
| 车型 | 领克 05 2020（`Lynk & Co 05_IP732`） |
| 车机 | 亿咖通 ECARX 深度定制，高通 820A / arm64 |
| 系统 | Android 9（API 28），`dcy11-userdebug`，`test-keys` |
| 固件 | `AP_DCY11_IHU_CN_DCY11_IP732_20012107` |
| 屏幕 | 中控 12.7″ 1920×1080；仪表 display 1「HDMI 屏幕」1920×720 |
| 包名 | `com.shihab.diplay`（与上游同名，但签名不同，两者不能互相覆盖安装） |
| 签名 | AOSP 平台密钥（车机是 `test-keys`，据此才能拿到 signature 级权限去调 DIM） |

亿咖通车机不是这一套的话（不是 `ecarx.adapterapi` / 没有 `DimInteraction`），本版不会比上游更好用：
设置页里「亿咖通导航」整块会自动隐藏，剩下的就是上游的 CarPlay 本体。

## 与上游的区别

| | 上游 DiPlay 0.2.21 | 本移植版 0.1 |
|---|---|---|
| 目标车机 | 比亚迪 DiLink 1.0/3/4/5.x、B 系列等 | 亿咖通 ECARX（领克 05） |
| 仪表导航 | BYD HUD + DiLink 集群节点 | 亿咖通 DIM：`INaviInteraction` 推箭头 id / 距离 / ETA / 路名 |
| 仪表画面 | BYD 专用投屏 | 本项目在 display 1 自建 `Presentation`，装 CarPlay **原生集群流**（1920×720，8:3），再由 DIM 开关把这块屏接到仪表 |
| 仪表媒体卡片 | BYD 自带音乐源 | `IMediaInteraction` 推曲名/歌手/专辑/播放状态（关蓝牙后唯一来源） |
| BYD 代码 | 有 | **全部删除** |
| 版本号 | `0.2.21`（versionCode 40） | `0.1`（versionCode **100**，保证能覆盖升级） |
| MFi 身份 | 发布包内置实验性身份 | **默认不内置**，构建时用环境变量挂载 |
| 取证/探针代码 | 少量 | 全部清除，不留在发布包里 |

上游留下的说明与文档（`docs/RELEASE-NOTES-*.md`、`docs/COMPATIBILITY.md` 等）作为上游历史保留在仓库里，
里面提到 BYD 车机、BYD HUD、DiLink 集群的段落**不代表本移植版的行为**（那部分代码已整体删除）。
上游英文/繁中 README 存档于 `docs/UPSTREAM-DIPLAY-README*.md`。

## 状态矩阵：说到哪一步就是哪一步

### ✅ 已实车验证（2026-10-24，领克 05 实车，无线连接）

- **无线 CarPlay 连接、中控投屏**（Wi-Fi Direct / 车机热点两条路都走通了）。
- **仪表导航**：仪表盘整幅显示 CarPlay 这一路的**高德车道级地图**（CarPlay 集群流，1920×720，贴合
  异形区域），同时经亿咖通 **DIM** 把箭头、距离、路名、预计到达时间递给车机（HUD 与卡片用）。
  导航开始时仪表**不再闪一下**，也不需要手动去开「仪表投屏」。实拍见
  [导航投屏](#导航投屏仪表和中控只能二选一)。
- **HUD**：导航开始时 HUD 与仪表卡片同时出现箭头与距离，导航结束后一起撤掉。
- **仪表媒体卡片**：曲名、歌手、专辑、播放状态（前提：关掉 iPhone 蓝牙，见[蓝牙与媒体](#蓝牙与媒体为什么必须关掉它)）。
- **导航结束收尾**：仪表交回车机自己的页面，不留残影、不盖住原厂仪表。
- **导航语音压低音乐（duck）**、**车机蓝牙让位与自动恢复**。

### ⚠️ 原 fork 当时未实车验证的项目（历史记录）

- **USB 有线连接。** 本版没有在这台车上试过有线。线材、USB 权限、iAP2 握手都可能有本车前未暴露的
  问题，请不要把「无线好用」外推到「有线也好用」。
- **方向盘按键。** 学习界面可用，媒体键 / 语音键 / 接听挂断已接线，但没有逐键系统验证。
  语音键可能被车机自带语音助手**重复响应**一次，见[功能设置](#功能设置哪些必须开哪些按需开哪些不要开)。
- **麦克风上行。** 通话与 Siri 的采音未在这台车上测过音质与回声，不要假设和上游一样。
- **iOS 版本跨度。** 只在测试机的 iOS 上验证过；系统升级后 Apple 端的认证与投屏行为可能变。
- **多车型。** 只在一台 05 上验证。其它亿咖通车型的 display 编号、仪表尺寸、DIM 版本都可能不同。

### 🚧 原 fork 当时的计划（历史记录）

- **USB 有线连接**的完整验证与修复（优先级最高）。
- **百度地图的转向数据**（未测试；高德与苹果地图已可用）。
- **其它亿咖通车型**的 display 探测与仪表尺寸自适应。

## 安装与升级

1. 车机允许安装第三方 APK（本车是 `test-keys`，可装）。
2. **换过签名就要先卸载**：`adb uninstall com.shihab.diplay`，或在车机应用管理里卸载。
   签名不同的 APK 不能覆盖安装，而强停旧版再装会残留 USB 授权状态。
3. `adb install -r <apk>`，或拷到 U 盘用文件管理器装。
4. 首次启动建议先看一遍[功能设置](#功能设置哪些必须开哪些按需开哪些不要开)。

同一台车机上**只留一个投屏 App**（DiPlay 与 EasyPlay 只能装一个，两者会抢同一批 USB/网络资源）。

## 功能设置：哪些必须开，哪些按需开，哪些不要开

全部集中在 **设置 → 位置 → 「亿咖通导航」** 一张卡片里，三个开关，都是"司机看得见的那三张画面"。

| 设置项 | 默认 | 建议 | 说明 |
|---|---|---|---|
| **仪表导航** | 开 | **必须开** | 不开就不向仪表推任何 DIM 数据，仪表与 HUD 都不会有箭头、距离、路名。导航开始时"仪表投屏"也由它驱动。 |
| **仪表媒体卡片** | 开 | **按需开**（建议开） | 把 CarPlay 的曲名/歌手/专辑/播放状态推到仪表。关掉后，仪表只在你**用车机自带蓝牙放歌**时才有内容；一旦按下面的做法关掉 iPhone 蓝牙，卡片就会一直是空的。 |
| **仪表投屏** | 关 | **不要开** | 它把**整块中控画面镜像到仪表**，会盖住仪表上的箭头和媒体卡片——这就是之前"开了投屏反而看不到箭头"的原因。导航开始时本项目会自己接管这块屏，不需要手动开；手动打开前它会弹确认框。 |

另外两组按需：

| 位置 | 设置项 | 什么时候动它 |
|---|---|---|
| 设置 → 方向盘按键 → 「亿咖通方向盘按键」 | 媒体键控制 CarPlay / 接听挂断 / 语音(Siri) | 想让方向盘按键管 CarPlay 时打开，然后逐个键点按学习。学习过的键继续生效。 |
| 设置 → 连接设置 → 「打开连接设置」 | 连接方式（Wi-Fi Direct / 车机热点 / 已有 Wi-Fi） | 默认即可。车机热点那一路需要在弹出提示时允许。 |

两个容易踩的坑：

- **语音键被响应两次。** 车机自己的语音助手和本 App 会同时接这一下按键，ROM 拒绝把按键独占交给
  第三方，侧工具改不了。解法是把车机自带助手停用：
  ```bash
  adb shell pm disable-user --user 0 <车机语音助手的包名>
  ```
- **不要同时用车机自带导航。** 车机自己的导航一开始就会去抢仪表那块屏，两套导航同时跑，画面会互相盖。

## 蓝牙与媒体：为什么必须关掉它

> 本节保留原 fork 在领克 05 上的历史绕行方案，不是当前领克 01 的已验证修复或通用建议。
> 用户实际操作是 iPhone「设置 → 蓝牙」关开，后续还需手点车机；A2DP 与自动重连根因仍待定位。

**症状**：CarPlay 连上了、画面也有了，但**没有声音**；或者有声音却和 iPhone 不同步；或者仪表媒体卡片
显示的是车机蓝牙里的歌，不是 CarPlay 正在放的那首。

**原因**：iPhone 同时被两条通道牵着——CarPlay（AirPlay 音频）和**车机经典蓝牙**（A2DP/HFP）。
只要经典蓝牙还连着，iPhone 就倾向把音频交给蓝牙通道，车机的音源也还停在蓝牙音乐上。所以：

> **必须切断 iPhone 与车机之间的经典蓝牙，音频才会走 CarPlay。**

**解法**：用 iPhone 自带的「快捷指令 → 自动化」，连上/断开 CarPlay 时自动关/开蓝牙。截图：

<p align="center">
  <img src="docs/screenshots/shortcut-connect.png" width="260" alt="连接 CarPlay 时：等待 2 秒 → 关闭蓝牙 → 等待 1 秒 → QQ音乐-继续播放" />
  <img src="docs/screenshots/shortcut-disconnect.png" width="260" alt="断开 CarPlay 时：打开蓝牙" />
  <img src="docs/screenshots/shortcut-list.png" width="260" alt="两条自动化都设为启用" />
</p>

### 那 2 秒是干什么的 —— 顺序不能改

```
「连接 CarPlay 车载时」
  1. 等待 2 秒      ← ★ 不能少，也不能改成 1 秒
  2. 关闭 蓝牙
  3. 等待 1 秒
  4. 播放音乐（例：QQ音乐-继续播放）
```

- **为什么等 2 秒**：CarPlay 的会话建立（iAP2 认证 → AirPlay 启动）大约需要 1 秒多。**1 秒就关蓝牙，
  正好落在握手中间，会话被打断 ⇒ 有画面没声音**；等 2 秒再关，握手已经完成，音频顺利切到 CarPlay。
  这是实测结论：`1 s ⇒ 无声，2 s ⇒ 正常`。
- **为什么还要等 1 秒再播放**：关闭蓝牙以后车机的音频路由要重新选一次，立刻播放会被吞掉开头。
- **断开时反过来**：`断开连接 CarPlay 车载时 → 打开蓝牙`。不打开的话下次上车车机蓝牙是关的，
  电话和车机自带音乐都用不了。

自动化本身还有两个开关要设对：

- **「运行时显示」关掉**（截图里就是关的）。开着的话每次连接都会在手机上弹一条通知。
- 新建自动化时选**「立即运行」**（不要「运行前询问」），否则每次上车还得手动点一下"运行"。
  iOS 版本不同措辞不同，位置在自动化编辑页底部。

### 副作用（这是取舍，不是 bug）

蓝牙一关，车机就收不到 iPhone 的 AVRCP 元数据，**仪表媒体卡片只能靠本 App 自己推**
（就是「仪表媒体卡片」那个开关）。所以这两个设置是一套的：关蓝牙 + 开仪表媒体卡片。

## 导航投屏：仪表和中控只能二选一

CarPlay 给仪表的画面和给中控的画面是**同一个地图 App 渲染的两路视图**，导航 App 只能让其中一路进
「车道级」。

<p align="center">
  <img src="docs/screenshots/cluster-amap-lane.jpg" width="720" alt="领克 05 仪表盘整幅显示高德车道级导航：进入 周山大道，右转 231 m，限速 50" />
</p>

*领克 05 实车：仪表盘整幅显示高德车道级导航（「进入 周山大道」右转 231 m、限速 50）。这一路占满了
仪表，中控那一路就只能是线路总览。*

### 高德地图

- **「3D 车道级」与「线路总览」在仪表和中控上只能二选一。** 仪表用车道级，中控就只能是线路总览；
  中控用车道级，仪表就退成线路总览。
- 切换方法：**在中控的导航界面上点「线路总览 / 3D 车道级」**那个按钮，两路画面会一起换。
- 仪表那一路是 CarPlay 的**集群地图流**（本项目在 display 1 自建窗口装它，1920×720），
  画的就是导航 App 自己渲染的车道级画面，上面截图里没有额外叠任何东西。
- 亿咖通 **DIM** 是**另一条线**：箭头 id、距离、路名、ETA 单独推给车机，HUD 与仪表卡片用的是它。
  两条线互不影响 —— 「仪表有地图」不等于「HUD 有箭头」，两边都要正常才算好。

### 苹果地图

- 点屏幕**右上角的方向箭头**切换（跟随车头 / 正北朝上）。

### 百度地图

- **未测试。** 它的转向数据能不能进仪表没有验证过，不保证。

## 第一次连接：iPhone 上要点「允许」

第一次连接时 iPhone 会弹几个框，**都必须点允许**，点错一次后面就会一直连不上：

1. 插线或发起无线连接后，iPhone 弹「**允许此配件访问**」/「**信任此电脑**」→ 点**允许**，需要时输入锁屏密码。
2. 蓝牙配对框 → 点**配对**，再点**允许**。
3. CarPlay 首次连接提示 → 允许。

如果之前误点过「不允许」：**设置 → 通用 → 传输或还原 iPhone → 还原 → 还原位置与隐私**，
然后重新连接，弹框会再次出现。

## 如何把 MFi 身份打进 APK

发布版故意不含身份；要在车上试，自己构建一版带上它。

### 1. 准备两个文件

```
<任意目录>/auth-assets/offline-mfi/identity.pk8       ← Apple 签发的配件私钥
<任意目录>/auth-assets/offline-mfi/certificate.p7b    ← 对应的配件证书链
```

**本项目不提供这两个文件**，也不教怎么获取。你要么持有 Apple MFi 授权拿到的身份，要么从你合法持有的
配件里自己取。两个文件缺一个、或长度为 0，构建会在 `verifyStandaloneAuthentication` 这一步直接失败。

### 2. 用环境变量挂上去再构建

```bash
DIPLAY_AUTH_ASSETS_DIR=/path/to/auth-assets ./gradlew :mobile:assembleRelease
```

- 不设 `DIPLAY_AUTH_ASSETS_DIR` 就是**不含身份**的发布包（默认）。
- `mobile/build.gradle.kts` 用 `assets.srcDir` 把那个目录挂进 APK；目录里只允许这两个文件，
  多塞一个 `.p12` / `.keystore` 会被 `rejectBundledCredentials` 挡下来。

### 3. 确认真的打进去了

```bash
unzip -l mobile/build/outputs/apk/release/mobile-release.apk | grep offline-mfi
# 期望两行：assets/offline-mfi/identity.pk8、assets/offline-mfi/certificate.p7b
```

`apksigner verify --print-certs` 看到的是你的平台密钥（不是身份证书）——两者不是一回事。

### 4. 风险，先说清楚

- **把含身份的 APK 发给别人 = 公开分发 Apple 的授权资产**，私钥可被提取，滥用后果自负。
  所以本仓库的发布包不含它，只写方法。
- 身份装进 APK 以后就不保密了：谁拿到包，谁就拿到私钥。
- 首次启动时 App 会把资产拷到私有目录（`noBackupFilesDir/offline-mfi`），之后从那里读。
  因此**覆盖安装（不卸载）会沿用旧身份**，`adb uninstall` 或换车机后必须重新打包。

## 诊断报告

出问题先抓报告，不要凭现象猜。

1. 复现问题，记下发生时间。
2. **设置 → 诊断 → 保存诊断报告**，Android 10 以上一般在 `Downloads/DiPlay`，
   Android 7–9 会先要存储权限，也是存到那里。
3. 报告里对仪表问题最有用的几行：
   - `Cluster panel: showing on "HDMI 屏幕" 1920x720 projection=true` — 仪表窗口上屏了
   - `DIM mirror left to the platform at guidance start` — 导航开始没有强抢仪表（正常应出现一次）
   - `DIM guidance started arrowId=NNN` / `DIM arrow=… arrowId=NNN` — 箭头 id 在发
     （`arrowId=0` 表示这一帧没拿到转向类型）
   - `DIM guidance ended` → `presentation switch -> false` → `Cluster panel: dismissed` — 结束收尾的
     三步，顺序反了会留残影
   - `media counters media=true sourceType=… sourceList=… pushed=…` — 媒体卡片通道是否活着

## 从源码构建

按当前 [CI 配置](.github/workflows/android.yml)准备 **JDK 25**、Android SDK `platforms;android-37.0`、
Build Tools `36.0.0` 和 platform-tools；仓库 wrapper 固定 **Gradle 9.5.0**。
CI 另安装 NDK `28.2.13676358`；本轮构建复用仓库预编译原生库，不能据此把安装该 NDK 写成所有普通构建的硬性前置。
`compileSdk` / `targetSdk` 为 37，应用 `minSdk` 为 24。运行 Gradle 的 JDK 与 Java 11 源码目标不同。
`BUILD.md` 保留历史环境说明，版本要求以当前 Gradle/CI 配置为准。

```bash
# 不含 MFi 身份的 Debug 包：适合代码/UI检查，不承诺可直接连 iPhone
./gradlew :mobile:assembleDebug
# 产物：mobile/build/outputs/apk/debug/mobile-debug.apk
```

完整检查命令见 [AGENTS.md](AGENTS.md)，覆盖 shared/common/home 测试、三个 lint 和三个 Debug 构建。
下面的 Release 构建还需要你自己的合法签名配置；没有签名材料不能照搬成可发布包。

```bash
# 发布包（不含 MFi 身份）
./gradlew :mobile:assembleRelease

# 含 MFi 身份的实车测试包：先把两个身份文件放到某个目录的 offline-mfi/ 下
DIPLAY_AUTH_ASSETS_DIR=/path/to/auth-assets ./gradlew :mobile:assembleRelease
```

产物：`mobile/build/outputs/apk/release/mobile-release.apk`。签名与平台权限的关系、手工校验命令
见 [BUILD.md](BUILD.md)。

## 已知限制

> 下列是原 fork 的历史限制，尤其“停用语音助手”“关闭蓝牙”等操作不应当作本仓库的通用修复。
> 当前领克 01 的未解决问题及验证边界见[当前适配与验证](#当前适配与验证)。

- 只在一台领克 05 上验证；其它亿咖通车型未适配。
- 仪表是箭头 + 距离 + 路名，不是整幅地图。
- 关掉 iPhone 蓝牙后，车机蓝牙电话、车机自带音乐都不可用（这是换取声音的代价）。
- 语音键可能双响应，需停用车机自带语音助手。
- 「仪表投屏」开关留着是历史遗留，正确用法是不碰它。

## 许可与致谢

- 上游 [DiPlay](https://github.com/shihabal3amri/DiPlay)（BSD/Apache 与 GPL 混合，见 `LICENSE` 与
  `docs/licenses`），其上游为 [xcertplay](https://github.com/shilapi/xcertplay)（GPL-3.0）。
- 界面与站点部分改编自 [DiAuto](https://github.com/shihabal3amri/DiAuto)（AGPL-3.0）。
- 分发修改版时请保留这些声明。CarPlay 与相关图标属于 Apple Inc.；本项目与 Apple、领克、亿咖通均无
  隶属或背书关系。
- 本仓库发布版**不含**任何 Apple 授权资产；MFi 身份的获取与使用由使用者自行负责。

---

## English summary

This fork now primarily targets the **2021 Lynk & Co 01 (ECARX)**. The original Lynk & Co 05 adaptation
and its historical notes are retained above; they are not proof of current validation on the 01.

- **Branches:** `release` is the default confirmed-feature branch; `dev` contains pending fixes/diagnostics;
  `main` preserves the original fork baseline. USB has been promoted independently to `release`.
- **Reported environment:** Android 9 / API 28, Qualcomm MSM8996, board `dcy11_a1`. The supplied report
  was not tied to an APK commit SHA, so it does not certify the current build on that vehicle.
- **User-confirmed:** cluster cover artwork and navigation lanes were confirmed in-car. The visible-area
  editor “looks OK,” not an exhaustive display test. USB was reported as “tested OK,” without identifying
  the device, platform or version used; do not infer complete vehicle/USB fault-path acceptance.
- **Still unresolved/unconfirmed:** HiCar waking alongside Siri, usable Siri microphone input, A2DP music
  taking the output, automatic reconnection after toggling iPhone Bluetooth in Settings, and OEM hotspot control.
  Microphone diagnostics, head-unit Bluetooth recovery and ACL/retry traces in `dev` do not prove these fixed.
- **UI languages:** English, Simplified Chinese and Traditional Chinese; system default remains available.
- **Build:** JDK 25, Gradle wrapper 9.5.0, SDK platform 37.0 and Build Tools 36.0.0 match CI.
  CI also installs NDK 28.2.13676358; the cloud build used prebuilt native libraries, so NDK installation
  is not asserted as mandatory for every ordinary build. The app's minimum is API 24, not a guarantee of hardware compatibility.

**Source-only APKs contain no MFi identity.** Without valid, lawfully available authentication prerequisites,
the app stops at setup/identity checks, before the USB or wireless connection flow. Installation, UI checks,
system hotspot association and OTG enumeration alone do not validate CarPlay. Do not share credential-bearing APKs.

For evidence levels and the USB promotion scope, see [the release feature record](docs/LYNK_01_VERIFIED_BRANCH.md).
The cloud check for commit `285953e7` (tree `436b917d`) passed the targeted regressions, lint and Debug builds,
but the full suite retained 14 baseline network-environment failures and one skip; it was **not all green**
and is not a substitute for vehicle testing.
