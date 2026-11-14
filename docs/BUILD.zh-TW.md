# 從原始碼建置 DiPlay

[繁體中文首頁](../README.zh-TW.md) · [英文建置指南](BUILD.md) · [Android CI](../.github/workflows/android.yml)

以下指令從專案根目錄執行，也就是包含 `gradlew` 與 `settings.gradle.kts` 的目錄。環境版本以本分支的建置設定及 CI 為準。

## 選擇應用程式

| Gradle 模組 | 用途 | Debug APK |
| --- | --- | --- |
| `:mobile` | 車機使用的主要 DiPlay 應用程式 | `mobile/build/outputs/apk/debug/DiPlay-v<versionCode>-<abi>-debug.apk` |
| `:home` | 選用的 DiPlay Home 啟動器 | `samples/home/build/outputs/apk/debug/home-debug.apk` |
| `:maphost` | 接收 DiPlay 地圖的範例應用程式 | `samples/maphost/build/outputs/apk/debug/maphost-debug.apk` |
| `:siribridge` | 方向盤語音鍵到 Siri 的橋接 | `integrations/siri/build/outputs/apk/debug/siribridge-debug.apk` |

`:common` 與 `:shared` 是共用函式庫。要安裝主程式，請選 **mobile**；如果裝到「DiPlay map host」，表示選錯模組或 APK。單獨執行 `assembleDebug` 可能建置多個應用程式。

主程式的 Debug／Release 都按 ABI 分包，每個 APK 只含一種 ABI。車機與本輪小米 8 驗證優先使用 `arm64-v8a`；`armeabi-v7a` 是備援，`x86_64` 僅供模擬器測試。

## 準備環境

- JDK 25。
- Android SDK Platform 37（`platforms;android-37.0`）。
- Android SDK Build-Tools 36.0.0。
- Android SDK Platform-Tools。
- Android NDK 28.2.13676358。
- Rust 1.97.1、Python 3，以及 bundled libopus／host JNI 測試使用的 CMake 與主機 C/C++ 建置工具。

使用專案附帶的 Gradle wrapper，首次建置需要網路下載相依套件。在 Android Studio 開啟根目錄，以 **Tools → SDK Manager** 安裝上述 SDK／NDK，將 **Gradle JDK** 設為 JDK 25，再等待同步。

### 準備 Rust Android 產物

主程式與 `shared` 打包前需要目前原始碼對應的三 ABI 原生程式庫與 `provenance.json`。在 Linux／macOS 執行：

```sh
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
scripts/check_rust_android_native.sh
```

預設輸出為 `.native/rust-android/`；Gradle 先核對來源與檔案雜湊，再打包，不會回退到檢入的舊 Rust 二進位檔。原生建置腳本使用 Linux／macOS 的 NDK 主機目錄；Windows 可使用符合目前原始碼的 CI 三 ABI 程式庫及 receipt，或在 Linux／macOS 建置後，將 `DIPLAY_JNI_LIBS_DIR` 設為該產物目錄的絕對路徑。詳細契約見[英文指南](BUILD.md#prepare-rust-android-inputs)。

### Windows PowerShell

將範例路徑換成實際安裝目錄；`JAVA_HOME` 指向 JDK 根目錄，不是 `bin`。

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'
$env:ANDROID_HOME = 'C:\Users\YOUR_NAME\AppData\Local\Android\Sdk'
& "$env:JAVA_HOME\bin\java.exe" -version
.\gradlew.bat :mobile:assembleDebug
```

也可在根目錄建立不提交到 Git 的 `local.properties`：

```properties
sdk.dir=C:/Users/YOUR_NAME/AppData/Local/Android/Sdk
```

### Linux／macOS 終端機

設定 `JAVA_HOME` 為 JDK 25、`ANDROID_HOME` 為 SDK 目錄，確認 `java -version` 顯示 Java 25，再執行：

```sh
./gradlew :mobile:assembleDebug
```

一般原始碼 APK 不包含配件認證身分，除非自行提供執行時認證素材；因此它可用於介面驗證，但獨立 CarPlay 連線需要下節素材。Debug 套件名稱為 `com.shihab.diplay.hudtest`，正式版為 `com.shihab.diplay`。

## Android Studio 執行

在 **Run → Edit Configurations** 選擇或建立 Android App 設定，將 Module 設為 **mobile**，選取 Android 裝置後執行。只要 APK 而不安裝時，在終端機執行 `:mobile:assembleDebug`，輸出見上表。

## 完整檢查

目前的完整 Gradle 指令見 [AGENTS.md](../AGENTS.md#checks)，CI 額外的 Rust 單元測試、三 ABI 驗證與四應用程式 Release R8 檢查見 [Android workflow](../.github/workflows/android.yml)。Rust 單元測試入口為：

```sh
cargo test --locked --lib --manifest-path shared/src/main/rust/diplay-media/Cargo.toml
```

Windows 將 Gradle 指令的 `./gradlew` 換成 `.\gradlew.bat`。確認結果為 `BUILD SUCCESSFUL`。JVM 測試會另建置並載入 host JNI，不需要 Android 原生產物。CI 另執行 `python3 scripts/check_public_tree.py`，避免認證容器或私密金鑰進入公開原始碼。裝置與實車結果另外登記於[驗收矩陣](RUST_CORE_REMAINING.zh-CN.md)。

USB 權限測試需要 POSIX shell。Linux／macOS 使用 `sh`；Windows 會自動使用標準安裝位置的 Git for Windows `bin/sh.exe`。若安裝在其他位置，可在執行測試前指定：

```powershell
$env:DIPLAY_TEST_SHELL = 'C:\path\to\Git\bin\sh.exe'
```

測試透過標準輸入傳送腳本，避免 Windows 命令列改寫引號。檔案替換及 FileProvider 的測試相容層會保留 Android 的行為，包含拒絕設定範圍外的檔案。

## 獨立實車測試 APK

在 Git 之外準備只包含指定執行時素材的目錄：

```text
runtime-assets/
└── offline-mfi/
    ├── identity.pk8
    └── certificate.p7b
```

不要將素材或私密金鑰提交到 Git。測試會在執行時產生合成身分，不需要把測試私密金鑰放入原始碼。

Windows PowerShell：

```powershell
$env:DIPLAY_AUTH_ASSETS_DIR = 'C:\path\to\runtime-assets'
.\gradlew.bat :mobile:assembleStandaloneDebug
```

Linux／macOS：

```sh
DIPLAY_AUTH_ASSETS_DIR=/absolute/path/to/runtime-assets ./gradlew :mobile:assembleStandaloneDebug
```

任一必要檔案缺少或為空時，任務會停止；建置也會拒絕非預期的憑證容器。ARM64 輸出為 `mobile/build/outputs/apk/debug/DiPlay-v<versionCode>-arm64-v8a-debug.apk`，另有 ARMv7 與 x86_64 分包。請確認 APK 中的 `assets/offline-mfi/identity.pk8` 與 `assets/offline-mfi/certificate.p7b` 符合選定素材。使用相同簽章更新既有測試程式，才能保留設定。

## 正式版建置

公開 Release 不內嵌 MFi 身分，請取消設定 `DIPLAY_AUTH_ASSETS_DIR`，並設定以下簽章環境變數：

| 變數 | 內容 |
| --- | --- |
| `ANDROID_KEYSTORE_PATH` | Android 簽章 keystore 路徑 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密碼 |
| `ANDROID_KEY_ALIAS` | 簽章金鑰別名 |
| `ANDROID_KEY_PASSWORD` | 簽章金鑰密碼 |

將 keystore 與密碼留在 Git 之外。Windows 執行：

```powershell
.\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintRelease :mobile:assembleRelease
```

Linux／macOS 使用 `./gradlew`。ARM64 輸出為 `mobile/build/outputs/apk/release/DiPlay-v<versionCode>-arm64-v8a-release.apk`，另有 ARMv7 備援與 x86_64 測試分包。只有明確設定 `DIPLAY_AUTH_ASSETS_DIR` 的本機車測建置才會加入外部配件身分；接收者可從 APK 擷取該身分。公開 Actions Release 不注入 MFi 身分，APK 也不含 Android 簽章私密金鑰。詳見[第三方聲明](THIRD_PARTY_NOTICES.md)。

[Release workflow](../.github/workflows/release.yml) 由已發布的 GitHub Release 或手動派送觸發，重建並驗證原生程式庫、執行主程式 Release lint 與簽章建置；它不重跑 Rust／JVM 測試，也不等待另一個 Android checks workflow。發布附件與 `update.json` 的預設 APK 為 ARM64，裝置更新依自己的 ABI 選包；手動派送只上傳 Actions artifact。

APK 的 `assets/licenses/` 包含 GPL、AGPL、元件授權全文、版權聲明及原始碼取得方式，可在「關於 → 授權與原始碼」離線閱讀。Actions 發布附件同時提供 `DiPlay-licenses.zip`、`DiPlay-source.tar.gz`、`SOURCE-REVISION.txt` 及 `SHA256SUMS.txt`；原始碼壓縮檔與 APK 使用同一提交。自行修改的版本須另提供實際使用的修改後原始碼。保留的 C 預編譯程式庫仍有[原始碼對應限制](../shared/src/main/jniLibs/NOTICE.md)，不能以附上倉庫壓縮檔取代此項驗證。

公開原始碼壓縮檔應對應 release tag，排除認證身分、簽章金鑰、本機設定及建置輸出。不要使用已退役的 `build-beta.py` 作為建置流程。

## 建置問題

| 問題 | 處理方式 |
| --- | --- |
| 找不到 SDK | 設定 `ANDROID_HOME` 或 `local.properties` 的 `sdk.dir`。 |
| 缺少 SDK／NDK | 在 SDK Manager 安裝上列指定版本。 |
| Java 版本不符 | 將終端機與 Android Studio 的 Gradle JDK 設為 JDK 25。 |
| APK 無法獨立啟動 CarPlay | 提供外部認證素材並使用 `assembleStandaloneDebug`。 |
| Standalone 任務找不到認證檔案 | 檢查外部目錄的絕對路徑及兩個必要檔案。 |
| Android 拒絕覆蓋安裝 | 確認應用程式套件名稱與簽章相同。 |
