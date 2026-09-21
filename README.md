# Velotune 🚗 🔊

> **Zero-Touch Automotive Volume Controller for Android**  
> 專為汽機車用車環境設計的 GPS 速度自適應音量調節工具，具備專業級音訊避讓、抗噪濾波與車載橫屏 HUD 儀表。

[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Language-Kotlin-blue.svg)](https://kotlinlang.org)
[![Architecture](https://img.shields.io/badge/Architecture-ForegroundService%20%7C%20Coroutines-orange.svg)]()
[![License](https://img.shields.io/badge/License-MIT-lightgrey.svg)](LICENSE)

---

## 📖 專案簡介 (Overview)

在汽機車駕駛或騎行過程中，環境風切聲與胎噪會隨車速劇烈變化，傳統手動微調音量容易造成行車分心。**Velotune** 是一款強調「零接觸（Zero-Touch）」的自動調音工具，透過高精度 GPS 速度追蹤與自訂 2D 曲線，實現全自動、無感平滑的音量過渡。

本專案特別針對真實用車工況優化，內建**導航語音避讓（Audio Ducking 防衝突）**、**隧道看門狗（GPS Watchdog）**、**藍牙連線堆疊識別（LIFO）**與**車載雙欄橫屏 HUD**，提供原廠頂級車載系統般的穩定度與細膩感。

---

## ✨ 核心特性 (Key Features)

### 1. 專業調音與訊號處理 (DSP & Motion Filtering)
* **2D 互動式曲線編輯器**：支援任意新增、刪除錨點，直覺拖曳定義速度與音量映射曲線。
* **無感音量漸變平滑器 (Volume Smoother)**：以微步插值消除跳階突兀感，即使急加速或緊急減速也不震耳。
* **複合速度抗噪濾波 (SpeedFilter)**：
  * **靜止抗飄截斷**：車速小於 $1.5 \text{ km/h}$ 自動鎖零，杜絕停等紅燈時的 GPS 噪訊漂移。
  * **自適應雙速率 EMA**：勻速巡航時超強平滑，大腳油門時敏捷跟隨。
  * **滯後死區 (Deadband Hysteresis)**：速度小幅晃動時鎖定輸出，避免音量神經質微調。

### 2. 行車情境與音訊避讓 (Audio & Navigation Protection)
* **導航語音避讓 (Audio Ducking Detector)**：整合系統級 `AudioPlaybackConfiguration`，當 Google Maps、導航王或通話語音播報時，**瞬間凍結調音**；播報結束後經防抖冷卻再平滑過渡，徹底杜絕音量暴衝互搶。
* **隧道/地下道保護 (GPS Watchdog)**：超過 4.5 秒收不到 GPS 點位時，自動判定為斷訊並執行安全處置（維持最後音量 / 降至安全音量 / 暫停控制）。
* **視覺化靜音倒數 (Visual Mute Countdown)**：靜音按鈕整合動態 `ClipDrawable` 紅色進度條，超時計時一目了然，並支援起步加速（超過門檻時速）自動恢復調音。

### 3. 自動化與多設備管理 (Automation & Bluetooth)
* **LIFO 藍牙連線堆疊**：後連裝置優先套用，斷線後自動回退至前一設備或唯一標記的 ⭐ 預設基準檔。
* **冷啟動預先識別**：App 開啟瞬間主動輪詢系統 A2DP / Headset 已連線裝置，無需等待重連廣播。
* **隨多工終止 (Clean Exit)**：從最近任務列表劃掉卡片時徹底關閉前台服務，不殘留後台進程與通知欄圖標。

### 4. 響應式車載儀表 (Automotive Dual-Pane HUD)
* **橫屏 HUD 模式 (Landscape)**：
  * **左側視覺監控 (55%)**：超大字體即時時速、動態音量數值與全高橫向延展的 2D 動態曲線。
  * **右側控制清單 (45%)**：獨立滾動的設定檔切換、四鍵控制台與錨點微調面板。
* **駕駛防休眠**：開啟畫面時自動保持螢幕常亮（`FLAG_KEEP_SCREEN_ON`）。

---

## 🛠️ 技術架構 (Architecture & Tech Stack)

* **開發語言**：Kotlin
* **非同步處理**：Kotlin Coroutines & StateFlow / Flow
* **系統服務**：
  * `AutoVolumeService`：以 `ForegroundService` 運行，設定為 `START_NOT_STICKY` 與 `stopWithTask="true"`。
  * `LocationProvider`：GPS / Fused Location 速度流採集。
  * `AudioManager`：串流音量調控與 `AudioPlaybackCallback` 語音會話監控。
  * `BluetoothTracker`：`BluetoothAdapter.getProfileProxy` 與連線事件廣播堆疊。
* **資料持久化**：本機 JSON 儲存（支援設定檔匯入/匯出備份）。

---

## 📲 權限說明 (Permissions)

| 權限名稱 | 用途說明 |
| :--- | :--- |
| `ACCESS_FINE_LOCATION` | 取得高精度 GPS 即時車速 |
| `FOREGROUND_SERVICE_LOCATION` | 支援背景持續追蹤速度與調音（Android 14+） |
| `POST_NOTIFICATIONS` | 顯示常駐控制通知面板（Android 13+） |
| `BLUETOOTH_CONNECT` | 讀取已連線藍牙裝置並實現自動設定檔切換（Android 12+） |

---

## 🚀 快速開始 (Getting Started)

### 系統需求
* Android 8.0 (API Level 26) 或更高版本
* 具備 GPS 模組的 Android 裝置
* Android Studio Iguana / Jellyfish 或更新版本

### 編譯與安裝
1. 複製專案庫到本地：
   ```bash
   git clone [https://github.com/williegoodjob/Velotune.git](https://github.com/williegoodjob/Velotune.git)
   ```
2. 使用 Android Studio 開啟專案根目錄。
3. 等待 Gradle Sync 完成。
4. 連接實體 Android 裝置（建議使用真機以獲得正確的 GPS 與藍牙硬體行為）。
5. 點擊 Run 'app' 進行編譯與安裝。

📄 設定檔範例 (Profile Format)
設定檔以 JSON 格式儲存於應用程式私有目錄，支援隨時匯出與匯入：

```JSON
[
  {
    "id": "default_standard",
    "name": "預設設定檔",
    "isDefaultStar": true,
    "boundBtAddress": "00:11:22:33:44:55",
    "boundBtName": "Helmet-Intercom-B1",
    "speedOffsetKmh": 0.0,
    "points": [
      { "speed": 0.0, "volume": 0.20 },
      { "speed": 30.0, "volume": 0.45 },
      { "speed": 60.0, "volume": 0.70 },
      { "speed": 90.0, "volume": 1.00 }
    ]
  }
]
```
🛡️ 開源協議 (License)
本專案基於 MIT License 條款開源發布。
