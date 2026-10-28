# DiPlay · 領克 01（2021 款）/ ECARX

本 fork 目前主要適配 **2021 款領克 01**。完整說明見 [README.md](README.md)，
其中保留原 fork 的領克 05 歷史紀錄；歷史紀錄不等於本版本已在領克 01 全面驗收。

- **分支**：`release` 為預設，收納已確認功能；`dev` 用於待驗證改動與 Debug；`main` 保留原 fork 基線。
- **報告環境**：Android 9 / API 28、Qualcomm MSM8996、`dcy11_a1`；報告未綁定 APK SHA，不能證明目前建置已在該車驗收。
- **已確認**：封面圖片、導航車道經使用者實車確認 OK；可視區域「看起來 OK」，不涵蓋全部顯示情境。
  USB 連接手機已獲使用者「測試 OK」回饋並獨立進入 release，但未說明測試裝置、平台或版本。
- **仍待解決/確認**：HiCar 同時喚醒、Siri 有效收音、A2DP 搶音樂輸出、iPhone 藍牙關開後的自動重連、OEM 熱點控制。
- **介面**：僅英文、簡體中文、繁體中文，保留跟隨系統。

[建置環境與命令](README.md#从源码构建)：JDK 25、Gradle wrapper 9.5.0、SDK 37.0、Build Tools 36.0.0；
應用程式最低 API 24，與報告中的車機 API 28 是不同概念。CI 另配 NDK，使用預編譯原生庫的建置不一概要求安裝它。
**source-only 套件不含 MFi 身分**，缺少合法認證前置時會停在設定/身分檢查，不能僅憑安裝或 OTG 枚舉成功宣稱 CarPlay 已通。

[目前驗證說明](README.md#当前适配与验证) · [逐功能證據](docs/LYNK_01_VERIFIED_BRANCH.md) ·
[原上游繁體說明（歷史存檔）](docs/UPSTREAM-DIPLAY-README.zh-TW.md)
