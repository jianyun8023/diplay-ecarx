# DiPlay 领克 05 移植版

**在亿咖通 ECARX（高通 820A / Android 9）车机上运行 CarPlay 的接收端。** 应用包名 `com.shihab.diplay`，独立应用，与车机原厂功能并存。

> ⚠️ **非官方、非 Apple 认证产品。** 本项目是社区移植，与 Apple、吉利/领克、亿咖通均无关联，也不代表其认可。刷机与侧载有风险，请自行判断。

上游项目：[shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay)（GPL-3.0）。上游原始说明保留在 [`docs/UPSTREAM-README.md`](docs/UPSTREAM-README.md) / [`docs/UPSTREAM-README.zh-CN.md`](docs/UPSTREAM-README.zh-CN.md)。

> 📄 **本仓库里有几份 README，别走错**：**本文件（`README.md`）是本移植版唯一权威说明**。
> 根目录的 `README.zh-CN.md` 与 `docs/UPSTREAM-README*.md` 都是**上游 DiPlay 面向 BYD DiLink 的原始说明**，
> 为尊重上游原貌完整保留、仅供参考——其中描述的机型支持范围（BYD）与本移植版（领克 05 / ECARX）**不通用**。

---

## 一、支持的车型

| 车型 / 车机 | 系统 | 状态 |
| --- | --- | --- |
| **领克 05（2020 款，车机代号 `Lynk & Co 05_IP732`）** | Android 9 / API 28 / arm64 | 主目标平台，实车测试中 |
| 领克 DCY11 平台同系车机（`AP_DCY11_IHU_CN_DCY11_IP732_*` 固件） | Android 9 / arm64 | 理论可用，未逐一验证 |
| 领克 CS11 平台（2018–2021 老车机，32 位 / Android 4.4–5.1） | Android 4.4–5.1 / armeabi-v7a | **装不了本应用**，需改用同一生效基线的 EasyPlay 兼容包 |

> 上游 DiPlay 只面向 BYD DiLink，**明确不支持其他品牌**。本移植版是独立分支，不向上游提交这些改动，也不承诺兼容 BYD。

---

## 二、⚠️ 三条必读声明

### 1. 本仓库不含 MFi 身份（accessory identity）

CarPlay 建立连接时需要一套 **MFi accessory 身份**（一个私钥 + 一份 Apple 签发的证书）来完成与 iPhone 的认证。**本仓库的源码不含任何身份文件，也不含任何签名密钥**——`assets/offline-mfi/` 下的两个文件由构建时从仓库外注入。

- 上游自述（原文见 [`docs/THIRD_PARTY_NOTICES.md`](docs/THIRD_PARTY_NOTICES.md) 的 *Experimental authentication data* 一节）：
  其预览版 APK 内嵌的是一套**从公开 Carlinkit C2Air（Allwinner V821）固件中恢复出来的**证书/密钥对，
  **不是**为该项目新签发的 Apple 凭据，也不作为项目源码再许可；且明确声明其
  **能否持续被接受、是否适合公开分发，均未定论**。
- 本项目**不含、不主张、也不再许可**该身份——它不在本仓库的许可证覆盖范围内。
  任何内嵌身份的构建产物，装的都是上游披露的那套实验性身份。
- ⇒ **没有身份，CarPlay 连不上**。自备身份的四条路径见 [`BUILD.md`](BUILD.md#3-第二步准备-mfi-运行时资产)。
- ⇒ 相关权责与凭据声明汇总在 [`NOTICE.md`](NOTICE.md) 第三节。

**关于保密性**：这套身份**不保密，也保不住**——任何拿到内嵌身份 APK 的人都能把私钥提取出来（上游已在 README 中明确披露这一点）。因此：

- ⚠️ **用这套身份构建并分发 APK，就等于公开分发该身份**——这是该路径固有的性质，不是疏漏。
- ⚠️ **接受此风险的含义**：Apple 有权吊销该证书，**一旦吊销，所有使用同一身份的设备（包括你自己的车）会同时失效**。
  细节与自担风险见 [`BUILD.md`](BUILD.md#3-第二步准备-mfi-运行时资产)。

### 2. 签名：本移植版必须使用 AOSP 默认 platform 密钥

**Android 的安装校验只检查 APK 内嵌的证书链，私钥从不随 APK 分发。** 所以「别人拿不到签名私钥」这一点完全不影响本项目的 APK 安装。

但**权限**取决于证书是否与车机 `framework-res` 一致——而本项目依赖一批系统级权限（见第四节）。本移植版的做法是：**使用 AOSP 仓库自带的默认 platform 密钥签名**。

为什么可行：领克 05 的固件指纹带 **`test-keys`**，说明**厂商从未替换 AOSP 默认密钥**。因此用该默认密钥签名，证书就与车机 `framework-res` 完全一致，所有 `protectionLevel` 含 `signature` 的权限都会被授予。

- 该密钥对**是公开的**（就在 AOSP 源码树里），任何人都能取得。**「不公开私钥」在这里不构成任何保护。**
- 取证与转换步骤（含下载地址）见 [`BUILD.md`](BUILD.md#2-第一步取-aosp-默认-platform-密钥)。
- ⚠️ **本仓库同样不含这把密钥**；它是公开的，但我们仍建议自行从 AOSP 获取，而不是从别人那里转手。

### 3. 如果你只下载源码自己编译 —— 请先读这一节

| 步骤 | 结果 |
| --- | --- |
| 编译 | ✅ 可以（`./gradlew :mobile:assembleDebug` 这条 source-only 路径） |
| 安装 | ✅ 可以 |
| 普通权限 / 运行时权限 | ✅ 正常 |
| **平台级权限**（`signature` 类） | ❌ **全部被拒**——除非你也用 AOSP 默认 platform 密钥签名（见上） |
| **CarPlay 能否用** | ❌ **不能**：没有 MFi 身份，与 iPhone 认证不过 |

⇒ **本仓库的定位是「源码 + 可复现的构建方法」，不是一个开箱即用的包。**

---

## 三、安装

车机必须允许安装非官方来源的 APK。两种方式：`adb install`，或把 APK 放到 U 盘从车机文件管理器安装。

```bash
# 平台签名版本的证书与官方版不同，无法覆盖安装 ⇒ 换成本移植版前先卸载
adb uninstall com.shihab.diplay
adb install -r DiPlay-<版本>-lynk<序号>-platform.apk
```

- ⚠️ **DiPlay 与 EasyPlay 只能装一个**。做隔离测试时必须**卸载**（强制停止会残留 USB 授权状态）。
- ⚠️ **证书一旦变化就不能覆盖安装**。同一签名系列内的新版本可以覆盖（保留设置）。
- 首发安装后建议先跑一次 **设置 → 诊断 → 保存诊断报告**，确认基础链路正常。

---

## 四、需要哪些系统级权限，为什么

本移植版依赖下列权限。★ 标记的保护级别含 `signature`，**只有与车机 `framework-res` 同证书的包才会被授予**——这正是必须用 AOSP 默认 platform 密钥签名的原因。

| 权限 | 用途 | |
| --- | --- | --- |
| `android.car.permission.CAR_VENDOR_EXTENSION` | 车机厂商扩展接口（ECARX XUI） | ★ |
| `android.car.permission.CAR_CONTROL_AUDIO_VOLUME` | 音频音量/焦点仲裁 | ★ |
| `android.cevt.dimsupportservice.permission.DIM_MESSAGING` | 仪表（DIM）导航与媒体信息投送 | ★ |
| `ecarx.oem.permission.OPENAPI_NAVI_PERMISSION` | 导航开放接口 | ★ |
| `android.permission.BLUETOOTH_PRIVILEGED` | 蓝牙 profile 级连接策略（让出车机蓝牙的媒体/通话） | ★ |
| `android.permission.WRITE_SETTINGS` | 修改系统设置项 | ★ |
| `android.permission.PACKAGE_USAGE_STATS` | 判断当前前台应用（投屏与回退逻辑） | ★ |
| `android.permission.SYSTEM_ALERT_WINDOW` | 悬浮窗 | |
| `android.permission.RECEIVE_BOOT_COMPLETED` | 开机自启 | |
| `android.permission.REQUEST_INSTALL_PACKAGES` | 应用内更新 | |
| `android.permission.RECORD_AUDIO` | Siri / 通话麦克风上行 | |
| `android.permission.MODIFY_AUDIO_SETTINGS` | 音频路由 | |
| `android.permission.BLUETOOTH_CONNECT` / `BLUETOOTH` / `BLUETOOTH_ADMIN` | 蓝牙引导通道（iAP2 握手） | |
| `android.permission.CHANGE_WIFI_STATE` / `ACCESS_WIFI_STATE` / `NEARBY_WIFI_DEVICES` / `CHANGE_WIFI_MULTICAST_STATE` | 热点与 WiFi 传输链路 | |
| `android.permission.ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | WiFi 扫描（交通类） | |
| `android.permission.INTERNET` / `ACCESS_NETWORK_STATE` | 网络 | |
| `android.permission.FOREGROUND_SERVICE`（含 `CONNECTED_DEVICE` / `MICROPHONE` / `LOCATION`） | 前台服务（连接期间保活） | |
| `android.permission.POST_NOTIFICATIONS` | 状态通知 | |

---

## 五、已知限制与未验证项

**诚实标注，不要当成已修好。**

| 项 | 状态 |
| --- | --- |
| 无线 CarPlay 连接、投屏、方控、仪表 | 实车可用（既有版本） |
| **音乐播放 1–2 秒后自动暂停** | ⚠️ **核心未解问题**。已定位在 iOS 侧音频会话（媒体流建立后被 iPhone 主动关闭，视频不受影响），根因假设为「iOS 把车机当成第二个音频输出」。已提供「让出车机蓝牙」的规避手段，**未经实车验证** |
| 蓝牙让位（CarPlay 期间关闭车机蓝牙的媒体/通话 profile） | ⚠️ **未实车验证** |
| 2.4 GHz 卡顿 / 微信语音条 / 通话回声 / RTP 时钟探针 | ⚠️ **未实车验证**（fix7 四项） |
| 车道级导航 | 桥已就绪，缺数据源，未实现 |
| 免提通话 UI | 仅打通回调，未实现界面 |
| 麦克风上行（Siri / 语音） | ❌ 未接通，必然不可用 |
| 方控按键完整映射 | 传感器 id 表尚未标定，仍为保守扫描 |
| `x86_64` 架构 | 缺 `libopus_encoder.so`，仅 `arm64-v8a` / `armeabi-v7a` 完整（本车为 arm64，不受影响） |

---

## 六、与上游的关系 / 我们改了什么

- 基线：上游 **0.2.15**，叠加本地 `main` 分支（源于移植期的 `feat/ecarx-hud` 开发线）。
- 移除了上游面向 BYD / DiLink 的专用内容（不影响 ECARX 路径）。
- 移植与修复内容按序标记为 `fix1` … `fix10`，涵盖：ECARX DIM 仪表输出、权限声明、DIM 参数修正、
  RTP 时钟探针与 Opus 编码器支持、通话回声参考、**蓝牙 profile 级让位（保留 iAP2 握手）**，
  以及**车机品牌化**（品牌名统一为 `Lynk&Co`、CarPlay 磁贴换为领克标）。
- ⚠️ **相对上游有一处功能删减**：设置里的「自定义车机图标 / 品牌名」入口已**移除**，改为只读车机卡片
  （图标与名称随应用打包）。这是本移植版的刻意选择，不是功能缺失。
- 本移植版**不向上游提交**这些改动，也**不代表上游立场**。

---

## 七、许可

- 主体许可：**GPL-3.0**（见 [`LICENSE`](LICENSE)，保持上游原文，未做任何修改）。
- 界面与站点部分改编自 [DiAuto](https://github.com/shihabal3amri/DiAuto)（AGPL-3.0），许可原文在 `docs/licenses`。
- 第三方组件与许可见 [`docs/THIRD_PARTY_NOTICES.md`](docs/THIRD_PARTY_NOTICES.md)。

**修改声明**：见 [`NOTICE.md`](NOTICE.md)——其中记录了修改者、修改日期、修改内容概要，
以及凭据与签名密钥的处理方式（这是 GPL-3.0 第 5 条的强制要求）。
**重新分发时请保留 `NOTICE.md` 与 `docs/THIRD_PARTY_NOTICES.md` 中的全部声明**，并同样以 GPL-3.0 授权。

CarPlay 及相关标识为 Apple Inc. 的商标。
