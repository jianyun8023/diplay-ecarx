# 构建 DiPlay 领克 01 / ECARX 753 移植版

> 通用流程（模块选择、Android Studio 用法、单元测试与 lint）见上游文档 [`docs/BUILD.md`](docs/BUILD.md)。
> **本文只讲本移植版特有的那几件事**——尤其是签名与 MFi 身份，这两件决定了产物能不能用。
> Gradle 命令在仓库根目录（有 `gradlew` 与 `settings.gradle.kts` 的那层）执行；凭据下载、转换与证书核对在仓库外的同一目录完成。

---

## 0. 先读：三个输入决定你能不能跑起来

| 输入 | 必需？ | 不给会怎样 |
| --- | --- | --- |
| **Android 签名密钥** | Release 签名必需；车机平台权限需匹配该车机平台证书 | 普通 Debug 可用默认调试签名构建，但不具备相同平台签名的权限 |
| **MFi 运行时认证后端** | 独立 CarPlay 连接必需，源码编译和单测不需要 | 公开 APK 默认不内置身份；新安装需另行配置认证身份或支持的硬件认证后端 |
| 平台工具链（JDK / SDK / Build-Tools） | 必需 | 编不过 |

**本仓库（源码）不含上面任何一项。** 三者都要你自己准备，且**都不能提交进 Git**。

ECARX 接口通过运行时反射和动态代理调用。编译和单测**不需要厂商 JAR、私有 Maven 仓库或额外 SDK 下载**；车机提供运行实现。未使用的 GKUI Policy 恢复声明模块已删除。见 [SDK 说明](docs/ECARX_SDK_CONTEXT.zh-CN.md)。

---

## 1. 工具链（实测通过的版本）

| 组件 | 版本 |
| --- | --- |
| JDK | **Temurin 25.0.4.1+1**（必须 25） |
| Android SDK Platform | `platforms;android-37.0` |
| Android SDK Build-Tools | **36.0.0** |
| Android SDK Platform-Tools | 含开发端 `adb`；`apksigner`、`aapt2`、`dexdump` 位于 Build-Tools |
| Gradle | 9.5.0（用仓库自带 wrapper，不要另装） |
| 工程配置 | AGP 9.3.0（内置 Kotlin）/ `compileSdk 37` / `minSdk 24`；生产目标仍为 Android 9 / API 28 |

**Rust 核心必须从当前源码构建。** 需要 NDK **28.2.13676358**、Rust **1.97.1**、
Python 3、可调用的 CMake 与主机 C/C++ 构建工具（bundled libopus／host JNI 测试使用），以及 `aarch64-linux-android`、`armv7-linux-androideabi`、`x86_64-linux-android`
三个 Rust target。C JNI 库仍使用 `shared/src/main/jniLibs/` 中的预编译文件；
其中历史 `libdiplay_media.so` 不再参与任何 Gradle APK/AAR 打包。

在运行下面的 Gradle APK 构建命令之前，执行：

```sh
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
scripts/check_rust_android_native.sh
```

该脚本构建并检查三个 ABI 的 ELF/JNI 导出，默认输出到 `.native/rust-android/`。
Gradle 的所有 variant 只使用经验证的生成 JNI 目录，不会静默回退到旧 Rust 二进制。
每个 ABI 的 `provenance.json` 绑定 Rust 源码、Cargo.lock、工具链/构建脚本和库文件的 SHA-256；
缺少任一 ABI、来源记录、源码变更或二进制不匹配都会在打包前失败，并提示重新构建。
这也适用于未提交的修改和没有 `.git` 的源码压缩包，不依赖提交号或时间戳。
记录用于发现意外陈旧/混用产物，不是抵御恶意篡改的签名。

CI 下载产物或自定义本地产物目录时，对构建脚本和 Gradle 设置同一个绝对路径：
`export DIPLAY_JNI_LIBS_DIR=/absolute/path/to/rust-android-libs`。
原生 Android 构建脚本使用 Linux／macOS 的 NDK 主机目录；Windows Gradle 构建可使用与当前源码匹配的 CI 三 ABI 库及 receipt，或在 Linux／macOS 构建后设置此目录。
单 ABI 检查（如 `scripts/check_rust_android_native.sh arm64-v8a`）不能替代完整 APK 打包需要的三个 ABI；
其他 ABI 必须已有与当前源码匹配的产物。普通 JVM 单元测试不要求 Android Rust 产物。
`gradlew clean` 清理生成的打包目录，但保留 `.native/` 供下次验证复用。

回归测试：`python3 -m unittest discover -s scripts -p 'test_rust_android_inputs.py'`。

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

目标车机的平台证书已与下面给出的 AOSP 默认 platform 证书指纹核对。`test-keys` 字样本身不足以证明平台签名相同；应比较该车机的实际证书。
只有证书匹配，才满足相应 `signature` 权限的签名条件；其他权限、服务及硬件是否可用仍需在应用诊断中核对。

> ⚠️ 该密钥对**是公开的**，就在 AOSP 源码树里，任何人都能取得。所以「保密」在这里毫无意义；
> 但也因此：**只在你自己的车机上这么用**。

### 下载

AOSP 源码树中的位置：`build/target/product/security/platform.pk8`（私钥，PKCS#8 DER）与
`platform.x509.pem`（证书）。

以下下载、转换和指纹核对命令在仓库外执行，完成后回到仓库根目录运行 Gradle。

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

CarPlay 需要可用的认证后端。下面是可选的离线身份输入：只有显式设置 `DIPLAY_AUTH_ASSETS_DIR` 时，构建才把它加入 APK 的 `assets/offline-mfi/`。**普通源码构建及当前公开 Actions Release 均不注入这些文件。**

身份只在首次启动时从 APK 复制到应用私有目录 `no_backup/offline-mfi/`，之后一律从磁盘读取。所以同包名、同签名的公开 Release 覆盖升级会保留它，见下文「首装与升级」。全新安装或清除应用数据后，需要重新首装带身份的本地包，或在「设置 › 高级 › MFi 证书与签名目标」中导入 `identity.pk8` 与 `certificate.p7b`（应用会先校验私钥与证书匹配，再原子替换）。身份和签名密钥应位于仓库外，并由提供者核对其使用、再分发权限。

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
| **离线身份文件** | 即上面那两个文件。上游部分历史实验包曾使用公开 Carlinkit 固件中恢复的身份；当前本仓库公开 APK 不内置它们 |

> **本地身份输入的边界**：确认所提供文件的来源与使用权限。任何拿到内置身份的 APK 的人都能提取其私钥；分享这样的 APK 同时也会分享身份。上游历史实验身份的来源说明不代表所有本地输入均来自该固件，也不授予再分发许可。

### 构建检查

当前 `mobile/build.gradle.kts` 的行为是：

1. `:mobile:assembleStandaloneDebug` 与 `:mobile:assembleStandaloneRelease` 要求显式设置 `DIPLAY_AUTH_ASSETS_DIR`，并检查两个身份文件存在且非空。
   `:mobile:assembleDebug` 和 `:mobile:assembleRelease` 不要求身份输入；Release 仍需要 Android 签名配置。
2. `rejectBundledCredentials` 白名单：APK 打包时会拒绝白名单之外的一切凭据容器
   （`*.pk8` / `*.p7b` / `*.key` / …），防止凭据被误塞进仓库或产物。

普通源码构建不需要绕过这些检查；保持身份环境变量未设置即可。

---

## 4. 第三步：构建

```sh
# 公开 Release / 源码构建不内置认证身份
unset DIPLAY_AUTH_ASSETS_DIR

# Release：使用与已安装车测包一致的平台签名
export ANDROID_KEYSTORE_PATH=/absolute/path/to/platform.jks
export ANDROID_KEYSTORE_PASSWORD=android
export ANDROID_KEY_ALIAS=aosp-platform
export ANDROID_KEY_PASSWORD=android
./gradlew :mobile:assembleRelease
# → mobile/build/outputs/apk/release/DiPlay-v<versionCode>-<abi>-release.apk（每个 ABI 一个：arm64-v8a / armeabi-v7a / x86_64）

# 普通源码 Debug
./gradlew :mobile:assembleDebug
# → mobile/build/outputs/apk/debug/DiPlay-v<versionCode>-<abi>-debug.apk（每个 ABI 一个）

# 仅在本地独立车测时显式注入身份
DIPLAY_AUTH_ASSETS_DIR=/absolute/path/to/runtime-assets ./gradlew :mobile:assembleStandaloneDebug
# → mobile/build/outputs/apk/debug/DiPlay-v<versionCode>-<abi>-debug.apk（每个 ABI 一个）

# 首装包：Release 包名 + 身份，之后由 GitHub 公开 Release 覆盖升级（签名变量同上）
DIPLAY_AUTH_ASSETS_DIR=/absolute/path/to/runtime-assets ./gradlew :mobile:assembleStandaloneRelease
# → mobile/build/outputs/apk/release/DiPlay-v<versionCode>-<abi>-release.apk（含身份，不要分享）
```

Release 默认开启 R8 代码与资源优化，主应用保留名称和源码调试信息供诊断使用。
构建后保存对应的 `mobile/build/outputs/mapping/release/mapping.txt`；内联、类合并后的堆栈仍需它还原。
GitHub Release 附带 `DiPlay-v<versionCode>-mapping.txt`，并将它纳入 `SHA256SUMS.txt`，详见 [构建说明](docs/BUILD.md#build-a-release-apk)。

### 首装与升级

1. 用 `assembleStandaloneRelease` 打首装包，覆盖安装到车机，**打开一次 DiPlay**，身份才写入 `no_backup/offline-mfi/`。
2. 此后在「设置 › 关于」检查更新，或手动安装 GitHub Release 中对应本机 ABI 的 `DiPlay-v<versionCode>-<abi>-release.apk`。公开包不含身份，沿用磁盘上的那份。
3. 应用内更新只对 Release 包名 `com.shihab.diplay` 开启，读取 `jianyun8023/diplay-ecarx` 最新 Release 的 `update.json`（CI 生成，含 tag、各 ABI 的 APK 名与 Release 正文作为更新说明；按本机 ABI 选对应平台的包）。Release tag 必须是 `v<versionCode>`（CI 会校验），比安装的 versionCode 大才算新版本。发布后再改 Release 正文不会更新 `update.json`，需重新运行发布任务。国内网络可在「设置 › 关于 › 下载来源」选加速站或自动切换。
4. 首装包的 versionCode 不能高于随后要装的公开 Release，否则 Android 拒绝降级。
5. 不要卸载或清除数据；身份丢失时用设置里的导入功能恢复。

- 应用包名：release = `com.shihab.diplay`；debug = `com.shihab.diplay.hudtest`（**两个是不同应用，可共存**）。
- 首次构建约 1.5–2.5 分钟（有缓存后更快）。
- ⚠️ **不要在 Gradle 运行期间修改源码**——Kotlin 增量缓存会被锁死，
  需 `./gradlew --stop` 并删除 `build/` 下的 `built_in_kotlinc` 才能恢复。
- ⚠️ **构建路径请用纯 ASCII**。包含中文的路径会让 `dexdump` / `apktool` **静默失败**
  （返回码 0 但没有输出），也会触发 AGP 的路径检查。

---

## 5. 第四步：校验产物

先选定一个具体 APK，替换下面的版本号；本轮车机／小米 8 验证优先使用 ARM64。
以下为 Linux／macOS 命令；Windows 的 Build-Tools 可执行文件分别是 `apksigner.bat` 和 `aapt2.exe`。

```sh
BT="$ANDROID_HOME/build-tools/36.0.0"
APK="mobile/build/outputs/apk/release/DiPlay-v<versionCode>-arm64-v8a-release.apk"

# ① 签名证书是不是那一把
"$BT/apksigner" verify --print-certs "$APK" \
    | grep "SHA-256 digest"
#   期望 c8a2e9bc…92ab8

# ② 版本号 / 包名 / 权限声明
"$BT/aapt2" dump badging "$APK" \
    | grep -E "^package|uses-permission.*(BLUETOOTH|car\.|ecarx|cevt)"

# ③ 公开源码构建应无身份文件；显式本地车测包才应包含所选两个文件
unzip -l "$APK" | grep -i offline-mfi

# ④ 确认产物里没有夹带私钥
unzip -l "$APK" | grep -iE "\.jks|\.keystore|\.p12|\.pem"
#   期望：无输出
```

发布前建议把上面四条跑一遍。项目开发中还用了两个不随仓库分发的自检脚本（DEX 字符串池基线、
类集合差异），它们依赖本机的真机转储与历史 APK，因此没有放进仓库；正常构建不需要它们。

---

## 6. 签名与权限的关系（原理）

背下来这三条，能避免绝大多数误判：

1. **安装校验的是 APK 内的证书链，不是私钥。** 私钥只在签名那一刻用一次，**从不随 APK 分发**。
   ⇒ 「别人没有签名私钥」不影响本项目的 APK 安装。
2. **平台级权限取决于「证书是否等于 `framework-res` 的证书」**，与密钥是否保密无关。
3. **换证书就不能覆盖安装。** 同一包名应继续使用原签名以保留设置和私有认证数据；签名不同的包不能直接覆盖。不要为解决签名不匹配而先卸载现有应用。

因此，应以本车实际平台证书与应用诊断中的授权状态为准，保持升级时签名一致。签名匹配不能证明某个厂商服务或硬件接口已经实现。

---

## 7. 常见问题

| 现象 | 原因 / 处理 |
| --- | --- |
| 能装但车机交互功能全无 | 没用 platform 密钥签名 ⇒ 见第 2 节 |
| 能装但 CarPlay 连不上 | 缺 MFi 身份，或身份文件不对 ⇒ 见第 3 节 |
| `assembleStandaloneDebug` 报缺少认证文件 | `DIPLAY_AUTH_ASSETS_DIR` 路径不对，或两个文件缺失/为空 |
| 覆盖安装被拒（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`） | 使用与现有应用一致的签名重新构建，保留已安装应用和数据 |
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
- [ ] 公开 Release 的 `unzip -l` 中没有 `assets/offline-mfi/` 或任何认证身份；显式本地车测包的两个文件与外部输入一致
- [ ] `unzip -l` 里**没有** `.jks` / `.keystore` / `.p12` / `.pem`
- [ ] 版本号已递增（`versionCode` / `versionName`），且与上一个发布版本不同
- [ ] 复核类型增删符合本次改动；不打包厂商实现或测试夹具
- [ ] `git status` 干净、`.gitattributes` 已钉死换行符
- [ ] Release 说明准确声明实际认证输入；当前公开构建不内置 MFi 身份
- [ ] 随二进制提供源码归档、GPL/AGPL 许可全文及适用第三方声明；APK 已打包 `assets/licenses/`，可从「关于 → 许可证与源码」离线查看。Actions 发布附件包含 `DiPlay-licenses.zip`、`DiPlay-source.tar.gz`、`SOURCE-REVISION.txt` 与校验和；本地修改包还须提供实际使用的修改后源码，见 [第三方声明](docs/THIRD_PARTY_NOTICES.md)
