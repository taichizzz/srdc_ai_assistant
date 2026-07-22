# AI 車載 IVI「IVI AI 助理」智慧語音操控系統 — 三週實習計劃表

> **這份 = 「誰 / 何時」**：排程、里程碑日期、分工（團隊內部用，中文）。**進度狀態以本文件為準。**
> **相關文件**：[PROJECT.md](PROJECT.md) — 願景與背景（英）· [ARCHITECTURE.md](ARCHITECTURE.md) — 工程契約，寫程式前先讀（英）

> **核心理念**：坐在副駕、看著螢幕、幫你動手的無形助理 — **聽 (Listen) → 看 (See) → 動手 (Act)**。
> **技術突破點**：拋棄過去 FoxMap 為每個 App 寫死 API 的盲操作，改用 **Android Accessibility Service** 建立通用操作橋樑 — 讀 UI 樹（眼）、代替點擊（手）。
> **成功標準**：跑通完整閉環。進度卡關時果斷砍功能，保住核心主線。

## 0. 專案速覽

| 項目         | 內容                                                                                                                         |
| ------------ | ---------------------------------------------------------------------------------------------------------------------------- |
| 核心載體     | 一支跑在 AAOS 的原生 Android App（主邏輯 / Pipeline 協調）                                                                   |
| 雲端輔助     | STT / LM（主要語意理解）/ TTS，三者獨立服務、App 各自串接。**執行與授權在端上**：LM 只回傳高階目標，選元素、動作、驗證都在 App |
| 眼與手       | 同一支 App 內的 Android Accessibility Service：讀 UI 樹（眼）、代替點擊（手）                                                |
| 核心閉環     | 指令 → LM 解析成高階目標 → `getRootInActiveWindow()` 讀畫面 → App 在畫面上定位元素並驗證 → `performAction(ACTION_CLICK)` → 回讀確認 → TTS 播報（LM 不可用時退回文字比對） |
| **保命主線** | **M1.3 → M2.3 → M3.2**（進度吃緊時，果斷放棄 Fork B 與 UI 打磨，全力保住此線。LM 已是主要語意層，不再是砍除對象；LM 不可用時由文字比對備援）                                                    |

**AccessibilityService 介面設計（目標）**

- `startApp` → start activity → return success / failed
- `clickEvent` → find view node → try to click → return success / failed
- `navigation`（Bonus）→ search / set route → return `<T>`
- 對外橋接介面：`ui_read_screen`、`ui_click`、`ui_set_text`、`ui_back`

## 0.5 團隊分工

> **Mentor 指示（2026/07/15）**：一階段一階段完成，**不同步進行**。全員聚焦當前階段，依專長分主責 / 支援，不預先開下一階段的工。

| 成員        | 專長             | 每階段角色                                                                  |
| ----------- | ---------------- | --------------------------------------------------------------------------- |
| **Leo**     | STT + Android    | W1 主責語音；W2 支援（決策/文字比對）；W3 主責 Target A + 語音接線          |
| **Mark**    | Android / 無障礙 | W1 App 架構骨架 + 支援語音；W2 主責 Bridge；W3 主責整合                     |
| **Rebecca** | PM / 產品設計    | 全程產品 / 測試 / 交付：場景、驗收、Debug 頁、Demo、簡報、Target B 語意標註 |

### 核心原則：循序推進，全員聚焦當前階段

- **依序完成**：Week 1 語音全部收斂並 Demo 通過，才進 Week 2；Week 2 通過才進 Week 3。
- **不預先開工**：當前階段做什麼，全員任務都服務該階段里程碑；不提前碰下一階段（例如 Week 1 不碰無障礙、不做 FoxMap 研究）。
- **非主責者不閒置**：該階段專長不對口的人，接該階段的**架構 / 支援 / 產品測試**工作或與主責 pair，而非跳做未來階段。

### 每階段主 / 副

|          | Week 1 語音                                | Week 2 手眼                                      | Week 3 整合                             |
| -------- | ------------------------------------------ | ------------------------------------------------ | --------------------------------------- |
| **主責** | Leo（STT/TTS/Loop）                        | Mark（Bridge 四介面 + M2.1–2.3）                 | Mark（整合 M3.1/M3.2）· Leo（Target A） |
| **支援** | Mark（App 架構骨架）· Rebecca（場景/驗收） | Leo（決策/文字比對）· Rebecca（格式/Debug/測試） | Rebecca（Demo/簡報/Target B 語意標註）  |

### Rebecca 成長路徑

低複雜度程式（Debug 頁、UI 清單格式）以 **pair programming** 跟當階段主責配對，既有產出又練手。

## 1. 時程總覽

| 階段     | 日期                    | 主題                             | 里程碑             | 驗收                        |
| -------- | ----------------------- | -------------------------------- | ------------------ | --------------------------- |
| 前置準備 | 07/15（三）–07/17（五） | 環境建置                         | 環境就緒           | 能 build & run 空白 App     |
| Week 1   | 07/20（一）–07/24（五） | 語音大腦（App 雛形）             | M1.1 / M1.2 / M1.3 | 現場 Demo：說→聽懂→回話     |
| Week 2   | 07/27（一）–07/31（五） | 虛擬手眼（Accessibility Bridge） | M2.1 / M2.2 / M2.3 | 現場 Demo：讀畫面→點擊→回讀 |
| Week 3   | 08/03（一）–08/07（五） | 最終整合實戰（Boss 戰）+ 交付    | M3.1 / M3.2 / M3.3 | 現場 Demo：語音操控完整閉環 |

> 週末（假日）預設略過。每週最後一個工作日為現場 Demo 驗收日。
> **循序推進**：前一階段 Demo 驗收通過，才開始下一階段（見 §0.5）。
> **用語對照**：程式碼 CHANGELOG / commit 訊息中的「M1.1 Phase 1~8」是 **M1.1（STT）的內部開發子步驟**，對應本表的 M1.1；與這裡的三週三階段（Week 1~3）不是同一層級。

---

## 2. 前置準備（07/15 三 – 07/17 五）

把三週用到的環境一次架好，避免佔用正式週工時。

- [ ] Android Studio + SDK 就緒，建空白專案（Kotlin），能在裝置上 build & run
- [ ] 備測試裝置：AAOS 模擬器優先；**模擬器有問題就先用一般手機 / 平板**
- [ ] 設定雲端金鑰：STT、TTS（LM 可延後）
- [ ] 建 Git Repo，定分支策略，寫 README 骨架
- [ ] 研讀開源參考（附錄 A），重點精讀 `droidrun/mobilerun-portal`

**產出**：可 build & run 的專案骨架 + 可用的測試裝置 + Repo。

---

## 3. Week 1 — 語音大腦 / App 雛形（07/20 – 07/24）

> 建 Android App 雛形，串接雲端服務，完成「說 → 聽懂 → 回話」基礎互動。
> ⚠️ **全員聚焦語音，本週不碰無障礙**（循序推進，見 §0.5）。

### 里程碑任務（本階段主線）

- [ ] **M1.1 STT 串接** `[Leo]`：麥克風錄音（`AudioRecord`）→ 雲端 STT → 畫面顯示辨識文字
- [ ] **M1.2 TTS 串接** `[Leo]`：傳入文字 → 雲端 TTS → 喇叭語音念出
- [ ] **M1.3 語音互動 Loop（主線）** `[Leo]`：說「你好」→ 聽懂 → 回覆（LM 或預設規則）→ 播報，全程 App 內自動流轉

### 支援任務（服務本階段）

- [ ] **App 架構骨架** `[Mark]`：搭 Pipeline 協調骨架，承載語音 Loop（後續階段亦掛載於此）
- [ ] **對話場景與回覆規則** `[Rebecca]`：各指令回什麼、驗收清單

### 驗收標準（Demo）

- [ ] 對 App 說一句話，畫面顯示辨識文字並語音回覆 —— 全程免手動介入

### 備註

- 回覆邏輯先用**預設規則 / 文字比對**，LM 為可選加分（附錄 B）。
- 參考：`android-docs-samples/speech`（官方 gRPC streaming 範例）。

---

## 4. Week 2 — 虛擬手眼 / Accessibility Bridge（07/27 – 07/31）

> 擴充 Week 1 的 App，讓它能看、能動，定義 Bridge 介面。
> 💡 **本週用按鈕 / 打字測試，不急著接語音**（手眼做穩再接嘴巴）。

### 開工先定契約

- [ ] 🔑 **定版介面契約** `[Leo + Mark]`（本階段第一件事）：Bridge 四介面回傳格式 + 語音層指令字串格式（Week 3 整合時兩端接同一份）
- [ ] 定義並實作 `[Mark]`：`ui_read_screen`、`ui_click`、`ui_set_text`、`ui_back`

### 里程碑任務（本階段主線）

- [ ] **M2.1 讀畫面** `[Mark]`：啟用服務後即時抽出當前畫面元素清單（Debug 頁或 Log）。格式如：`{"screen":"home","elements":[{"i":0,"text":"設定"},{"i":1,"text":"音樂"}]}`
- [ ] **M2.2 操作畫面** `[Mark]`：下「設定」指令，App 代替使用者精準點開設定頁（`performAction(ACTION_CLICK)`）
- [ ] **M2.3 操作閉環（主線）** `[Mark]`：點擊後自動「回讀畫面」，確認狀態已改變

### 支援任務（服務本階段）

- [ ] **決策 / 文字比對** `[Leo]`：M2.2 需要 —— 收指令 → 比對畫面元素 → 決定點哪個；並協助讀 UI 樹
- [ ] **UI 清單格式設計** `[Rebecca]`：留哪些欄位、如何呈現最好比對（與 Mark 配對）
- [ ] **Debug 顯示頁** `[Rebecca]`（pair with Mark）＋ 測試案例 / Demo 腳本

### 驗收標準（Demo）

- [ ] 打字下「設定」，App 讀出畫面元素 → 點開設定頁 → 回讀確認已進入 —— 完整閉環

### 備註

- **最重要參考**：`droidrun/mobilerun-portal`（97% Kotlin，抽 UI 樹與執行點擊範例）。
- 官方入門：Google Accessibility Codelab（建 Service、宣告 manifest、`performAction`）。

---

## 5. Week 3 — 最終整合實戰 / Boss 戰 + 交付（08/03 – 08/07）

> 將 Week 1 語音接上 Week 2 Bridge，跑通「所見即可說」完整閉環並交付。

### Integration Base（整合基座）— 語音 + Bridge 整合，全員上陣

- [ ] **M3.1 單步執行** `[Mark 主 / Leo 支援]`：一句語音 → 讀畫面 → 決策 → 執行 → 回讀 → 語音回饋（單一動作閉環）
- [ ] **M3.2 連續多步導航（主線）** `[Mark 主 / Leo 支援]`：多步任務 = 單步閉環連續跑多次，每步依賴「當下的畫面」
- [ ] **M3.3 整合驗收** `[全員]`：完整場景端到端跑通

### 實戰目標（擇一主攻，行有餘力再攻 Target B）

- [ ] **Target A — Car Settings（標準，主攻）** `[Leo 主 / Mark 支援]`：語音「打開設定 → 顯示 → 字型調大」。標準 Android UI 原生支援 Accessibility，適合驗收。
- [ ] **Target B — FoxMap 改造（進階 / 選配）** `[Rebecca 主 / Mark 支援]`：先做 FoxMap 產品面研究，摸清自繪 UI 的語意標註挑戰 → 回 `kitt-map` 補 `contentDescription` 等語意標註，讓 FoxMap 可被看見與操作。

### 交付成果包

- [ ] 可執行 Android APK `[Mark]`
- [ ] 完整程式碼 Repo `[全員]`
- [ ] Demo 影片（展示「所見即可說」無縫流轉）`[Rebecca]`
- [ ] 成果簡報（系統架構、遇到的坑與解法）`[Rebecca]`

### ⚠️ 主管提醒

> 進度吃緊時果斷放棄進階挑戰（Fork B）與 UI 打磨，**保住核心主線 M1.3 → M2.3 → M3.2**。LM 為主要語意理解層，不再列入砍除清單；若 LM 不可用，由文字比對備援維持基本可用。

---

## 附錄 A：開源武器庫（站在巨人肩膀上）

| 用途                         | 資源                                     | 說明                                              |
| ---------------------------- | ---------------------------------------- | ------------------------------------------------- |
| 語音 I/O（STT/TTS）          | `android-docs-samples/speech`            | 官方 gRPC streaming 範例                          |
| **無障礙核心參考（最重要）** | `droidrun/mobilerun-portal`              | 97% Kotlin，抽 UI 樹與執行點擊的完美範例          |
| 無障礙官方指南               | Google Accessibility Codelab             | 建立 Service、宣告 manifest、`performAction` 入門 |
| 全雲端 Agent 備案            | `livekit-examples/agent-starter-android` | 若需打包走 LiveKit 的 client 骨架                 |

## 附錄 B：選配 / 加分項（Stretch — 主線穩了才做）

- [ ] **Bonus — 語音控制 FoxMap App**（= Week 3 Target B）
- [ ] **Bonus2 — STT/TTS 效能優化**（降低延遲 / 串流化）
- [ ] **LM 決策**：複雜意圖才問 LM，簡單指令靠文字比對（可選）
- [ ] **navigation 功能**：`search / set route`（AccessibilityService 的 Bonus 分支）

## 附錄 C：風險與備援

> 完整風險表以 [PROJECT.md §9 Risk Management & Cut Lines](PROJECT.md#9-risk-management--cut-lines) 為準；此處僅列團隊排程最相關的重點。

| 風險                | 備援方案                                               |
| ------------------- | ------------------------------------------------------ |
| AAOS 模擬器有問題   | 先用一般手機 / 平板測試                                |
| 進度落後            | 依序砍 Target B → LM → polish，保住 M1.3 → M2.3 → M3.2 |
| 雲端服務延遲 / 不穩 | 先用預設規則文字比對；LM 延後                          |
