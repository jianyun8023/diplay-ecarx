# 构建 DiPlay 领克 05 移植版

> 通用流程（模块选择、Android Studio 用法、单元测试与 lint）见上游文档 [`docs/BUILD.md`](docs/BUILD.md)。
> **本文只讲本移植版特有的那几件事**——尤其是签名与 MFi 身份，这两件决定了产物能不能用。
> 所有命令都在仓库根目录（有 `gradlew` 与 `settings.gradle.kts` 的那层）执行。

---

## 0. 先读：三个输入决定你能不能跑起来

| 输入 | 必需？ | 不给会怎样 |
| --- | --- | --- |
| **签名密钥**（AOSP 默认 platform 密钥） | 想要系统级权限就必需 | 能编译、能装，但**平台级权限全被拒**，车机交互功能全废 |
| **MFi 运行时身份**（`identity.pk8` + `certificate.p7b`） | **必需** | 能编译、能装，但**与 iPhone 认证不过 ⇒ CarPlay 连不上** |
| 平台工具链（JDK / SDK / Build-Tools） | 必需 | 编不过 |

**本仓库（源码）不含上面任何一项。** 三者都要你自己准备，且**都不能提交进 Git**。

---

## 1. 工具链（实测通过的版本）

| 组件 | 版本 |
| --- | --- |
| JDK | **Temurin 25.0.4.1+1**（必须 25） |
| Android SDK Platform | `platforms;android-37.0` |
| Android SDK Build-Tools | **36.0.0** |
| Android SDK Platform-Tools | 37.0.1（含 `adb`、`apksigner`、`aapt2`、`dexdump`） |
| Gradle | 9.5.0（用仓库自带 wrapper，不要另装） |
| 工程配置 | AGP 9.3.0 / Kotlin 2.2.10 / `compileSdk 37` / `minSdk 28` |

★ **不需要 NDK。** 上游要求 NDK 28.2.13676358，但本移植版已把原生库改成**预编译 `.so`**（见
`shared/src/main/jniLibs/`，随仓库提供），并移除了 `ndkBuild` / `ndkVersion` 配置——省掉几 GB 下载。

```sh
export JAVA_HOME=/path/to/jdk-25
export ANDROID_HOME=/path/to/android-sdk
java -version    # 输出必须显示 25
```

**国内网络**：`repo.maven.apache.org` / `dl.google.com` 可能不可达，需在 `settings.gradle.kts` 或
`init.gradle` 里配镜像，否则首次同步会长时间卡住。

---

## 2. 第一步：取 AOSP 默认 platform 密钥

### 为什么

本车机固件指纹带 **`test-keys`**，说明**厂商没有替换 AOSP 的默认 platform 密钥**。因此只要用同一把默认密钥
签名，证书就与车机 `framework-res` 完全一致，清单里保护级别含 `signature` 的权限会被自动授予。
**没有这条，本应用的车机交互能力（ECARX XUI、仪表投送、音频仲裁、蓝牙策略）全部失效。**

> ⚠️ 该密钥对**是公开的**，就在 AOSP 源码树里，任何人都能取得。所以「保密」在这里毫无意义；
> 但也因此：**只在你自己的车机上这么用**。

### 下载

AOSP 源码树中的位置：`build/target/product/security/platform.pk8`（私钥，PKCS#8 DER）与
`platform.x509.pem`（证书）。

```sh
# 规范来源（aosp-mirror 只读镜像）
curl -LO https://raw.githubusercontent.com/aosp-mirror/platform_build/master/target/product/security/platform.pk8
curl -LO https://raw.githubusercontent.com/aosp-mirror/platform_build/master/target/product/security/platform.x509.pem

# 也可直接从任意 AOSP 检出里复制：
#   build/target/product/security/platform.{pk8,x509.pem}
```

### 转成 Gradle 能用的 keystore

```sh
openssl pkcs8 -inform DER -nocrypt -in platform.pk8 -out platform.key.pem
openssl pkcs12 -export -in platform.x509.pem -inkey platform.key.pem \
        -name aosp-platform -out platform.p12 -passout pass:android
keytool -importkeystore -srckeystore platform.p12 -srcstoretype PKCS12 \
        -srcstorepass android -destkeystore platform.jks \
        -deststorepass android -destkeypass android -alias aosp-platform
```

★ **转换本身即校验**：`pkcs12 -export` 在公私钥不匹配时会直接报错。它成功，就说明 `pk8` 与 `pem` 确是同一对。
（`keytool` 可能警告 `MD5withRSA 已弃用`——AOSP 默认密钥年代久远，属正常，不影响签名。）

### 校验证书指纹

```sh
keytool -list -v -keystore platform.jks -storepass android | grep SHA256
```

应当看到（与本车机 `framework-res` 相同）：

```text
SHA256: C8:A2:E9:BC:CF:59:7C:2F:B6:DC:66:BE:E2:93:FC:13:F2:FC:47:EC:77:BC:6B:2B:0D:52:C1:1F:51:19:2A:B8
```

指纹对不上 ⇒ 车机固件换过密钥，本移植版这套权限方案**不适用于该车机**，不要继续。

---

## 3. 第二步：准备 MFi 运行时资产

### 为什么必需

CarPlay 需要一套 **MFi accessory 身份**才能与 iPhone 完成认证。构建时它被注入到 APK 的
`assets/offline-mfi/`，应用首次启动会把它从 assets 复制到私有目录使用。**缺少它，CarPlay 无法连接。**

### 目录结构

```text
runtime-assets/
└── offline-mfi/
    ├── identity.pk8      # PKCS#8 DER 私钥
    └── certificate.p7b   # Apple 签发的证书链（PKCS#7）
```

用 `DIPLAY_AUTH_ASSETS_DIR` 指向这个目录（**绝对路径**）。

### 四条获取路径

| 路径 | 说明 |
| --- | --- |
| **实体 MFi 芯片 + CH341 I2C 桥** | 上游作者的开源转接板：<https://github.com/shilapi/ch341-to-mfi-chip> |
| **板载 I2C 直连 MFi 芯片** | 直接把芯片接在车机的 `/dev/i2c-N` 上 |
| **Remote MFi 服务** | 把一台远程服务当作 MFi 芯片调用（上游 README 的 `Remote MFI` 一节） |
| **离线身份文件** | 即上面那两个文件。上游公开版 APK 内嵌的是一套**实验性身份，来源为公开的 Carlinkit 固件** |

> ⚠️ **关于「离线身份文件」**：它不是本项目新申请的身份，**也不保密**——任何拿到内嵌该身份的 APK 的人
> 都能把私钥提取出来（上游已在 README 中明确披露）。它不随本仓库分发，需要你自己准备。
> **一旦你用它构建并分发 APK，就等于公开分发这套身份**；且 Apple 有权吊销，
> **吊销后所有使用同一身份的设备（包括你自己的车）会同时失效。**

### 构建闸门（为什么缺文件会直接失败）

上游/本移植版的 Gradle 配置里有两道有意设下的闸：

1. `:mobile:assembleStandaloneDebug` 与 `:mobile:assembleRelease` 会检查
   `DIPLAY_AUTH_ASSETS_DIR` 下的两个文件是否存在且非空——**缺任何一个，任务直接失败**。
2. `rejectBundledCredentials` 白名单：APK 打包时会拒绝白名单之外的一切凭据容器
   （`*.pk8` / `*.p7b` / `*.key` / …），防止凭据被误塞进仓库或产物。

⇒ 这是刻意的设计，**不要为了绕开它去改 Gradle**。

---

## 4. 第三步：构建

```sh
export DIPLAY_AUTH_ASSETS_DIR=/absolute/path/to/runtime-assets

# 平台签名 release（推荐）
export ANDROID_KEYSTORE_PATH=/absolute/path/to/platform.jks
export ANDROID_KEYSTORE_PASSWORD=android
export ANDROID_KEY_ALIAS=aosp-platform
export ANDROID_KEY_PASSWORD=android
./gradlew :mobile:assembleRelease
# → mobile/build/outputs/apk/release/mobile-release.apk

# 或先做一个带复制的 debug 版做链路测试
./gradlew :mobile:assembleStandaloneDebug
# → mobile/build/outputs/apk/debug/mobile-debug.apk
```

- 应用包名：release = `com.shihab.diplay`；debug = `com.shihab.diplay.hudtest`（**两个是不同应用，可共存**）。
- 首次构建约 1.5–2.5 分钟（有缓存后更快）。
- ⚠️ **不要在 Gradle 运行期间修改源码**——Kotlin 增量缓存会被锁死，
  需 `./gradlew --stop` 并删除 `build/` 下的 `built_in_kotlinc` 才能恢复。
- ⚠️ **构建路径请用纯 ASCII**。包含中文的路径会让 `dexdump` / `apktool` **静默失败**
  （返回码 0 但没有输出），也会触发 AGP 的路径检查。

---

## 5. 第四步：校验产物

```sh
BT="$ANDROID_HOME/build-tools/36.0.0"

# ① 签名证书是不是那一把
"$BT/apksigner.bat" verify --print-certs mobile/build/outputs/apk/release/mobile-release.apk \
    | grep "SHA-256 digest"
#   期望 c8a2e9bc…92ab8

# ② 版本号 / 包名 / 权限声明
"$BT/aapt2.exe" dump badging mobile/build/outputs/apk/release/mobile-release.apk \
    | grep -E "^package|uses-permission.*(BLUETOOTH|car\.|ecarx|cevt)"

# ③ 身份文件是否真的进去了（两个文件都应在）
unzip -l mobile/build/outputs/apk/release/mobile-release.apk | grep -i offline-mfi

# ④ 确认产物里没有夹带私钥
unzip -l mobile/build/outputs/apk/release/mobile-release.apk | grep -iE "\.jks|\.keystore|\.p12|\.pem"
#   期望：无输出
```

**本移植版附带的自检脚本**（发布前请一并放入仓库，例如 `tools/verify-ecarx.py`）会一次性检查：
DEX 字符串池基线、类清单、资源项、权限声明、签名指纹、原生库溯源等约 43 项契约。建议与上面的手工校验并用。

---

## 6. 签名与权限的关系（原理）

背下来这三条，能避免绝大多数误判：

1. **安装校验的是 APK 内的证书链，不是私钥。** 私钥只在签名那一刻用一次，**从不随 APK 分发**。
   ⇒ 「别人没有签名私钥」不影响本项目的 APK 安装。
2. **平台级权限取决于「证书是否等于 `framework-res` 的证书」**，与密钥是否保密无关。
3. **换证书就不能覆盖安装。** 同一签名系列内可以覆盖升级（保留设置）；证书一变，
   系统视为另一个应用，必须先 `adb uninstall com.shihab.diplay`。

因此：用 AOSP 默认 platform 密钥签名后，产物**能装、权限齐全**；换任何其他密钥，
**能装、但平台级权限全无**。

---

## 7. 常见问题

| 现象 | 原因 / 处理 |
| --- | --- |
| 能装但车机交互功能全无 | 没用 platform 密钥签名 ⇒ 见第 2 节 |
| 能装但 CarPlay 连不上 | 缺 MFi 身份，或身份文件不对 ⇒ 见第 3 节 |
| `assembleStandaloneDebug` 报缺少认证文件 | `DIPLAY_AUTH_ASSETS_DIR` 路径不对，或两个文件缺失/为空 |
| 覆盖安装被拒（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`） | 证书变了 ⇒ 先 `adb uninstall com.shihab.diplay` |
| Gradle 报 Kotlin 编译错误但源码没问题 | 增量缓存损坏 ⇒ `./gradlew --stop` 后删 `build/` |
| `dexdump` / `apktool` 无输出 | 路径含中文 ⇒ 换纯 ASCII 路径 |
| Gradle 同步卡在下载 | 国内网络 ⇒ 配 Maven 镜像 |
| 装了 EasyPlay 又想装 DiPlay | **两者只能装一个**，先卸载另一个；强停会残留 USB 授权 |

---

## 8. 不要做的事

- ❌ **不要**把 `runtime-assets/`（MFi 身份）或任何签名 keystore 提交进 Git。
  仓库的 `.gitignore` 已硬排除 `*.jks` `*.keystore` `*.pk8` `*.p7b` `*.p12` `*.pem` `*.key` `*.apk` `*.aab`，
  但**未跟踪文件仍可能被 `git add -A` 带进来**——提交前请再确认一次。
- ❌ **不要**为了绕开凭据检查去改 `rejectBundledCredentials` 或 `assembleStandaloneDebug` 的闸门。
- ❌ **不要**把预编译 APK 附到源码仓库里当作「源码发布」——两者性质不同，说明也不同（见 README 第二节）。
- ❌ **不要**用这套密钥去签别人的设备要用的应用。
- ❌ **不要**在 Gradle 运行时改源码。

---

## 9. 交付前检查清单

- [ ] `apksigner verify --print-certs` 的 SHA-256 = `C8:A2:E9:BC:…:2A:B8`
- [ ] `unzip -l` 里 `assets/offline-mfi/` 两个文件都在
- [ ] `unzip -l` 里**没有** `.jks` / `.keystore` / `.p12` / `.pem`
- [ ] 版本号已递增（`versionCode` / `versionName`），且与上一个发布版本不同
- [ ] 与上一版做类清单对比：**新增类可以，删除类必须为 0**（否则是回归）
- [ ] `git status` 干净、`.gitattributes` 已钉死换行符
- [ ] 若发布预编译 APK：已在 Release 说明中**明确写出它内嵌了实验性 MFi 身份**
