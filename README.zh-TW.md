# DiPlay · 領克 01 CarPlay

[简体中文](README.zh-CN.md) · [繁體中文](README.zh-TW.md) · [English](README.md#english)

DiPlay 讓你在領克車機上使用 iPhone 的導航、音樂、Siri 和通話。適配**億咖通車機 753 版本**（領克高通 820A 平台），目前於 **2021 款領克 01** 驗證；同平台的 753 車機理論上通用。

每個功能的開啟位置、前提條件、如何確認生效，見 [功能與使用說明](docs/FEATURES.zh-CN.md)（簡體中文）。

| 功能 | 需要開啟的設定 |
| --- | --- |
| 儀表和 HUD 導航提示 | **設定 → 導航 → 億咖通導航**，開啟「儀表導航」 |
| 車道指引 | 同一頁面開啟「儀表車道指引」 |
| 歌曲資訊和封面 | 同一頁面開啟「儀表媒體卡片」 |
| 日夜模式跟隨車輛 | **設定 → 顯示 → 顯示方式與效能**，把「CarPlay 日夜模式」設為「跟隨車輛」 |
| 音樂走 CarPlay、保留藍牙電話 | **設定 → 音訊 → 藍牙音訊**，開啟「CarPlay 期間停用藍牙媒體」，關閉「透過車輛藍牙播放 CarPlay 音訊」 |
| 無線連接 | 在連接設定中選擇車機熱點、Wi-Fi Direct 或既有 Wi-Fi，依照提示完成設定 |
| 開機自動連接 | **設定 → 連接 → 自動連接**，開啟「車機啟動後開啟」和「開啟 DiPlay 時連線」 |
| 自訂方向盤按鍵 | **設定 → 車輛 → 方向盤按鍵**，啟用按鍵服務並學習需要的按鍵 |

**使用 HY 時，請關閉 HY 的方控接管。**

**DEV 的 Siri 短按功能**：連接後自動生效，無需額外開關。此功能仍在實車驗證中。

只顯示導航提示和歌曲資訊時，保持「整屏鏡像到儀表」關閉。修改設定後，依照畫面提示重新連接。

目前 USB 連接仍有問題；拔掉 USB 後會自動回到無線。遇到異常，請到 **設定 → 診斷 → 儲存診斷報告**。

基於 [DiPlay](https://github.com/shihabal3amri/DiPlay)、[DiPlay-ECARX](https://github.com/jiopia/DiPlay-ECARX) 和 [xcertplay](https://github.com/shilapi/xcertplay)，部分介面改編自 [DiAuto](https://github.com/shihabal3amri/DiAuto)。許可與第三方聲明見 [LICENSE](LICENSE) 及 [相關說明](docs/THIRD_PARTY_NOTICES.md)。

CarPlay 及相關標識屬於 Apple；本專案與 Apple、領克、億咖通無隸屬或背書關係。
